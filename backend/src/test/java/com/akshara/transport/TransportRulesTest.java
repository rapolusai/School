package com.akshara.transport;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import com.akshara.transport.TransportRules.Period;

/** Document warnings, capacity, which allocation covers a day, and the bus fee per instalment. */
class TransportRulesTest {

    static final LocalDate TODAY = LocalDate.of(2026, 10, 11);

    @Test
    void documentsWarnThirtyDaysAhead() {
        assertThat(TransportRules.documentState(null, TODAY)).isEqualTo(DocumentState.MISSING);
        assertThat(TransportRules.documentState(TODAY.minusDays(1), TODAY)).isEqualTo(DocumentState.EXPIRED);
        // The expiry day itself still counts as valid, with a warning.
        assertThat(TransportRules.documentState(TODAY, TODAY)).isEqualTo(DocumentState.EXPIRING);
        assertThat(TransportRules.documentState(TODAY.plusDays(30), TODAY)).isEqualTo(DocumentState.EXPIRING);
        assertThat(TransportRules.documentState(TODAY.plusDays(31), TODAY)).isEqualTo(DocumentState.VALID);
        assertThat(TransportRules.daysLeft(TODAY.plusDays(12), TODAY)).isEqualTo(12);
        assertThat(TransportRules.daysLeft(TODAY.minusDays(3), TODAY)).isEqualTo(-3);
        assertThat(TransportRules.daysLeft(null, TODAY)).isNull();
        assertThat(TransportRules.invalid(DocumentState.EXPIRED)).isTrue();
        assertThat(TransportRules.invalid(DocumentState.MISSING)).isTrue();
        assertThat(TransportRules.invalid(DocumentState.EXPIRING)).isFalse();
        assertThat(TransportRules.invalid(DocumentState.VALID)).isFalse();
    }

    @Test
    void capacityCountsTheSeats() {
        assertThat(TransportRules.overCapacity(39, 1, 40)).isFalse();
        assertThat(TransportRules.overCapacity(40, 1, 40)).isTrue();
        assertThat(TransportRules.overCapacity(30, 11, 40)).isTrue();
        // A route without a vehicle has no limit.
        assertThat(TransportRules.overCapacity(500, 1, null)).isFalse();
    }

    @Test
    void periodsCoverTheirFirstAndLastDays() {
        Period open = new Period(TODAY, null);
        Period closed = new Period(TODAY, TODAY.plusDays(9));
        assertThat(open.covers(TODAY)).isTrue();
        assertThat(open.covers(TODAY.minusDays(1))).isFalse();
        assertThat(open.covers(TODAY.plusYears(5))).isTrue();
        assertThat(closed.covers(TODAY.plusDays(9))).isTrue();
        assertThat(closed.covers(TODAY.plusDays(10))).isFalse();
        assertThat(closed.overlaps(new Period(TODAY.plusDays(9), null))).isTrue();
        assertThat(closed.overlaps(new Period(TODAY.plusDays(10), null))).isFalse();
        assertThat(open.overlaps(new Period(TODAY.minusDays(20), TODAY.minusDays(1)))).isFalse();
        assertThat(open.overlaps(new Period(TODAY.minusDays(20), null))).isTrue();
    }

    @Test
    void theAllocationThatStartedLastCoversTheDay() {
        Period old = new Period(TODAY.minusDays(60), TODAY);
        Period changed = new Period(TODAY, null);
        Period future = new Period(TODAY.plusDays(30), null);
        List<Period> list = List.of(old, changed, future);
        assertThat(TransportRules.covering(list, p -> p, TODAY.minusDays(1))).contains(old);
        // Both cover the day the student changed route: the new one wins.
        assertThat(TransportRules.covering(list, p -> p, TODAY)).contains(changed);
        assertThat(TransportRules.covering(list, p -> p, TODAY.plusDays(40))).contains(future);
        assertThat(TransportRules.covering(list, p -> p, TODAY.minusDays(61))).isEmpty();
    }

    @Test
    void theAnnualFeeIsSplitEvenlyWithTheLastInstalmentTakingTheRounding() {
        // ₹16,000 over four quarters.
        assertThat(IntStream.range(0, 4).mapToLong(i -> TransportRules.instalmentCharge(16_000_00, i, 4)))
                .containsExactly(4_000_00L, 4_000_00L, 4_000_00L, 4_000_00L);
        // ₹10,000 over three: nothing is lost to rounding.
        long[] thirds = IntStream.range(0, 3).mapToLong(i -> TransportRules.instalmentCharge(10_000_00, i, 3))
                .toArray();
        assertThat(thirds[0] + thirds[1] + thirds[2]).isEqualTo(10_000_00);
        assertThat(thirds[0]).isEqualTo(thirds[1]);
        assertThat(TransportRules.instalmentCharge(0, 0, 4)).isZero();
        assertThat(TransportRules.instalmentCharge(12_000_00, 0, 0)).isZero();
    }
}
