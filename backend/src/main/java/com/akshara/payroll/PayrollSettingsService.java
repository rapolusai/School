package com.akshara.payroll;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.payroll.PayrollForms.PtSlabInput;
import com.akshara.payroll.PayrollForms.PtSlabsForm;
import com.akshara.payroll.PayrollForms.SettingsForm;
import com.akshara.payroll.PayrollSettings.Values;
import com.akshara.payroll.PayrollViews.PtSlabView;
import com.akshara.payroll.PayrollViews.PtSlabsView;
import com.akshara.payroll.PayrollViews.RateView;
import com.akshara.payroll.PayrollViews.SettingsView;
import com.akshara.payroll.PayrollViews.StatutoryView;
import com.akshara.payroll.PayrollViews.TaxRuleView;
import com.akshara.payroll.PayrollViews.TaxSlabView;
import com.akshara.payroll.StatutoryTables.TaxYear;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;

/**
 * A school's payroll settings and the statutory figures they are used with: the pay day, proration, which statutory
 * deductions apply, registration numbers, the bank account salaries are paid from, and the school's own professional
 * tax slabs. Rates and limits are data with an effective date; the screens ask schools to confirm them with their
 * accountant.
 */
@Service
@Transactional
public class PayrollSettingsService {

    static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    private final PayrollSettingsRepository settings;
    private final PtSlabRepository ownSlabs;
    private final StatutoryTables statutory;
    private final AuditService audit;

    PayrollSettingsService(PayrollSettingsRepository settings, PtSlabRepository ownSlabs, StatutoryTables statutory,
            AuditService audit) {
        this.settings = settings;
        this.ownSlabs = ownSlabs;
        this.statutory = statutory;
        this.audit = audit;
    }

    static YearMonth thisMonth() {
        return YearMonth.now(INDIA);
    }

    /** The school's settings as saved, or the defaults until they are. */
    @Transactional(readOnly = true)
    public Values values() {
        TenantContext.require();
        return row().map(PayrollSettings::values).orElse(Values.DEFAULTS);
    }

    @Transactional(readOnly = true)
    public SettingsView view() {
        TenantContext.require();
        Optional<PayrollSettings> row = row();
        Values v = row.map(PayrollSettings::values).orElse(Values.DEFAULTS);
        return new SettingsView(row.isPresent(), v.payDay(), v.prorationBasis(), v.pfEnabled(), v.pfOnFullWages(),
                v.esiEnabled(), v.ptEnabled(), v.ptState(), v.pfEstablishmentCode(), v.esiEmployerCode(),
                v.ptRegistration(), v.tan(), v.bankName(), v.bankBranch(), v.bankAccountNumber(), v.bankIfsc(),
                row.map(PayrollSettings::getUpdatedAt).orElse(null), statutoryView(v.ptState()));
    }

    public SettingsView save(SettingsForm form, Actor actor) {
        TenantContext.require();
        String state = PayrollForms.upper(form.ptState());
        if (form.ptEnabled() && state == null) {
            throw ApiException.badRequest("Pick the state whose professional tax applies.", "ptState");
        }
        String account = PayrollForms.clean(form.bankAccountNumber());
        String ifsc = PayrollForms.upper(form.bankIfsc());
        if ((account == null) != (ifsc == null)) {
            throw ApiException.badRequest("Enter the account number and its IFSC together.",
                    account == null ? "bankAccountNumber" : "bankIfsc");
        }
        Values values = new Values(form.payDay(), form.prorationBasis(), form.pfEnabled(), form.pfOnFullWages(),
                form.esiEnabled(), form.ptEnabled(), state, PayrollForms.upper(form.pfEstablishmentCode()),
                PayrollForms.upper(form.esiEmployerCode()), PayrollForms.upper(form.ptRegistration()),
                PayrollForms.upper(form.tan()), PayrollForms.clean(form.bankName()),
                PayrollForms.clean(form.bankBranch()), account, ifsc);
        Optional<PayrollSettings> row = row();
        Values before = row.map(PayrollSettings::values).orElse(Values.DEFAULTS);
        PayrollSettings entity = row.orElse(null);
        if (entity == null) {
            entity = settings.save(new PayrollSettings(values));
        } else {
            entity.apply(values);
        }
        settings.flush();
        audit.record(actor, "payroll_settings.updated", "payroll_settings", entity.getId(),
                Map.of("changed", changed(before, values)));
        return view();
    }

    /** The slabs of a state in force now: the school's own when it has any, otherwise the published ones. */
    @Transactional(readOnly = true)
    public PtSlabsView ptSlabs(String state) {
        TenantContext.require();
        String code = stateCode(state);
        return statutory.ptSetFor(code, thisMonth()).or(() -> statutory.ptSetFor(code, null))
                .map(set -> new PtSlabsView(code, set.custom(), set.effectiveFrom(),
                        set.slabs().stream().map(PtSlabView::of).toList()))
                .orElse(new PtSlabsView(code, false, null, List.of()));
    }

    /** Replaces the school's own slabs for a state. */
    public PtSlabsView savePtSlabs(String state, PtSlabsForm form, Actor actor) {
        TenantContext.require();
        String code = stateCode(state);
        List<PtSlabInput> slabs = form.slabs();
        for (int i = 0; i < slabs.size(); i++) {
            PtSlabInput s = slabs.get(i);
            if (s.toPaise() != null && s.toPaise() < s.fromPaise()) {
                throw ApiException.badRequest("The upper limit must not be below the lower limit.",
                        "slabs[" + i + "].toPaise");
            }
            for (int j = 0; j < i; j++) {
                if (overlap(slabs.get(j), s)) {
                    throw ApiException.badRequest("This slab overlaps slab " + (j + 1)
                            + ": the same pay would fall in both.", "slabs[" + i + "].fromPaise");
                }
            }
        }
        ownSlabs.deleteByState(code);
        ownSlabs.flush();
        for (PtSlabInput s : slabs) {
            String months = s.months() == null || s.months().isEmpty() ? null
                    : s.months().stream().distinct().sorted().map(String::valueOf).collect(Collectors.joining(","));
            ownSlabs.save(new PtSlab(code, form.effectiveFrom(), s.appliesTo(), s.fromPaise(), s.toPaise(),
                    s.amountPaise(), months));
        }
        ownSlabs.flush();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("state", code);
        details.put("effectiveFrom", form.effectiveFrom().toString());
        details.put("slabs", slabs.size());
        audit.record(actor, "payroll_pt_slabs.updated", "payroll_settings", code, details);
        return ptSlabs(code);
    }

    /**
     * Two slabs overlap when they can apply to the same person in the same month at the same pay. A slab for
     * specific months may sit on top of an every-month slab (it replaces it in those months), and slabs for women
     * and for everyone else are separate; slabs for everyone conflict with both.
     */
    static boolean overlap(PtSlabInput a, PtSlabInput b) {
        if (a.appliesTo() != b.appliesTo() && a.appliesTo() != PtAppliesTo.ALL && b.appliesTo() != PtAppliesTo.ALL) {
            return false;
        }
        boolean aEvery = a.months() == null || a.months().isEmpty();
        boolean bEvery = b.months() == null || b.months().isEmpty();
        if (aEvery != bEvery) {
            return false;
        }
        if (!aEvery && Collections.disjoint(a.months(), b.months())) {
            return false;
        }
        long aTo = a.toPaise() == null ? Long.MAX_VALUE : a.toPaise();
        long bTo = b.toPaise() == null ? Long.MAX_VALUE : b.toPaise();
        return a.fromPaise() <= bTo && b.fromPaise() <= aTo;
    }

    /** Removes the school's own slabs for a state, so the published ones apply again. */
    public PtSlabsView resetPtSlabs(String state, Actor actor) {
        TenantContext.require();
        String code = stateCode(state);
        int removed = ownSlabs.deleteByState(code);
        ownSlabs.flush();
        if (removed > 0) {
            audit.record(actor, "payroll_pt_slabs.reset", "payroll_settings", code, Map.of("state", code));
        }
        return ptSlabs(code);
    }

    // ------------------------------------------------------------------ helpers

    private Optional<PayrollSettings> row() {
        return settings.findForSchool().stream().findFirst();
    }

    private StatutoryView statutoryView(String ptState) {
        YearMonth month = thisMonth();
        List<RateView> rates = statutory.allRates().stream().map(RateView::of).toList();
        RateView rate = rates.stream().filter(r -> !r.effectiveFrom().isAfter(month.atDay(1))).findFirst()
                .orElse(null);
        Optional<TaxYear> year = statutory.taxYearFor(month);
        List<TaxRuleView> rules = statutory.allTaxRules().stream().map(this::taxRuleView).toList();
        return new StatutoryView(month.toString(), rate, rates, PayrollMath.financialYear(month),
                year.map(y -> taxRuleView(y.rule())).orElse(null), year.map(TaxYear::forThisYear).orElse(false),
                rules, statutory.ptStates(), ptState == null ? null : ptSlabs(ptState));
    }

    private TaxRuleView taxRuleView(TaxRule rule) {
        return new TaxRuleView(rule.getFinancialYear(), rule.getStartsOn(), rule.getStandardDeductionPaise(),
                rule.getRebateIncomeLimitPaise(), rule.getRebateMaxPaise(), rule.getCessPercent(),
                statutory.slabsOf(rule).stream()
                        .map(s -> new TaxSlabView(s.getFromPaise(), s.getToPaise(), s.getRatePercent())).toList(),
                rule.getNote());
    }

    static String stateCode(String state) {
        String code = PayrollForms.upper(state);
        if (code == null || !code.matches("^[A-Z]{2}$")) {
            throw ApiException.badRequest("Give the state as its two-letter code, like TG.", "state");
        }
        return code;
    }

    /** The names of the settings that changed; never their values (bank details are sensitive). */
    private static List<String> changed(Values a, Values b) {
        List<String> names = new ArrayList<>();
        Object[][] pairs = {{"payDay", a.payDay(), b.payDay()},
            {"prorationBasis", a.prorationBasis(), b.prorationBasis()},
            {"pfEnabled", a.pfEnabled(), b.pfEnabled()}, {"pfOnFullWages", a.pfOnFullWages(), b.pfOnFullWages()},
            {"esiEnabled", a.esiEnabled(), b.esiEnabled()}, {"ptEnabled", a.ptEnabled(), b.ptEnabled()},
            {"ptState", a.ptState(), b.ptState()},
            {"pfEstablishmentCode", a.pfEstablishmentCode(), b.pfEstablishmentCode()},
            {"esiEmployerCode", a.esiEmployerCode(), b.esiEmployerCode()},
            {"ptRegistration", a.ptRegistration(), b.ptRegistration()}, {"tan", a.tan(), b.tan()},
            {"bankName", a.bankName(), b.bankName()}, {"bankBranch", a.bankBranch(), b.bankBranch()},
            {"bankAccountNumber", a.bankAccountNumber(), b.bankAccountNumber()},
            {"bankIfsc", a.bankIfsc(), b.bankIfsc()}};
        for (Object[] p : pairs) {
            if (!Objects.equals(p[1], p[2])) {
                names.add((String) p[0]);
            }
        }
        return names;
    }

    /** Today in India. */
    static LocalDate today() {
        return LocalDate.now(INDIA);
    }
}
