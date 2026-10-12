package com.akshara.frontoffice;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class FrontOfficeTextTest {

    @Test
    void masksTheMiddleOfAPhoneNumber() {
        assertThat(FrontOfficeText.maskPhone("9876543201")).isEqualTo("98765•••01");
        assertThat(FrontOfficeText.maskPhone("+91 98765 43201")).isEqualTo("91987•••01");
        assertThat(FrontOfficeText.maskPhone("12345")).isEqualTo("•••");
        assertThat(FrontOfficeText.maskPhone("")).isEmpty();
        assertThat(FrontOfficeText.maskPhone("   ")).isEmpty();
        assertThat(FrontOfficeText.maskPhone(null)).isEmpty();
    }

    @Test
    void worksInIndiaTime() {
        Instant lateEvening = Instant.parse("2026-10-11T20:30:00Z");
        assertThat(FrontOfficeText.dayOf(lateEvening)).isEqualTo(LocalDate.of(2026, 10, 12));
        assertThat(FrontOfficeText.startOfDay(LocalDate.of(2026, 10, 12)))
                .isEqualTo(Instant.parse("2026-10-11T18:30:00Z"));
        assertThat(FrontOfficeText.time(Instant.parse("2026-10-11T09:00:00Z"))).isEqualTo("11 Oct 2026, 2:30 pm");
    }

    @Test
    void cleansAndComparesNames() {
        assertThat(FrontOfficeText.clean("  Ravi  ")).isEqualTo("Ravi");
        assertThat(FrontOfficeText.clean("   ")).isNull();
        assertThat(FrontOfficeText.clean(null)).isNull();
        assertThat(FrontOfficeText.sameName("Dr. Meera Joshi", "dr meera joshi")).isTrue();
        assertThat(FrontOfficeText.sameName("Meera Joshi", "Meera Joshi Rao")).isFalse();
        assertThat(FrontOfficeText.sameName("", "")).isFalse();
        assertThat(FrontOfficeText.sameName(null, "x")).isFalse();
    }
}
