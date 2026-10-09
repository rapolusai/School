package com.akshara.fees;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.academics.AcademicsDirectory;
import com.akshara.academics.AcademicsDirectory.SectionInfo;
import com.akshara.academics.AcademicsDirectory.YearInfo;
import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.fees.FeeForms.ReminderForm;
import com.akshara.fees.FeeViews.CollectionReport;
import com.akshara.fees.FeeViews.CollectionTotal;
import com.akshara.fees.FeeViews.HeadTotal;
import com.akshara.fees.FeeViews.ModeTotal;
import com.akshara.fees.FeeViews.OutstandingReport;
import com.akshara.fees.FeeViews.OutstandingRow;
import com.akshara.fees.FeeViews.OverdueReport;
import com.akshara.fees.FeeViews.OverdueRow;
import com.akshara.fees.FeeViews.Overview;
import com.akshara.fees.FeeViews.ReminderResult;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;

/**
 * Fee reports: collections by mode and head, outstanding by class and section, the overdue list with reminders, and
 * CSV exports for accounting. Reports first create dues for students who joined a class after its structure was
 * published, so they include everyone.
 */
@Service
@Transactional
public class FeeReportService {

    /** The longest range a collection report or export covers. */
    static final int MAX_RANGE_DAYS = 731;

    private static final DateTimeFormatter TALLY_DATE = DateTimeFormatter.ofPattern("dd-MM-yyyy");

    private final FeeDuesService duesService;
    private final StudentDueRepository dues;
    private final ReceiptRepository receipts;
    private final PaymentAllocationRepository allocations;
    private final FeeHeadRepository heads;
    private final FeeInstalmentRepository instalments;
    private final LateFeeWaiverRepository waivers;
    private final FeeReminderRepository reminders;
    private final StudentRoster roster;
    private final AcademicsDirectory academics;
    private final AuditService audit;
    private final ApplicationEventPublisher events;

    FeeReportService(FeeDuesService duesService, StudentDueRepository dues, ReceiptRepository receipts,
            PaymentAllocationRepository allocations, FeeHeadRepository heads, FeeInstalmentRepository instalments,
            LateFeeWaiverRepository waivers, FeeReminderRepository reminders, StudentRoster roster,
            AcademicsDirectory academics, AuditService audit, ApplicationEventPublisher events) {
        this.duesService = duesService;
        this.dues = dues;
        this.receipts = receipts;
        this.allocations = allocations;
        this.heads = heads;
        this.instalments = instalments;
        this.waivers = waivers;
        this.reminders = reminders;
        this.roster = roster;
        this.academics = academics;
        this.audit = audit;
        this.events = events;
    }

    // ------------------------------------------------------------------ overview

    /** Today's and this month's collection, and what is outstanding and overdue in the current year. */
    public Overview overview() {
        TenantContext.require();
        LocalDate today = SchoolDay.today();
        CollectionTotal todayTotal = total(today, today);
        CollectionTotal month = total(today.withDayOfMonth(1), today);
        var recent = receipts.findAllByOrderByReceivedAtDesc(Limit.of(8)).stream().map(ReceiptMapper::summary)
                .toList();
        Optional<YearInfo> year = academics.currentYear();
        if (year.isEmpty()) {
            return new Overview(null, null, today, todayTotal, month, 0, 0, 0, 0, recent);
        }
        duesService.syncYear(year.get().id(), roster.year(year.get().id()));
        long outstanding = 0;
        long overdue = 0;
        long overdueStudents = 0;
        for (Object[] row : dues.summaryPerStudent(year.get().id(), today)) {
            outstanding += num(row[1]) - num(row[2]) - num(row[3]);
            long o = num(row[5]);
            overdue += o;
            if (o > 0) {
                overdueStudents++;
            }
        }
        return new Overview(year.get().id(), year.get().name(), today, todayTotal, month, outstanding, overdue,
                overdueStudents, outstanding - overdue, recent);
    }

    private CollectionTotal total(LocalDate from, LocalDate to) {
        long amount = 0;
        long count = 0;
        for (Object[] row : receipts.collectedPerMode(from, to)) {
            count += num(row[1]);
            amount += num(row[2]);
        }
        return new CollectionTotal(amount, count);
    }

    // ------------------------------------------------------------------ collection

    /** Receipts issued between two days (inclusive) that are not cancelled, by mode and by fee head. */
    @Transactional(readOnly = true)
    public CollectionReport collection(LocalDate from, LocalDate to) {
        TenantContext.require();
        checkRange(from, to);
        List<ModeTotal> byMode = new ArrayList<>();
        long total = 0;
        long count = 0;
        for (Object[] row : receipts.collectedPerMode(from, to)) {
            ModeTotal m = new ModeTotal((PaymentMode) row[0], num(row[1]), num(row[2]));
            byMode.add(m);
            total += m.amountPaise();
            count += m.receiptCount();
        }
        byMode.sort(Comparator.comparing(ModeTotal::mode));
        Map<UUID, FeeHead> headById = duesService.headsById();
        List<HeadTotal> byHead = allocations.collectedPerHead(from, to).stream()
                .map(row -> {
                    FeeHead h = headById.get((UUID) row[0]);
                    return new HeadTotal((UUID) row[0], h == null ? "" : h.getName(), num(row[1]));
                })
                .sorted(Comparator.comparing((HeadTotal t) -> FeeDuesService.headOrder(headById.get(t.headId())))
                        .thenComparing(HeadTotal::headName))
                .toList();
        long lateFee = 0;
        long advance = 0;
        for (Object[] row : allocations.collectedPerKind(from, to)) {
            if (PaymentAllocation.LATE_FEE.equals(row[0])) {
                lateFee = num(row[1]);
            } else if (PaymentAllocation.ADVANCE.equals(row[0])) {
                advance = num(row[1]);
            }
        }
        return new CollectionReport(from, to, total, count, receipts.cancelledBetween(from, to), byMode, byHead,
                lateFee, advance);
    }

    static void checkRange(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw ApiException.badRequest("The end date must not be before the start date.", "to");
        }
        if (ChronoUnit.DAYS.between(from, to) > MAX_RANGE_DAYS) {
            throw ApiException.badRequest("Choose a range of at most two years.", "to");
        }
    }

    // ------------------------------------------------------------------ outstanding

    /** What each section owes in a year (the current year by default), optionally for one class or section. */
    public OutstandingReport outstanding(UUID yearId, UUID classId, UUID sectionId) {
        TenantContext.require();
        Optional<YearInfo> year = year(yearId);
        LocalDate today = SchoolDay.today();
        if (year.isEmpty()) {
            return new OutstandingReport(null, null, today, List.of(), emptyRow());
        }
        Map<UUID, StudentRoster.Entry> students = roster.year(year.get().id());
        duesService.syncYear(year.get().id(), students);
        Map<UUID, Integer> sectionOrder = new HashMap<>();
        List<SectionInfo> sections = academics.sections();
        for (int i = 0; i < sections.size(); i++) {
            sectionOrder.put(sections.get(i).id(), i);
        }
        Map<UUID, long[]> bySection = new LinkedHashMap<>();
        Map<UUID, StudentRoster.Entry> sample = new HashMap<>();
        UUID noSection = new UUID(0, 0);
        for (Object[] row : dues.summaryPerStudent(year.get().id(), today)) {
            StudentRoster.Entry s = students.get((UUID) row[0]);
            if (classId != null && (s == null || !classId.equals(s.classId()))) {
                continue;
            }
            if (sectionId != null && (s == null || !sectionId.equals(s.sectionId()))) {
                continue;
            }
            UUID key = s == null || s.sectionId() == null ? noSection : s.sectionId();
            sample.putIfAbsent(key, s);
            long[] t = bySection.computeIfAbsent(key, k -> new long[6]);
            long net = num(row[1]) - num(row[2]);
            t[0]++;
            t[1] += net;
            t[2] += num(row[3]);
            t[3] += net - num(row[3]);
            t[4] += num(row[4]);
            t[5] += num(row[5]);
        }
        List<OutstandingRow> rows = bySection.entrySet().stream()
                .sorted(Comparator.comparing(e -> sectionOrder.getOrDefault(e.getKey(), Integer.MAX_VALUE)))
                .map(e -> {
                    StudentRoster.Entry s = sample.get(e.getKey());
                    long[] t = e.getValue();
                    return new OutstandingRow(s == null ? null : s.classId(), s == null ? null : s.className(),
                            s == null ? null : s.sectionId(), s == null ? null : s.sectionName(), t[0], t[1], t[2],
                            t[3], t[4], t[5]);
                })
                .toList();
        long[] sum = new long[6];
        for (OutstandingRow r : rows) {
            sum[0] += r.students();
            sum[1] += r.netPaise();
            sum[2] += r.paidPaise();
            sum[3] += r.balancePaise();
            sum[4] += r.dueSoFarPaise();
            sum[5] += r.overduePaise();
        }
        return new OutstandingReport(year.get().id(), year.get().name(), today, rows,
                new OutstandingRow(null, null, null, null, sum[0], sum[1], sum[2], sum[3], sum[4], sum[5]));
    }

    private static OutstandingRow emptyRow() {
        return new OutstandingRow(null, null, null, null, 0, 0, 0, 0, 0, 0);
    }

    // ------------------------------------------------------------------ overdue

    /** Students with fees still owed after their due date, most overdue first. Phone numbers are masked. */
    public OverdueReport overdue(UUID yearId, UUID classId, UUID sectionId, Integer minDays) {
        TenantContext.require();
        Optional<YearInfo> year = year(yearId);
        LocalDate today = SchoolDay.today();
        if (year.isEmpty()) {
            return new OverdueReport(null, null, today, List.of(), 0);
        }
        Map<UUID, StudentRoster.Entry> students = roster.year(year.get().id());
        duesService.syncYear(year.get().id(), students);
        List<OverdueRow> rows = overdueRows(year.get().id(), students, today).stream()
                .filter(r -> classId == null || classId.equals(classOf(students, r.studentId())))
                .filter(r -> sectionId == null || sectionId.equals(sectionOf(students, r.studentId())))
                .filter(r -> minDays == null || r.daysOverdue() >= minDays)
                .toList();
        return new OverdueReport(year.get().id(), year.get().name(), today, rows,
                rows.stream().mapToLong(OverdueRow::overduePaise).sum());
    }

    private static UUID classOf(Map<UUID, StudentRoster.Entry> students, UUID id) {
        StudentRoster.Entry s = students.get(id);
        return s == null ? null : s.classId();
    }

    private static UUID sectionOf(Map<UUID, StudentRoster.Entry> students, UUID id) {
        StudentRoster.Entry s = students.get(id);
        return s == null ? null : s.sectionId();
    }

    private List<OverdueRow> overdueRows(UUID yearId, Map<UUID, StudentRoster.Entry> students, LocalDate today) {
        List<StudentDue> list = dues.overdue(yearId, today);
        if (list.isEmpty()) {
            return List.of();
        }
        Set<UUID> studentIds = list.stream().map(StudentDue::getStudentId).collect(Collectors.toSet());
        Map<UUID, FeeInstalment> instalmentById = instalments.findAllById(list.stream()
                .map(StudentDue::getInstalmentId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(FeeInstalment::getId, Function.identity()));
        FeeMath.LateFeeRule rule = duesService.lateFeeRule();
        Set<String> waived = new HashSet<>();
        for (LateFeeWaiver w : waivers.findByStudentIdIn(studentIds)) {
            waived.add(w.getStudentId() + "/" + w.getInstalmentId());
        }
        Map<String, Long> charged = new HashMap<>();
        for (Object[] row : allocations.lateFeesCharged(studentIds)) {
            charged.put(row[0] + "/" + row[1], num(row[2]));
        }
        Map<UUID, Instant> lastReminder = new HashMap<>();
        for (Object[] row : reminders.lastRequested(studentIds)) {
            lastReminder.put((UUID) row[0], (Instant) row[1]);
        }
        // Per student, per instalment: the overdue balance.
        Map<UUID, Map<UUID, Long>> byStudent = new HashMap<>();
        for (StudentDue d : list) {
            byStudent.computeIfAbsent(d.getStudentId(), k -> new HashMap<>())
                    .merge(d.getInstalmentId(), d.balance(), Long::sum);
        }
        List<OverdueRow> rows = new ArrayList<>();
        for (Map.Entry<UUID, Map<UUID, Long>> e : byStudent.entrySet()) {
            UUID studentId = e.getKey();
            StudentRoster.Entry s = students.get(studentId);
            long overdue = 0;
            long lateFee = 0;
            LocalDate oldest = null;
            TreeMap<LocalDate, String> labels = new TreeMap<>();
            for (Map.Entry<UUID, Long> i : e.getValue().entrySet()) {
                FeeInstalment instalment = instalmentById.get(i.getKey());
                overdue += i.getValue();
                String key = studentId + "/" + instalment.getId();
                lateFee += FeeDuesService.lateFeeOutstanding(rule, instalment.getDueDate(), i.getValue(),
                        waived.contains(key), charged.getOrDefault(key, 0L), today);
                if (oldest == null || instalment.getDueDate().isBefore(oldest)) {
                    oldest = instalment.getDueDate();
                }
                labels.put(instalment.getDueDate(), instalment.getLabel());
            }
            rows.add(new OverdueRow(studentId, s == null ? null : s.fullName(), s == null ? null : s.admissionNo(),
                    s == null ? null : s.className(), s == null ? null : s.sectionName(),
                    s == null ? null : s.guardianName(), s == null ? null : StudentRoster.maskPhone(s.guardianPhone()),
                    overdue, lateFee, oldest, ChronoUnit.DAYS.between(oldest, today),
                    new ArrayList<>(new LinkedHashSet<>(labels.values())), lastReminder.get(studentId)));
        }
        rows.sort(Comparator.comparingLong(OverdueRow::daysOverdue).reversed()
                .thenComparing(Comparator.comparingLong(OverdueRow::overduePaise).reversed())
                .thenComparing(r -> r.fullName() == null ? "" : r.fullName()));
        return rows;
    }

    /**
     * Records an overdue-fee reminder for each chosen student who is overdue in the current year and publishes a
     * {@link FeeReminderRequested} event per student; students with nothing overdue are skipped. Ids of students who
     * are not in this school's current year are a 400.
     */
    public ReminderResult remind(ReminderForm form, Actor actor) {
        TenantContext.require();
        Actor by = Fees.actor(actor);
        YearInfo year = academics.currentYear()
                .orElseThrow(() -> Fees.conflict("No academic year", "Set up an academic year first."));
        Map<UUID, StudentRoster.Entry> students = roster.year(year.id());
        Set<UUID> ids = new LinkedHashSet<>(form.studentIds());
        if (!students.keySet().containsAll(ids)) {
            throw ApiException.badRequest("Pick students of this school.", "studentIds");
        }
        duesService.syncYear(year.id(), students);
        Map<UUID, OverdueRow> overdue = overdueRows(year.id(), students, SchoolDay.today()).stream()
                .collect(Collectors.toMap(OverdueRow::studentId, Function.identity()));
        int requested = 0;
        int skipped = 0;
        UUID tenantId = TenantContext.require();
        for (UUID id : ids) {
            OverdueRow row = overdue.get(id);
            if (row == null) {
                skipped++;
                continue;
            }
            FeeReminder reminder = reminders.save(new FeeReminder(id, row.overduePaise(),
                    (int) Math.min(Integer.MAX_VALUE, row.daysOverdue()), by.id(), by.name()));
            events.publishEvent(new FeeReminderRequested(tenantId, reminder.getId(), id, row.overduePaise(),
                    row.daysOverdue(), row.oldestDueDate()));
            requested++;
        }
        reminders.flush();
        if (requested > 0) {
            audit.record(by, "fee_reminders.requested", "fee_reminder", null, Map.of("requested", requested,
                    "skipped", skipped, "academicYear", year.name()));
        }
        return new ReminderResult(requested, skipped);
    }

    // ------------------------------------------------------------------ Tally export

    /**
     * Collections for accounting software such as Tally: one row per receipt and ledger (fee head, "Late fee" or
     * "Advance fee"), dated the day received. A receipt cancelled within the range adds the same rows with negative
     * amounts on the day it was cancelled, so the export always adds up to the money actually kept.
     */
    @Transactional(readOnly = true)
    public String tallyCsv(LocalDate from, LocalDate to) {
        TenantContext.require();
        checkRange(from, to);
        record Line(LocalDate date, String receiptNo, String ledger, long amountPaise, String mode,
                String narration) {
        }
        List<Line> lines = new ArrayList<>();
        List<Receipt> issued = receipts.issuedBetween(from, to);
        List<Receipt> cancelled = receipts.cancelledDuring(SchoolDay.startOf(from), SchoolDay.startOf(to.plusDays(1)));
        Set<UUID> ids = new HashSet<>();
        issued.forEach(r -> ids.add(r.getId()));
        cancelled.forEach(r -> ids.add(r.getId()));
        Map<UUID, List<PaymentAllocation>> allocationsOf = ids.isEmpty() ? Map.of()
                : allocations.findByReceiptIdInOrderByLineNoAscEntryAsc(ids).stream()
                        .filter(a -> PaymentAllocation.ALLOCATION.equals(a.getEntry()))
                        .collect(Collectors.groupingBy(PaymentAllocation::getReceiptId));
        for (Receipt r : issued) {
            for (Map.Entry<String, Long> l : ledgers(allocationsOf.getOrDefault(r.getId(), List.of())).entrySet()) {
                lines.add(new Line(r.getReceivedOn(), r.getReceiptNo(), l.getKey(), l.getValue(), r.getMode().name(),
                        narration(r, allocationsOf.getOrDefault(r.getId(), List.of()))));
            }
        }
        for (Receipt r : cancelled) {
            LocalDate on = SchoolDay.of(r.getCancelledAt());
            for (Map.Entry<String, Long> l : ledgers(allocationsOf.getOrDefault(r.getId(), List.of())).entrySet()) {
                lines.add(new Line(on, r.getReceiptNo(), l.getKey(), -l.getValue(), r.getMode().name(),
                        "Cancelled receipt " + r.getReceiptNo() + ": " + r.getCancelReason()));
            }
        }
        lines.sort(Comparator.comparing(Line::date).thenComparing(Line::receiptNo)
                .thenComparing(l -> l.amountPaise() < 0 ? 1 : 0));
        Csv csv = new Csv("Date", "Receipt No", "Ledger", "Amount", "Mode", "Narration");
        for (Line l : lines) {
            csv.row(l.date().format(TALLY_DATE), l.receiptNo(), l.ledger(), new Csv.Money(l.amountPaise()), l.mode(),
                    l.narration());
        }
        return csv.toString();
    }

    /** Amount per ledger of a receipt, heads first in the order paid. */
    private static Map<String, Long> ledgers(List<PaymentAllocation> lines) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (PaymentAllocation a : lines) {
            String ledger = switch (a.getKind()) {
                case PaymentAllocation.LATE_FEE -> "Late fee";
                case PaymentAllocation.ADVANCE -> "Advance fee";
                default -> a.getHeadName();
            };
            result.merge(ledger, a.getAmountPaise(), Long::sum);
        }
        return result;
    }

    private static String narration(Receipt r, List<PaymentAllocation> lines) {
        StringBuilder text = new StringBuilder("Fee from ").append(r.getStudentName()).append(" (")
                .append(r.getAdmissionNo()).append(')');
        if (r.getClassLabel() != null) {
            text.append(", ").append(r.getClassLabel());
        }
        String instalmentLabels = lines.stream().map(PaymentAllocation::getInstalmentLabel)
                .filter(l -> l != null).distinct().collect(Collectors.joining(", "));
        if (!instalmentLabels.isEmpty()) {
            text.append(", ").append(instalmentLabels);
        }
        if (r.getChequeNo() != null) {
            text.append(", cheque ").append(r.getChequeNo()).append(' ').append(r.getBankName());
        }
        if (r.getReference() != null) {
            text.append(", ref ").append(r.getReference());
        }
        if (r.getGatewayPaymentId() != null) {
            text.append(", payment ").append(r.getGatewayPaymentId());
        }
        if (r.isCancelled()) {
            text.append(" (cancelled later)");
        }
        return text.toString();
    }

    // ------------------------------------------------------------------ helpers

    private Optional<YearInfo> year(UUID yearId) {
        return yearId == null ? academics.currentYear()
                : Optional.of(academics.year(yearId).orElseThrow(() -> ApiException.notFound("Academic year")));
    }

    private static long num(Object value) {
        return value == null ? 0 : ((Number) value).longValue();
    }
}
