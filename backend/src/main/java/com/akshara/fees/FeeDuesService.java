package com.akshara.fees;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.persistence.EntityManager;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.academics.AcademicsDirectory;
import com.akshara.academics.AcademicsDirectory.YearInfo;
import com.akshara.fees.FeeViews.ConcessionView;
import com.akshara.fees.FeeViews.DuesChange;
import com.akshara.fees.FeeViews.HeadDue;
import com.akshara.fees.FeeViews.HeadRef;
import com.akshara.fees.FeeViews.InstalmentDue;
import com.akshara.fees.FeeViews.LateFeeRuleView;
import com.akshara.fees.FeeViews.StudentFees;
import com.akshara.fees.FeeViews.StudentRef;
import com.akshara.fees.FeeViews.Totals;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;
import com.akshara.students.StudentStatus;

/**
 * Students' dues: creating them from published fee structures, re-pricing what is unpaid when a structure or a
 * concession changes, and reading a student's ledger with late fees.
 *
 * <p>Dues are created for every active student of the class when a structure is published, and lazily for students
 * enrolled later: the first time their fees are read or paid, or a school-wide fee report runs. Other modules can call
 * {@link #generateForStudent(UUID)} directly (for example from a future "student enrolled" event).
 */
@Service
@Transactional
public class FeeDuesService {

    /** A structure with its instalments (by number) and each instalment's head shares. */
    record StructureData(FeeStructure structure, List<FeeInstalment> instalments,
            Map<UUID, List<FeeInstalmentShare>> shares) {
    }

    /** What a student should owe for one cell, before payments. */
    private record Target(FeeInstalment instalment, UUID headId, long grossPaise, long concessionPaise) {
    }

    /** Something a payment can go to, oldest first: an instalment's late fee or a head of it. */
    record PlanItem(String kind, StudentDue due, FeeInstalment instalment, FeeHead head, long outstandingPaise) {
    }

    private final StudentDueRepository dues;
    private final FeeStructureRepository structures;
    private final FeeInstalmentRepository instalments;
    private final FeeInstalmentShareRepository shares;
    private final FeeHeadRepository heads;
    private final ConcessionRepository concessions;
    private final ConcessionHeadRepository concessionHeads;
    private final PaymentAllocationRepository allocations;
    private final LateFeeWaiverRepository waivers;
    private final LateFeeRuleRepository lateFeeRules;
    private final ReceiptRepository receipts;
    private final StudentRoster roster;
    private final AcademicsDirectory academics;
    private final EntityManager entityManager;

    FeeDuesService(StudentDueRepository dues, FeeStructureRepository structures, FeeInstalmentRepository instalments,
            FeeInstalmentShareRepository shares, FeeHeadRepository heads, ConcessionRepository concessions,
            ConcessionHeadRepository concessionHeads, PaymentAllocationRepository allocations,
            LateFeeWaiverRepository waivers, LateFeeRuleRepository lateFeeRules, ReceiptRepository receipts,
            StudentRoster roster, AcademicsDirectory academics, EntityManager entityManager) {
        this.dues = dues;
        this.structures = structures;
        this.instalments = instalments;
        this.shares = shares;
        this.heads = heads;
        this.concessions = concessions;
        this.concessionHeads = concessionHeads;
        this.allocations = allocations;
        this.waivers = waivers;
        this.lateFeeRules = lateFeeRules;
        this.receipts = receipts;
        this.roster = roster;
        this.academics = academics;
        this.entityManager = entityManager;
    }

    // ------------------------------------------------------------------ generation

    StructureData load(FeeStructure structure) {
        List<FeeInstalment> list = instalments.findByStructureIdOrderBySeq(structure.getId());
        Map<UUID, List<FeeInstalmentShare>> byInstalment = list.isEmpty() ? Map.of()
                : shares.findByInstalmentIdIn(list.stream().map(FeeInstalment::getId).toList()).stream()
                        .collect(Collectors.groupingBy(FeeInstalmentShare::getInstalmentId));
        return new StructureData(structure, list, byInstalment);
    }

    Map<UUID, FeeHead> headsById() {
        return heads.findAll().stream().collect(Collectors.toMap(FeeHead::getId, Function.identity()));
    }

    /**
     * Creates or re-prices the dues of every student of a published structure: students who already have dues from
     * it, and active students of the class who have no dues yet this year.
     */
    DuesChange generateForStructure(FeeStructure structure) {
        TenantContext.require();
        if (!structure.isPublished()) {
            return DuesChange.NONE;
        }
        StructureData data = load(structure);
        Map<UUID, FeeHead> headById = headsById();
        Set<UUID> existing = new HashSet<>(dues.studentIdsOfStructure(structure.getId()));
        Set<UUID> withDuesThisYear = new HashSet<>(dues.studentIdsInYear(structure.getAcademicYearId()));
        // One pass in id order, so two transactions never wait for each other's students in opposite orders.
        TreeSet<UUID> students = new TreeSet<>(existing);
        for (UUID studentId : roster.activeInClass(structure.getAcademicYearId(), structure.getClassId())) {
            if (!withDuesThisYear.contains(studentId)) {
                students.add(studentId);
            }
        }
        int created = 0;
        int updated = 0;
        int kept = 0;
        for (UUID studentId : students) {
            kept += apply(studentId, data, headById);
            if (existing.contains(studentId)) {
                updated++;
            } else {
                created++;
            }
        }
        return new DuesChange(created, updated, kept);
    }

    /**
     * Creates the current year's dues of a student who has none yet, from the published structure of their class.
     * Returns whether anything was created. Safe to call at any time and from any module; a student keeps one set of
     * dues per year even if they change class during it.
     */
    public boolean generateForStudent(UUID studentId) {
        TenantContext.require();
        Optional<StudentRoster.Placement> placement = roster.currentPlacement(studentId);
        if (placement.isEmpty() || !placement.get().active()) {
            return false;
        }
        UUID yearId = placement.get().academicYearId();
        lockStudent(studentId);
        if (dues.existsByStudentIdAndAcademicYearId(studentId, yearId)) {
            return false;
        }
        Optional<FeeStructure> structure = structures.findByAcademicYearIdAndClassId(yearId,
                placement.get().classId()).filter(FeeStructure::isPublished);
        if (structure.isEmpty()) {
            return false;
        }
        apply(studentId, load(structure.get()), headsById());
        return true;
    }

    /**
     * Creates dues for active students of a year who joined a class after its structure was published. Run before
     * school-wide reports so they include everyone. {@code students} is the year's roster. Returns how many students
     * got dues.
     */
    int syncYear(UUID academicYearId, Map<UUID, StudentRoster.Entry> students) {
        TenantContext.require();
        List<FeeStructure> published = structures.findPublishedInYear(academicYearId);
        if (published.isEmpty()) {
            return 0;
        }
        Set<UUID> withDues = new HashSet<>(dues.studentIdsInYear(academicYearId));
        Map<UUID, FeeHead> headById = null;
        int created = 0;
        for (FeeStructure structure : published) {
            List<UUID> missing = students.values().stream()
                    .filter(s -> s.status() == StudentStatus.ACTIVE)
                    .filter(s -> structure.getClassId().equals(s.classId()))
                    .map(StudentRoster.Entry::id)
                    .filter(id -> !withDues.contains(id))
                    .sorted()
                    .toList();
            if (missing.isEmpty()) {
                continue;
            }
            StructureData data = load(structure);
            if (headById == null) {
                headById = headsById();
            }
            for (UUID studentId : missing) {
                lockStudent(studentId);
                if (!dues.existsByStudentIdAndAcademicYearId(studentId, academicYearId)) {
                    apply(studentId, data, headById);
                    created++;
                }
            }
        }
        return created;
    }

    /** Re-prices a student's dues of a year after their concessions changed. */
    void regenerateStudent(UUID studentId, UUID academicYearId) {
        Map<UUID, FeeHead> headById = headsById();
        for (UUID structureId : dues.structureIdsOf(studentId, academicYearId)) {
            FeeStructure structure = structures.findById(structureId).orElseThrow();
            apply(studentId, load(structure), headById);
        }
    }

    /**
     * Brings one student's dues from a structure in line with it. New cells are added; a cell is re-priced unless that
     * would take it below what is already paid, in which case only its due date moves (counted as "kept"). Cells no
     * longer in the structure are deleted when nothing was ever paid on them, set to zero when only cancelled payments
     * touched them, and kept as they are when something is paid. Returns the number of cells kept.
     */
    int apply(UUID studentId, StructureData data, Map<UUID, FeeHead> headById) {
        lockStudent(studentId);
        List<Target> targets = targets(studentId, data, headById);
        Map<String, StudentDue> existing = new HashMap<>();
        for (StudentDue due : dues.lockByStudentAndStructure(studentId, data.structure().getId())) {
            existing.put(key(due.getInstalmentId(), due.getHeadId()), due);
        }
        Map<UUID, FeeInstalment> instalmentById = data.instalments().stream()
                .collect(Collectors.toMap(FeeInstalment::getId, Function.identity()));
        int kept = 0;
        List<StudentDue> added = new ArrayList<>();
        for (Target t : targets) {
            StudentDue due = existing.remove(key(t.instalment().getId(), t.headId()));
            LocalDate dueDate = t.instalment().getDueDate();
            if (due == null) {
                if (t.grossPaise() > 0) {
                    added.add(new StudentDue(studentId, data.structure().getAcademicYearId(),
                            data.structure().getId(), t.instalment().getId(), t.headId(), dueDate, t.grossPaise(),
                            t.concessionPaise()));
                }
                continue;
            }
            long newNet = t.grossPaise() - t.concessionPaise();
            if (due.getPaidPaise() == 0 || newNet >= due.getPaidPaise()) {
                if (due.getGrossPaise() != t.grossPaise() || due.getConcessionPaise() != t.concessionPaise()
                        || !due.getDueDate().equals(dueDate)) {
                    due.reprice(t.grossPaise(), t.concessionPaise(), dueDate);
                }
            } else {
                if (!due.getDueDate().equals(dueDate)) {
                    due.moveDueDate(dueDate);
                }
                kept++;
            }
        }
        // Cells that are no longer part of the structure.
        List<StudentDue> leftover = new ArrayList<>(existing.values());
        Set<UUID> withHistory = leftover.isEmpty() ? Set.of()
                : new HashSet<>(allocations.dueIdsWithHistory(leftover.stream().map(StudentDue::getId).toList()));
        List<StudentDue> removed = new ArrayList<>();
        for (StudentDue due : leftover) {
            FeeInstalment instalment = instalmentById.get(due.getInstalmentId());
            LocalDate dueDate = instalment == null ? due.getDueDate() : instalment.getDueDate();
            if (due.getPaidPaise() > 0) {
                if (!due.getDueDate().equals(dueDate)) {
                    due.moveDueDate(dueDate);
                }
                kept++;
            } else if (withHistory.contains(due.getId())) {
                if (due.getGrossPaise() != 0 || due.getConcessionPaise() != 0) {
                    due.reprice(0, 0, dueDate);
                }
            } else {
                removed.add(due);
            }
        }
        if (!removed.isEmpty()) {
            dues.deleteAll(removed);
        }
        dues.saveAll(added);
        dues.flush();
        return kept;
    }

    /** Every cell the student should owe from the structure, with their active concessions applied. */
    private List<Target> targets(UUID studentId, StructureData data, Map<UUID, FeeHead> headById) {
        List<FeeInstalment> instalmentList = new ArrayList<>();
        List<FeeMath.Cell> cells = new ArrayList<>();
        Comparator<FeeInstalmentShare> byHead = Comparator.comparing(
                (FeeInstalmentShare s) -> headOrder(headById.get(s.getHeadId()))).thenComparing(s -> s.getHeadId());
        for (FeeInstalment instalment : data.instalments()) {
            List<FeeInstalmentShare> list = new ArrayList<>(data.shares().getOrDefault(instalment.getId(), List.of()));
            list.sort(byHead);
            for (FeeInstalmentShare share : list) {
                if (share.getAmountPaise() <= 0) {
                    continue;
                }
                instalmentList.add(instalment);
                cells.add(new FeeMath.Cell(share.getHeadId(), share.getAmountPaise()));
            }
        }
        long[] concession = FeeMath.concessions(cells,
                rules(concessions.findActive(studentId, data.structure().getAcademicYearId())));
        List<Target> result = new ArrayList<>(cells.size());
        for (int i = 0; i < cells.size(); i++) {
            result.add(new Target(instalmentList.get(i), cells.get(i).headId(), cells.get(i).grossPaise(),
                    concession[i]));
        }
        return result;
    }

    private List<FeeMath.ConcessionRule> rules(List<Concession> active) {
        if (active.isEmpty()) {
            return List.of();
        }
        Map<UUID, Set<UUID>> headsOf = new HashMap<>();
        for (ConcessionHead ch : concessionHeads.findByConcessionIdIn(active.stream().map(Concession::getId)
                .toList())) {
            headsOf.computeIfAbsent(ch.getConcessionId(), k -> new HashSet<>()).add(ch.getHeadId());
        }
        return active.stream()
                .map(c -> new FeeMath.ConcessionRule(c.getMode(), c.getPercentBp() == null ? 0 : c.getPercentBp(),
                        c.getFixedPaise() == null ? 0 : c.getFixedPaise(), headsOf.getOrDefault(c.getId(), Set.of())))
                .toList();
    }

    /** Serialises everything that creates or changes one student's dues, for the length of the transaction. */
    private void lockStudent(UUID studentId) {
        entityManager.createNativeQuery("select 1 from (select pg_advisory_xact_lock(hashtextextended(?1, 0))) l")
                .setParameter(1, "fees.student:" + studentId)
                .getSingleResult();
    }

    private static String key(UUID instalmentId, UUID headId) {
        return instalmentId + "/" + headId;
    }

    static int headOrder(FeeHead head) {
        return head == null ? Integer.MAX_VALUE : head.getDisplayOrder();
    }

    // ------------------------------------------------------------------ late fees

    FeeMath.LateFeeRule lateFeeRule() {
        return lateFeeRules.findFirstBy().map(LateFeeRuleEntity::toRule).orElse(FeeMath.LateFeeRule.NONE);
    }

    static LateFeeRuleView view(FeeMath.LateFeeRule rule) {
        return new LateFeeRuleView(rule.mode(), rule.graceDays(), rule.flatPaise(), rule.perDayPaise(),
                rule.capPaise());
    }

    /**
     * The late fee still to collect on an instalment: what the rule gives on {@code asOf} less what receipts already
     * charged for it. Nothing once the instalment is paid or when the late fee was waived.
     */
    static long lateFeeOutstanding(FeeMath.LateFeeRule rule, LocalDate dueDate, long balancePaise, boolean waived,
            long chargedPaise, LocalDate asOf) {
        if (balancePaise <= 0 || waived) {
            return 0;
        }
        return Math.max(0, FeeMath.lateFee(rule, dueDate, asOf) - chargedPaise);
    }

    static Map<UUID, Long> chargedByInstalment(List<Object[]> rows) {
        Map<UUID, Long> result = new HashMap<>();
        for (Object[] row : rows) {
            result.put((UUID) row[0], ((Number) row[1]).longValue());
        }
        return result;
    }

    // ------------------------------------------------------------------ ledger

    /** A student's fees as staff and their parents see them. Creates this year's dues first if they are missing. */
    public StudentFees studentFees(UUID studentId) {
        TenantContext.require();
        StudentRoster.Entry student = roster.student(studentId);
        generateForStudent(studentId);
        LocalDate asOf = SchoolDay.today();
        FeeMath.LateFeeRule rule = lateFeeRule();
        List<InstalmentDue> ledger = ledger(dues.findByStudentId(studentId), rule, asOf,
                waivedInstalments(studentId), chargedByInstalment(allocations.lateFeesCharged(studentId)));

        long gross = 0;
        long concession = 0;
        long paid = 0;
        long overdue = 0;
        long lateFee = 0;
        long dueNow = 0;
        for (InstalmentDue i : ledger) {
            gross += i.grossPaise();
            concession += i.concessionPaise();
            paid += i.paidPaise();
            lateFee += i.lateFeePaise();
            if (i.dueDate().isBefore(asOf)) {
                overdue += i.balancePaise();
            }
            if (!i.dueDate().isAfter(asOf)) {
                dueNow += i.balancePaise();
            }
        }
        long net = gross - concession;
        Totals totals = new Totals(gross, concession, net, paid, net - paid, overdue, lateFee, dueNow + lateFee);
        List<ConcessionView> concessionViews = concessionViews(concessions.findByStudentIdOrderByCreatedAtDesc(
                studentId), id -> id.equals(studentId) ? student : null);
        return new StudentFees(ref(student), ledger, totals, concessionViews,
                receipts.findByStudentIdOrderByReceivedAtDesc(studentId).stream().map(ReceiptMapper::summary)
                        .toList(),
                view(rule), asOf);
    }

    Set<UUID> waivedInstalments(UUID studentId) {
        return waivers.findByStudentId(studentId).stream().map(LateFeeWaiver::getInstalmentId)
                .collect(Collectors.toSet());
    }

    /** The student's dues grouped by instalment, oldest due date first, with late fees on {@code asOf}. */
    List<InstalmentDue> ledger(List<StudentDue> studentDues, FeeMath.LateFeeRule rule, LocalDate asOf,
            Set<UUID> waived, Map<UUID, Long> charged) {
        if (studentDues.isEmpty()) {
            return List.of();
        }
        Map<UUID, FeeInstalment> instalmentById = instalments.findAllById(studentDues.stream()
                .map(StudentDue::getInstalmentId).distinct().toList()).stream()
                .collect(Collectors.toMap(FeeInstalment::getId, Function.identity()));
        Map<UUID, FeeHead> headById = headsById();
        Map<UUID, String> yearNames = academics.years().stream()
                .collect(Collectors.toMap(YearInfo::id, YearInfo::name));
        Map<UUID, List<StudentDue>> byInstalment = studentDues.stream()
                .collect(Collectors.groupingBy(StudentDue::getInstalmentId, LinkedHashMap::new, Collectors.toList()));
        List<InstalmentDue> result = new ArrayList<>();
        for (Map.Entry<UUID, List<StudentDue>> e : byInstalment.entrySet()) {
            FeeInstalment instalment = instalmentById.get(e.getKey());
            List<StudentDue> cells = new ArrayList<>(e.getValue());
            cells.sort(Comparator.comparing((StudentDue d) -> headOrder(headById.get(d.getHeadId())))
                    .thenComparing(d -> headName(headById, d.getHeadId())));
            LocalDate dueDate = instalment.getDueDate();
            long gross = 0;
            long concession = 0;
            long paid = 0;
            List<HeadDue> headDues = new ArrayList<>();
            for (StudentDue d : cells) {
                gross += d.getGrossPaise();
                concession += d.getConcessionPaise();
                paid += d.getPaidPaise();
                if (d.getGrossPaise() == 0 && d.getPaidPaise() == 0) {
                    continue;
                }
                headDues.add(new HeadDue(d.getId(), d.getHeadId(), headName(headById, d.getHeadId()),
                        d.getGrossPaise(), d.getConcessionPaise(), d.net(), d.getPaidPaise(), d.balance(),
                        FeeMath.status(d.net(), d.getPaidPaise(), dueDate, asOf)));
            }
            if (headDues.isEmpty()) {
                continue;
            }
            long net = gross - concession;
            long balance = net - paid;
            boolean isWaived = waived.contains(instalment.getId());
            long lateFee = lateFeeOutstanding(rule, dueDate, balance, isWaived,
                    charged.getOrDefault(instalment.getId(), 0L), asOf);
            long daysOverdue = balance > 0 && asOf.isAfter(dueDate) ? ChronoUnit.DAYS.between(dueDate, asOf) : 0;
            UUID yearId = cells.getFirst().getAcademicYearId();
            result.add(new InstalmentDue(instalment.getId(), instalment.getSeq(), instalment.getLabel(), dueDate,
                    yearId, yearNames.get(yearId), FeeMath.status(net, paid, dueDate, asOf), gross, concession, net,
                    paid, balance, lateFee, isWaived, daysOverdue, headDues));
        }
        result.sort(Comparator.comparing(InstalmentDue::dueDate).thenComparing(InstalmentDue::seq)
                .thenComparing(InstalmentDue::instalmentId));
        return result;
    }

    private static String headName(Map<UUID, FeeHead> headById, UUID headId) {
        FeeHead head = headById.get(headId);
        return head == null ? "" : head.getName();
    }

    static StudentRef ref(StudentRoster.Entry s) {
        return new StudentRef(s.id(), s.fullName(), s.admissionNo(), s.status(), s.className(), s.sectionName(),
                s.rollNo());
    }

    // ------------------------------------------------------------------ payment plan

    /**
     * What a payment for the student can go to, in the order it is applied: instalments by due date (only
     * {@code onlyInstalments} when given), and within each its late fee (when {@code includeLateFee}) and then its
     * heads in display order. Locks the student's dues until the transaction ends. Instalment ids the student does not
     * owe are a 400 on "instalmentIds".
     */
    List<PlanItem> plan(UUID studentId, Collection<UUID> onlyInstalments, boolean includeLateFee, LocalDate asOf) {
        lockStudent(studentId);
        List<StudentDue> locked = dues.lockByStudent(studentId);
        Set<UUID> owned = locked.stream().map(StudentDue::getInstalmentId).collect(Collectors.toSet());
        Set<UUID> only = onlyInstalments == null || onlyInstalments.isEmpty() ? null : new HashSet<>(onlyInstalments);
        if (only != null && !owned.containsAll(only)) {
            throw ApiException.badRequest("Pick instalments of this student.", "instalmentIds");
        }
        if (locked.isEmpty()) {
            return List.of();
        }
        Map<UUID, FeeInstalment> instalmentById = instalments.findAllById(owned).stream()
                .collect(Collectors.toMap(FeeInstalment::getId, Function.identity()));
        Map<UUID, FeeHead> headById = headsById();
        FeeMath.LateFeeRule rule = lateFeeRule();
        Set<UUID> waived = waivedInstalments(studentId);
        Map<UUID, Long> charged = chargedByInstalment(allocations.lateFeesCharged(studentId));

        Map<UUID, List<StudentDue>> byInstalment = locked.stream()
                .collect(Collectors.groupingBy(StudentDue::getInstalmentId));
        List<FeeInstalment> order = byInstalment.keySet().stream()
                .filter(id -> only == null || only.contains(id))
                .map(instalmentById::get)
                .sorted(Comparator.comparing(FeeInstalment::getDueDate).thenComparing(FeeInstalment::getSeq)
                        .thenComparing(FeeInstalment::getId))
                .toList();
        List<PlanItem> plan = new ArrayList<>();
        for (FeeInstalment instalment : order) {
            List<StudentDue> cells = new ArrayList<>(byInstalment.get(instalment.getId()));
            cells.sort(Comparator.comparing((StudentDue d) -> headOrder(headById.get(d.getHeadId())))
                    .thenComparing(d -> headName(headById, d.getHeadId())));
            long balance = cells.stream().mapToLong(StudentDue::balance).sum();
            if (balance <= 0) {
                continue;
            }
            if (includeLateFee) {
                long lateFee = lateFeeOutstanding(rule, instalment.getDueDate(), balance,
                        waived.contains(instalment.getId()), charged.getOrDefault(instalment.getId(), 0L), asOf);
                if (lateFee > 0) {
                    plan.add(new PlanItem(PaymentAllocation.LATE_FEE, null, instalment, null, lateFee));
                }
            }
            for (StudentDue d : cells) {
                if (d.balance() > 0) {
                    plan.add(new PlanItem(PaymentAllocation.DUE, d, instalment, headById.get(d.getHeadId()),
                            d.balance()));
                }
            }
        }
        return plan;
    }

    // ------------------------------------------------------------------ concessions as shown

    /**
     * Concessions as the API shows them. {@code students} finds a student's name and class (null when unknown, for
     * example a student who left before this year).
     */
    List<ConcessionView> concessionViews(List<Concession> list, Function<UUID, StudentRoster.Entry> students) {
        if (list.isEmpty()) {
            return List.of();
        }
        Map<UUID, FeeHead> headById = headsById();
        Map<UUID, String> yearNames = academics.years().stream()
                .collect(Collectors.toMap(YearInfo::id, YearInfo::name));
        Map<UUID, List<ConcessionHead>> headsOf = concessionHeads.findByConcessionIdIn(list.stream()
                .map(Concession::getId).toList()).stream()
                .collect(Collectors.groupingBy(ConcessionHead::getConcessionId));
        Map<String, Long> totals = new HashMap<>();
        List<ConcessionView> result = new ArrayList<>();
        for (Concession c : list) {
            StudentRoster.Entry s = students.apply(c.getStudentId());
            List<HeadRef> refs = headsOf.getOrDefault(c.getId(), List.of()).stream()
                    .map(ch -> headById.get(ch.getHeadId()))
                    .filter(h -> h != null)
                    .sorted(Comparator.comparingInt(FeeHead::getDisplayOrder).thenComparing(FeeHead::getName))
                    .map(h -> new HeadRef(h.getId(), h.getName()))
                    .toList();
            long total = totals.computeIfAbsent(c.getStudentId() + "/" + c.getAcademicYearId(),
                    k -> dues.concessionTotal(c.getStudentId(), c.getAcademicYearId()));
            result.add(new ConcessionView(c.getId(), c.getStudentId(), s == null ? null : s.fullName(),
                    s == null ? null : s.admissionNo(), s == null ? null : s.className(),
                    s == null ? null : s.sectionName(), c.getAcademicYearId(), yearNames.get(c.getAcademicYearId()),
                    c.getType(), c.getMode(),
                    c.getPercentBp() == null ? null : BigDecimal.valueOf(c.getPercentBp(), 2),
                    c.getFixedPaise(), refs, c.getReason(), c.getApprovedByName(), c.getCreatedAt(), c.getStatus(),
                    c.getRevokedAt(), c.getRevokedByName(), c.getRevokeReason(), total));
        }
        return result;
    }

    // ------------------------------------------------------------------ parents

    /** 404 unless the student is one of the parent's own children. */
    void requireOwnChild(UUID userId, UUID studentId) {
        boolean own = roster.childrenOf(userId).stream().anyMatch(c -> c.id().equals(studentId));
        if (!own) {
            throw ApiException.notFound("Student");
        }
    }
}
