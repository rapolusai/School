package com.akshara.hostel;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

import org.junit.jupiter.api.Test;

/** The hostel's pure rules: pass dates, return times, lateness, nights covered, overlaps and occupancy. */
class HostelRulesTest {

    static final LocalDate TODAY = LocalDate.of(2026, 10, 22);

    @Test
    void anOutingIsOneDayFromTodayWithinSixtyDays() {
        assertThat(HostelRules.passDatesProblem(PassKind.OUTING, TODAY, null, TODAY)).isNull();
        assertThat(HostelRules.passDatesProblem(PassKind.OUTING, TODAY, TODAY, TODAY)).isNull();
        assertThat(HostelRules.passDatesProblem(PassKind.OUTING, TODAY.plusDays(60), null, TODAY)).isNull();
        assertThat(HostelRules.passDatesProblem(PassKind.OUTING, TODAY.minusDays(1), null, TODAY))
                .isEqualTo("fromDate");
        assertThat(HostelRules.passDatesProblem(PassKind.OUTING, TODAY.plusDays(61), null, TODAY))
                .isEqualTo("fromDate");
        assertThat(HostelRules.passDatesProblem(PassKind.OUTING, null, null, TODAY)).isEqualTo("fromDate");
        assertThat(HostelRules.passDatesProblem(PassKind.OUTING, TODAY, TODAY.plusDays(1), TODAY))
                .isEqualTo("toDate");
    }

    @Test
    void homeLeaveNeedsAnEndWithinThirtyDays() {
        assertThat(HostelRules.passDatesProblem(PassKind.HOME_LEAVE, TODAY, TODAY, TODAY)).isNull();
        assertThat(HostelRules.passDatesProblem(PassKind.HOME_LEAVE, TODAY, TODAY.plusDays(30), TODAY)).isNull();
        assertThat(HostelRules.passDatesProblem(PassKind.HOME_LEAVE, TODAY, null, TODAY)).isEqualTo("toDate");
        assertThat(HostelRules.passDatesProblem(PassKind.HOME_LEAVE, TODAY.plusDays(2), TODAY.plusDays(1), TODAY))
                .isEqualTo("toDate");
        assertThat(HostelRules.passDatesProblem(PassKind.HOME_LEAVE, TODAY, TODAY.plusDays(31), TODAY))
                .isEqualTo("toDate");
    }

    @Test
    void returnTimesAreSixPmForOutingsAndEightPmForLeave() {
        Instant outing = HostelRules.returnBy(PassKind.OUTING, TODAY, TODAY);
        assertThat(outing).isEqualTo(TODAY.atTime(LocalTime.of(18, 0)).atZone(HostelText.INDIA).toInstant());
        Instant leave = HostelRules.returnBy(PassKind.HOME_LEAVE, TODAY, TODAY.plusDays(3));
        assertThat(leave).isEqualTo(TODAY.plusDays(3).atTime(LocalTime.of(20, 0)).atZone(HostelText.INDIA)
                .toInstant());
        assertThat(HostelRules.isLate(outing, outing.minusSeconds(1))).isFalse();
        assertThat(HostelRules.isLate(outing, outing)).isFalse();
        assertThat(HostelRules.isLate(outing, outing.plusSeconds(1))).isTrue();
    }

    @Test
    void nightsCoveredAndOverlaps() {
        assertThat(HostelRules.coversNight(TODAY, TODAY.plusDays(2), TODAY.plusDays(2))).isTrue();
        assertThat(HostelRules.coversNight(TODAY, TODAY.plusDays(2), TODAY.plusDays(3))).isFalse();
        assertThat(HostelRules.coversNight(TODAY, TODAY.plusDays(2), TODAY.minusDays(1))).isFalse();
        assertThat(HostelRules.overlaps(TODAY, TODAY, TODAY, TODAY)).isTrue();
        assertThat(HostelRules.overlaps(TODAY, TODAY.plusDays(2), TODAY.plusDays(2), TODAY.plusDays(5))).isTrue();
        assertThat(HostelRules.overlaps(TODAY, TODAY.plusDays(2), TODAY.plusDays(3), TODAY.plusDays(5))).isFalse();
        assertThat(HostelRules.overlaps(TODAY.plusDays(3), TODAY.plusDays(5), TODAY, TODAY.plusDays(2))).isFalse();
    }

    @Test
    void occupancyIsAWholePercentageAndAllotmentDatesAreBounded() {
        assertThat(HostelRules.occupancyPercent(0, 0)).isZero();
        assertThat(HostelRules.occupancyPercent(1, 3)).isEqualTo(33);
        assertThat(HostelRules.occupancyPercent(2, 3)).isEqualTo(67);
        assertThat(HostelRules.occupancyPercent(3, 3)).isEqualTo(100);
        assertThat(HostelRules.allotmentDateOk(TODAY, TODAY)).isTrue();
        assertThat(HostelRules.allotmentDateOk(TODAY.minusDays(365), TODAY)).isTrue();
        assertThat(HostelRules.allotmentDateOk(TODAY.minusDays(366), TODAY)).isFalse();
        assertThat(HostelRules.allotmentDateOk(TODAY.plusDays(120), TODAY)).isTrue();
        assertThat(HostelRules.allotmentDateOk(TODAY.plusDays(121), TODAY)).isFalse();
        assertThat(HostelRules.allotmentDateOk(null, TODAY)).isFalse();
    }

    @Test
    void phoneNumbersAreMasked() {
        assertThat(HostelText.maskPhone("9876543201")).isEqualTo("98765•••01");
        assertThat(HostelText.maskPhone("+91 98765 43201")).isEqualTo("91987•••01");
        assertThat(HostelText.maskPhone("12345")).isEqualTo("•••");
        assertThat(HostelText.maskPhone(null)).isEmpty();
    }
}
