package com.akshara.certificates;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class SerialsTest {

    @Test
    void serialNumbersCarryThePrefixTheYearAndAFourDigitRunningNumber() {
        assertThat(Serials.serialNo("TC", "2026-27", 1)).isEqualTo("TC/2026-27/0001");
        assertThat(Serials.serialNo("BC", "2026-27", 123)).isEqualTo("BC/2026-27/0123");
        assertThat(Serials.serialNo("ID", "2026-27", 12345)).isEqualTo("ID/2026-27/12345");
        assertThatThrownBy(() -> Serials.serialNo("TC", "2026-27", 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void academicYearRunsFromAprilToMarch() {
        assertThat(Serials.academicYear(LocalDate.of(2026, 10, 11))).isEqualTo("2026-27");
        assertThat(Serials.academicYear(LocalDate.of(2027, 3, 31))).isEqualTo("2026-27");
        assertThat(Serials.academicYear(LocalDate.of(2027, 4, 1))).isEqualTo("2027-28");
        assertThat(Serials.academicYear(LocalDate.of(2099, 4, 1))).isEqualTo("2099-00");
    }

    @Test
    void yearKeyUsesTheSchoolsYearNameWhenItLooksLikeOne() {
        LocalDate day = LocalDate.of(2026, 10, 11);
        assertThat(Serials.yearKey("2025-26", day)).isEqualTo("2025-26");
        assertThat(Serials.yearKey(" 2025-2026 ", day)).isEqualTo("2025-26");
        assertThat(Serials.yearKey("Session 2026", day)).isEqualTo("2026-27");
        assertThat(Serials.yearKey(null, day)).isEqualTo("2026-27");
    }

    @Test
    void tokensAreRandomLettersAndDigitsOfTheRightLength() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String token = Serials.newToken();
            assertThat(token).hasSize(24).matches("^[A-Za-z0-9]{24}$");
            assertThat(Serials.looksLikeToken(token)).isTrue();
            seen.add(token);
        }
        assertThat(seen).hasSize(200);
        assertThat(Serials.looksLikeToken("short")).isFalse();
        assertThat(Serials.looksLikeToken("abc-def-ghi-jkl-mno-pqr-")).isFalse();
        assertThat(Serials.looksLikeToken(null)).isFalse();
    }

    @Test
    void masksAllButTheLastFourDigitsOfAPhone() {
        assertThat(Serials.maskPhone("9876543210")).isEqualTo("XXXXXX3210");
        assertThat(Serials.maskPhone("+91 98765 43210")).isEqualTo("XXXXXXXX3210");
        assertThat(Serials.maskPhone("123")).isEqualTo("XXX");
        assertThat(Serials.maskPhone(" ")).isNull();
        assertThat(Serials.maskPhone(null)).isNull();
    }

    @Test
    void firstNameIsTheFirstWord() {
        assertThat(Serials.firstName("Asha Rao Menon")).isEqualTo("Asha");
        assertThat(Serials.firstName("  Asha ")).isEqualTo("Asha");
        assertThat(Serials.firstName(null)).isNull();
    }
}
