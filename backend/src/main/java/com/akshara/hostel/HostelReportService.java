package com.akshara.hostel;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.fees.FeeDuesService;
import com.akshara.fees.FeeViews.StudentFees;
import com.akshara.hostel.HostelViews.BoarderDues;
import com.akshara.hostel.HostelViews.BoarderDuesReport;
import com.akshara.hostel.HostelViews.BoarderView;
import com.akshara.hostel.HostelViews.HostelOccupancy;
import com.akshara.hostel.HostelViews.NamedCount;
import com.akshara.hostel.HostelViews.OccupancyReport;
import com.akshara.hostel.HostelViews.PassView;
import com.akshara.hostel.HostelViews.RollCallNight;
import com.akshara.hostel.HostelViews.RollCallReport;
import com.akshara.hostel.HostelViews.RollCallReportRow;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;

/**
 * The hostel's reports for the reports hub: occupancy by hostel (with the boarder list), roll-call summary over a
 * range, outings by month and the fee dues of boarders (read from the fees module's public ledger). Each has a CSV.
 */
@Service
@Transactional(readOnly = true)
public class HostelReportService {

    private final HostelRepository hostels;
    private final RoomRepository rooms;
    private final BedRepository beds;
    private final AllotmentRepository allotments;
    private final RollCallRepository rollCalls;
    private final HostelPassRepository passes;
    private final HostelPeople people;
    private final AllotmentService allotmentService;
    private final RollCallService rollCallService;
    private final HostelPassService passService;
    private final FeeDuesService fees;

    HostelReportService(HostelRepository hostels, RoomRepository rooms, BedRepository beds,
            AllotmentRepository allotments, RollCallRepository rollCalls, HostelPassRepository passes,
            HostelPeople people, AllotmentService allotmentService, RollCallService rollCallService,
            HostelPassService passService, FeeDuesService fees) {
        this.hostels = hostels;
        this.rooms = rooms;
        this.beds = beds;
        this.allotments = allotments;
        this.rollCalls = rollCalls;
        this.passes = passes;
        this.people = people;
        this.allotmentService = allotmentService;
        this.rollCallService = rollCallService;
        this.passService = passService;
        this.fees = fees;
    }

    /* ------------------------------------------------------------------ occupancy */

    public OccupancyReport occupancy() {
        TenantContext.require();
        List<Hostel> all = hostels.findAllByOrderByNameAsc();
        Map<UUID, String> wardens = people.staffNames(all.stream().map(Hostel::getWardenUserId).toList());
        List<HostelOccupancy> rows = new ArrayList<>();
        long totalBeds = 0;
        long totalOccupied = 0;
        for (Hostel h : all) {
            long bedCount = beds.countByHostelId(h.getId());
            long occupied = allotments.countActiveInHostel(h.getId());
            totalBeds += bedCount;
            totalOccupied += occupied;
            rows.add(new HostelOccupancy(h.getId(), h.getName(), h.getType(), wardens.get(h.getWardenUserId()),
                    rooms.findByHostelIdOrderByFloorAscNumberAsc(h.getId()).size(), bedCount, occupied,
                    bedCount - occupied, HostelRules.occupancyPercent(occupied, bedCount)));
        }
        return new OccupancyReport(HostelText.today(), totalBeds, totalOccupied, totalBeds - totalOccupied,
                HostelRules.occupancyPercent(totalOccupied, totalBeds), rows,
                allotmentService.boarderViews(allotments.active()));
    }

    public HostelCsv occupancyCsv() {
        OccupancyReport r = occupancy();
        HostelCsv csv = new HostelCsv("Hostel", "Type", "Warden", "Rooms", "Beds", "Occupied", "Free",
                "Occupancy %");
        r.hostels().forEach(h -> csv.row(h.hostelName(), h.type().name(), h.wardenName(), h.rooms(), h.beds(),
                h.occupied(), h.free(), h.occupancyPercent()));
        csv.row();
        csv.row("Student", "Admission no.", "Class", "Hostel", "Block", "Room", "Bed", "Since", "Guardian",
                "Guardian phone");
        r.rows().forEach(b -> csv.row(b.studentName(), b.admissionNo(), b.className(), b.hostelName(),
                b.blockName(), b.roomNumber(), b.bedLabel(), b.since(), b.guardianName(), b.guardianPhoneMasked()));
        return csv;
    }

    /* ------------------------------------------------------------------ roll call */

    public RollCallReport rollCall(LocalDate from, LocalDate to) {
        TenantContext.require();
        LocalDate end = to == null ? HostelText.today() : to;
        LocalDate start = from == null ? end.minusDays(30) : from;
        if (start.isAfter(end)) {
            throw ApiException.badRequest("The range ends before it starts.", "to");
        }
        List<RollCall> nights = rollCalls.allBetween(start, end);
        Map<UUID, String> names = hostels.findAllByOrderByNameAsc().stream()
                .collect(Collectors.toMap(Hostel::getId, Hostel::getName));
        Map<UUID, RollCall> byId = nights.stream().collect(Collectors.toMap(RollCall::getId, n -> n));
        List<RollCallReportRow> rows = new ArrayList<>();
        long present = 0;
        long onLeave = 0;
        long absent = 0;
        for (RollCallNight n : rollCallService.nightViews(nights)) {
            RollCall call = byId.get(n.id());
            present += n.present();
            onLeave += n.onLeave();
            absent += n.absent();
            rows.add(new RollCallReportRow(n.night(), call.getHostelId(), names.get(call.getHostelId()),
                    n.present(), n.onLeave(), n.absent(), n.absentees()));
        }
        return new RollCallReport(start, end, rows.size(), present, onLeave, absent, rows);
    }

    public HostelCsv rollCallCsv(LocalDate from, LocalDate to) {
        RollCallReport r = rollCall(from, to);
        HostelCsv csv = new HostelCsv("Night", "Hostel", "Present", "On leave", "Absent", "Absentees");
        r.rows().forEach(n -> csv.row(n.night(), n.hostelName(), n.present(), n.onLeave(), n.absent(),
                String.join("; ", n.absentees())));
        return csv;
    }

    /* ------------------------------------------------------------------ outings */

    public HostelViews.OutingsReport outings(String month) {
        TenantContext.require();
        YearMonth ym;
        try {
            ym = month == null ? YearMonth.from(HostelText.today()) : YearMonth.parse(month);
        } catch (java.time.format.DateTimeParseException e) {
            throw ApiException.badRequest("Give the month as YYYY-MM.", "month");
        }
        List<PassView> rows = passService.views(passes.between(ym.atDay(1), ym.atEndOfMonth()));
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (HostelPassStatus s : HostelPassStatus.values()) {
            byStatus.put(s.name(), 0L);
        }
        long outings = 0;
        long leaves = 0;
        long late = 0;
        for (PassView p : rows) {
            byStatus.merge(p.status().name(), 1L, Long::sum);
            if (p.kind() == PassKind.OUTING) {
                outings++;
            } else {
                leaves++;
            }
            if (p.late()) {
                late++;
            }
        }
        List<NamedCount> counts = byStatus.entrySet().stream().map(e -> new NamedCount(e.getKey(), e.getValue()))
                .toList();
        return new HostelViews.OutingsReport(ym.toString(), rows.size(), outings, leaves, late, counts, rows);
    }

    public HostelCsv outingsCsv(String month) {
        HostelViews.OutingsReport r = outings(month);
        HostelCsv csv = new HostelCsv("Student", "Class", "Hostel", "Kind", "From", "To", "Return by", "Status",
                "Requested by", "Decided by", "Out at", "In at", "Late", "Reason");
        r.rows().forEach(p -> csv.row(p.studentName(), p.className(), p.hostelName(), p.kind().name(),
                p.fromDate(), p.toDate(), p.returnBy(), p.status().name(), p.requestedByName(), p.decidedByName(),
                p.outAt(), p.inAt(), p.late() ? "yes" : "", p.reason()));
        return csv;
    }

    /* ------------------------------------------------------------------ fee dues */

    /**
     * The boarders' fee standing from the fees module (every fee head, not only hostel fees). Not read-only: the
     * fees ledger creates this year's dues when they are missing.
     */
    @Transactional
    public BoarderDuesReport feeDues() {
        TenantContext.require();
        List<BoarderView> boarders = allotmentService.boarderViews(allotments.active());
        List<BoarderDues> rows = new ArrayList<>();
        long balance = 0;
        long overdue = 0;
        Set<UUID> seen = new java.util.HashSet<>();
        for (BoarderView b : boarders) {
            if (!seen.add(b.studentId())) {
                continue;
            }
            StudentFees f;
            try {
                f = fees.studentFees(b.studentId());
            } catch (ApiException e) {
                continue;
            }
            balance += f.totals().balancePaise();
            overdue += f.totals().overduePaise();
            rows.add(new BoarderDues(b.studentId(), b.studentName(), b.admissionNo(), b.className(),
                    b.hostelName(), b.roomNumber(), f.totals().netPaise(), f.totals().paidPaise(),
                    f.totals().balancePaise(), f.totals().overduePaise()));
        }
        return new BoarderDuesReport(HostelText.today(), rows.size(), balance, overdue, rows);
    }

    @Transactional
    public HostelCsv feeDuesCsv() {
        BoarderDuesReport r = feeDues();
        HostelCsv csv = new HostelCsv("Student", "Admission no.", "Class", "Hostel", "Room", "Net fees (Rs)",
                "Paid (Rs)", "Balance (Rs)", "Overdue (Rs)");
        r.rows().forEach(d -> csv.row(d.studentName(), d.admissionNo(), d.className(), d.hostelName(),
                d.roomNumber(), HostelCsv.rupees(d.netPaise()), HostelCsv.rupees(d.paidPaise()),
                HostelCsv.rupees(d.balancePaise()), HostelCsv.rupees(d.overduePaise())));
        return csv;
    }
}
