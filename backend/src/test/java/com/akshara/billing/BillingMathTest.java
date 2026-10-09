package com.akshara.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.akshara.platform.Plan;
import com.akshara.platform.TenantStatus;

/** GST, invoice numbers, GSTINs, plan prices and the admins' banner: pure rules, no database. */
class BillingMathTest {

    @Test
    void sameStateIsCgstPlusSgstAndAnotherStateIsIgst() {
        GstMath.Tax within = GstMath.tax(10_000_00, "36", "36");
        assertThat(within.split()).isEqualTo(GstMath.Split.CGST_SGST);
        assertThat(within.cgstPaise()).isEqualTo(900_00);
        assertThat(within.sgstPaise()).isEqualTo(900_00);
        assertThat(within.igstPaise()).isZero();
        assertThat(within.totalPaise()).isEqualTo(11_800_00);

        GstMath.Tax across = GstMath.tax(10_000_00, "36", "27");
        assertThat(across.split()).isEqualTo(GstMath.Split.IGST);
        assertThat(across.cgstPaise()).isZero();
        assertThat(across.sgstPaise()).isZero();
        assertThat(across.igstPaise()).isEqualTo(1_800_00);
        assertThat(across.totalPaise()).isEqualTo(11_800_00);
    }

    @Test
    void eachTaxIsRoundedHalfUpToWholePaiseSoCgstAndSgstStayEqual() {
        // 9% of 1,00,005 paise is 9,000.45 paise; 18% is 18,000.9 paise.
        GstMath.Tax within = GstMath.tax(1_00_005, "36", "36");
        assertThat(within.cgstPaise()).isEqualTo(9_000).isEqualTo(within.sgstPaise());
        assertThat(within.totalPaise()).isEqualTo(1_00_005 + 18_000);
        assertThat(GstMath.tax(1_00_005, "36", "29").igstPaise()).isEqualTo(18_001);
        // 9% of 50 paise is 4.5 paise: half up.
        assertThat(GstMath.tax(50, "36", "36").cgstPaise()).isEqualTo(5);
        assertThatThrownBy(() -> GstMath.tax(-1, "36", "36")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void financialYearsRunAprilToMarchAndNumbersFitSixteenCharacters() {
        assertThat(GstMath.financialYear(LocalDate.of(2026, 10, 9))).isEqualTo("2026-27");
        assertThat(GstMath.financialYear(LocalDate.of(2027, 3, 31))).isEqualTo("2026-27");
        assertThat(GstMath.financialYear(LocalDate.of(2027, 4, 1))).isEqualTo("2027-28");
        assertThat(GstMath.financialYear(LocalDate.of(2099, 4, 1))).isEqualTo("2099-00");
        String number = GstMath.invoiceNo("AKS", "2026-27", 123);
        assertThat(number).isEqualTo("AKS/26-27/000123").hasSizeLessThanOrEqualTo(16);
        assertThatThrownBy(() -> GstMath.invoiceNo("AKS", "2026-27", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GstMath.invoiceNo("AKS", "2026-27", 1_000_000))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void gstinsNeedTheirShapeAndCheckCharacter() {
        String first14 = "27ABCDE1234F1Z";
        String valid = first14 + Gstin.checkCharacter(first14);
        assertThat(Gstin.isValid(valid)).isTrue();
        char wrong = valid.charAt(14) == 'A' ? 'B' : 'A';
        assertThat(Gstin.isValid(first14 + wrong)).isFalse();
        assertThat(Gstin.isValid(valid.toLowerCase())).isFalse();
        assertThat(Gstin.isValid("27ABCDE1234F1Z")).isFalse();
        assertThat(Gstin.isValid(null)).isFalse();
        // The seller's placeholder has the shape but can never be valid.
        assertThat(Gstin.isValid(BillingProperties.PLACEHOLDER_GSTIN)).isFalse();
        assertThat(Gstin.isStateCode("36")).isTrue();
        assertThat(Gstin.isStateCode("25")).isFalse();
        assertThat(Gstin.isStateCode("99")).isFalse();
    }

    @Test
    void pricesArePerStudentPerYearAndAMonthIsATenth() {
        PlanCatalog.PlanInfo growth = PlanCatalog.of(Plan.GROWTH);
        assertThat(growth.pricePerStudentPerYearPaise()).isEqualTo(200_00);
        assertThat(growth.unitPricePaise(BillingCycle.YEARLY)).isEqualTo(200_00);
        assertThat(growth.unitPricePaise(BillingCycle.MONTHLY)).isEqualTo(20_00);
        assertThat(PlanCatalog.of(Plan.STARTER).unitPricePaise(BillingCycle.MONTHLY)).isEqualTo(8_30);
        assertThat(PlanCatalog.of(Plan.STARTER).covers(300)).isTrue();
        assertThat(PlanCatalog.of(Plan.STARTER).covers(301)).isFalse();
        assertThat(PlanCatalog.of(Plan.ENTERPRISE).covers(100_000)).isTrue();
        assertThat(PlanCatalog.all()).extracting(PlanCatalog.PlanInfo::plan)
                .containsExactly(Plan.STARTER, Plan.GROWTH, Plan.ENTERPRISE);
        assertThat(BillingCycle.YEARLY.periodEnd(LocalDate.of(2026, 4, 1))).isEqualTo(LocalDate.of(2027, 3, 31));
        assertThat(BillingCycle.MONTHLY.periodEnd(LocalDate.of(2026, 1, 31))).isEqualTo(LocalDate.of(2026, 2, 27));
    }

    @Test
    void rupeesUseIndianGrouping() {
        assertThat(BillingService.rupees(0)).isEqualTo("₹0");
        assertThat(BillingService.rupees(1_000_00)).isEqualTo("₹1,000");
        assertThat(BillingService.rupees(1_84_300_00)).isEqualTo("₹1,84,300");
        assertThat(BillingService.rupees(12_34_567_50)).isEqualTo("₹12,34,567.50");
        assertThat(BillingService.rupees(1_23_45_678_05)).isEqualTo("₹1,23,45,678.05");
    }

    @Test
    void theBannerPutsOverduePaymentBeforeTheTrialAndNeverShowsForSuspendedSchools() {
        Instant now = Instant.parse("2026-10-09T06:30:00Z");
        LocalDate today = LocalDate.of(2026, 10, 9);
        Instant inThreeDays = Instant.parse("2026-10-12T06:30:00Z");
        Instant inTwentyDays = Instant.parse("2026-10-29T06:30:00Z");

        assertThat(BillingMapper.notice(TenantStatus.TRIAL, inThreeDays, List.of(), today, now, 7).kind())
                .isEqualTo("TRIAL_ENDING");
        assertThat(BillingMapper.notice(TenantStatus.TRIAL, inThreeDays, List.of(), today, now, 7).trialDaysLeft())
                .isEqualTo(3);
        assertThat(BillingMapper.notice(TenantStatus.TRIAL, inTwentyDays, List.of(), today, now, 7).kind()).isNull();
        assertThat(BillingMapper.notice(TenantStatus.TRIAL, now.minusSeconds(60), List.of(), today, now, 7).kind())
                .isEqualTo("TRIAL_ENDED");
        assertThat(BillingMapper.notice(TenantStatus.PAST_DUE, null, List.of(), today, now, 7).kind())
                .isEqualTo("PAYMENT_OVERDUE");
        assertThat(BillingMapper.notice(TenantStatus.ACTIVE, null, List.of(), today, now, 7).kind()).isNull();
        assertThat(BillingMapper.notice(TenantStatus.SUSPENDED, inThreeDays, List.of(), today, now, 7).kind())
                .isNull();
        assertThat(BillingMapper.trialDaysLeft(TenantStatus.ACTIVE, inThreeDays, now)).isNull();
        assertThat(BillingMapper.trialDaysLeft(TenantStatus.TRIAL, now.minusSeconds(1), now)).isZero();
    }
}
