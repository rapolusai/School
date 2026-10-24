package com.akshara.certificates;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.akshara.academics.AcademicsDirectory;
import com.akshara.academics.AcademicsDirectory.SectionInfo;
import com.akshara.academics.AcademicsDirectory.YearInfo;
import com.akshara.attendance.AttendanceReportService;
import com.akshara.attendance.AttendanceReportService.StudentSummary;
import com.akshara.attendance.AttendanceScope;
import com.akshara.certificates.CertificateViews.Subject;
import com.akshara.fees.FeeDuesService;
import com.akshara.fees.FeePaymentService;
import com.akshara.fees.FeePaymentService.ReceiptQuery;
import com.akshara.fees.FeeViews.ReceiptPage;
import com.akshara.library.LibraryDirectory;
import com.akshara.payroll.PayStaffService;
import com.akshara.payroll.PayrollViews.PayStaffDetail;
import com.akshara.payroll.PayrollViews.PayslipRef;
import com.akshara.payroll.PayrollViews.StructureView;
import com.akshara.shared.ApiException;
import com.akshara.staff.StaffPayrollInputs;
import com.akshara.staff.StaffPayrollInputs.PayrollPerson;
import com.akshara.staff.StaffService;
import com.akshara.staff.StaffService.StaffDetail;
import com.akshara.students.Gender;
import com.akshara.students.GuardianRelation;
import com.akshara.students.StudentRoster;
import com.akshara.students.StudentRoster.GuardianContact;
import com.akshara.students.StudentRoster.RosterStudent;
import com.akshara.students.StudentService;
import com.akshara.students.StudentService.GuardianView;
import com.akshara.students.StudentService.StudentDetail;
import com.akshara.students.StudentStatus;

/**
 * Whom certificates and cards are for, read through the other modules' public services only: students (the
 * students module), staff (the staff module), the class (academics), attendance for a transfer certificate, fees
 * paid and due (fees) and the salary (payroll). Nothing about them is stored here except the ids and the values
 * copied into a certificate's fields.
 */
@Component
class CertificateSubjects {

    private static final Logger log = LoggerFactory.getLogger(CertificateSubjects.class);
    /** Up to this many ids, classes are looked up one by one; above it, the whole year's sections are read at once. */
    private static final int ONE_BY_ONE = 10;

    private final StudentService students;
    private final StudentRoster roster;
    private final AcademicsDirectory academics;
    private final StaffService staff;
    private final StaffPayrollInputs staffInputs;
    private final AttendanceReportService attendance;
    private final FeeDuesService dues;
    private final FeePaymentService payments;
    private final PayStaffService payroll;
    private final LibraryDirectory library;

    CertificateSubjects(StudentService students, StudentRoster roster, AcademicsDirectory academics,
            StaffService staff, StaffPayrollInputs staffInputs, AttendanceReportService attendance,
            FeeDuesService dues, FeePaymentService payments, PayStaffService payroll, LibraryDirectory library) {
        this.students = students;
        this.roster = roster;
        this.academics = academics;
        this.staff = staff;
        this.staffInputs = staffInputs;
        this.attendance = attendance;
        this.dues = dues;
        this.payments = payments;
        this.payroll = payroll;
        this.library = library;
    }

    // ------------------------------------------------------------------ one subject

    /** A student of this school, in any status; empty for unknown ids and other schools' students. */
    Optional<StudentDetail> student(UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(students.detail(id));
        } catch (ApiException e) {
            return Optional.empty();
        }
    }

    /** A member of this school's staff (active or not); empty for anyone else. */
    Optional<PayrollPerson> staffPerson(UUID userId) {
        return userId == null ? Optional.empty() : staffInputs.person(userId);
    }

    Subject subject(StudentDetail s) {
        String cls = s.currentEnrollment() == null ? null
                : label(s.currentEnrollment().className(), s.currentEnrollment().sectionName());
        return new Subject(SubjectType.STUDENT, s.id(), s.fullName(), s.firstName(), s.admissionNo(), cls,
                s.status() == StudentStatus.ACTIVE);
    }

    Subject subject(PayrollPerson p) {
        return new Subject(SubjectType.STAFF, p.userId(), p.name(), Serials.firstName(p.name()), p.employeeCode(),
                p.designation(), p.active());
    }

    /** The subject of a certificate or card, or a nameless one when the person can no longer be found. */
    Subject subject(SubjectType type, UUID id) {
        return describe(List.of(new Ref(type, id))).get(new Ref(type, id));
    }

    record Ref(SubjectType type, UUID id) {
    }

    /** Names, codes and classes or designations of many people at once, in the caller's order. */
    Map<Ref, Subject> describe(Collection<Ref> refs) {
        Map<Ref, Subject> found = new HashMap<>();
        Set<UUID> studentIds = refs.stream().filter(r -> r.type() == SubjectType.STUDENT).map(Ref::id)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<UUID> staffIds = refs.stream().filter(r -> r.type() == SubjectType.STAFF).map(Ref::id)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!studentIds.isEmpty()) {
            UUID yearId = academics.currentYear().map(YearInfo::id).orElse(null);
            Map<UUID, RosterStudent> people = roster.students(studentIds, yearId);
            Map<UUID, String> classes = classLabels(people.keySet(), yearId);
            for (UUID id : studentIds) {
                RosterStudent s = people.get(id);
                found.put(new Ref(SubjectType.STUDENT, id), s == null
                        ? new Subject(SubjectType.STUDENT, id, null, null, null, null, false)
                        : new Subject(SubjectType.STUDENT, id, s.fullName(), Serials.firstName(s.fullName()),
                                s.admissionNo(), classes.get(id), s.active()));
            }
        }
        if (!staffIds.isEmpty()) {
            Map<UUID, PayrollPerson> people = staffInputs.people().stream()
                    .collect(Collectors.toMap(PayrollPerson::userId, p -> p, (a, b) -> a));
            for (UUID id : staffIds) {
                PayrollPerson p = people.get(id);
                found.put(new Ref(SubjectType.STAFF, id), p == null
                        ? new Subject(SubjectType.STAFF, id, null, null, null, null, false) : subject(p));
            }
        }
        Map<Ref, Subject> ordered = new LinkedHashMap<>();
        for (Ref ref : refs) {
            ordered.put(ref, found.get(ref));
        }
        return ordered;
    }

    /** The section a student is in this year, if any. */
    Optional<SectionInfo> sectionOf(UUID studentId) {
        return academics.currentYear().flatMap(y -> roster.sectionOf(studentId, y.id())).flatMap(academics::section);
    }

    /** The active students of a section this year. */
    List<RosterStudent> activeInSection(UUID sectionId) {
        return academics.currentYear().map(y -> roster.activeInSection(y.id(), sectionId)).orElse(List.of());
    }

    Optional<SectionInfo> section(UUID sectionId) {
        return sectionId == null ? Optional.empty() : academics.section(sectionId);
    }

    List<PayrollPerson> activeStaff() {
        return staffInputs.people().stream().filter(PayrollPerson::active).toList();
    }

    /** Each student's primary parent or guardian. */
    Map<UUID, GuardianContact> primaryGuardians(Collection<UUID> studentIds) {
        return studentIds.isEmpty() ? Map.of() : roster.primaryGuardians(studentIds);
    }

    /** A parent's children (student ids), or none for anyone else. */
    Set<UUID> childIdsOf(UUID userId) {
        return roster.childIdsOf(userId);
    }

    List<StudentService.ChildView> childrenOf(UUID userId) {
        return students.childrenOf(userId);
    }

    Optional<StudentService.ChildView> studentOf(UUID userId) {
        return students.studentOf(userId);
    }

    // ------------------------------------------------------------------ prefilled fields

    /** The placeholder values for a student certificate, from the student record and the other modules. */
    Map<String, String> prefill(StudentDetail s, CertificateType type, String purpose, LocalDate from, LocalDate to,
            LocalDate today) {
        Map<String, String> f = new LinkedHashMap<>();
        String father = null;
        String mother = null;
        String guardian = null;
        for (GuardianView g : s.guardians()) {
            if (g.relation() == GuardianRelation.FATHER && father == null) {
                father = g.name();
            } else if (g.relation() == GuardianRelation.MOTHER && mother == null) {
                mother = g.name();
            } else if (guardian == null) {
                guardian = g.name();
            }
        }
        String parent = father != null ? father : mother != null ? mother : guardian;
        f.put("student", s.fullName());
        f.put("firstName", s.firstName());
        f.put("admissionNo", s.admissionNo());
        f.put("class", s.currentEnrollment() == null ? null
                : label(s.currentEnrollment().className(), s.currentEnrollment().sectionName()));
        f.put("section", s.currentEnrollment() == null ? null : s.currentEnrollment().sectionName());
        f.put("dob", iso(s.dateOfBirth()));
        f.put("gender", s.gender() == null ? null : s.gender() == Gender.MALE ? "Male"
                : s.gender() == Gender.FEMALE ? "Female" : "Other");
        f.put("relation", father != null ? (s.gender() == Gender.FEMALE ? "daughter of" : s.gender() == Gender.MALE
                ? "son of" : "child of") : mother != null ? (s.gender() == Gender.FEMALE ? "daughter of"
                : s.gender() == Gender.MALE ? "son of" : "child of") : "ward of");
        f.put("father", parent);
        f.put("mother", mother);
        f.put("guardian", guardian);
        f.put("admissionDate", iso(s.admissionDate()));
        f.put("academicYear", academics.currentYear().map(YearInfo::name).orElse(Serials.academicYear(today)));
        f.put("address", s.address());
        f.put("previousSchool", s.previousSchool());
        f.put("purpose", purpose);
        LocalDate start = from != null ? from : academics.currentYear().map(YearInfo::startsOn).orElse(
                today.withDayOfYear(1));
        LocalDate end = to != null ? to : today;
        f.put("from", iso(start));
        f.put("to", iso(end));
        f.put("conduct", "good");
        f.put("reason", s.leavingReason() != null ? s.leavingReason() : purpose);
        f.put("dateOfLeaving", iso(s.leftOn() != null ? s.leftOn() : today));
        if (type == CertificateType.TRANSFER) {
            f.put("promotionStatus", "Promoted to the next class.");
            f.put("duesCleared", "Yes");
            attendance(s.id()).ifPresent(a -> {
                f.put("workingDays", Integer.toString(a.daysMarked()));
                f.put("daysPresent", Integer.toString(a.counts().present() + a.counts().late()
                        + a.counts().halfDay()));
            });
        }
        if (type == CertificateType.FEE) {
            receipts(s.id(), start, end).ifPresent(r -> {
                f.put("feesPaid", Placeholders.rupees(r.totalPaise()));
                f.put("receipts", Long.toString(r.total()));
            });
        }
        return f;
    }

    /** The placeholder values for a staff certificate, from the staff and payroll modules. */
    Map<String, String> prefill(PayrollPerson p, CertificateType type, String purpose, LocalDate today) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("staff", p.name());
        f.put("firstName", Serials.firstName(p.name()));
        f.put("employeeCode", p.employeeCode());
        f.put("designation", p.designation());
        f.put("department", p.departmentName());
        f.put("dateOfJoining", iso(p.dateOfJoining()));
        f.put("dateOfLeaving", iso(p.dateOfLeaving() != null ? p.dateOfLeaving() : today));
        f.put("purpose", purpose);
        f.put("conduct", "good");
        try {
            StaffDetail d = staff.detail(p.userId());
            if (d.profile() != null && d.profile().leavingReason() != null) {
                f.put("reason", d.profile().leavingReason());
            }
        } catch (RuntimeException e) {
            log.debug("No staff profile for {} ({})", p.userId(), e.getClass().getSimpleName());
        }
        if (type == CertificateType.SALARY) {
            salary(p.userId()).ifPresent(s -> {
                f.put("salaryMonth", s.month());
                f.put("grossSalary", Placeholders.rupees(s.grossPaise()));
                f.put("netSalary", s.netPaise() == null ? null : Placeholders.rupees(s.netPaise()));
            });
        }
        return f;
    }

    /** The salary as the payroll module shows it to payroll readers: the monthly gross and the latest net. */
    record Salary(String month, long grossPaise, Long netPaise) {
    }

    Optional<Salary> salary(UUID userId) {
        try {
            PayStaffDetail d = payroll.detail(userId);
            StructureView current = d.structures().stream().filter(StructureView::current).findFirst()
                    .orElse(d.structures().isEmpty() ? null : d.structures().getFirst());
            if (current == null) {
                return Optional.empty();
            }
            PayslipRef latest = d.payslips().isEmpty() ? null : d.payslips().getFirst();
            String month = latest != null ? latest.month() : YearMonth.now(Serials.INDIA).toString();
            YearMonth ym = YearMonth.parse(month);
            String monthName = ym.getMonth().getDisplayName(java.time.format.TextStyle.FULL,
                    java.util.Locale.ENGLISH) + " " + ym.getYear();
            return Optional.of(new Salary(monthName, current.grossPaise(), latest == null ? null
                    : latest.netPaise()));
        } catch (RuntimeException e) {
            log.debug("No salary for {} ({})", userId, e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** This year's attendance of a student, for a transfer certificate; empty when there is none. */
    Optional<StudentSummary> attendance(UUID studentId) {
        try {
            return Optional.of(attendance.student(AttendanceScope.WHOLE_SCHOOL, studentId, null, null));
        } catch (RuntimeException e) {
            log.debug("No attendance for {} ({})", studentId, e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** The fee receipts of a student in a period (issued ones; the page's total is the amount). */
    Optional<ReceiptPage> receipts(UUID studentId, LocalDate from, LocalDate to) {
        try {
            return Optional.of(payments.receipts(new ReceiptQuery(from, to, null, "ISSUED", null, studentId, 0,
                    FeePaymentService.MAX_PAGE_SIZE)));
        } catch (RuntimeException e) {
            log.debug("No receipts for {} ({})", studentId, e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /**
     * Library books still out, for the warning on a transfer certificate (the student's) or an experience certificate
     * (the staff member's); null for other certificates or when the library cannot say.
     */
    Integer booksOut(CertificateType type, SubjectType subjectType, UUID subjectId) {
        boolean leaving = type == CertificateType.TRANSFER || type == CertificateType.EXPERIENCE;
        if (!leaving || subjectId == null) {
            return null;
        }
        try {
            return subjectType == SubjectType.STUDENT ? library.booksOutOfStudent(subjectId)
                    : library.booksOutOfStaff(subjectId);
        } catch (RuntimeException e) {
            log.debug("No library loans for {} ({})", subjectId, e.getClass().getSimpleName());
            return null;
        }
    }

    /** Fees still due from a student, for the transfer certificate's warning; empty when fees are not set up. */
    Optional<Long> duesPaise(UUID studentId) {
        try {
            return Optional.of(dues.studentFees(studentId).totals().balancePaise());
        } catch (RuntimeException e) {
            log.debug("No fee ledger for {} ({})", studentId, e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------ helpers

    private Map<UUID, String> classLabels(Collection<UUID> studentIds, UUID yearId) {
        Map<UUID, String> labels = new HashMap<>();
        if (yearId == null || studentIds.isEmpty()) {
            return labels;
        }
        Map<UUID, SectionInfo> sections = academics.sections().stream()
                .collect(Collectors.toMap(SectionInfo::id, s -> s));
        if (studentIds.size() > ONE_BY_ONE) {
            for (StudentRoster.Family f : roster.families(yearId, null)) {
                SectionInfo s = sections.get(f.sectionId());
                if (s != null && studentIds.contains(f.studentId())) {
                    labels.put(f.studentId(), s.label());
                }
            }
        }
        for (UUID id : studentIds) {
            if (!labels.containsKey(id)) {
                roster.sectionOf(id, yearId).map(sections::get).ifPresent(s -> labels.put(id, s.label()));
            }
        }
        return labels;
    }

    static String label(String className, String sectionName) {
        if (className == null) {
            return null;
        }
        return sectionName == null ? className : className + " " + sectionName;
    }

    static String iso(LocalDate date) {
        return date == null ? null : date.toString();
    }
}
