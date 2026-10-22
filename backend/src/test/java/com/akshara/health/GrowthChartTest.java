package com.akshara.health;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.akshara.health.GrowthChart.Point;
import com.akshara.health.GrowthChart.Series;

class GrowthChartTest {

    private static final LocalDate BORN = LocalDate.of(2016, 4, 12);

    @Test
    void sortsPointsByDateAndComputesAgeInMonthsAndTheSpans() {
        Series s = GrowthChart.of(BORN, List.of(
                new Point(LocalDate.of(2026, 10, 1), 0, new BigDecimal("131.0"), new BigDecimal("28.5")),
                new Point(LocalDate.of(2026, 4, 12), 0, new BigDecimal("128.0"), null),
                new Point(LocalDate.of(2025, 10, 1), 0, null, new BigDecimal("25.0"))));
        assertThat(s.points()).extracting(Point::date).containsExactly(LocalDate.of(2025, 10, 1),
                LocalDate.of(2026, 4, 12), LocalDate.of(2026, 10, 1));
        assertThat(s.points()).extracting(Point::ageMonths).containsExactly(113L, 120L, 125L);
        assertThat(s.minHeight()).isEqualByComparingTo("128.0");
        assertThat(s.maxHeight()).isEqualByComparingTo("131.0");
        assertThat(s.minWeight()).isEqualByComparingTo("25.0");
        assertThat(s.maxWeight()).isEqualByComparingTo("28.5");
    }

    @Test
    void anEmptyOrUndatedChartHasNoSpans() {
        Series empty = GrowthChart.of(BORN, List.of());
        assertThat(empty.points()).isEmpty();
        assertThat(empty.minHeight()).isNull();
        assertThat(empty.maxWeight()).isNull();
        Series unknownBirth = GrowthChart.of(null, List.of(new Point(LocalDate.of(2026, 1, 1), 0,
                new BigDecimal("100.0"), null)));
        assertThat(unknownBirth.points().getFirst().ageMonths()).isZero();
        assertThat(GrowthChart.ageMonths(BORN, BORN.minusDays(1))).isZero();
    }
}
