package com.akshara.frontoffice;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.frontoffice.FrontOfficeViews.DayCount;
import com.akshara.frontoffice.FrontOfficeViews.EnquiriesReport;
import com.akshara.frontoffice.FrontOfficeViews.EnquiryView;
import com.akshara.frontoffice.FrontOfficeViews.FollowUp;
import com.akshara.frontoffice.FrontOfficeViews.FollowUpQueue;
import com.akshara.frontoffice.FrontOfficeViews.FollowUpsReport;
import com.akshara.frontoffice.FrontOfficeViews.GatePassView;
import com.akshara.frontoffice.FrontOfficeViews.NamedCount;
import com.akshara.frontoffice.FrontOfficeViews.PassDay;
import com.akshara.frontoffice.FrontOfficeViews.PassesReport;
import com.akshara.frontoffice.FrontOfficeViews.VisitorView;
import com.akshara.frontoffice.FrontOfficeViews.VisitorsReport;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;

/** The front office's reports for the reports hub: visitors, gate passes, enquiries and follow-ups, as JSON or CSV. */
@Service
@Transactional(readOnly = true)
public class FrontOfficeReportService {

    static final int MAX_RANGE_DAYS = 400;

    private final VisitorRepository visitors;
    private final GatePassRepository passes;
    private final EnquiryRepository enquiries;
    private final GatePassService gatePasses;
    private final EnquiryService enquiryService;

    FrontOfficeReportService(VisitorRepository visitors, GatePassRepository passes, EnquiryRepository enquiries,
            GatePassService gatePasses, EnquiryService enquiryService) {
        this.visitors = visitors;
        this.passes = passes;
        this.enquiries = enquiries;
        this.gatePasses = gatePasses;
        this.enquiryService = enquiryService;
    }

    record Range(LocalDate from, LocalDate to) {

        Instant start() {
            return FrontOfficeText.startOfDay(from);
        }

        Instant end() {
            return FrontOfficeText.startOfDay(to.plusDays(1));
        }
    }

    /** This month to today when no dates are given; at most 400 days. */
    static Range range(LocalDate from, LocalDate to) {
        LocalDate today = FrontOfficeText.today();
        LocalDate end = to == null ? today : to;
        LocalDate start = from == null ? end.withDayOfMonth(1) : from;
        if (end.isBefore(start)) {
            throw ApiException.badRequest("The end date is before the start date.", "to");
        }
        if (start.plusDays(MAX_RANGE_DAYS).isBefore(end)) {
            throw ApiException.badRequest("Choose a range of at most " + MAX_RANGE_DAYS + " days.", "to");
        }
        return new Range(start, end);
    }

    public VisitorsReport visitors(LocalDate from, LocalDate to) {
        TenantContext.require();
        Range r = range(from, to);
        List<Visitor> rows = visitors.registerBetween(r.start(), r.end());
        Map<LocalDate, long[]> days = new TreeMap<>();
        for (Visitor v : rows) {
            long[] d = days.computeIfAbsent(FrontOfficeText.dayOf(v.getCheckedInAt()), k -> new long[2]);
            d[0]++;
            d[1] += v.getPeopleCount();
        }
        List<DayCount> byDay = days.entrySet().stream()
                .map(e -> new DayCount(e.getKey(), e.getValue()[0], e.getValue()[1])).toList();
        List<NamedCount> byPurpose = counts(rows, v -> v.getPurpose().name());
        long people = rows.stream().mapToLong(Visitor::getPeopleCount).sum();
        return new VisitorsReport(r.from(), r.to(), rows.size(), people, byDay, byPurpose,
                rows.stream().map(v -> VisitorService.view(v, List.of(), false)).toList());
    }

    public FrontOfficeCsv visitorsCsv(LocalDate from, LocalDate to) {
        VisitorsReport report = visitors(from, to);
        FrontOfficeCsv csv = new FrontOfficeCsv("Date", "Checked in", "Checked out", "Name", "Phone", "Purpose",
                "To meet", "People", "ID shown", "Badge");
        for (VisitorView v : report.rows()) {
            csv.row(FrontOfficeText.dayOf(v.checkedInAt()), v.checkedInAt(), v.checkedOutAt(), v.name(),
                    v.phoneMasked(), v.purpose().name(), v.toMeetName(), v.peopleCount(), v.idType().name(),
                    v.badgeNo());
        }
        return csv;
    }

    public PassesReport gatePasses(LocalDate from, LocalDate to) {
        TenantContext.require();
        Range r = range(from, to);
        List<GatePass> rows = passes.between(r.start(), r.end());
        Map<LocalDate, long[]> days = new TreeMap<>();
        for (GatePass p : rows) {
            long[] d = days.computeIfAbsent(FrontOfficeText.dayOf(p.getPickupAt()), k -> new long[7]);
            d[p.getStatus().ordinal()]++;
        }
        List<PassDay> byDay = days.entrySet().stream().map(e -> new PassDay(e.getKey(), e.getValue()[0],
                e.getValue()[1], e.getValue()[2], e.getValue()[3], e.getValue()[4], e.getValue()[5],
                e.getValue()[6])).toList();
        return new PassesReport(r.from(), r.to(), rows.size(), byDay, gatePasses.views(rows, Instant.now()));
    }

    public FrontOfficeCsv gatePassesCsv(LocalDate from, LocalDate to) {
        PassesReport report = gatePasses(from, to);
        FrontOfficeCsv csv = new FrontOfficeCsv("Date", "Pickup at", "Student", "Admission no.", "Class", "Source",
                "Requested by", "Collector", "Status", "Approved by", "Released at", "Collected by");
        for (GatePassView p : report.rows()) {
            csv.row(FrontOfficeText.dayOf(p.pickupAt()), p.pickupAt(), p.studentName(), p.admissionNo(),
                    p.className(), p.source().name(), p.requestedByName(),
                    p.collectorKind() == CollectorKind.SELF ? "Parent" : p.collectorName(), p.status().name(),
                    p.approvedByName(), p.releasedAt(), p.collectedByName());
        }
        return csv;
    }

    public EnquiriesReport enquiries(LocalDate from, LocalDate to) {
        TenantContext.require();
        Range r = range(from, to);
        List<Enquiry> rows = enquiries.between(r.start(), r.end());
        return new EnquiriesReport(r.from(), r.to(), rows.size(), counts(rows, e -> e.getSource().name()),
                counts(rows, e -> e.getTopic().name()), counts(rows, e -> e.getStatus().name()),
                rows.stream().map(EnquiryService::view).toList());
    }

    public FrontOfficeCsv enquiriesCsv(LocalDate from, LocalDate to) {
        EnquiriesReport report = enquiries(from, to);
        FrontOfficeCsv csv = new FrontOfficeCsv("Date", "Source", "Topic", "Name", "Phone", "Child", "Class",
                "Details", "Status", "Outcome", "Follow-up", "Assigned to");
        for (EnquiryView e : report.rows()) {
            csv.row(FrontOfficeText.dayOf(e.enquiredAt()), e.source().name(), e.topic().name(), e.name(),
                    e.phoneMasked(), e.childName(), e.className(), e.details(), e.status().name(), e.outcome(),
                    e.followUpOn(), e.assignedToName());
        }
        return csv;
    }

    public FollowUpsReport followUps() {
        FollowUpQueue queue = enquiryService.followUps();
        return new FollowUpsReport(queue.today(), queue.overdue(), queue.items());
    }

    public FrontOfficeCsv followUpsCsv() {
        FollowUpsReport report = followUps();
        FrontOfficeCsv csv = new FrontOfficeCsv("Kind", "Name", "Phone", "About", "Follow-up on", "Days overdue",
                "Assigned to");
        for (FollowUp f : report.rows()) {
            csv.row(f.kind(), f.name(), f.phoneMasked(), f.about(), f.followUpOn(), f.daysOverdue(),
                    f.assignedToName());
        }
        return csv;
    }

    private static <T> List<NamedCount> counts(List<T> rows, Function<T, String> key) {
        Map<String, Long> counts = rows.stream().collect(Collectors.groupingBy(key, TreeMap::new,
                Collectors.counting()));
        List<NamedCount> result = new ArrayList<>();
        counts.forEach((k, v) -> result.add(new NamedCount(k, v)));
        result.sort((a, b) -> Long.compare(b.count(), a.count()));
        return result;
    }
}
