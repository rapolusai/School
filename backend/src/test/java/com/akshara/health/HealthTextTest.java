package com.akshara.health;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.akshara.health.HealthText.Due;

class HealthTextTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 22);

    @Test
    void masksPolicyAndReferenceNumbersToTheLastFour() {
        assertThat(HealthText.maskNumber("POL-2026-778812")).isEqualTo("••••8812");
        assertThat(HealthText.maskNumber("  PV/HYD/2025/4100 ")).isEqualTo("••••4100");
        assertThat(HealthText.maskNumber("1234")).isEqualTo("••••");
        assertThat(HealthText.maskNumber("12")).isEqualTo("••••");
        assertThat(HealthText.maskNumber("")).isNull();
        assertThat(HealthText.maskNumber(null)).isNull();
    }

    @Test
    void masksTheMiddleOfAPhoneNumber() {
        assertThat(HealthText.maskPhone("9876543201")).isEqualTo("98765•••01");
        assertThat(HealthText.maskPhone("12345")).isEqualTo("•••");
        assertThat(HealthText.maskPhone(null)).isNull();
    }

    @Test
    void worksInIndiaTime() {
        assertThat(HealthText.dayOf(Instant.parse("2026-10-11T20:30:00Z"))).isEqualTo(LocalDate.of(2026, 10, 12));
        assertThat(HealthText.startOfDay(LocalDate.of(2026, 10, 12))).isEqualTo(Instant.parse("2026-10-11T18:30:00Z"));
        assertThat(HealthText.time(Instant.parse("2026-10-11T09:00:00Z"))).isEqualTo("11 Oct 2026, 2:30 pm");
        assertThat(HealthText.clean("  x ")).isEqualTo("x");
        assertThat(HealthText.clean("   ")).isNull();
    }

    @Test
    void datedRequirementsAreOkDueSoonOverdueOrMissing() {
        assertThat(HealthText.dueState(null, TODAY)).isEqualTo(Due.MISSING);
        assertThat(HealthText.dueState(TODAY.minusDays(1), TODAY)).isEqualTo(Due.OVERDUE);
        assertThat(HealthText.dueState(TODAY, TODAY)).isEqualTo(Due.DUE_SOON);
        assertThat(HealthText.dueState(TODAY.plusDays(30), TODAY)).isEqualTo(Due.DUE_SOON);
        assertThat(HealthText.dueState(TODAY.plusDays(31), TODAY)).isEqualTo(Due.OK);
    }

    @Test
    void verificationsAreDueUnlessClearedAndValid() {
        assertThat(HealthText.verificationState(null, null, TODAY)).isEqualTo(Due.MISSING);
        assertThat(HealthText.verificationState(VerificationStatus.PENDING, null, TODAY)).isEqualTo(Due.OVERDUE);
        assertThat(HealthText.verificationState(VerificationStatus.REQUESTED, TODAY.plusYears(1), TODAY))
                .isEqualTo(Due.OVERDUE);
        assertThat(HealthText.verificationState(VerificationStatus.FLAGGED, null, TODAY)).isEqualTo(Due.OVERDUE);
        assertThat(HealthText.verificationState(VerificationStatus.CLEARED, null, TODAY)).isEqualTo(Due.OK);
        assertThat(HealthText.verificationState(VerificationStatus.CLEARED, TODAY.plusYears(1), TODAY))
                .isEqualTo(Due.OK);
        assertThat(HealthText.verificationState(VerificationStatus.CLEARED, TODAY.plusDays(10), TODAY))
                .isEqualTo(Due.DUE_SOON);
        assertThat(HealthText.verificationState(VerificationStatus.CLEARED, TODAY.minusDays(1), TODAY))
                .isEqualTo(Due.OVERDUE);
    }

    @Test
    void trainingsNeedRenewalWhenExpiringWithinAMonth() {
        assertThat(HealthText.trainingNeedsRenewal(null, TODAY)).isFalse();
        assertThat(HealthText.trainingNeedsRenewal(TODAY.plusDays(60), TODAY)).isFalse();
        assertThat(HealthText.trainingNeedsRenewal(TODAY.plusDays(25), TODAY)).isTrue();
        assertThat(HealthText.trainingNeedsRenewal(TODAY.minusDays(5), TODAY)).isTrue();
    }
}
