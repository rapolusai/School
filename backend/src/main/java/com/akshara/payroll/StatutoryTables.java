package com.akshara.payroll;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.payroll.PayrollMath.TaxBand;
import com.akshara.payroll.PayrollMath.TaxRules;
import com.akshara.shared.ApiException;

/**
 * Finds the statutory figures in force for a month: PF and ESI rates, the income tax rules of the financial year and
 * the professional tax slabs of a state (the school's own when it saved any for the state, otherwise the published
 * defaults). Rates, rules and slabs are data (V14__payroll.sql); a new value is a new row with its own date.
 */
@Component
@Transactional(readOnly = true)
class StatutoryTables {

    /** The states whose professional tax slabs are seeded, by code, in the order the screens list them. */
    static final List<String> SEEDED_STATES = List.of("TG", "AP", "KA", "MH", "TN", "WB");

    /** A year's income tax rules and whether they were published for the year asked about. */
    record TaxYear(TaxRule rule, TaxRules rules, boolean forThisYear) {
    }

    /** A state's slabs as a set: the school's own or the defaults, all from one effective date. */
    record PtSet(String state, boolean custom, LocalDate effectiveFrom, List<? extends PtSlabRow> slabs) {
    }

    private final StatutoryRateRepository rates;
    private final TaxRuleRepository taxRules;
    private final TaxSlabRepository taxSlabs;
    private final PtSlabDefaultRepository ptDefaults;
    private final PtSlabRepository ptSlabs;

    StatutoryTables(StatutoryRateRepository rates, TaxRuleRepository taxRules, TaxSlabRepository taxSlabs,
            PtSlabDefaultRepository ptDefaults, PtSlabRepository ptSlabs) {
        this.rates = rates;
        this.taxRules = taxRules;
        this.taxSlabs = taxSlabs;
        this.ptDefaults = ptDefaults;
        this.ptSlabs = ptSlabs;
    }

    /** Every PF and ESI rate row, newest first. */
    List<StatutoryRate> allRates() {
        return rates.findAllNewestFirst();
    }

    /** The PF and ESI rates in force on the first day of the month (409 when none is). */
    StatutoryRate rateFor(YearMonth month) {
        LocalDate first = month.atDay(1);
        return rates.findAllNewestFirst().stream().filter(r -> !r.getEffectiveFrom().isAfter(first)).findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "No PF and ESI rates",
                        "No PF and ESI rates are in force for " + month + "."));
    }

    /** Every income tax year, newest first. */
    List<TaxRule> allTaxRules() {
        return taxRules.findAllNewestFirst();
    }

    List<TaxSlab> slabsOf(TaxRule rule) {
        return taxSlabs.findByRule(rule.getId());
    }

    /**
     * The income tax rules for the month's financial year: that year's when published, otherwise the latest earlier
     * year's ({@code forThisYear} false, so the screens ask to verify them). Empty when there are none at all.
     */
    Optional<TaxYear> taxYearFor(YearMonth month) {
        String year = PayrollMath.financialYear(month);
        LocalDate first = month.atDay(1);
        return taxRules.findAllNewestFirst().stream().filter(r -> !r.getStartsOn().isAfter(first)).findFirst()
                .map(rule -> new TaxYear(rule, rules(rule), rule.getFinancialYear().equals(year)));
    }

    TaxRules rules(TaxRule rule) {
        List<TaxBand> bands = slabsOf(rule).stream()
                .map(s -> new TaxBand(s.getFromPaise(), s.getToPaise(), s.getRatePercent())).toList();
        return new TaxRules(rule.getFinancialYear(), rule.getStandardDeductionPaise(),
                rule.getRebateIncomeLimitPaise(), rule.getRebateMaxPaise(), rule.getCessPercent(), bands);
    }

    /** Every state with published default slabs or slabs of the school's own. */
    List<String> ptStates() {
        Set<String> states = new LinkedHashSet<>(SEEDED_STATES);
        states.addAll(ptSlabs.customStates());
        return List.copyOf(states);
    }

    /**
     * The slabs of a state in force for the month (the latest effective date on or before its first day). The school's
     * own slabs replace the defaults; when none of its own are in force yet, the defaults apply.
     */
    Optional<PtSet> ptSetFor(String state, YearMonth month) {
        if (state == null) {
            return Optional.empty();
        }
        LocalDate first = month == null ? LocalDate.MAX : month.atDay(1);
        Optional<PtSet> own = latest(state, true, ptSlabs.findByState(state), first);
        return own.isPresent() ? own : latest(state, false, ptDefaults.findByState(state), first);
    }

    /** The slabs as the calculator uses them; empty when the state has none. */
    List<PayrollMath.PtSlab> ptSlabsFor(String state, YearMonth month) {
        return ptSetFor(state, month).map(set -> set.slabs().stream().map(PtSlabRow::toMath).toList())
                .orElse(List.of());
    }

    /** The school's own slabs for a state, if it saved any. */
    List<PtSlab> ownSlabs(String state) {
        return ptSlabs.findByState(state);
    }

    private static Optional<PtSet> latest(String state, boolean custom, List<? extends PtSlabRow> rows,
            LocalDate onOrBefore) {
        TreeMap<LocalDate, List<PtSlabRow>> byDate = rows.stream().<PtSlabRow>map(r -> r)
                .collect(Collectors.groupingBy(PtSlabRow::getEffectiveFrom, TreeMap::new, Collectors.toList()));
        return byDate.descendingMap().entrySet().stream()
                .filter(e -> !e.getKey().isAfter(onOrBefore))
                .findFirst()
                .map(e -> new PtSet(state, custom, e.getKey(), e.getValue().stream()
                        .sorted(Comparator.comparing(PtSlabRow::getAppliesTo).thenComparingLong(PtSlabRow::getFromPaise)
                                .thenComparing(r -> Objects.requireNonNullElse(r.getMonths(), "")))
                        .toList()));
    }
}
