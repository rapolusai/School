package com.akshara.health;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.academics.AcademicsDirectory.SectionInfo;
import com.akshara.health.HealthViews.AllergiesReport;
import com.akshara.health.HealthViews.ClassAllergies;
import com.akshara.health.HealthViews.DrillView;
import com.akshara.health.HealthViews.DrillsReport;
import com.akshara.health.HealthViews.InfirmaryReport;
import com.akshara.health.HealthViews.MonthCount;
import com.akshara.health.HealthViews.NamedCount;
import com.akshara.health.HealthViews.VerificationView;
import com.akshara.health.HealthViews.VerificationsReport;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;

/**
 * The health and safety reports of the reports hub: infirmary visits by month, allergies by class (counts only,
 * never a name), drills with their due dates, and verifications due. No report names a student's complaint or
 * condition, so a reader with health.read alone learns no diagnosis.
 */
@Service
@Transactional(readOnly = true)
public class HealthReportService {

    /** The longest range a report accepts, as the other reports (docs/api/phase-1-reports.md). */
    static final int MAX_RANGE_DAYS = 400;

    private final InfirmaryVisitRepository visits;
    private final RecordItemRepository items;
    private final SafetyService safety;
    private final StaffChecksService checks;
    private final HealthPeople people;

    HealthReportService(InfirmaryVisitRepository visits, RecordItemRepository items, SafetyService safety,
            StaffChecksService checks, HealthPeople people) {
        this.visits = visits;
        this.items = items;
        this.safety = safety;
        this.checks = checks;
        this.people = people;
    }

    public InfirmaryReport infirmary(LocalDate from, LocalDate to) {
        TenantContext.require();
        LocalDate today = HealthText.today();
        LocalDate end = to == null ? today : to;
        LocalDate start = from == null ? end.minusMonths(6).withDayOfMonth(1) : from;
        range(start, end);
        List<InfirmaryVisit> rows = visits.between(HealthText.startOfDay(start), HealthText.startOfDay(end.plusDays(1)));
        Map<String, long[]> byMonth = new TreeMap<>();
        Map<String, Long> byOutcome = new LinkedHashMap<>();
        Map<String, Long> byComplaint = new HashMap<>();
        for (InfirmaryVisit v : rows) {
            String month = YearMonth.from(HealthText.dayOf(v.getTimeIn())).toString();
            long[] c = byMonth.computeIfAbsent(month, k -> new long[5]);
            c[0]++;
            if (v.getPersonKind() == PersonKind.STUDENT) {
                c[1]++;
            } else {
                c[2]++;
            }
            if (v.getOutcome() == VisitOutcome.SENT_HOME) {
                c[3]++;
            } else if (v.getOutcome() == VisitOutcome.HOSPITAL) {
                c[4]++;
            }
            String outcome = v.getOutcome() == null ? "OPEN" : v.getOutcome().name();
            byOutcome.merge(outcome, 1L, Long::sum);
            byComplaint.merge(complaintKey(v.getComplaint()), 1L, Long::sum);
        }
        List<NamedCount> complaints = byComplaint.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(10).map(e -> new NamedCount(e.getKey(), e.getValue())).toList();
        return new InfirmaryReport(start, end, rows.size(), byMonth.entrySet().stream()
                .map(e -> new MonthCount(e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2],
                        e.getValue()[3], e.getValue()[4])).toList(),
                byOutcome.entrySet().stream().map(e -> new NamedCount(e.getKey(), e.getValue())).toList(),
                complaints);
    }

    HealthCsv infirmaryCsv(LocalDate from, LocalDate to) {
        InfirmaryReport r = infirmary(from, to);
        HealthCsv csv = new HealthCsv("Month", "Visits", "Students", "Staff", "Sent home", "Hospital");
        r.byMonth().forEach(m -> csv.row(m.month(), m.visits(), m.students(), m.staff(), m.sentHome(), m.hospital()));
        return csv;
    }

    /** Counts of students with allergies per class this year. Never a name. */
    public AllergiesReport allergies() {
        TenantContext.require();
        Map<UUID, SectionInfo> sectionOf = people.sectionsOfActiveStudents();
        Map<UUID, Set<UUID>> withAllergy = new HashMap<>();
        Set<UUID> severe = new HashSet<>();
        for (RecordItem i : items.findByKind(ItemKind.ALLERGY)) {
            SectionInfo s = sectionOf.get(i.getStudentId());
            if (s == null) {
                continue;
            }
            withAllergy.computeIfAbsent(s.id(), k -> new HashSet<>()).add(i.getStudentId());
            if (i.getSeverity() == AllergySeverity.SEVERE) {
                severe.add(i.getStudentId());
            }
        }
        Map<UUID, Long> studentsPerSection = new HashMap<>();
        sectionOf.values().forEach(s -> studentsPerSection.merge(s.id(), 1L, Long::sum));
        List<ClassAllergies> byClass = people.sections().stream()
                .map(s -> new ClassAllergies(s.id(), s.label(), studentsPerSection.getOrDefault(s.id(), 0L),
                        withAllergy.getOrDefault(s.id(), Set.of()).size(),
                        withAllergy.getOrDefault(s.id(), Set.of()).stream().filter(severe::contains).count()))
                .toList();
        long students = sectionOf.size();
        long any = withAllergy.values().stream().mapToLong(Set::size).sum();
        return new AllergiesReport(HealthText.today(), students, any, severe.stream()
                .filter(sectionOf::containsKey).count(), byClass);
    }

    HealthCsv allergiesCsv() {
        AllergiesReport r = allergies();
        HealthCsv csv = new HealthCsv("Class", "Students", "With allergies", "Severe");
        r.byClass().forEach(c -> csv.row(c.label(), c.students(), c.withAllergies(), c.severe()));
        return csv;
    }

    public DrillsReport drills() {
        TenantContext.require();
        List<DrillView> rows = safety.drills();
        LocalDate today = HealthText.today();
        LocalDate nextDue = SafetyService.nextDrillDue(rows);
        return new DrillsReport(today, nextDue, HealthText.dueState(nextDue, today), rows);
    }

    HealthCsv drillsCsv() {
        DrillsReport r = drills();
        HealthCsv csv = new HealthCsv("Date", "Kind", "Block", "Time to clear (s)", "Attendance", "Issues",
                "Next due", "Status");
        r.rows().forEach(d -> csv.row(d.drillDate(), d.kind().name(), d.block(), d.timeToClearSeconds(),
                d.attendance(), d.issues(), d.nextDue(), d.due().name()));
        return csv;
    }

    public VerificationsReport verificationsDue() {
        TenantContext.require();
        List<VerificationView> rows = checks.due();
        return new VerificationsReport(HealthText.today(), (int) rows.stream()
                .filter(r -> r.due() == HealthText.Due.OVERDUE || r.due() == HealthText.Due.DUE_SOON).count(),
                (int) rows.stream().filter(r -> r.due() == HealthText.Due.MISSING).count(), rows);
    }

    HealthCsv verificationsCsv() {
        VerificationsReport r = verificationsDue();
        HealthCsv csv = new HealthCsv("Name", "Designation", "Verification", "Requested", "Cleared", "Expires",
                "State", "POCSO training due", "First-aid training due");
        r.rows().forEach(v -> csv.row(v.name(), v.designation(), v.status() == null ? "MISSING" : v.status().name(),
                v.requestedOn(), v.clearedOn(), v.expiresOn(), v.due().name(), v.pocsoTrainingDue() ? "yes" : "no",
                v.firstAidTrainingDue() ? "yes" : "no"));
        return csv;
    }

    private static void range(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw ApiException.badRequest("The end is before the start.", "to");
        }
        if (from.plusDays(MAX_RANGE_DAYS).isBefore(to)) {
            throw ApiException.badRequest("Choose a range of at most " + MAX_RANGE_DAYS + " days.", "to");
        }
    }

    /** The first few words of a complaint, lower-cased, so "Fever" and "fever and cough" group together. */
    static String complaintKey(String complaint) {
        String[] words = complaint.trim().toLowerCase().split("\\s+");
        return words.length == 0 ? "" : words[0].replaceAll("[^\\p{L}\\p{N}]", "");
    }
}
