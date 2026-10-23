package com.akshara.assist;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.academics.AcademicsDirectory;
import com.akshara.academics.AcademicsDirectory.SectionInfo;
import com.akshara.assist.AssistCsv.Download;
import com.akshara.assist.AssistViews.AcceptedReport;
import com.akshara.assist.AssistViews.AcceptedRow;
import com.akshara.assist.AssistViews.AtRiskClassRow;
import com.akshara.assist.AssistViews.AtRiskReport;
import com.akshara.assist.AssistViews.Reason;
import com.akshara.assist.AssistViews.WatchRow;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;

/** The two reports in the reports hub: the at-risk list by class, and drafts accepted by teacher and month. */
@Service
@Transactional(readOnly = true)
public class AssistReportService {

    static final int MAX_MONTHS = 24;

    private final RiskService risk;
    private final RemarkDraftRepository drafts;
    private final AssistSettingsService settings;
    private final AcademicsDirectory academics;

    AssistReportService(RiskService risk, RemarkDraftRepository drafts, AssistSettingsService settings,
            AcademicsDirectory academics) {
        this.risk = risk;
        this.drafts = drafts;
        this.settings = settings;
        this.academics = academics;
    }

    // ------------------------------------------------------------------ at risk by class

    public AtRiskReport atRisk(UUID classId) {
        TenantContext.require();
        Map<UUID, SectionInfo> sections = academics.sections().stream()
                .collect(Collectors.toMap(SectionInfo::id, s -> s, (a, b) -> a, LinkedHashMap::new));
        Map<UUID, int[]> perSection = new LinkedHashMap<>();
        List<WatchRow> rows = new ArrayList<>();
        int high = 0;
        int medium = 0;
        int low = 0;
        for (WatchRow r : risk.allRows()) {
            SectionInfo section = sections.get(r.sectionId());
            if (classId != null && (section == null || !section.classId().equals(classId))) {
                continue;
            }
            int[] c = perSection.computeIfAbsent(r.sectionId(), k -> new int[3]);
            switch (r.level()) {
                case HIGH -> {
                    c[0]++;
                    high++;
                }
                case MEDIUM -> {
                    c[1]++;
                    medium++;
                }
                case LOW -> {
                    c[2]++;
                    low++;
                }
            }
            rows.add(r);
        }
        List<AtRiskClassRow> classes = new ArrayList<>();
        for (SectionInfo s : sections.values()) {
            int[] c = perSection.get(s.id());
            if (c != null) {
                classes.add(new AtRiskClassRow(s.id(), s.label(), c[0] + c[1] + c[2], c[0], c[1], c[2]));
            }
        }
        // Entries of sections that no longer exist still count, at the end.
        for (Map.Entry<UUID, int[]> e : perSection.entrySet()) {
            if (!sections.containsKey(e.getKey())) {
                int[] c = e.getValue();
                classes.add(new AtRiskClassRow(e.getKey(), rows.stream().filter(r -> r.sectionId().equals(e.getKey()))
                        .map(WatchRow::classLabel).findFirst().orElse(""), c[0] + c[1] + c[2], c[0], c[1], c[2]));
            }
        }
        return new AtRiskReport(settings.current().getLastRunAt(), classId, rows.size(), high, medium, low, classes,
                rows);
    }

    public Download atRiskCsv(UUID classId) {
        AtRiskReport report = atRisk(classId);
        AssistCsv csv = new AssistCsv("Class", "Student", "Score", "Level", "Status", "Assigned to",
                "Parent informed", "Reasons");
        for (WatchRow r : report.entries()) {
            csv.row(r.classLabel(), r.studentName(), r.score(), r.level().name(), r.status().name(),
                    r.assignedToName(), r.parentInformedAt() == null ? "" : r.parentInformedAt().toString(),
                    r.reasons().stream().map(Reason::detail).collect(Collectors.joining("; ")));
        }
        return csv.download("at-risk-students.csv");
    }

    // ------------------------------------------------------------------ drafts accepted by teacher and month

    /** Months "YYYY-MM" (both included); the last six months when both are null. */
    public AcceptedReport accepted(String from, String to) {
        TenantContext.require();
        YearMonth end = month(to, YearMonth.now(UsageService.INDIA), "to");
        YearMonth start = month(from, end.minusMonths(5), "from");
        if (end.isBefore(start)) {
            throw ApiException.badRequest("The end month is before the start month.", "to");
        }
        if (start.plusMonths(MAX_MONTHS).compareTo(end) <= 0) {
            throw ApiException.badRequest("Choose at most " + MAX_MONTHS + " months.", "to");
        }
        Map<String, long[]> counts = new LinkedHashMap<>();
        long drafted = 0;
        long accepted = 0;
        for (RemarkDraft d : drafts.findByCreatedAtGreaterThanEqualAndCreatedAtLessThan(UsageService.start(start),
                UsageService.start(end.plusMonths(1)))) {
            String month = YearMonth.from(d.getCreatedAt().atZone(UsageService.INDIA)).toString();
            long[] c = counts.computeIfAbsent(d.getCreatedByName() + "\u0000" + month, k -> new long[2]);
            c[0]++;
            drafted++;
            if (d.getStatus() == RemarkDraft.Status.ACCEPTED) {
                c[1]++;
                accepted++;
            }
        }
        List<AcceptedRow> rows = counts.entrySet().stream()
                .map(e -> new AcceptedRow(e.getKey().substring(0, e.getKey().indexOf('\u0000')),
                        e.getKey().substring(e.getKey().indexOf('\u0000') + 1), e.getValue()[0], e.getValue()[1]))
                .sorted((a, b) -> {
                    int byName = a.teacherName().compareToIgnoreCase(b.teacherName());
                    return byName != 0 ? byName : a.month().compareTo(b.month());
                })
                .toList();
        return new AcceptedReport(start.toString(), end.toString(), drafted, accepted, rows);
    }

    public Download acceptedCsv(String from, String to) {
        AcceptedReport report = accepted(from, to);
        AssistCsv csv = new AssistCsv("Teacher", "Month", "Drafted", "Accepted");
        for (AcceptedRow r : report.rows()) {
            csv.row(r.teacherName(), r.month(), r.drafted(), r.accepted());
        }
        return csv.download("remark-drafts-accepted-" + report.from() + "-to-" + report.to() + ".csv");
    }

    private static YearMonth month(String value, YearMonth fallback, String field) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return YearMonth.parse(value);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("Give the month as YYYY-MM.", field);
        }
    }
}
