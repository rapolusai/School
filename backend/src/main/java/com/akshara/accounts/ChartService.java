package com.akshara.accounts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.accounts.AccountsForms.CostCentreForm;
import com.akshara.accounts.AccountsForms.HeadForm;
import com.akshara.accounts.AccountsViews.CostCentreView;
import com.akshara.accounts.AccountsViews.HeadView;
import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.fees.FeeSetupService;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;

/**
 * The chart of heads (income and expense, one level deep) and the cost centres. The standard chart is added on
 * request: the income side maps every fee head of the fees module under "Fee income", plus donations, grants,
 * transport, hostel, rentals and other income; the expense side has salaries, electricity, water, repairs,
 * stationery, transport fuel, events, rent, taxes and other. Heads the module itself posts to carry a system code and
 * are created on demand.
 */
@Service
@Transactional
public class ChartService {

    record Standard(String systemCode, String code, String name, HeadKind kind, boolean gst) {
    }

    static final List<Standard> STANDARD = List.of(
            new Standard("FEES", "INC-FEES", "Fee income", HeadKind.INCOME, false),
            new Standard("DONATIONS", "INC-DON", "Donations", HeadKind.INCOME, false),
            new Standard("GRANTS", "INC-GRANT", "Grants", HeadKind.INCOME, false),
            new Standard("TRANSPORT_INCOME", "INC-TRANS", "Transport income", HeadKind.INCOME, false),
            new Standard("HOSTEL_INCOME", "INC-HOSTEL", "Hostel income", HeadKind.INCOME, false),
            new Standard("RENTALS", "INC-RENT", "Rentals", HeadKind.INCOME, true),
            new Standard("OTHER_INCOME", "INC-OTHER", "Other income", HeadKind.INCOME, false),
            new Standard("SALARIES", "EXP-SAL", "Salaries and wages", HeadKind.EXPENSE, false),
            new Standard("ELECTRICITY", "EXP-ELEC", "Electricity", HeadKind.EXPENSE, true),
            new Standard("WATER", "EXP-WATER", "Water", HeadKind.EXPENSE, false),
            new Standard("REPAIRS", "EXP-REPAIR", "Repairs and maintenance", HeadKind.EXPENSE, true),
            new Standard("STATIONERY", "EXP-STAT", "Stationery and printing", HeadKind.EXPENSE, true),
            new Standard("FUEL", "EXP-FUEL", "Transport fuel", HeadKind.EXPENSE, false),
            new Standard("EVENTS", "EXP-EVENT", "Events and functions", HeadKind.EXPENSE, true),
            new Standard("RENT", "EXP-RENT", "Rent", HeadKind.EXPENSE, true),
            new Standard("TAXES", "EXP-TAX", "Taxes and fees", HeadKind.EXPENSE, false),
            new Standard("PETTY_CASH", "EXP-PETTY", "Petty expenses", HeadKind.EXPENSE, false),
            new Standard("OTHER_EXPENSE", "EXP-OTHER", "Other expenses", HeadKind.EXPENSE, true));

    private final HeadRepository heads;
    private final CostCentreRepository costCentres;
    private final ExpenseRepository expenses;
    private final IncomeReceiptRepository receipts;
    private final PettyCashEntryRepository petty;
    private final BudgetRepository budgets;
    private final FeeSetupService fees;
    private final AuditService audit;

    ChartService(HeadRepository heads, CostCentreRepository costCentres, ExpenseRepository expenses,
            IncomeReceiptRepository receipts, PettyCashEntryRepository petty, BudgetRepository budgets,
            FeeSetupService fees, AuditService audit) {
        this.heads = heads;
        this.costCentres = costCentres;
        this.expenses = expenses;
        this.receipts = receipts;
        this.petty = petty;
        this.budgets = budgets;
        this.fees = fees;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ heads

    @Transactional(readOnly = true)
    public List<HeadView> heads() {
        TenantContext.require();
        return views(heads.findAllOrdered());
    }

    public HeadView createHead(HeadForm form, Actor actor) {
        TenantContext.require();
        String code = form.code().strip().toUpperCase(Locale.ROOT);
        String name = form.name().strip();
        if (heads.codeTaken(code)) {
            throw ApiException.conflict("A head with this code already exists.", "code");
        }
        if (heads.nameTaken(name, new UUID(0, 0))) {
            throw ApiException.conflict("A head with this name already exists.", "name");
        }
        UUID parentId = parent(form.parentId(), form.kind(), null);
        Head head = new Head(code, name, form.kind(), parentId, form.gstApplicable(), null, null,
                form.displayOrder());
        if (!form.active()) {
            head.update(name, parentId, form.gstApplicable(), false, form.displayOrder());
        }
        save(head);
        audit.record(actor, "account_head.created", "account_head", head.getId(), Map.of("code", code, "name", name,
                "kind", form.kind().name()));
        return view(head, byId());
    }

    public HeadView updateHead(UUID id, HeadForm form, Actor actor) {
        TenantContext.require();
        Head head = heads.findById(id).orElseThrow(() -> ApiException.notFound("Head"));
        String name = form.name().strip();
        if (heads.nameTaken(name, id)) {
            throw ApiException.conflict("A head with this name already exists.", "name");
        }
        if (form.kind() != head.getKind()) {
            throw ApiException.badRequest("A head's kind cannot change.", "kind");
        }
        UUID parentId = parent(form.parentId(), head.getKind(), id);
        if (parentId != null && heads.hasChildren(id)) {
            throw ApiException.badRequest("This head has sub-heads, so it cannot become a sub-head itself.",
                    "parentId");
        }
        head.update(name, parentId, form.gstApplicable(), form.active(), form.displayOrder());
        save(head);
        audit.record(actor, "account_head.updated", "account_head", id, Map.of("code", head.getCode(), "name", name,
                "active", form.active()));
        return view(head, byId());
    }

    /** Adds the standard heads the school does not have yet, and an income sub-head per fee head. */
    public List<HeadView> addStandardHeads(Actor actor) {
        TenantContext.require();
        int added = 0;
        for (Standard s : STANDARD) {
            if (heads.findBySystemCode(s.systemCode()).isEmpty()) {
                systemHead(s.systemCode());
                added++;
            }
        }
        Head feesHead = systemHead("FEES");
        int order = 1;
        for (var fee : fees.heads()) {
            if (heads.findByFeeHeadId(fee.id()).isPresent()) {
                order++;
                continue;
            }
            String code = uniqueCode("FEE-" + fee.kind().name());
            String name = uniqueName(fee.name());
            save(new Head(code, name, HeadKind.INCOME, feesHead.getId(), false, fee.id(), null, order++));
            added++;
        }
        audit.record(actor, "account_heads.standard_added", "account_head", null, Map.of("added", added));
        return heads();
    }

    /** The fee income head when the chart has one; reports post fee collections there without creating it. */
    java.util.Optional<UUID> feesHeadId() {
        return heads.findBySystemCode("FEES").map(Head::getId);
    }

    /** The head the module posts to for this purpose, created with its standard name when missing. */
    Head systemHead(String systemCode) {
        return heads.findBySystemCode(systemCode).orElseGet(() -> {
            Standard s = STANDARD.stream().filter(x -> x.systemCode().equals(systemCode)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown system head " + systemCode));
            int order = (int) STANDARD.indexOf(s) + 1;
            Head head = new Head(uniqueCode(s.code()), uniqueName(s.name()), s.kind(), null, s.gst(), null,
                    s.systemCode(), order);
            return save(head);
        });
    }

    /** The head, which must exist, be active and be of the kind (400 on the field otherwise). */
    Head activeHead(UUID id, HeadKind kind, String field) {
        Head head = heads.findById(id).orElse(null);
        if (head == null || !head.isActive()) {
            throw ApiException.badRequest("Pick an active " + kind.name().toLowerCase(Locale.ROOT) + " head.",
                    field);
        }
        if (head.getKind() != kind) {
            throw ApiException.badRequest("This is not an " + kind.name().toLowerCase(Locale.ROOT) + " head.",
                    field);
        }
        return head;
    }

    Map<UUID, Head> byId() {
        return heads.findAll().stream().collect(Collectors.toMap(Head::getId, Function.identity()));
    }

    static String nameOf(Map<UUID, Head> byId, UUID id) {
        Head h = id == null ? null : byId.get(id);
        return h == null ? null : h.getName();
    }

    static String codeOf(Map<UUID, Head> byId, UUID id) {
        Head h = id == null ? null : byId.get(id);
        return h == null ? null : h.getCode();
    }

    private UUID parent(UUID parentId, HeadKind kind, UUID self) {
        if (parentId == null) {
            return null;
        }
        if (parentId.equals(self)) {
            throw ApiException.badRequest("A head cannot be its own parent.", "parentId");
        }
        Head parent = heads.findById(parentId).orElse(null);
        if (parent == null || parent.getKind() != kind) {
            throw ApiException.badRequest("Pick a parent head of the same kind.", "parentId");
        }
        if (parent.getParentId() != null) {
            throw ApiException.badRequest("Sub-heads cannot have sub-heads of their own (one level only).",
                    "parentId");
        }
        return parentId;
    }

    private Head save(Head head) {
        try {
            return heads.saveAndFlush(head);
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("A head with this code or name already exists.", "code");
        }
    }

    private String uniqueCode(String base) {
        String code = base.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_-]", "");
        code = code.substring(0, Math.min(17, code.length()));
        String candidate = code;
        int n = 2;
        while (heads.codeTaken(candidate)) {
            candidate = code + "-" + n++;
        }
        return candidate;
    }

    private String uniqueName(String base) {
        String name = base.length() > 70 ? base.substring(0, 70) : base;
        String candidate = name;
        int n = 2;
        while (heads.nameTaken(candidate, new UUID(0, 0))) {
            candidate = name + " (" + n++ + ")";
        }
        return candidate;
    }

    private List<HeadView> views(List<Head> list) {
        Map<UUID, Head> byId = list.stream().collect(Collectors.toMap(Head::getId, Function.identity()));
        List<HeadView> out = new ArrayList<>();
        for (Head h : list) {
            out.add(view(h, byId));
        }
        return out;
    }

    private HeadView view(Head h, Map<UUID, Head> byId) {
        boolean inUse = h.getSystemCode() != null || heads.hasChildren(h.getId()) || expenses.headUsed(h.getId())
                || receipts.headUsed(h.getId()) || petty.headUsed(h.getId()) || budgets.headUsed(h.getId());
        return new HeadView(h.getId(), h.getCode(), h.getName(), h.getKind(), h.getParentId(),
                nameOf(byId, h.getParentId()), h.isGstApplicable(), h.getFeeHeadId(), h.getSystemCode(),
                h.isActive(), h.getDisplayOrder(), inUse);
    }

    // ------------------------------------------------------------------ cost centres

    @Transactional(readOnly = true)
    public List<CostCentreView> costCentres() {
        TenantContext.require();
        return costCentres.findAllOrdered().stream().map(this::view).toList();
    }

    public CostCentreView createCostCentre(CostCentreForm form, Actor actor) {
        TenantContext.require();
        String code = form.code().strip().toUpperCase(Locale.ROOT);
        String name = form.name().strip();
        if (costCentres.codeTaken(code)) {
            throw ApiException.conflict("A cost centre with this code already exists.", "code");
        }
        if (costCentres.nameTaken(name, new UUID(0, 0))) {
            throw ApiException.conflict("A cost centre with this name already exists.", "name");
        }
        CostCentre c = new CostCentre(code, name);
        c.update(name, form.active());
        costCentres.saveAndFlush(c);
        audit.record(actor, "cost_centre.created", "cost_centre", c.getId(), Map.of("code", code, "name", name));
        return view(c);
    }

    public CostCentreView updateCostCentre(UUID id, CostCentreForm form, Actor actor) {
        TenantContext.require();
        CostCentre c = costCentres.findById(id).orElseThrow(() -> ApiException.notFound("Cost centre"));
        String name = form.name().strip();
        if (costCentres.nameTaken(name, id)) {
            throw ApiException.conflict("A cost centre with this name already exists.", "name");
        }
        c.update(name, form.active());
        costCentres.saveAndFlush(c);
        audit.record(actor, "cost_centre.updated", "cost_centre", id, Map.of("code", c.getCode(), "name", name,
                "active", form.active()));
        return view(c);
    }

    public void deleteCostCentre(UUID id, Actor actor) {
        TenantContext.require();
        CostCentre c = costCentres.findById(id).orElseThrow(() -> ApiException.notFound("Cost centre"));
        if (expenses.costCentreUsed(id)) {
            throw new ApiException(HttpStatus.CONFLICT, "In use",
                    "Expenses are charged to this cost centre. Mark it inactive instead.");
        }
        costCentres.delete(c);
        costCentres.flush();
        audit.record(actor, "cost_centre.deleted", "cost_centre", id, Map.of("code", c.getCode(), "name",
                c.getName()));
    }

    /** The cost centre, which must exist and be active (400 on the field otherwise); null for none. */
    CostCentre activeCostCentre(UUID id, String field) {
        if (id == null) {
            return null;
        }
        CostCentre c = costCentres.findById(id).orElse(null);
        if (c == null || !c.isActive()) {
            throw ApiException.badRequest("Pick an active cost centre.", field);
        }
        return c;
    }

    Map<UUID, CostCentre> costCentresById() {
        return costCentres.findAll().stream().collect(Collectors.toMap(CostCentre::getId, Function.identity()));
    }

    static String costCentreName(Map<UUID, CostCentre> byId, UUID id) {
        CostCentre c = id == null ? null : byId.get(id);
        return c == null ? null : c.getName();
    }

    private CostCentreView view(CostCentre c) {
        return new CostCentreView(c.getId(), c.getCode(), c.getName(), c.isActive(),
                expenses.costCentreUsed(c.getId()));
    }

    static Map<String, Object> details(Head head) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("code", head.getCode());
        d.put("name", head.getName());
        return d;
    }
}
