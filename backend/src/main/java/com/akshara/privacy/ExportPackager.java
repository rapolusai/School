package com.akshara.privacy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

import com.akshara.academics.AcademicsDirectory;
import com.akshara.academics.AcademicsDirectory.YearInfo;
import com.akshara.admissions.AdmissionRecords;
import com.akshara.admissions.AdmissionsService.ApplicationDetail;
import com.akshara.attendance.AttendanceReportService;
import com.akshara.attendance.AttendanceReportService.StudentSummary;
import com.akshara.attendance.AttendanceScope;
import com.akshara.fees.FeeDuesService;
import com.akshara.fees.FeePaymentService;
import com.akshara.fees.FeeViews.ReceiptView;
import com.akshara.fees.FeeViews.StudentFees;
import com.akshara.identity.UserAccount;
import com.akshara.platform.SchoolProfile;
import com.akshara.platform.TenantDirectory;
import com.akshara.shared.TenantContext;
import com.akshara.students.StudentService;
import com.akshara.students.StudentService.ChildView;
import com.akshara.students.StudentService.GuardianView;
import com.akshara.students.StudentService.StudentDetail;

/**
 * Collects what the school holds about a child (or about the parent asking) for an access request and packs it as
 * JSON files in a ZIP, built with {@code java.util.zip}. Other modules are read only through their public services:
 * students (record, guardians, enrollments), admissions (the application), attendance, and fees (dues and receipts).
 * Other people's contact details (the other parent, brothers and sisters) are left out: each person asks for their own.
 */
@Component
class ExportPackager {

    /** One file in the ZIP. */
    record ExportFile(String name, byte[] content) {
    }

    /** The finished ZIP: its file name, bytes, SHA-256 and the names of the files inside. */
    record Packed(String fileName, byte[] zip, String sha256, List<String> files) {
    }

    private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE;

    private final StudentService students;
    private final AdmissionRecords admissions;
    private final AttendanceReportService attendance;
    private final AcademicsDirectory academics;
    private final FeeDuesService feeDues;
    private final FeePaymentService feePayments;
    private final ConsentService consents;
    private final DataRequestService requests;
    private final TenantDirectory tenants;
    private final JsonMapper json;

    ExportPackager(StudentService students, AdmissionRecords admissions, AttendanceReportService attendance,
            AcademicsDirectory academics, FeeDuesService feeDues, FeePaymentService feePayments,
            ConsentService consents, DataRequestService requests, TenantDirectory tenants, JsonMapper json) {
        this.students = students;
        this.admissions = admissions;
        this.attendance = attendance;
        this.academics = academics;
        this.feeDues = feeDues;
        this.feePayments = feePayments;
        this.consents = consents;
        this.requests = requests;
        this.tenants = tenants;
        this.json = json;
    }

    /** Builds the export for an access request: about the child, or about the parent themselves. */
    Packed pack(DataRequest request, UserAccount requester, Instant now, Instant expiresAt) {
        UUID tenantId = TenantContext.require();
        String school = tenants.profile(tenantId).map(SchoolProfile::name).orElse("The school");
        LocalDate today = DataRequestService.today();
        List<ExportFile> files = new ArrayList<>();
        String subjectLabel;
        String baseName;
        if (request.getSubject() == RequestSubject.CHILD) {
            StudentDetail child = students.detail(request.getStudentId());
            subjectLabel = child.fullName() + " (admission no. " + child.admissionNo() + ")";
            baseName = "data-export-" + child.admissionNo();
            childFiles(child, requester, today, files);
        } else {
            subjectLabel = requester.getName() + " (" + requester.getEmail() + ")";
            baseName = "data-export-parent";
            selfFiles(requester, files);
        }
        files.addFirst(new ExportFile("README.txt", readme(school, subjectLabel, request, files, now, expiresAt)
                .getBytes(StandardCharsets.UTF_8)));
        byte[] zip = zip(files, now);
        String fileName = safeFileName(baseName + "-" + DAY.format(today)) + ".zip";
        return new Packed(fileName, zip, sha256(zip), files.stream().map(ExportFile::name).toList());
    }

    // ------------------------------------------------------------------ the child

    private void childFiles(StudentDetail child, UserAccount requester, LocalDate today, List<ExportFile> files) {
        UUID studentId = child.id();
        String requesterEmail = requester == null ? null : requester.getEmail();

        Map<String, Object> record = toMap(child);
        record.remove("guardians");
        record.remove("siblings");
        record.remove("enrollments");
        files.add(file("student.json", record));

        List<GuardianView> guardians = child.guardians().stream()
                .map(g -> isRequester(g, requesterEmail) ? g : reduced(g))
                .toList();
        files.add(file("guardians.json", guardians));
        files.add(file("enrollments.json", child.enrollments()));

        Optional<GuardianView> own = child.guardians().stream().filter(g -> isRequester(g, requesterEmail))
                .findFirst();
        admissions.forStudent(studentId)
                .ifPresent(application -> files.add(file("admission-application.json", application(application,
                        own.orElse(null), requesterEmail))));

        files.add(file("attendance.json", attendance(child, today)));

        StudentFees fees = feeDues.studentFees(studentId);
        files.add(file("fees.json", fees));
        List<ReceiptView> receipts = fees.receipts().stream()
                .map(r -> feePayments.receiptOf(studentId, r.id()))
                .toList();
        files.add(file("fee-receipts.json", receipts));

        files.add(file("consents.json", consents.historyOf(studentId)));
    }

    /** Attendance for each academic year the child was enrolled in, up to today. */
    private List<StudentSummary> attendance(StudentDetail child, LocalDate today) {
        Set<UUID> yearIds = child.enrollments().stream().map(e -> e.academicYearId()).collect(Collectors.toSet());
        List<StudentSummary> years = new ArrayList<>();
        for (YearInfo year : academics.years()) {
            if (!yearIds.contains(year.id()) || year.startsOn().isAfter(today)) {
                continue;
            }
            LocalDate to = year.endsOn().isBefore(today) ? year.endsOn() : today;
            LocalDate from = year.startsOn();
            long limit = AttendanceReportService.MAX_RANGE_DAYS - 1L;
            if (ChronoUnit.DAYS.between(from, to) > limit) {
                from = to.minusDays(limit);
            }
            years.add(attendance.student(AttendanceScope.WHOLE_SCHOOL, child.id(), from, to));
        }
        years.sort((a, b) -> a.from().compareTo(b.from()));
        return years;
    }

    /** The application without its internal workflow hints, with other people's contact details left out. */
    private Map<String, Object> application(ApplicationDetail application, GuardianView own, String requesterEmail) {
        Map<String, Object> map = toMap(application);
        map.remove("nextStages");
        List<Map<String, Object>> guardians = new ArrayList<>();
        for (var g : application.guardians()) {
            boolean mine = (requesterEmail != null && g.email() != null && g.email().equalsIgnoreCase(requesterEmail))
                    || (own != null && Objects.equals(own.phone(), g.phone()));
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", g.name());
            entry.put("relation", g.relation());
            entry.put("primary", g.primary());
            if (mine) {
                entry.put("phone", g.phone());
                entry.put("email", g.email());
            }
            guardians.add(entry);
        }
        map.put("guardians", guardians);
        return map;
    }

    private static boolean isRequester(GuardianView g, String requesterEmail) {
        return requesterEmail != null && g.signInEmail() != null && g.signInEmail().equalsIgnoreCase(requesterEmail);
    }

    /** Another guardian: who they are to the child, without their contact details. */
    private static GuardianView reduced(GuardianView g) {
        return new GuardianView(g.id(), g.name(), g.relation(), null, null, null, g.primary(), g.hasSignIn(), null);
    }

    // ------------------------------------------------------------------ the parent themselves

    private void selfFiles(UserAccount requester, List<ExportFile> files) {
        UUID userId = requester.getId();
        Map<String, Object> account = new LinkedHashMap<>();
        account.put("name", requester.getName());
        account.put("email", requester.getEmail());
        account.put("roles", requester.roleCodes());
        account.put("status", requester.getStatus());
        account.put("createdAt", requester.getCreatedAt());
        account.put("lastSignInAt", requester.getLastLoginAt());
        files.add(file("account.json", account));

        List<ChildView> children = students.childrenOf(userId);
        GuardianView own = null;
        for (ChildView child : children) {
            own = students.detail(child.id()).guardians().stream()
                    .filter(g -> isRequester(g, requester.getEmail())).findFirst().orElse(null);
            if (own != null) {
                break;
            }
        }
        if (own != null) {
            files.add(file("guardian.json", own));
        }
        files.add(file("children.json", children));
        files.add(file("consents-given.json", consents.givenBy(userId)));
        files.add(file("data-requests.json", requests.mine(userId)));
    }

    // ------------------------------------------------------------------ files

    private String readme(String school, String subject, DataRequest request, List<ExportFile> files, Instant now,
            Instant expiresAt) {
        StringBuilder text = new StringBuilder();
        text.append("Personal data export / व्यक्तिगत डेटा की प्रति\n");
        text.append("==============================================\n\n");
        text.append("School / विद्यालय: ").append(school).append('\n');
        text.append("About / किसके बारे में: ").append(subject).append('\n');
        text.append("Requested by / अनुरोधकर्ता: ").append(request.getRequesterName()).append('\n');
        text.append("Request / अनुरोध: ").append(request.getId()).append('\n');
        text.append("Made at (UTC) / बनाया गया: ").append(now.truncatedTo(ChronoUnit.SECONDS)).append('\n');
        text.append("Available until (UTC) / उपलब्ध: ").append(expiresAt.truncatedTo(ChronoUnit.SECONDS))
                .append("\n\n");
        text.append("This file was made for your access request under the Digital Personal Data Protection Act, 2023.\n");
        text.append("It holds the personal data the school keeps in Akshara School Cloud, as JSON files:\n\n");
        Map<String, String> about = describe();
        for (ExportFile f : files) {
            text.append("- ").append(f.name()).append(": ").append(about.getOrDefault(f.name(), "")).append('\n');
        }
        text.append("\nDetails of other people (the other parent's contacts, brothers and sisters) are not included;\n");
        text.append("each of them can ask the school separately. Amounts are in paise (100 paise = 1 rupee).\n");
        text.append("If something is wrong, raise a correction request in the parent app.\n\n");
        text.append("यह फ़ाइल डिजिटल व्यक्तिगत डेटा संरक्षण अधिनियम, 2023 के अंतर्गत आपके अनुरोध पर बनाई गई है।\n");
        text.append("इसमें विद्यालय द्वारा रखा गया व्यक्तिगत डेटा JSON फ़ाइलों में है। कोई जानकारी गलत हो तो\n");
        text.append("पैरेंट ऐप में सुधार का अनुरोध करें।\n");
        return text.toString();
    }

    private static Map<String, String> describe() {
        Map<String, String> about = new LinkedHashMap<>();
        about.put("README.txt", "this note");
        about.put("student.json", "the student record");
        about.put("guardians.json", "parents and guardians on the record (your own contact details in full)");
        about.put("enrollments.json", "classes and sections, year by year");
        about.put("admission-application.json", "the admission application and its steps");
        about.put("attendance.json", "attendance marks for each academic year");
        about.put("fees.json", "fee dues, concessions and payments");
        about.put("fee-receipts.json", "every fee receipt in full");
        about.put("consents.json", "consent given, declined or withdrawn, with the notice version");
        about.put("account.json", "your sign-in account");
        about.put("guardian.json", "your parent or guardian record");
        about.put("children.json", "your children at the school");
        about.put("consents-given.json", "consent decisions you made");
        about.put("data-requests.json", "your data requests to the school");
        return about;
    }

    private ExportFile file(String name, Object value) {
        return new ExportFile(name, json.writerWithDefaultPrettyPrinter().writeValueAsBytes(value));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(Object value) {
        return new LinkedHashMap<>(json.convertValue(value, Map.class));
    }

    static byte[] zip(List<ExportFile> files, Instant at) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            for (ExportFile f : files) {
                ZipEntry entry = new ZipEntry(f.name());
                entry.setTime(at.toEpochMilli());
                zip.putNextEntry(entry);
                zip.write(f.content());
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Letters, digits and hyphens only, at most 100 characters. */
    static String safeFileName(String name) {
        String safe = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]+", "-").replaceAll("-{2,}", "-");
        return safe.length() > 100 ? safe.substring(0, 100) : safe;
    }
}
