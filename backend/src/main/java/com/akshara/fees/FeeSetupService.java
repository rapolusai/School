package com.akshara.fees;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.academics.AcademicsDirectory;
import com.akshara.academics.AcademicsDirectory.SectionInfo;
import com.akshara.academics.AcademicsDirectory.YearInfo;
import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.fees.FeeForms.ConcessionForm;
import com.akshara.fees.FeeForms.HeadAmount;
import com.akshara.fees.FeeForms.HeadForm;
import com.akshara.fees.FeeForms.InstalmentForm;
import com.akshara.fees.FeeForms.LateFeeRuleForm;
import com.akshara.fees.FeeForms.StructureForm;
import com.akshara.fees.FeeViews.ConcessionView;
import com.akshara.fees.FeeViews.DuesChange;
import com.akshara.fees.FeeViews.HeadView;
import com.akshara.fees.FeeViews.InstalmentView;
import com.akshara.fees.FeeViews.LateFeeRuleView;
import com.akshara.fees.FeeViews.Share;
import com.akshara.fees.FeeViews.StructureHead;
import com.akshara.fees.FeeViews.StructureResult;
import com.akshara.fees.FeeViews.StructureSummary;
import com.akshara.fees.FeeViews.StructureView;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;

/**
 * Fee setup of the current school: fee heads, the fee structure of each class per year, concessions and the late-fee
 * rule. Changes need fees.manage and are written to the audit trail in the same transaction.
 */
@Service
@Transactional
public class FeeSetupService {

    /** The heads most Indian schools start with. */
    static final List<HeadForm> DEFAULT_HEADS = List.of(
            new HeadForm("Tuition fee", FeeHeadKind.TUITION, false, true),
            new HeadForm("Admission fee", FeeHeadKind.ADMISSION, true, true),
            new HeadForm("Annual charges", FeeHeadKind.ANNUAL, false, true),
            new HeadForm("Exam fee", FeeHeadKind.EXAM, false, true),
            new HeadForm("Lab fee", FeeHeadKind.LAB, false, true),
            new HeadForm("Transport fee", FeeHeadKind.TRANSPORT, false, true));

    private final FeeHeadRepository heads;
    private final FeeStructureRepository structures;
    private final FeeInstalmentRepository instalments;
    private final FeeInstalmentShareRepository shares;
    private final StudentDueRepository dues;
    private final ConcessionRepository concessions;
    private final ConcessionHeadRepository concessionHeads;
    private final PaymentAllocationRepository allocations;
    private final LateFeeWaiverRepository waivers;
    private final LateFeeRuleRepository lateFeeRules;
    private final FeeDuesService duesService;
    private final StudentRoster roster;
    private final AcademicsDirectory academics;
    private final AuditService audit;

    FeeSetupService(FeeHeadRepository heads, FeeStructureRepository structures, FeeInstalmentRepository instalments,
            FeeInstalmentShareRepository shares, StudentDueRepository dues, ConcessionRepository concessions,
            ConcessionHeadRepository concessionHeads, PaymentAllocationRepository allocations,
            LateFeeWaiverRepository waivers, LateFeeRuleRepository lateFeeRules, FeeDuesService duesService,
            StudentRoster roster, AcademicsDirectory academics, AuditService audit) {
        this.heads = heads;
        this.structures = structures;
        this.instalments = instalments;
        this.shares = shares;
        this.dues = dues;
        this.concessions = concessions;
        this.concessionHeads = concessionHeads;
        this.allocations = allocations;
        this.waivers = waivers;
        this.lateFeeRules = lateFeeRules;
        this.duesService = duesService;
        this.roster = roster;
        this.academics = academics;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ heads

    @Transactional(readOnly = true)
    public List<HeadView> heads() {
        TenantContext.require();
        return heads.findAllOrdered().stream().map(this::view).toList();
    }

    public HeadView createHead(HeadForm form, Actor actor) {
        TenantContext.require();
        String name = form.name().trim();
        if (heads.existsByNameExcept(name, new UUID(0, 0))) {
            throw ApiException.conflict("A fee head with this name already exists.", "name");
        }
        FeeHead head = new FeeHead(name, form.kind(), form.oneTime(), heads.maxDisplayOrder() + 1);
        if (!form.active()) {
            head.update(name, form.kind(), form.oneTime(), false);
        }
        heads.saveAndFlush(head);
        record(actor, "fee_head.created", "fee_head", head.getId(), Map.of("name", name, "kind", form.kind().name(),
                "oneTime", form.oneTime(), "active", form.active()));
        return view(head);
    }

    public HeadView updateHead(UUID id, HeadForm form, Actor actor) {
        TenantContext.require();
        FeeHead head = heads.findById(id).orElseThrow(() -> ApiException.notFound("Fee head"));
        String name = form.name().trim();
        if (heads.existsByNameExcept(name, id)) {
            throw ApiException.conflict("A fee head with this name already exists.", "name");
        }
        head.update(name, form.kind(), form.oneTime(), form.active());
        heads.saveAndFlush(head);
        record(actor, "fee_head.updated", "fee_head", id, Map.of("name", name, "kind", form.kind().name(),
                "oneTime", form.oneTime(), "active", form.active()));
        return view(head);
    }

    /** Adds the usual heads (tuition, admission, annual, exam, lab, transport) that the school does not have yet. */
    public List<HeadView> addDefaultHeads(Actor actor) {
        TenantContext.require();
        Set<String> existing = heads.findAll().stream().map(h -> h.getName().toLowerCase())
                .collect(Collectors.toSet());
        for (HeadForm form : DEFAULT_HEADS) {
            if (!existing.contains(form.name().toLowerCase())) {
                createHead(form, actor);
            }
        }
        return heads();
    }

    private HeadView view(FeeHead h) {
        boolean inUse = shares.existsByHeadId(h.getId()) || concessionHeads.existsByHeadId(h.getId())
                || allocations.existsByHeadId(h.getId());
        return new HeadView(h.getId(), h.getName(), h.getKind(), h.isOneTime(), h.isActive(), h.getDisplayOrder(),
                inUse);
    }

    // ------------------------------------------------------------------ structures

    /** Every class of the year (the current year by default) with its structure, if it has one. */
    @Transactional(readOnly = true)
    public List<StructureSummary> structures(UUID yearId) {
        TenantContext.require();
        Optional<YearInfo> year = yearId == null ? academics.currentYear()
                : Optional.of(academics.year(yearId).orElseThrow(() -> ApiException.notFound("Academic year")));
        if (year.isEmpty()) {
            return List.of();
        }
        Map<UUID, FeeStructure> byClass = structures.findByAcademicYearId(year.get().id()).stream()
                .collect(Collectors.toMap(FeeStructure::getClassId, Function.identity()));
        Map<UUID, List<FeeInstalment>> instalmentsOf = byClass.isEmpty() ? Map.of()
                : instalments.findByStructureIdIn(byClass.values().stream().map(FeeStructure::getId).toList())
                        .stream().collect(Collectors.groupingBy(FeeInstalment::getStructureId));
        Map<UUID, Long> totalOf = new HashMap<>();
        List<UUID> allInstalments = instalmentsOf.values().stream().flatMap(List::stream).map(FeeInstalment::getId)
                .toList();
        Map<UUID, UUID> structureOfInstalment = instalmentsOf.values().stream().flatMap(List::stream)
                .collect(Collectors.toMap(FeeInstalment::getId, FeeInstalment::getStructureId));
        if (!allInstalments.isEmpty()) {
            for (FeeInstalmentShare s : shares.findByInstalmentIdIn(allInstalments)) {
                totalOf.merge(structureOfInstalment.get(s.getInstalmentId()), s.getAmountPaise(), Long::sum);
            }
        }
        Map<UUID, Long> studentsOf = new HashMap<>();
        for (Object[] row : dues.studentsPerStructure(year.get().id())) {
            studentsOf.put((UUID) row[0], ((Number) row[1]).longValue());
        }
        List<StructureSummary> result = new ArrayList<>();
        for (Map.Entry<UUID, String> c : classes().entrySet()) {
            FeeStructure s = byClass.get(c.getKey());
            if (s == null) {
                result.add(new StructureSummary(null, c.getKey(), c.getValue(), null, 0, 0, null, 0));
            } else {
                result.add(new StructureSummary(s.getId(), c.getKey(), c.getValue(), s.getStatus(),
                        totalOf.getOrDefault(s.getId(), 0L), instalmentsOf.getOrDefault(s.getId(), List.of()).size(),
                        s.getPublishedAt(), studentsOf.getOrDefault(s.getId(), 0L)));
            }
        }
        return result;
    }

    @Transactional(readOnly = true)
    public StructureView structure(UUID id) {
        TenantContext.require();
        return view(structures.findById(id).orElseThrow(() -> ApiException.notFound("Fee structure")));
    }

    /** Creates the structure of a class for a year (409 when it already has one). It starts as a draft. */
    public StructureResult createStructure(StructureForm form, Actor actor) {
        TenantContext.require();
        YearInfo year = yearInBody(form.academicYearId());
        String className = classInBody(form.classId());
        if (structures.findByAcademicYearIdAndClassId(year.id(), form.classId()).isPresent()) {
            throw ApiException.conflict(className + " already has a fee structure for " + year.name() + ".",
                    "classId");
        }
        Plan plan = plan(form, Set.of());
        FeeStructure structure = structures.saveAndFlush(new FeeStructure(year.id(), form.classId()));
        write(structure, plan);
        record(actor, "fee_structure.saved", "fee_structure", structure.getId(), details(structure, year, className,
                plan, DuesChange.NONE));
        return new StructureResult(view(structure), DuesChange.NONE);
    }

    /**
     * Replaces the heads and instalments of a structure. When it is published, students' dues follow: unpaid amounts
     * are re-priced, paid amounts never change, and an instalment that has payments cannot be removed (409).
     */
    public StructureResult updateStructure(UUID id, StructureForm form, Actor actor) {
        TenantContext.require();
        FeeStructure structure = structures.lock(id).orElseThrow(() -> ApiException.notFound("Fee structure"));
        if (!structure.getAcademicYearId().equals(form.academicYearId())) {
            throw ApiException.badRequest("A structure stays in its academic year.", "academicYearId");
        }
        if (!structure.getClassId().equals(form.classId())) {
            throw ApiException.badRequest("A structure stays with its class.", "classId");
        }
        YearInfo year = yearInBody(form.academicYearId());
        String className = classInBody(form.classId());
        List<FeeInstalment> current = instalments.findByStructureIdOrderBySeq(id);
        Set<UUID> headsInUse = current.isEmpty() ? Set.of()
                : shares.findByInstalmentIdIn(current.stream().map(FeeInstalment::getId).toList()).stream()
                        .map(FeeInstalmentShare::getHeadId).collect(Collectors.toSet());
        Plan plan = plan(form, headsInUse);
        write(structure, plan);
        structure.edited();
        structures.saveAndFlush(structure);
        DuesChange change = duesService.generateForStructure(structure);
        record(actor, "fee_structure.saved", "fee_structure", id, details(structure, year, className, plan, change));
        return new StructureResult(view(structure), change);
    }

    /**
     * Publishes a structure: every active student of the class gets their dues. Publishing again creates dues for
     * students who joined the class since.
     */
    public StructureResult publish(UUID id, Actor actor) {
        TenantContext.require();
        FeeStructure structure = structures.lock(id).orElseThrow(() -> ApiException.notFound("Fee structure"));
        if (instalments.findByStructureIdOrderBySeq(id).isEmpty()) {
            throw Fees.conflict("Not ready", "Add instalments before publishing.");
        }
        boolean first = !structure.isPublished();
        if (first) {
            structure.publish();
            structures.saveAndFlush(structure);
        }
        DuesChange change = duesService.generateForStructure(structure);
        if (first || change.studentsCreated() > 0) {
            YearInfo year = academics.year(structure.getAcademicYearId()).orElseThrow();
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("className", classes().getOrDefault(structure.getClassId(), ""));
            details.put("academicYear", year.name());
            details.put("studentsCreated", change.studentsCreated());
            details.put("studentsUpdated", change.studentsUpdated());
            record(actor, "fee_structure.published", "fee_structure", id, details);
        }
        return new StructureResult(view(structure), change);
    }

    /** A validated structure form: head totals and each instalment's shares. */
    private record Plan(List<HeadAmount> heads, List<InstalmentForm> instalments, List<Map<UUID, Long>> shares,
            long totalPaise) {
    }

    private Plan plan(StructureForm form, Set<UUID> headsAlreadyUsed) {
        Map<UUID, FeeHead> headById = duesService.headsById();
        Map<UUID, Long> amounts = new LinkedHashMap<>();
        long total = 0;
        for (int i = 0; i < form.heads().size(); i++) {
            HeadAmount h = form.heads().get(i);
            FeeHead head = headById.get(h.headId());
            if (head == null || (!head.isActive() && !headsAlreadyUsed.contains(h.headId()))) {
                throw ApiException.badRequest("Pick an active fee head of this school.", "heads[" + i + "].headId");
            }
            if (amounts.containsKey(h.headId())) {
                throw ApiException.badRequest(head.getName() + " is listed twice.", "heads[" + i + "].headId");
            }
            amounts.put(h.headId(), h.amountPaise());
            total += h.amountPaise();
        }
        if (total <= 0) {
            throw ApiException.badRequest("Enter an amount for at least one fee head.", "heads");
        }
        List<InstalmentForm> list = form.instalments();
        LocalDate previous = null;
        for (int i = 0; i < list.size(); i++) {
            LocalDate due = list.get(i).dueDate();
            if (previous != null && !due.isAfter(previous)) {
                throw ApiException.badRequest("Each instalment must be due after the one before it.",
                        "instalments[" + i + "].dueDate");
            }
            previous = due;
        }
        long withShares = list.stream().filter(f -> f.shares() != null).count();
        if (withShares != 0 && withShares != list.size()) {
            throw ApiException.badRequest("Give the head amounts for every instalment, or for none to split them "
                    + "evenly.", "instalments");
        }
        int n = list.size();
        List<Map<UUID, Long>> result = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            result.add(new LinkedHashMap<>());
        }
        if (withShares == 0) {
            for (Map.Entry<UUID, Long> e : amounts.entrySet()) {
                if (headById.get(e.getKey()).isOneTime()) {
                    result.getFirst().put(e.getKey(), e.getValue());
                } else {
                    long[] split = FeeMath.splitEvenly(e.getValue(), n);
                    for (int i = 0; i < n; i++) {
                        result.get(i).put(e.getKey(), split[i]);
                    }
                }
            }
        } else {
            Map<UUID, Long> sums = new HashMap<>();
            for (int i = 0; i < n; i++) {
                List<HeadAmount> given = list.get(i).shares();
                for (int j = 0; j < given.size(); j++) {
                    HeadAmount s = given.get(j);
                    String field = "instalments[" + i + "].shares[" + j + "].headId";
                    if (!amounts.containsKey(s.headId())) {
                        throw ApiException.badRequest("Use only the heads of this structure.", field);
                    }
                    if (result.get(i).containsKey(s.headId())) {
                        throw ApiException.badRequest(headById.get(s.headId()).getName() + " is listed twice.",
                                field);
                    }
                    result.get(i).put(s.headId(), s.amountPaise());
                    sums.merge(s.headId(), s.amountPaise(), Long::sum);
                }
            }
            for (Map.Entry<UUID, Long> e : amounts.entrySet()) {
                long sum = sums.getOrDefault(e.getKey(), 0L);
                if (sum != e.getValue()) {
                    throw ApiException.badRequest(headById.get(e.getKey()).getName() + ": the instalments add up to "
                            + Fees.rupees(sum) + ", not " + Fees.rupees(e.getValue()) + ".", "instalments");
                }
            }
        }
        List<HeadAmount> headList = amounts.entrySet().stream().map(e -> new HeadAmount(e.getKey(), e.getValue()))
                .toList();
        return new Plan(headList, list, result, total);
    }

    /** Writes the instalments and shares of a validated plan, keeping instalments by number. */
    private void write(FeeStructure structure, Plan plan) {
        List<FeeInstalment> current = instalments.findByStructureIdOrderBySeq(structure.getId());
        int n = plan.instalments().size();
        List<FeeInstalment> removed = current.stream().filter(i -> i.getSeq() > n).toList();
        if (!removed.isEmpty()) {
            List<UUID> removedIds = removed.stream().map(FeeInstalment::getId).toList();
            boolean history = allocations.existsByInstalmentIdIn(removedIds) || waivers.existsByInstalmentIdIn(
                    removedIds) || dues.findByInstalmentIdIn(removedIds).stream().anyMatch(d -> d.getPaidPaise() > 0);
            if (history) {
                throw Fees.conflict("Has payments", "An instalment you removed already has payments, so it cannot "
                        + "be removed. Keep at least " + (removed.stream().mapToInt(FeeInstalment::getSeq).max()
                                .orElse(n)) + " instalments.");
            }
            dues.deleteAll(dues.findByInstalmentIdIn(removedIds));
            dues.flush();
        }
        if (!current.isEmpty()) {
            shares.deleteByInstalmentIds(current.stream().map(FeeInstalment::getId).toList());
        }
        if (!removed.isEmpty()) {
            instalments.deleteAll(removed);
            instalments.flush();
        }
        Map<Integer, FeeInstalment> bySeq = current.stream().filter(i -> i.getSeq() <= n)
                .collect(Collectors.toMap(FeeInstalment::getSeq, Function.identity()));
        List<FeeInstalmentShare> newShares = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            InstalmentForm f = plan.instalments().get(i);
            int seq = i + 1;
            String label = Fees.blankToNull(f.label()) == null ? defaultLabel(seq, n) : f.label().trim();
            FeeInstalment instalment = bySeq.get(seq);
            if (instalment == null) {
                instalment = new FeeInstalment(structure.getId(), seq, label, f.dueDate());
            } else {
                instalment.update(label, f.dueDate());
            }
            instalments.save(instalment);
            for (Map.Entry<UUID, Long> s : plan.shares().get(i).entrySet()) {
                if (s.getValue() > 0) {
                    newShares.add(new FeeInstalmentShare(instalment.getId(), s.getKey(), s.getValue()));
                }
            }
        }
        instalments.flush();
        shares.saveAllAndFlush(newShares);
    }

    static String defaultLabel(int seq, int count) {
        return switch (count) {
            case 1 -> "Annual";
            case 2 -> "Term " + seq;
            case 4 -> "Quarter " + seq;
            default -> "Instalment " + seq;
        };
    }

    private Map<String, Object> details(FeeStructure s, YearInfo year, String className, Plan plan,
            DuesChange change) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("className", className);
        details.put("academicYear", year.name());
        details.put("status", s.getStatus());
        details.put("totalPaise", plan.totalPaise());
        details.put("instalments", plan.instalments().size());
        if (s.isPublished()) {
            details.put("studentsUpdated", change.studentsUpdated());
            details.put("studentsCreated", change.studentsCreated());
            details.put("paidCellsKept", change.paidCellsKept());
        }
        return details;
    }

    StructureView view(FeeStructure s) {
        Map<UUID, FeeHead> headById = duesService.headsById();
        List<FeeInstalment> list = instalments.findByStructureIdOrderBySeq(s.getId());
        Map<UUID, List<FeeInstalmentShare>> sharesOf = list.isEmpty() ? Map.of()
                : shares.findByInstalmentIdIn(list.stream().map(FeeInstalment::getId).toList()).stream()
                        .collect(Collectors.groupingBy(FeeInstalmentShare::getInstalmentId));
        Map<UUID, Long> headTotals = new LinkedHashMap<>();
        List<InstalmentView> instalmentViews = new ArrayList<>();
        long total = 0;
        for (FeeInstalment i : list) {
            List<FeeInstalmentShare> own = new ArrayList<>(sharesOf.getOrDefault(i.getId(), List.of()));
            own.sort(java.util.Comparator.comparingInt((FeeInstalmentShare x) -> FeeDuesService.headOrder(
                    headById.get(x.getHeadId()))).thenComparing(x -> x.getHeadId()));
            long amount = 0;
            List<Share> shareViews = new ArrayList<>();
            for (FeeInstalmentShare x : own) {
                amount += x.getAmountPaise();
                headTotals.merge(x.getHeadId(), x.getAmountPaise(), Long::sum);
                shareViews.add(new Share(x.getHeadId(), x.getAmountPaise()));
            }
            total += amount;
            instalmentViews.add(new InstalmentView(i.getId(), i.getSeq(), i.getLabel(), i.getDueDate(), amount,
                    shareViews));
        }
        List<StructureHead> headViews = headTotals.entrySet().stream()
                .map(e -> headById.get(e.getKey()))
                .sorted(java.util.Comparator.comparingInt(FeeHead::getDisplayOrder).thenComparing(FeeHead::getName))
                .map(h -> new StructureHead(h.getId(), h.getName(), h.getKind(), h.isOneTime(),
                        headTotals.get(h.getId())))
                .toList();
        String yearName = academics.year(s.getAcademicYearId()).map(YearInfo::name).orElse(null);
        long students = dues.studentIdsOfStructure(s.getId()).size();
        return new StructureView(s.getId(), s.getAcademicYearId(), yearName, s.getClassId(),
                classes().getOrDefault(s.getClassId(), null), s.getStatus(), s.getPublishedAt(), headViews,
                instalmentViews, total, students);
    }

    /** Classes of the school in display order (from their sections; a class without sections has no students). */
    private Map<UUID, String> classes() {
        Map<UUID, String> result = new LinkedHashMap<>();
        for (SectionInfo s : academics.sections()) {
            result.putIfAbsent(s.classId(), s.className());
        }
        return result;
    }

    private YearInfo yearInBody(UUID yearId) {
        return academics.year(yearId)
                .orElseThrow(() -> ApiException.badRequest("Pick an academic year of this school.", "academicYearId"));
    }

    private String classInBody(UUID classId) {
        String name = classes().get(classId);
        if (name == null) {
            throw ApiException.badRequest("Pick a class of this school that has sections.", "classId");
        }
        return name;
    }

    // ------------------------------------------------------------------ concessions

    /** Concessions of a student (any year), or of every student in a year (the current year by default). */
    @Transactional(readOnly = true)
    public List<ConcessionView> concessions(UUID yearId, UUID studentId) {
        TenantContext.require();
        if (studentId != null) {
            StudentRoster.Entry student = roster.student(studentId);
            return duesService.concessionViews(concessions.findByStudentIdOrderByCreatedAtDesc(studentId),
                    id -> student);
        }
        Optional<YearInfo> year = yearId == null ? academics.currentYear()
                : Optional.of(academics.year(yearId).orElseThrow(() -> ApiException.notFound("Academic year")));
        if (year.isEmpty()) {
            return List.of();
        }
        Map<UUID, StudentRoster.Entry> students = roster.year(year.get().id());
        return duesService.concessionViews(concessions.findByAcademicYearIdOrderByCreatedAtDesc(year.get().id()),
                students::get);
    }

    /**
     * Grants a concession and re-prices the student's unpaid dues of that year. RTE is always 100% of the school's
     * active tuition heads, whatever else the form says.
     */
    public ConcessionView grant(ConcessionForm form, Actor actor) {
        TenantContext.require();
        Actor by = Fees.actor(actor);
        StudentRoster.Entry student = roster.studentInBody(form.studentId(), "studentId");
        YearInfo year = form.academicYearId() == null
                ? academics.currentYear().orElseThrow(() -> ApiException.badRequest("Set up an academic year first.",
                        "academicYearId"))
                : yearInBody(form.academicYearId());
        ConcessionMode mode;
        Integer percentBp = null;
        Long fixedPaise = null;
        Set<UUID> headIds;
        if (form.type() == ConcessionType.RTE) {
            if (concessions.findActive(student.id(), year.id()).stream()
                    .anyMatch(c -> c.getType() == ConcessionType.RTE)) {
                throw ApiException.conflict("This student already has an RTE concession for " + year.name() + ".",
                        "type");
            }
            mode = ConcessionMode.PERCENT;
            percentBp = FeeMath.FULL_PERCENT_BP;
            headIds = heads.findByKindAndActiveTrue(FeeHeadKind.TUITION).stream().map(FeeHead::getId)
                    .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
            if (headIds.isEmpty()) {
                throw ApiException.badRequest("Add a tuition fee head first: RTE covers tuition.", "type");
            }
        } else {
            if (form.mode() == null) {
                throw ApiException.badRequest("Choose a percentage or a fixed amount.", "mode");
            }
            mode = form.mode();
            if (mode == ConcessionMode.PERCENT) {
                if (form.percent() == null) {
                    throw ApiException.badRequest("Enter a percentage from 0.01 to 100.", "percent");
                }
                percentBp = form.percent().movePointRight(2).setScale(0, java.math.RoundingMode.UNNECESSARY)
                        .intValueExact();
            } else {
                if (form.fixedPaise() == null) {
                    throw ApiException.badRequest("Enter the amount.", "fixedPaise");
                }
                fixedPaise = form.fixedPaise();
            }
            if (form.headIds() == null || form.headIds().isEmpty()) {
                throw ApiException.badRequest("Pick at least one fee head.", "headIds");
            }
            Map<UUID, FeeHead> headById = duesService.headsById();
            headIds = new java.util.LinkedHashSet<>();
            for (UUID id : form.headIds()) {
                if (!headById.containsKey(id)) {
                    throw ApiException.badRequest("Pick fee heads of this school.", "headIds");
                }
                headIds.add(id);
            }
        }
        Concession concession = concessions.saveAndFlush(new Concession(student.id(), year.id(), form.type(), mode,
                percentBp, fixedPaise, form.reason().trim(), by.id(), by.name()));
        concessionHeads.saveAllAndFlush(headIds.stream().map(h -> new ConcessionHead(concession.getId(), h))
                .toList());
        duesService.regenerateStudent(student.id(), year.id());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("studentName", student.fullName());
        details.put("admissionNo", student.admissionNo());
        details.put("academicYear", year.name());
        details.put("type", form.type().name());
        details.put("mode", mode.name());
        if (percentBp != null) {
            details.put("percent", BigDecimal.valueOf(percentBp, 2).toPlainString());
        }
        if (fixedPaise != null) {
            details.put("fixedPaise", fixedPaise);
        }
        details.put("heads", headIds.size());
        details.put("reason", form.reason().trim());
        record(by, "concession.granted", "concession", concession.getId(), details);
        return duesService.concessionViews(List.of(concession), id -> student).getFirst();
    }

    /** Revokes a concession; the student's unpaid dues go back up. Paid amounts never change. */
    public ConcessionView revoke(UUID id, String reason, Actor actor) {
        TenantContext.require();
        Actor by = Fees.actor(actor);
        Concession concession = concessions.findById(id).orElseThrow(() -> ApiException.notFound("Concession"));
        if (!concession.isActive()) {
            throw Fees.conflict("Already revoked", "This concession was already revoked.");
        }
        concession.revoke(by.id(), by.name(), reason.trim());
        concessions.saveAndFlush(concession);
        duesService.regenerateStudent(concession.getStudentId(), concession.getAcademicYearId());
        StudentRoster.Entry student = roster.student(concession.getStudentId());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("studentName", student.fullName());
        details.put("admissionNo", student.admissionNo());
        details.put("type", concession.getType().name());
        details.put("reason", reason.trim());
        record(by, "concession.revoked", "concession", id, details);
        return duesService.concessionViews(List.of(concession), x -> student).getFirst();
    }

    // ------------------------------------------------------------------ late fee rule

    @Transactional(readOnly = true)
    public LateFeeRuleView lateFeeRule() {
        TenantContext.require();
        return FeeDuesService.view(duesService.lateFeeRule());
    }

    public LateFeeRuleView updateLateFeeRule(LateFeeRuleForm form, Actor actor) {
        TenantContext.require();
        int grace = form.graceDays() == null ? 0 : form.graceDays();
        long flat = 0;
        long perDay = 0;
        long cap = 0;
        switch (form.mode()) {
            case NONE -> grace = 0;
            case FLAT -> {
                if (form.flatPaise() == null || form.flatPaise() <= 0) {
                    throw ApiException.badRequest("Enter the late fee per overdue instalment.", "flatPaise");
                }
                flat = form.flatPaise();
            }
            case PER_DAY -> {
                if (form.perDayPaise() == null || form.perDayPaise() <= 0) {
                    throw ApiException.badRequest("Enter the late fee per day.", "perDayPaise");
                }
                perDay = form.perDayPaise();
                cap = form.capPaise() == null ? 0 : form.capPaise();
                if (cap > 0 && cap < perDay) {
                    throw ApiException.badRequest("The cap must be at least one day's late fee.", "capPaise");
                }
            }
            default -> throw new IllegalStateException();
        }
        FeeMath.LateFeeRule rule = new FeeMath.LateFeeRule(form.mode(), grace, flat, perDay, cap);
        LateFeeRuleEntity entity = lateFeeRules.findFirstBy().orElse(null);
        if (entity == null) {
            entity = new LateFeeRuleEntity(rule);
        } else {
            entity.apply(rule);
        }
        lateFeeRules.saveAndFlush(entity);
        record(actor, "late_fee_rule.updated", "late_fee_rule", entity.getId(), Map.of("mode", rule.mode().name(),
                "graceDays", grace, "flatPaise", flat, "perDayPaise", perDay, "capPaise", cap));
        return FeeDuesService.view(rule);
    }

    // ------------------------------------------------------------------ helpers

    private void record(Actor actor, String action, String entityType, UUID entityId, Map<String, ?> details) {
        if (actor == null) {
            audit.record(action, entityType, entityId, details);
        } else {
            audit.record(actor, action, entityType, entityId, details);
        }
    }
}
