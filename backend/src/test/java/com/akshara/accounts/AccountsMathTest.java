package com.akshara.accounts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.akshara.accounts.Approvals.Outcome;
import com.akshara.accounts.StatementMatcher.Candidate;
import com.akshara.accounts.StatementMatcher.Line;
import com.akshara.accounts.StatementMatcher.Match;
import com.akshara.accounts.StatementMatcher.Quality;
import com.akshara.shared.ApiException;

/** The pure rules of the accounts module: GST and TDS maths, budgets, the financial year, approval limits and
 * statement matching. Amounts are in paise. */
class AccountsMathTest {

    @Nested
    class GstAndTds {

        @Test
        void gstIsRoundedToTheNearestPaisa() {
            assertThat(AccountsMath.gst(1_000_00, new BigDecimal("18"))).isEqualTo(180_00);
            assertThat(AccountsMath.gst(333_33, new BigDecimal("18"))).isEqualTo(60_00); // 5999.94 → 6000
            assertThat(AccountsMath.gst(1_00, new BigDecimal("2.5"))).isEqualTo(3); // 2.5 paise rounds up
            assertThat(AccountsMath.gst(1_000_00, BigDecimal.ZERO)).isZero();
            assertThat(AccountsMath.gst(1_000_00, null)).isZero();
        }

        @ParameterizedTest
        @CsvSource({"194C, 1, 10000000, 100000", "194C, 2, 10000000, 200000", "194J, 10, 5000000, 500000",
                "194J, 10, 1, 0"})
        void tdsIsOnTheAmountBeforeGst(String section, String rate, long amount, long expected) {
            assertThat(AccountsMath.tds(amount, new BigDecimal(rate))).as(section).isEqualTo(expected);
        }

        @Test
        void noSectionMeansNoTds() {
            assertThat(AccountsMath.tds(10_000_00, null)).isZero();
            assertThat(AccountsMath.tds(0, new BigDecimal("2"))).isZero();
        }
    }

    @Nested
    class Budgets {

        @Test
        void statusWarnsAtNinetyPercentAndFlagsOver() {
            assertThat(AccountsMath.budgetStatus(0, 0)).isEqualTo("NONE");
            assertThat(AccountsMath.budgetStatus(5_000_00, 10_000_00)).isEqualTo("OK");
            assertThat(AccountsMath.budgetStatus(8_949_00, 10_000_00)).isEqualTo("OK"); // 89.49% rounds to 89
            assertThat(AccountsMath.budgetStatus(8_950_00, 10_000_00)).isEqualTo("NEAR"); // 89.5% rounds to 90
            assertThat(AccountsMath.budgetStatus(10_000_00, 10_000_00)).isEqualTo("NEAR");
            assertThat(AccountsMath.budgetStatus(10_000_01, 10_000_00)).isEqualTo("OVER");
        }

        @Test
        void percentUsedIsCappedAndZeroWithoutABudget() {
            assertThat(AccountsMath.percentUsed(1, 0)).isZero();
            assertThat(AccountsMath.percentUsed(2_500_00, 10_000_00)).isEqualTo(25);
            assertThat(AccountsMath.percentUsed(10_000_000_00L, 1_00)).isEqualTo(999);
        }

        @Test
        void evenSpreadGivesTheOddPaiseToTheLastMonths() {
            List<Long> months = AccountsMath.spreadEvenly(100);
            assertThat(months).hasSize(12).containsExactly(8L, 8L, 8L, 8L, 8L, 8L, 8L, 8L, 9L, 9L, 9L, 9L);
            assertThat(months.stream().mapToLong(Long::longValue).sum()).isEqualTo(100);
            assertThat(AccountsMath.spreadEvenly(0)).containsOnly(0L);
            assertThat(AccountsMath.spreadEvenly(12_000_00)).containsOnly(1_000_00L);
        }
    }

    @Nested
    class IndianFinancialYear {

        @Test
        void runsFromAprilToMarch() {
            FinancialYear fy = FinancialYear.of(LocalDate.of(2026, 10, 11));
            assertThat(fy.label()).isEqualTo("2026-27");
            assertThat(fy.start()).isEqualTo(LocalDate.of(2026, 4, 1));
            assertThat(fy.end()).isEqualTo(LocalDate.of(2027, 3, 31));
            assertThat(FinancialYear.of(LocalDate.of(2027, 3, 31)).label()).isEqualTo("2026-27");
            assertThat(FinancialYear.of(LocalDate.of(2027, 4, 1)).label()).isEqualTo("2027-28");
            assertThat(FinancialYear.of(YearMonth.of(2026, 1)).label()).isEqualTo("2025-26");
            assertThat(FinancialYear.of(LocalDate.of(2099, 4, 1)).label()).isEqualTo("2099-00");
        }

        @Test
        void monthsAreNumberedFromApril() {
            FinancialYear fy = FinancialYear.parse("2026-27", "financialYear");
            assertThat(fy.monthIndex(LocalDate.of(2026, 4, 1))).isEqualTo(1);
            assertThat(fy.monthIndex(LocalDate.of(2026, 12, 31))).isEqualTo(9);
            assertThat(fy.monthIndex(LocalDate.of(2027, 3, 15))).isEqualTo(12);
            assertThat(fy.monthIndex(LocalDate.of(2026, 3, 31))).isZero();
            assertThat(fy.months()).hasSize(12).first().isEqualTo(YearMonth.of(2026, 4));
            assertThat(fy.months().getLast()).isEqualTo(YearMonth.of(2027, 3));
            assertThat(fy.previous().label()).isEqualTo("2025-26");
        }

        @Test
        void parsingChecksTheShape() {
            assertThat(FinancialYear.parse(" 2026-27 ", "fy").label()).isEqualTo("2026-27");
            assertThat(FinancialYear.parseOrCurrent(null, "fy")).isEqualTo(FinancialYear.current());
            assertThat(FinancialYear.parseOrCurrent(" ", "fy")).isEqualTo(FinancialYear.current());
            for (String bad : List.of("2026", "2026-28", "26-27", "1999-00", "2026-2027")) {
                assertThatThrownBy(() -> FinancialYear.parse(bad, "fy")).as(bad).isInstanceOf(ApiException.class)
                        .hasMessageContaining("YYYY-YY");
            }
        }
    }

    @Nested
    class ApprovalLimits {

        @Test
        void withinTheLimitApprovesAndAboveItPassesOn() {
            assertThat(Approvals.decide(50_000_00L, null, 50_000_00, 200_000_00L)).isEqualTo(Outcome.APPROVE);
            assertThat(Approvals.decide(50_000_00L, null, 50_000_01, 200_000_00L)).isEqualTo(Outcome.ESCALATE);
            assertThat(Approvals.decide(50_000_00L, null, 50_000_01, null)).isEqualTo(Outcome.ESCALATE);
        }

        @Test
        void noLimitApprovesAnything() {
            assertThat(Approvals.decide(null, null, 99_999_999_00L, 0L)).isEqualTo(Outcome.APPROVE);
            assertThat(Approvals.decide(null, 50_000_00L, 99_999_999_00L, 0L)).isEqualTo(Outcome.APPROVE);
        }

        @Test
        void oncePassedOnOnlyALargerLimitMayAct() {
            assertThat(Approvals.decide(50_000_00L, 50_000_00L, 60_000_00, null)).isEqualTo(Outcome.NOT_YOURS);
            assertThat(Approvals.decide(40_000_00L, 50_000_00L, 60_000_00, null)).isEqualTo(Outcome.NOT_YOURS);
            assertThat(Approvals.decide(100_000_00L, 50_000_00L, 60_000_00, 50_000_00L)).isEqualTo(Outcome.APPROVE);
            assertThat(Approvals.mayAct(50_000_00L, 50_000_00L)).isFalse();
            assertThat(Approvals.mayAct(50_000_01L, 50_000_00L)).isTrue();
            assertThat(Approvals.mayAct(null, 50_000_00L)).isTrue();
        }

        @Test
        void nobodyAboveIsReported() {
            assertThat(Approvals.decide(50_000_00L, null, 60_000_00, 0L)).isEqualTo(Outcome.NOBODY_ABOVE);
            assertThat(Approvals.decide(50_000_00L, null, 60_000_00, 50_000_00L)).isEqualTo(Outcome.NOBODY_ABOVE);
        }
    }

    @Nested
    class StatementMatching {

        final Candidate rent = new Candidate(UUID.randomUUID(), true, 25_000_00, "UTR111", "Rent");
        final Candidate power = new Candidate(UUID.randomUUID(), true, 25_000_00, "UTR222", "Power");
        final Candidate water = new Candidate(UUID.randomUUID(), true, 1_200_00, null, "Water");
        final Candidate donation = new Candidate(UUID.randomUUID(), false, 5_000_00, "DON9", "Donation");

        @Test
        void amountAndReferenceMatchAmountAloneIsProbable() {
            List<Match> matches = StatementMatcher.match(List.of(
                    new Line(0, 25_000_00, 0, "NEFT utr111 Sharma", null),
                    new Line(1, 1_200_00, 0, "Water board", null),
                    new Line(2, 0, 5_000_00, "IMPS DON9", "DON9"),
                    new Line(3, 25_000_00, 0, "Unknown transfer", null)),
                    List.of(rent, power, water, donation));
            assertThat(matches).extracting(Match::quality)
                    .containsExactly(Quality.MATCHED, Quality.PROBABLE, Quality.MATCHED, Quality.PROBABLE);
            assertThat(matches.get(0).candidate()).isEqualTo(rent);
            assertThat(matches.get(1).candidate()).isEqualTo(water);
            assertThat(matches.get(2).candidate()).isEqualTo(donation);
            // Rent is used, so the only remaining 25,000 debit is power.
            assertThat(matches.get(3).candidate()).isEqualTo(power);
        }

        @Test
        void ambiguousAmountsAndWrongSidesStayUnmatched() {
            List<Match> matches = StatementMatcher.match(List.of(
                    new Line(0, 25_000_00, 0, "Transfer", null),
                    new Line(1, 0, 1_200_00, "Water refund", null),
                    new Line(2, 999_00, 0, "Nothing like it", null)),
                    List.of(rent, power, water));
            assertThat(matches).extracting(Match::quality).containsOnly(Quality.NONE);
            assertThat(matches).extracting(Match::candidate).containsOnlyNulls();
        }
    }
}
