package com.akshara.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Month;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.akshara.payroll.PayrollMath.Pf;
import com.akshara.payroll.PayrollMath.PfRates;
import com.akshara.payroll.PayrollMath.PtSlab;
import com.akshara.payroll.PayrollMath.TaxBand;
import com.akshara.payroll.PayrollMath.TaxRules;
import com.akshara.payroll.PayrollMath.TaxWorking;

/** The statutory arithmetic, with the rates seeded in V14__payroll.sql. Amounts are in paise. */
class PayrollMathTest {

    static final PfRates PF = new PfRates(new BigDecimal("12.000"), new BigDecimal("12.000"), 15_000_00,
            new BigDecimal("8.330"), 1_250_00, new BigDecimal("0.500"), 75_00, new BigDecimal("0.500"));
    static final BigDecimal ESI_EE = new BigDecimal("0.750");
    static final BigDecimal ESI_ER = new BigDecimal("3.250");
    static final long ESI_CEILING = 21_000_00;

    /** FY 2025-26, new regime. */
    static final TaxRules FY_2025_26 = new TaxRules("2025-26", 75_000_00, 12_00_000_00L, 60_000_00,
            new BigDecimal("4.000"), List.of(
                    new TaxBand(0, 4_00_000_00L, new BigDecimal("0")),
                    new TaxBand(4_00_000_00L, 8_00_000_00L, new BigDecimal("5")),
                    new TaxBand(8_00_000_00L, 12_00_000_00L, new BigDecimal("10")),
                    new TaxBand(12_00_000_00L, 16_00_000_00L, new BigDecimal("15")),
                    new TaxBand(16_00_000_00L, 20_00_000_00L, new BigDecimal("20")),
                    new TaxBand(20_00_000_00L, 24_00_000_00L, new BigDecimal("25")),
                    new TaxBand(24_00_000_00L, null, new BigDecimal("30"))));

    static PtSlab slab(PtAppliesTo who, long fromRupees, Long toRupees, long amountRupees, Integer... months) {
        return new PtSlab(who, fromRupees * 100, toRupees == null ? null : toRupees * 100, amountRupees * 100,
                Set.of(months));
    }

    static final List<PtSlab> TELANGANA = List.of(
            slab(PtAppliesTo.ALL, 0, 15_000L, 0),
            new PtSlab(PtAppliesTo.ALL, 15_000_01, 20_000_00L, 150_00, Set.of()),
            new PtSlab(PtAppliesTo.ALL, 20_000_01, null, 200_00, Set.of()));

    static final List<PtSlab> MAHARASHTRA = List.of(
            slab(PtAppliesTo.OTHERS, 0, 7_500L, 0),
            new PtSlab(PtAppliesTo.OTHERS, 7_500_01, 10_000_00L, 175_00, Set.of()),
            new PtSlab(PtAppliesTo.OTHERS, 10_000_01, null, 200_00, Set.of()),
            new PtSlab(PtAppliesTo.OTHERS, 10_000_01, null, 300_00, Set.of(2)),
            slab(PtAppliesTo.WOMEN, 0, 25_000L, 0),
            new PtSlab(PtAppliesTo.WOMEN, 25_000_01, null, 200_00, Set.of()),
            new PtSlab(PtAppliesTo.WOMEN, 25_000_01, null, 300_00, Set.of(2)));

    static final List<PtSlab> KARNATAKA = List.of(
            new PtSlab(PtAppliesTo.ALL, 0, 24_999_99L, 0, Set.of()),
            slab(PtAppliesTo.ALL, 25_000, null, 200),
            slab(PtAppliesTo.ALL, 25_000, null, 300, 2));

    static final List<PtSlab> TAMIL_NADU = List.of(
            new PtSlab(PtAppliesTo.ALL, 0, 3_500_00L, 0, Set.of(3, 9)),
            new PtSlab(PtAppliesTo.ALL, 3_500_01, 5_000_00L, 180_00, Set.of(3, 9)),
            new PtSlab(PtAppliesTo.ALL, 5_000_01, 7_500_00L, 425_00, Set.of(3, 9)),
            new PtSlab(PtAppliesTo.ALL, 7_500_01, 10_000_00L, 930_00, Set.of(3, 9)),
            new PtSlab(PtAppliesTo.ALL, 10_000_01, 12_500_00L, 1_025_00, Set.of(3, 9)),
            new PtSlab(PtAppliesTo.ALL, 12_500_01, null, 1_250_00, Set.of(3, 9)));

    @Nested
    class Rounding {

        @Test
        void roundsToRupeesTensAndUp() {
            assertThat(PayrollMath.nearestRupee(new BigDecimal("1250"))).isEqualTo(1300);
            assertThat(PayrollMath.nearestRupee(new BigDecimal("1249.99"))).isEqualTo(1200);
            assertThat(PayrollMath.nextRupee(new BigDecimal("11666.25"))).isEqualTo(11700);
            assertThat(PayrollMath.nextRupee(new BigDecimal("11600"))).isEqualTo(11600);
            assertThat(PayrollMath.nearestTenRupees(new BigDecimal("123450"))).isEqualTo(123000);
            assertThat(PayrollMath.nearestTenRupees(new BigDecimal("123500"))).isEqualTo(124000);
        }

        @Test
        void proratesToTheNearestRupeeWithHalvesUp() {
            // ₹30,000 for 15 of 30 days, and for 10 of 31 days (₹9,677.42).
            assertThat(PayrollMath.prorate(30_000_00, BigDecimal.valueOf(15), BigDecimal.valueOf(30)))
                    .isEqualTo(15_000_00);
            assertThat(PayrollMath.prorate(30_000_00, BigDecimal.valueOf(10), BigDecimal.valueOf(31)))
                    .isEqualTo(9_677_00);
            // ₹101 for half the days is ₹50.50, which rounds up to ₹51.
            assertThat(PayrollMath.prorate(101_00, BigDecimal.ONE, BigDecimal.TWO)).isEqualTo(51_00);
            // A half day of loss of pay in a 30-day month.
            assertThat(PayrollMath.prorate(30_000_00, new BigDecimal("29.5"), BigDecimal.valueOf(30)))
                    .isEqualTo(29_500_00);
            // A full month is the monthly amount exactly, even with paise; no days pays nothing.
            assertThat(PayrollMath.prorate(12_345_67, BigDecimal.valueOf(30), BigDecimal.valueOf(30)))
                    .isEqualTo(12_345_67);
            assertThat(PayrollMath.prorate(30_000_00, BigDecimal.ZERO, BigDecimal.valueOf(30))).isZero();
            assertThat(PayrollMath.prorate(30_000_00, BigDecimal.ONE, BigDecimal.ZERO)).isZero();
        }
    }

    @Nested
    class ProvidentFund {

        @Test
        void contributesOnWagesUpToTheCeilingByDefault() {
            Pf pf = PayrollMath.pf(20_000_00, false, PF);
            assertThat(pf.pfWages()).isEqualTo(20_000_00);
            assertThat(pf.epfWages()).isEqualTo(15_000_00);
            assertThat(pf.employee()).isEqualTo(1_800_00);
            // Employer's 12% (₹1,800): EPS 8.33% of ₹15,000 = ₹1,249.50, rounded to ₹1,250; EPF gets the rest.
            assertThat(pf.eps()).isEqualTo(1_250_00);
            assertThat(pf.epfEmployer()).isEqualTo(550_00);
            assertThat(pf.employerShare()).isEqualTo(1_800_00);
            assertThat(pf.edli()).isEqualTo(75_00);
            assertThat(pf.admin()).isEqualTo(75_00);
        }

        @Test
        void contributesOnFullWagesWhenTheSchoolChooses() {
            Pf pf = PayrollMath.pf(20_000_00, true, PF);
            assertThat(pf.epfWages()).isEqualTo(20_000_00);
            assertThat(pf.epsWages()).isEqualTo(15_000_00);
            assertThat(pf.edliWages()).isEqualTo(15_000_00);
            assertThat(pf.employee()).isEqualTo(2_400_00);
            // EPS stays capped at ₹1,250; EPF takes the rest of the employer's ₹2,400.
            assertThat(pf.eps()).isEqualTo(1_250_00);
            assertThat(pf.epfEmployer()).isEqualTo(1_150_00);
            assertThat(pf.edli()).isEqualTo(75_00);
            assertThat(pf.admin()).isEqualTo(100_00);
        }

        @Test
        void belowTheCeilingEverythingIsOnActualWages() {
            Pf pf = PayrollMath.pf(12_000_00, false, PF);
            assertThat(pf.epfWages()).isEqualTo(12_000_00);
            assertThat(pf.employee()).isEqualTo(1_440_00);
            // 8.33% of ₹12,000 = ₹999.60, rounded to ₹1,000.
            assertThat(pf.eps()).isEqualTo(1_000_00);
            assertThat(pf.epfEmployer()).isEqualTo(440_00);
            assertThat(pf.edli()).isEqualTo(60_00);
        }

        @Test
        void capsThePensionShare() {
            PfRates higher = new PfRates(PF.employeePercent(), PF.employerPercent(), 15_000_00,
                    new BigDecimal("9.000"), 1_250_00, PF.edliPercent(), 75_00, PF.adminPercent());
            Pf pf = PayrollMath.pf(15_000_00, false, higher);
            assertThat(pf.eps()).isEqualTo(1_250_00);
            assertThat(pf.epfEmployer()).isEqualTo(550_00);
            assertThat(PayrollMath.pf(0, false, PF)).isEqualTo(Pf.NONE);
        }
    }

    @Nested
    class StateInsurance {

        @Test
        void coversGrossWagesUpToTheCeiling() {
            assertThat(PayrollMath.esiCovered(21_000_00, ESI_CEILING)).isTrue();
            assertThat(PayrollMath.esiCovered(21_000_01, ESI_CEILING)).isFalse();
            assertThat(PayrollMath.esiCovered(0, ESI_CEILING)).isFalse();
        }

        @Test
        void roundsEachShareUpToTheNextRupee() {
            PayrollMath.Esi esi = PayrollMath.esi(true, 20_000_00, ESI_EE, ESI_ER);
            assertThat(esi.employee()).isEqualTo(150_00);
            assertThat(esi.employer()).isEqualTo(650_00);
            // ₹15,555: 0.75% = ₹116.66, 3.25% = ₹505.54.
            PayrollMath.Esi odd = PayrollMath.esi(true, 15_555_00, ESI_EE, ESI_ER);
            assertThat(odd.employee()).isEqualTo(117_00);
            assertThat(odd.employer()).isEqualTo(506_00);
            assertThat(PayrollMath.esi(false, 15_555_00, ESI_EE, ESI_ER)).isEqualTo(PayrollMath.Esi.NONE);
        }

        @ParameterizedTest
        @CsvSource({"2026-04,2026-04", "2026-09,2026-04", "2026-10,2026-10", "2026-12,2026-10", "2027-01,2026-10",
            "2027-03,2026-10"})
        void contributionPeriodsRunAprilToSeptemberAndOctoberToMarch(String month, String start) {
            assertThat(PayrollMath.esiPeriodStart(YearMonth.parse(month))).isEqualTo(YearMonth.parse(start));
        }
    }

    @Nested
    class ProfessionalTax {

        @ParameterizedTest
        @CsvSource({"15000.00,0", "15000.01,150", "20000.00,150", "20000.01,200", "90000.00,200"})
        void telanganaSlabs(String gross, String tax) {
            assertThat(PayrollMath.professionalTax(TELANGANA, paise(gross), Month.SEPTEMBER, false))
                    .isEqualTo(paise(tax));
        }

        @Test
        void maharashtraHasWomensSlabsAndChargesMoreInFebruary() {
            assertThat(PayrollMath.professionalTax(MAHARASHTRA, 7_500_00, Month.JULY, false)).isZero();
            assertThat(PayrollMath.professionalTax(MAHARASHTRA, 8_000_00, Month.JULY, false)).isEqualTo(175_00);
            assertThat(PayrollMath.professionalTax(MAHARASHTRA, 12_000_00, Month.JULY, false)).isEqualTo(200_00);
            assertThat(PayrollMath.professionalTax(MAHARASHTRA, 12_000_00, Month.FEBRUARY, false)).isEqualTo(300_00);
            // February's ₹300 is only for the top slab.
            assertThat(PayrollMath.professionalTax(MAHARASHTRA, 8_000_00, Month.FEBRUARY, false)).isEqualTo(175_00);
            // Women: nothing up to ₹25,000.
            assertThat(PayrollMath.professionalTax(MAHARASHTRA, 20_000_00, Month.JULY, true)).isZero();
            assertThat(PayrollMath.professionalTax(MAHARASHTRA, 30_000_00, Month.JULY, true)).isEqualTo(200_00);
            assertThat(PayrollMath.professionalTax(MAHARASHTRA, 30_000_00, Month.FEBRUARY, true)).isEqualTo(300_00);
        }

        @Test
        void karnatakaAndTamilNadu() {
            assertThat(PayrollMath.professionalTax(KARNATAKA, 24_999_99, Month.JUNE, false)).isZero();
            assertThat(PayrollMath.professionalTax(KARNATAKA, 25_000_00, Month.JUNE, false)).isEqualTo(200_00);
            assertThat(PayrollMath.professionalTax(KARNATAKA, 25_000_00, Month.FEBRUARY, false)).isEqualTo(300_00);
            // Tamil Nadu's half-yearly tax is deducted in September and March only.
            assertThat(PayrollMath.professionalTax(TAMIL_NADU, 6_000_00, Month.SEPTEMBER, false)).isEqualTo(425_00);
            assertThat(PayrollMath.professionalTax(TAMIL_NADU, 6_000_00, Month.AUGUST, false)).isZero();
            assertThat(PayrollMath.professionalTax(TAMIL_NADU, 40_000_00, Month.MARCH, false)).isEqualTo(1_250_00);
            // No slabs, no tax; no wages, no tax.
            assertThat(PayrollMath.professionalTax(List.of(), 40_000_00, Month.MARCH, false)).isZero();
            assertThat(PayrollMath.professionalTax(TELANGANA, 0, Month.MARCH, false)).isZero();
        }
    }

    @Nested
    class IncomeTax {

        /** Income before the ₹75,000 standard deduction, in rupees, and the year's tax in rupees. */
        @ParameterizedTest
        @CsvSource({
            // Up to ₹4 lakh taxable: nil.
            "475000,0",
            // ₹4-8 lakh at 5% and ₹8-12 lakh at 10% are fully rebated up to ₹12 lakh taxable (₹12.75 lakh salary).
            "875000,0", "1275000,0",
            // Just above ₹12 lakh: marginal relief keeps the tax at the income above ₹12 lakh, plus cess.
            "1285000,10400", "1345000,72800",
            // ₹12,75,000 taxable: slab tax ₹71,250 is less than the ₹75,000 above the limit, so no relief.
            "1350000,74100",
            // Each slab boundary above the rebate: 15%, 20%, 25% and 30% bands, plus 4% cess.
            "1675000,124800", "2075000,208000", "2475000,312000", "3075000,499200"})
        void newRegimeFy202526(long incomeRupees, long taxRupees) {
            TaxWorking w = PayrollMath.annualTax(incomeRupees * 100, FY_2025_26);
            assertThat(w.total()).isEqualTo(taxRupees * 100);
        }

        @Test
        void showsHowTheTaxWasWorkedOut() {
            TaxWorking rebated = PayrollMath.annualTax(12_75_000_00L, FY_2025_26);
            assertThat(rebated.taxable()).isEqualTo(12_00_000_00L);
            assertThat(rebated.slabTax()).isEqualTo(60_000_00);
            assertThat(rebated.rebate()).isEqualTo(60_000_00);
            TaxWorking relieved = PayrollMath.annualTax(12_85_000_00L, FY_2025_26);
            assertThat(relieved.taxable()).isEqualTo(12_10_000_00L);
            assertThat(relieved.slabTax()).isEqualTo(61_500_00);
            assertThat(relieved.marginalRelief()).isEqualTo(51_500_00);
            assertThat(relieved.cess()).isEqualTo(400_00);
            assertThat(PayrollMath.annualTax(-5, FY_2025_26).total()).isZero();
        }

        @Test
        void spreadsTheRemainingTaxOverTheMonthsLeft() {
            assertThat(PayrollMath.monthlyTds(1_24_800_00, 12)).isEqualTo(10_400_00);
            // ₹10,000 over 7 months is ₹1,428.57, rounded to ₹1,429.
            assertThat(PayrollMath.monthlyTds(10_000_00, 7)).isEqualTo(1_429_00);
            assertThat(PayrollMath.monthlyTds(-100, 3)).isZero();
            assertThat(PayrollMath.monthlyTds(100_00, 0)).isZero();
        }

        @Test
        void namesTheFinancialYear() {
            assertThat(PayrollMath.financialYear(YearMonth.of(2026, 3))).isEqualTo("2025-26");
            assertThat(PayrollMath.financialYear(YearMonth.of(2026, 4))).isEqualTo("2026-27");
            assertThat(PayrollMath.financialYear(YearMonth.of(2099, 12))).isEqualTo("2099-00");
            assertThat(PayrollMath.financialYearStart(YearMonth.of(2027, 2))).isEqualTo(YearMonth.of(2026, 4));
        }
    }

    static long paise(String rupees) {
        return new BigDecimal(rupees).movePointRight(2).longValueExact();
    }
}
