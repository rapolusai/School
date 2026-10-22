package com.akshara.health;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * The points of a student's growth chart: height and weight against age in months, computed once here so the web
 * app only draws the SVG line. Measurements without a value for a series are left out of that series.
 */
public final class GrowthChart {

    /** One measurement as a chart point: the day, the age in whole months, and the value (null when not taken). */
    public record Point(LocalDate date, long ageMonths, BigDecimal heightCm, BigDecimal weightKg) {
    }

    /** The chart's points in date order, with the span of each series (null when the series is empty). */
    public record Series(List<Point> points, BigDecimal minHeight, BigDecimal maxHeight, BigDecimal minWeight,
            BigDecimal maxWeight) {
    }

    private GrowthChart() {
    }

    /** Builds the chart from measurements (any order) for a student born on {@code dateOfBirth} (may be null). */
    public static Series of(LocalDate dateOfBirth, List<Point> measurements) {
        List<Point> points = measurements.stream()
                .sorted((a, b) -> a.date().compareTo(b.date()))
                .map(p -> new Point(p.date(), ageMonths(dateOfBirth, p.date()), p.heightCm(), p.weightKg()))
                .toList();
        return new Series(points, min(points, true), max(points, true), min(points, false), max(points, false));
    }

    /** Whole months between birth and the day, never negative; 0 when the birth date is unknown. */
    public static long ageMonths(LocalDate dateOfBirth, LocalDate on) {
        if (dateOfBirth == null || on.isBefore(dateOfBirth)) {
            return 0;
        }
        return ChronoUnit.MONTHS.between(dateOfBirth, on);
    }

    private static BigDecimal min(List<Point> points, boolean height) {
        return points.stream().map(p -> height ? p.heightCm() : p.weightKg()).filter(v -> v != null)
                .min(BigDecimal::compareTo).orElse(null);
    }

    private static BigDecimal max(List<Point> points, boolean height) {
        return points.stream().map(p -> height ? p.heightCm() : p.weightKg()).filter(v -> v != null)
                .max(BigDecimal::compareTo).orElse(null);
    }
}
