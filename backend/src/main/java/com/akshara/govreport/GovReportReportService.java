package com.akshara.govreport;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.academics.AcademicsDirectory.YearInfo;
import com.akshara.academics.AcademicsService;
import com.akshara.academics.AcademicsService.ClassView;
import com.akshara.govreport.GovReportTypes.CodeListType;
import com.akshara.govreport.GovReportTypes.SchemeStatus;
import com.akshara.govreport.GovReportViews.CodeView;
import com.akshara.govreport.GovReportViews.CompletenessReport;
import com.akshara.govreport.GovReportViews.EnrolmentReport;
import com.akshara.govreport.GovReportViews.EnrolmentRow;
import com.akshara.govreport.GovReportViews.IntakeView;
import com.akshara.govreport.GovReportViews.RteReport;
import com.akshara.govreport.GovReportViews.SchemeReport;
import com.akshara.govreport.GovReportViews.SchemeTotal;
import com.akshara.govreport.GovReportViews.SectionCompleteness;
import com.akshara.students.Gender;
import com.akshara.students.StudentService.StudentRow;

/** The "Government" group of the reports hub: completeness, enrolment by category, RTE seats, scheme enrolments. */
@Service
@Transactional
public class GovReportReportService {

    static final String NOT_RECORDED = "NOT_RECORDED";

    private final StudentFieldsService fields;
    private final SchoolProfileService profiles;
    private final CodeListService codes;
    private final AcademicsService academicsService;
    private final RteService rte;
    private final SchemeEnrolmentRepository enrolments;

    GovReportReportService(StudentFieldsService fields, SchoolProfileService profiles, CodeListService codes,
            AcademicsService academicsService, RteService rte, SchemeEnrolmentRepository enrolments) {
        this.fields = fields;
        this.profiles = profiles;
        this.codes = codes;
        this.academicsService = academicsService;
        this.rte = rte;
        this.enrolments = enrolments;
    }

    // ------------------------------------------------------------------ completeness by section

    public CompletenessReport completeness(UUID yearId) {
        return fields.completeness(yearId);
    }

    public GovCsv completenessCsv(UUID yearId) {
        CompletenessReport r = completeness(yearId);
        List<String> header = new ArrayList<>(List.of("Section", "Students", "Complete", "Percent"));
        StudentFieldsService.REQUIRED.forEach(f -> header.add("Missing " + f));
        GovCsv csv = new GovCsv(header.toArray(String[]::new));
        for (SectionCompleteness s : r.rows()) {
            List<Object> row = new ArrayList<>(List.of(s.label(), s.students(), s.complete(), s.percent()));
            StudentFieldsService.REQUIRED.forEach(f -> row.add(s.missingByField().getOrDefault(f, 0)));
            csv.row(row.toArray());
        }
        return csv;
    }

    // ------------------------------------------------------------------ enrolment by category and gender

    public EnrolmentReport enrolment(UUID yearId, LocalDate asOf) {
        YearInfo year = profiles.year(yearId);
        LocalDate day = asOf != null ? asOf : GovReportAuth.today();
        Map<String, CodeView> categories = codes.inForce(CodeListType.SOCIAL_CATEGORY, day);
        List<String> categoryCodes = new ArrayList<>(categories.keySet());
        categoryCodes.add(NOT_RECORDED);
        List<StudentRow> pupils = fields.studentsOf(year.id(), null);
        Map<UUID, StudentGovRecord> saved = fields.recordsOf(pupils.stream().map(StudentRow::id).toList());
        Map<UUID, ClassView> classes = academicsService.classes().stream()
                .collect(Collectors.toMap(ClassView::id, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        Map<UUID, List<StudentRow>> byClass = new LinkedHashMap<>();
        classes.keySet().forEach(id -> byClass.put(id, new ArrayList<>()));
        for (StudentRow s : pupils) {
            if (s.classId() != null) {
                byClass.computeIfAbsent(s.classId(), k -> new ArrayList<>()).add(s);
            }
        }
        List<EnrolmentRow> rows = new ArrayList<>();
        long[] totals = new long[3];
        Map<String, Long> totalByCategory = new LinkedHashMap<>();
        categoryCodes.forEach(c -> totalByCategory.put(c, 0L));
        for (Map.Entry<UUID, List<StudentRow>> e : byClass.entrySet()) {
            if (e.getValue().isEmpty()) {
                continue;
            }
            long[] g = new long[3];
            Map<String, Long> byCategory = new LinkedHashMap<>();
            categoryCodes.forEach(c -> byCategory.put(c, 0L));
            for (StudentRow s : e.getValue()) {
                g[genderIndex(s.gender())]++;
                StudentGovRecord r = saved.get(s.id());
                String c = r == null || r.getSocialCategory() == null || !categories.containsKey(r.getSocialCategory())
                        ? NOT_RECORDED : r.getSocialCategory();
                byCategory.merge(c, 1L, Long::sum);
            }
            for (int i = 0; i < 3; i++) {
                totals[i] += g[i];
            }
            byCategory.forEach((c, n) -> totalByCategory.merge(c, n, Long::sum));
            ClassView cv = classes.get(e.getKey());
            rows.add(new EnrolmentRow(e.getKey(), cv == null ? "—" : cv.name(), g[0], g[1], g[2],
                    e.getValue().size(), byCategory));
        }
        EnrolmentRow total = new EnrolmentRow(null, "Total", totals[0], totals[1], totals[2], pupils.size(),
                totalByCategory);
        return new EnrolmentReport(year.id(), year.name(), day, categoryCodes, rows, total);
    }

    public GovCsv enrolmentCsv(UUID yearId, LocalDate asOf) {
        EnrolmentReport r = enrolment(yearId, asOf);
        Map<String, CodeView> categories = codes.inForce(CodeListType.SOCIAL_CATEGORY, r.asOf());
        List<String> header = new ArrayList<>(List.of("Class", "Boys", "Girls", "Other", "Total"));
        for (String c : r.categories()) {
            header.add(NOT_RECORDED.equals(c) ? "Category not recorded" : codes.label(categories, c));
        }
        GovCsv csv = new GovCsv(header.toArray(String[]::new));
        List<EnrolmentRow> rows = new ArrayList<>(r.rows());
        rows.add(r.totals());
        for (EnrolmentRow row : rows) {
            List<Object> cells = new ArrayList<>(List.of(row.className(), row.boys(), row.girls(), row.other(),
                    row.total()));
            r.categories().forEach(c -> cells.add(row.byCategory().getOrDefault(c, 0L)));
            csv.row(cells.toArray());
        }
        return csv;
    }

    static int genderIndex(Gender g) {
        if (g == null) {
            return 2;
        }
        return switch (g) {
            case MALE -> 0;
            case FEMALE -> 1;
            default -> 2;
        };
    }

    // ------------------------------------------------------------------ RTE seats and allotments

    public RteReport rteSeats(UUID yearId) {
        YearInfo year = profiles.year(yearId);
        List<IntakeView> intakes = rte.intakes(year.id());
        long seats = 0;
        long allotted = 0;
        long waitlisted = 0;
        for (IntakeView i : intakes) {
            seats += i.rteSeats();
            long[] counts = rte.outcomeCounts(i.id());
            allotted += counts[0];
            waitlisted += counts[1];
        }
        return new RteReport(year.id(), year.name(), seats, allotted, waitlisted, intakes);
    }

    public GovCsv rteSeatsCsv(UUID yearId) {
        RteReport r = rteSeats(yearId);
        GovCsv csv = new GovCsv("Entry class", "Total seats", "RTE seats", "Applicants", "Eligible", "Allotted",
                "Waitlisted", "Status", "Lottery runs");
        for (IntakeView i : r.intakes()) {
            long[] counts = rte.outcomeCounts(i.id());
            csv.row(i.className(), i.totalSeats(), i.rteSeats(), i.applicants(), i.eligible(), counts[0], counts[1],
                    i.status().name(), i.runs());
        }
        return csv;
    }

    // ------------------------------------------------------------------ scheme enrolments

    public SchemeReport schemes(UUID yearId) {
        YearInfo year = profiles.year(yearId);
        Map<String, CodeView> schemes = codes.inForce(CodeListType.SCHEME, GovReportAuth.today());
        Map<String, List<SchemeEnrolment>> byScheme = enrolments.findByAcademicYearIdOrderByCreatedAtDesc(year.id())
                .stream().collect(Collectors.groupingBy(SchemeEnrolment::getSchemeCode, LinkedHashMap::new,
                        Collectors.toList()));
        List<SchemeTotal> rows = new ArrayList<>();
        long students = 0;
        long amount = 0;
        for (Map.Entry<String, List<SchemeEnrolment>> e : byScheme.entrySet()) {
            Map<SchemeStatus, Long> byStatus = new EnumMap<>(SchemeStatus.class);
            for (SchemeStatus s : SchemeStatus.values()) {
                byStatus.put(s, 0L);
            }
            long sum = 0;
            for (SchemeEnrolment en : e.getValue()) {
                byStatus.merge(en.getStatus(), 1L, Long::sum);
                if (en.getAmountPaise() != null && en.getStatus() != SchemeStatus.REJECTED) {
                    sum += en.getAmountPaise();
                }
            }
            students += e.getValue().size();
            amount += sum;
            rows.add(new SchemeTotal(e.getKey(), codes.label(schemes, e.getKey()), e.getValue().size(), sum,
                    byStatus));
        }
        return new SchemeReport(year.id(), year.name(), students, amount, rows);
    }

    public GovCsv schemesCsv(UUID yearId) {
        SchemeReport r = schemes(yearId);
        GovCsv csv = new GovCsv("Scheme", "Students", "Applied", "Approved", "Received", "Rejected", "Amount (INR)");
        for (SchemeTotal t : r.rows()) {
            csv.row(t.schemeLabel(), t.students(), t.byStatus().get(SchemeStatus.APPLIED),
                    t.byStatus().get(SchemeStatus.APPROVED), t.byStatus().get(SchemeStatus.RECEIVED),
                    t.byStatus().get(SchemeStatus.REJECTED), t.amountPaise() / 100);
        }
        return csv;
    }
}
