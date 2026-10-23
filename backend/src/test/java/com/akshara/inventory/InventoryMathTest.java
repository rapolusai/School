package com.akshara.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.akshara.accounts.Approvals;
import com.akshara.accounts.Approvals.Outcome;

/** Stock sums, GST, straight-line depreciation and the approval limits, as pure arithmetic. */
class InventoryMathTest {

    private static StockMovement movement(MovementKind kind, int direction, int quantity) {
        return new StockMovement(UUID.randomUUID(), null, kind, direction, quantity, null, LocalDate.of(2026, 4, 1),
                null, null, null, null, null, null, null, null, null, "Keeper");
    }

    @Nested
    class Stock {

        @Test
        void sumsReceiptsLessIssuesAndWriteOffsPlusReturnsAndAdjustments() {
            assertThat(InventoryMath.stock(List.of(movement(MovementKind.RECEIPT, 1, 50),
                    movement(MovementKind.ISSUE, -1, 12), movement(MovementKind.RETURN, 1, 2),
                    movement(MovementKind.WRITE_OFF, -1, 5), movement(MovementKind.ADJUSTMENT, -1, 3),
                    movement(MovementKind.ADJUSTMENT, 1, 1)))).isEqualTo(33);
            assertThat(InventoryMath.stock(List.of())).isZero();
        }

        @Test
        void directionFollowsTheKind() {
            assertThat(InventoryMath.direction(MovementKind.RECEIPT, false)).isEqualTo(1);
            assertThat(InventoryMath.direction(MovementKind.RETURN, false)).isEqualTo(1);
            assertThat(InventoryMath.direction(MovementKind.ISSUE, true)).isEqualTo(-1);
            assertThat(InventoryMath.direction(MovementKind.WRITE_OFF, true)).isEqualTo(-1);
            assertThat(InventoryMath.direction(MovementKind.ADJUSTMENT, true)).isEqualTo(1);
            assertThat(InventoryMath.direction(MovementKind.ADJUSTMENT, false)).isEqualTo(-1);
        }
    }

    @Nested
    class Gst {

        @Test
        void roundsHalfUpToThePaisa() {
            assertThat(InventoryMath.gst(100_000, 1800)).isEqualTo(18_000);
            assertThat(InventoryMath.gst(1_234_567, 1200)).isEqualTo(148_148);
            assertThat(InventoryMath.gst(5, 1800)).isEqualTo(1);
            assertThat(InventoryMath.gst(2, 1800)).isZero();
            assertThat(InventoryMath.gst(100_000, 0)).isZero();
        }
    }

    @Nested
    class Depreciation {

        final LocalDate bought = LocalDate.of(2024, 4, 1);

        @Test
        void isStraightLinePerDayHeld() {
            // ₹42,000 at 15% a year: ₹6,300 a year, exactly one year later.
            assertThat(InventoryMath.depreciation(4_200_000, 1500, bought, LocalDate.of(2025, 4, 1)))
                    .isEqualTo(630_000);
            // Half a year (182 days of 365).
            assertThat(InventoryMath.depreciation(4_200_000, 1500, bought, bought.plusDays(182)))
                    .isEqualTo(630_000L * 182 / 365);
            assertThat(InventoryMath.writtenDown(4_200_000, 1500, bought, LocalDate.of(2025, 4, 1)))
                    .isEqualTo(3_570_000);
        }

        @Test
        void neverExceedsTheCostAndIsZeroBeforePurchaseOrAtNoRate() {
            assertThat(InventoryMath.depreciation(100_000, 5000, bought, bought.plusYears(10))).isEqualTo(100_000);
            assertThat(InventoryMath.writtenDown(100_000, 5000, bought, bought.plusYears(10))).isZero();
            assertThat(InventoryMath.depreciation(100_000, 1500, bought, bought)).isZero();
            assertThat(InventoryMath.depreciation(100_000, 1500, bought, bought.minusDays(1))).isZero();
            assertThat(InventoryMath.depreciation(100_000, 0, bought, bought.plusYears(1))).isZero();
            assertThat(InventoryMath.depreciation(100_000, 1500, bought, null)).isZero();
        }
    }

    @Nested
    class ApprovalLimits {

        @Test
        void approvesWithinTheLimitAndPassesOnAbove() {
            assertThat(Approvals.decide(50_000_00L, null, 42_000_00, null)).isEqualTo(Outcome.APPROVE);
            assertThat(Approvals.decide(50_000_00L, null, 50_000_00, null)).isEqualTo(Outcome.APPROVE);
            assertThat(Approvals.decide(50_000_00L, null, 50_000_01, null)).isEqualTo(Outcome.ESCALATE);
            assertThat(Approvals.decide(null, null, 9_99_99_999L, null)).isEqualTo(Outcome.APPROVE);
        }

        @Test
        void onlySomeoneAboveActsOnceEscalated() {
            assertThat(Approvals.decide(50_000_00L, 50_000_00L, 60_000_00, null)).isEqualTo(Outcome.NOT_YOURS);
            assertThat(Approvals.decide(40_000_00L, 50_000_00L, 60_000_00, null)).isEqualTo(Outcome.NOT_YOURS);
            assertThat(Approvals.decide(null, 50_000_00L, 60_000_00, 0L)).isEqualTo(Outcome.APPROVE);
            assertThat(Approvals.decide(50_000_00L, null, 60_000_00, 0L)).isEqualTo(Outcome.NOBODY_ABOVE);
            assertThat(Approvals.decide(50_000_00L, null, 60_000_00, 30_000_00L)).isEqualTo(Outcome.NOBODY_ABOVE);
        }
    }
}
