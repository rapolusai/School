package com.akshara.exams;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The arithmetic of results: percentages, grades, the pass rule, weightage and ranks. Percentages are worked out
 * exactly and rounded once to two decimals (half up); grades and pass/fail are decided on that rounded figure, which
 * is also the figure printed. No floating point anywhere.
 */
final class GradeMath {

    static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    static final int SCALE = 2;

    private GradeMath() {
    }

    /** A grade band: a percentage at or above {@code minPercent} (and below the next band up) gets the grade. */
    record Band(BigDecimal minPercent, String grade, BigDecimal gradePoint, String remark) {
    }

    /** A scheme's bands and pass marks. Bands are kept highest first. */
    record Scale(List<Band> bands, BigDecimal passPercent, BigDecimal overallPassPercent) {

        Scale {
            bands = bands.stream().sorted(Comparator.comparing(Band::minPercent).reversed()).toList();
        }

        /** The band for a percentage; the lowest band when the percentage is below every band; null without bands. */
        Band bandFor(BigDecimal percent) {
            if (percent == null || bands.isEmpty()) {
                return null;
            }
            for (Band b : bands) {
                if (percent.compareTo(b.minPercent()) >= 0) {
                    return b;
                }
            }
            return bands.getLast();
        }

        boolean usesGradePoints() {
            return bands.stream().anyMatch(b -> b.gradePoint() != null);
        }

        boolean passes(BigDecimal percent) {
            return percent != null && percent.compareTo(passPercent) >= 0;
        }

        boolean passesOverall(BigDecimal percent) {
            return percent != null && percent.compareTo(overallPassPercent) >= 0;
        }
    }

    /** The maximum marks of a paper by component. A component with maximum 0 is not part of the paper. */
    record Max(BigDecimal theory, BigDecimal practical, BigDecimal internal) {

        Max {
            theory = zeroIfNull(theory);
            practical = zeroIfNull(practical);
            internal = zeroIfNull(internal);
        }

        BigDecimal total() {
            return theory.add(practical).add(internal);
        }
    }

    /** What was entered for one student: marks per component, or a code instead. */
    record Entry(BigDecimal theory, BigDecimal practical, BigDecimal internal, MarkCode code) {
    }

    /**
     * One student's result in one subject. {@code obtained}, {@code max} and {@code percent} are null when the subject
     * is left out (ML, EX) or marks are missing; {@code passed} is null when there is nothing to judge.
     */
    record SubjectScore(BigDecimal obtained, BigDecimal max, BigDecimal percent, Band band, Boolean passed,
            MarkCode code, boolean missing) {

        /** Part of the totals: marks entered, or absent (which counts as zero). */
        boolean counted() {
            return !missing && (code == null || !code.excluded());
        }
    }

    /** Totals over the subjects, the overall grade, grade point average and result. */
    record Overall(BigDecimal obtained, BigDecimal max, BigDecimal percent, Band band, BigDecimal gpa,
            OverallResult result) {
    }

    /** A percentage and its weight, for term and final results. */
    record Weighted(BigDecimal percent, BigDecimal weight) {
    }

    /** Obtained as a percentage of the maximum, two decimals, half up; null when the maximum is zero or missing. */
    static BigDecimal percent(BigDecimal obtained, BigDecimal max) {
        if (obtained == null || max == null || max.signum() <= 0) {
            return null;
        }
        return obtained.multiply(HUNDRED).divide(max, SCALE, RoundingMode.HALF_UP);
    }

    static SubjectScore subject(Entry entry, Max max, Scale scale) {
        if (entry == null) {
            return missing();
        }
        if (entry.code() == MarkCode.AB) {
            BigDecimal pct = percent(BigDecimal.ZERO, max.total());
            return new SubjectScore(BigDecimal.ZERO, max.total(), pct, scale.bandFor(pct), false, MarkCode.AB, false);
        }
        if (entry.code() != null) {
            return new SubjectScore(null, null, null, null, null, entry.code(), false);
        }
        BigDecimal obtained = BigDecimal.ZERO;
        BigDecimal[][] parts = {{entry.theory(), max.theory()}, {entry.practical(), max.practical()},
            {entry.internal(), max.internal()}};
        for (BigDecimal[] part : parts) {
            if (part[1].signum() > 0) {
                if (part[0] == null) {
                    return missing();
                }
                obtained = obtained.add(part[0]);
            }
        }
        BigDecimal pct = percent(obtained, max.total());
        return new SubjectScore(obtained, max.total(), pct, scale.bandFor(pct), scale.passes(pct), null, false);
    }

    private static SubjectScore missing() {
        return new SubjectScore(null, null, null, null, null, null, true);
    }

    /**
     * Totals over the subjects. INCOMPLETE while any subject's marks are missing or when every subject is left out;
     * otherwise PASS when every counted subject is passed and the overall percentage reaches the overall pass mark,
     * else NEEDS_IMPROVEMENT. The grade point average is over the counted subjects (a band without a grade point
     * counts as 0), and only for schemes with grade points.
     */
    static Overall overall(List<SubjectScore> subjects, Scale scale) {
        boolean anyMissing = subjects.stream().anyMatch(SubjectScore::missing);
        List<SubjectScore> counted = subjects.stream().filter(SubjectScore::counted).toList();
        BigDecimal obtained = counted.stream().map(SubjectScore::obtained).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal max = counted.stream().map(SubjectScore::max).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal pct = percent(obtained, max);
        BigDecimal gpa = null;
        if (scale.usesGradePoints() && !counted.isEmpty()) {
            BigDecimal points = counted.stream()
                    .map(s -> s.band() == null || s.band().gradePoint() == null ? BigDecimal.ZERO
                            : s.band().gradePoint())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            gpa = points.divide(BigDecimal.valueOf(counted.size()), 1, RoundingMode.HALF_UP);
        }
        OverallResult result;
        if (anyMissing || counted.isEmpty()) {
            result = OverallResult.INCOMPLETE;
        } else {
            boolean allPassed = counted.stream().allMatch(s -> Boolean.TRUE.equals(s.passed()));
            result = allPassed && scale.passesOverall(pct) ? OverallResult.PASS : OverallResult.NEEDS_IMPROVEMENT;
        }
        return new Overall(counted.isEmpty() ? null : obtained, counted.isEmpty() ? null : max, pct,
                scale.bandFor(pct), gpa, result);
    }

    /**
     * The weighted average of the percentages that are there, two decimals, half up. Items without a percentage (an
     * exam not yet held or published, a subject left out) are dropped and the others re-weighted, so a term with only
     * its first test so far shows that test's percentage. When the remaining weights are all zero, a plain average.
     * Null when nothing is there.
     */
    static BigDecimal weighted(List<Weighted> items) {
        List<Weighted> present = items.stream().filter(w -> w.percent() != null).toList();
        if (present.isEmpty()) {
            return null;
        }
        BigDecimal totalWeight = present.stream().map(w -> zeroIfNull(w.weight())).reduce(BigDecimal.ZERO,
                BigDecimal::add);
        if (totalWeight.signum() == 0) {
            BigDecimal sum = present.stream().map(Weighted::percent).reduce(BigDecimal.ZERO, BigDecimal::add);
            return sum.divide(BigDecimal.valueOf(present.size()), SCALE, RoundingMode.HALF_UP);
        }
        BigDecimal sum = present.stream().map(w -> w.percent().multiply(zeroIfNull(w.weight())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(totalWeight, SCALE, RoundingMode.HALF_UP);
    }

    /**
     * The result of a term or the year from subject percentages and the overall percentage: INCOMPLETE when there is
     * nothing yet, PASS when every subject and the overall percentage reach the pass marks.
     */
    static OverallResult result(Collection<BigDecimal> subjectPercents, BigDecimal overall, Scale scale) {
        if (overall == null) {
            return OverallResult.INCOMPLETE;
        }
        boolean allPassed = subjectPercents.stream().filter(Objects::nonNull).allMatch(scale::passes);
        return allPassed && scale.passesOverall(overall) ? OverallResult.PASS : OverallResult.NEEDS_IMPROVEMENT;
    }

    /** True when the weights add up to exactly 100. */
    static boolean totals100(Collection<BigDecimal> weights) {
        return weights.stream().map(GradeMath::zeroIfNull).reduce(BigDecimal.ZERO, BigDecimal::add)
                .compareTo(HUNDRED) == 0;
    }

    /**
     * Standard competition ranking by percentage, highest first: equal percentages share a rank and the next rank
     * skips ("1, 2, 2, 4"). Keys without a percentage get no rank.
     */
    static <K> Map<K, Integer> ranks(Map<K, BigDecimal> percents) {
        List<Map.Entry<K, BigDecimal>> ordered = percents.entrySet().stream().filter(e -> e.getValue() != null)
                .sorted(Map.Entry.<K, BigDecimal>comparingByValue().reversed()).toList();
        Map<K, Integer> ranks = new HashMap<>();
        BigDecimal previous = null;
        int rank = 0;
        for (int i = 0; i < ordered.size(); i++) {
            BigDecimal value = ordered.get(i).getValue();
            if (previous == null || value.compareTo(previous) != 0) {
                rank = i + 1;
                previous = value;
            }
            ranks.put(ordered.get(i).getKey(), rank);
        }
        return ranks;
    }

    static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
