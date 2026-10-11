package com.akshara.payroll;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.payroll.PayrollForms.ComponentFields;
import com.akshara.payroll.PayrollForms.NewComponent;
import com.akshara.payroll.PayrollViews.ComponentView;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;

/**
 * The earnings and fixed deductions salary structures are made of. Schools start with the standard set (Basic, DA,
 * HRA, Conveyance, Special allowance, Loan recovery, Other deduction) and can add their own. A component's code and
 * kind never change; one in use is switched off rather than removed.
 */
@Service
@Transactional
public class SalaryComponentService {

    record Standard(String code, String name, ComponentKind kind, boolean pfWage, boolean esiWage, boolean taxable,
            boolean prorated, int order) {
    }

    /** Basic and DA are PF wages; every earning counts for ESI and tax and is prorated; deductions are fixed. */
    static final List<Standard> STANDARD = List.of(
            new Standard("BASIC", "Basic", ComponentKind.EARNING, true, true, true, true, 10),
            new Standard("DA", "Dearness allowance", ComponentKind.EARNING, true, true, true, true, 20),
            new Standard("HRA", "House rent allowance", ComponentKind.EARNING, false, true, true, true, 30),
            new Standard("CONVEYANCE", "Conveyance allowance", ComponentKind.EARNING, false, true, true, true, 40),
            new Standard("SPECIAL", "Special allowance", ComponentKind.EARNING, false, true, true, true, 50),
            new Standard("LOAN", "Loan recovery", ComponentKind.DEDUCTION, false, false, false, false, 10),
            new Standard("OTHER_DED", "Other deduction", ComponentKind.DEDUCTION, false, false, false, false, 20));

    /** The codes of payroll's own payslip lines: statutory deductions, employer contributions and adjustments. */
    static final Set<String> RESERVED_CODES = Set.of(PayslipCalculator.PF, PayslipCalculator.ESI,
            PayslipCalculator.PT, PayslipCalculator.TDS, PayslipCalculator.ARREARS, PayslipCalculator.BONUS,
            PayslipCalculator.DEDUCTION, PayslipCalculator.EPF_ER, PayslipCalculator.EPS, PayslipCalculator.EDLI,
            PayslipCalculator.PF_ADMIN, PayslipCalculator.ESI_ER);

    private final SalaryComponentRepository components;
    private final SalaryStructureLineRepository lines;
    private final AuditService audit;

    SalaryComponentService(SalaryComponentRepository components, SalaryStructureLineRepository lines,
            AuditService audit) {
        this.components = components;
        this.lines = lines;
        this.audit = audit;
    }

    /** Earnings first, then deductions, each in display order. */
    @Transactional(readOnly = true)
    public List<ComponentView> list() {
        TenantContext.require();
        Set<UUID> used = lines.findAll().stream().map(SalaryStructureLine::getComponentId).collect(Collectors.toSet());
        return components.findAllOrdered().stream().map(c -> view(c, used.contains(c.getId()))).toList();
    }

    /** Adds the standard components the school does not have yet (by code). Safe to repeat. */
    public List<ComponentView> addStandard(Actor actor) {
        TenantContext.require();
        Set<String> codes = components.findAll().stream().map(SalaryComponent::getCode).collect(Collectors.toSet());
        int added = 0;
        for (Standard s : STANDARD) {
            if (codes.contains(s.code()) || components.nameTaken(s.name(), new UUID(0, 0))) {
                continue;
            }
            components.save(new SalaryComponent(s.code(), s.kind(), new SalaryComponent.Fields(s.name(), s.pfWage(),
                    s.esiWage(), s.taxable(), s.prorated(), s.order(), true)));
            added++;
        }
        components.flush();
        if (added > 0) {
            audit.record(actor, "salary_component.standard_added", "salary_component", null, Map.of("added", added));
        }
        return list();
    }

    public ComponentView create(NewComponent form, Actor actor) {
        TenantContext.require();
        String code = form.code().strip().toUpperCase(Locale.ROOT);
        if (RESERVED_CODES.contains(code)) {
            throw ApiException.badRequest("Payroll uses this code for its own lines. Pick another.", "code");
        }
        if (components.codeTaken(code)) {
            throw ApiException.conflict("Another component already has this code.", "code");
        }
        String name = form.fields().name().strip();
        if (components.nameTaken(name, new UUID(0, 0))) {
            throw ApiException.conflict("Another component already has this name.", "name");
        }
        SalaryComponent c = components.saveAndFlush(new SalaryComponent(code, form.kind(), fields(form.fields())));
        audit.record(actor, "salary_component.created", "salary_component", c.getId(), details(c));
        return view(c, false);
    }

    public ComponentView update(UUID id, ComponentFields form, Actor actor) {
        TenantContext.require();
        SalaryComponent c = components.findById(id).orElseThrow(() -> ApiException.notFound("Salary component"));
        String name = form.name().strip();
        if (components.nameTaken(name, id)) {
            throw ApiException.conflict("Another component already has this name.", "name");
        }
        c.apply(fields(form));
        components.flush();
        audit.record(actor, "salary_component.updated", "salary_component", c.getId(), details(c));
        return view(c, lines.componentUsed(id));
    }

    private static SalaryComponent.Fields fields(ComponentFields f) {
        return new SalaryComponent.Fields(f.name().strip(), f.pfWage(), f.esiWage(), f.taxable(), f.prorated(),
                f.displayOrder(), f.active());
    }

    private static Map<String, Object> details(SalaryComponent c) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("code", c.getCode());
        details.put("name", c.getName());
        details.put("kind", c.getKind().name());
        details.put("active", c.isActive());
        return details;
    }

    static ComponentView view(SalaryComponent c, boolean inUse) {
        return new ComponentView(c.getId(), c.getCode(), c.getName(), c.getKind(), c.isPfWage(), c.isEsiWage(),
                c.isTaxable(), c.isProrated(), c.getDisplayOrder(), c.isActive(), inUse);
    }
}
