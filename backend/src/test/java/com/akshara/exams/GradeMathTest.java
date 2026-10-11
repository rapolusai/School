package com.akshara.exams;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Percentages, grades, the pass rule, weightage and ranks, at their boundaries, in exact decimals. */
class GradeMathTest {

    /** The built-in CBSE classes 1 to 8 scale: A1 91-100 down to E, 32 and below; pass 33. */
    static final GradeMath.Scale CBSE = new GradeMath.Scale(List.of(
            band(91, "A1", 10), band(81, "A2", 9), band(71, "B1", 8), band(61, "B2", 7), band(51, "C1", 6),
            band(41, "C2", 5), band(33, "D", 4), new GradeMath.Band(BigDecimal.ZERO, "E", null, null)),
            new BigDecimal("33"), new BigDecimal("33"));

    static final GradeMath.Max FORTY = new GradeMath.Max(new BigDecimal("40"), null, null);
    static final GradeMath.Max HUNDRED_SPLIT = new GradeMath.Max(new BigDecimal("70"), new BigDecimal("20"),
            new BigDecimal("10"));

    @Test
    void percentagesRoundOnceToTwoDecimalsHalfUp() {
        assertThat(GradeMath.percent(new BigDecimal("13"), new BigDecimal("40"))).isEqualByComparingTo("32.50");
        assertThat(GradeMath.percent(new BigDecimal("1"), new BigDecimal("3"))).isEqualByComparingTo("33.33");
        assertThat(GradeMath.percent(new BigDecimal("2"), new BigDecimal("3"))).isEqualByComparingTo("66.67");
        // 0.005 rounds up.
        assertThat(GradeMath.percent(new BigDecimal("1"), new BigDecimal("8000"))).isEqualByComparingTo("0.01");
        assertThat(GradeMath.percent(new BigDecimal("40"), new BigDecimal("40")).scale()).isEqualTo(2);
        assertThat(GradeMath.percent(BigDecimal.ONE, BigDecimal.ZERO)).isNull();
        assertThat(GradeMath.percent(null, BigDecimal.TEN)).isNull();
    }

    @Test
    void gradesFollowTheBandBoundaries() {
        assertThat(CBSE.bandFor(new BigDecimal("100")).grade()).isEqualTo("A1");
        assertThat(CBSE.bandFor(new BigDecimal("91")).grade()).isEqualTo("A1");
        assertThat(CBSE.bandFor(new BigDecimal("90.99")).grade()).isEqualTo("A2");
        assertThat(CBSE.bandFor(new BigDecimal("81")).grade()).isEqualTo("A2");
        assertThat(CBSE.bandFor(new BigDecimal("33")).grade()).isEqualTo("D");
        assertThat(CBSE.bandFor(new BigDecimal("32.99")).grade()).isEqualTo("E");
        assertThat(CBSE.bandFor(BigDecimal.ZERO).grade()).isEqualTo("E");
        assertThat(CBSE.bandFor(null)).isNull();
        // Bands are kept highest first whatever order they come in.
        GradeMath.Scale shuffled = new GradeMath.Scale(List.of(band(0, "C", null), band(67, "A", null),
                band(34, "B", null)), new BigDecimal("33"), new BigDecimal("33"));
        assertThat(shuffled.bandFor(new BigDecimal("66.99")).grade()).isEqualTo("B");
        assertThat(shuffled.bandFor(new BigDecimal("67")).grade()).isEqualTo("A");
        assertThat(new GradeMath.Scale(List.of(), new BigDecimal("33"), new BigDecimal("33"))
                .bandFor(BigDecimal.TEN)).isNull();
    }

    @Test
    void aSubjectIsPassedAtExactlyThePassMark() {
        // 13.2 of 40 is exactly 33%.
        GradeMath.SubjectScore pass = GradeMath.subject(marks("13.2"), FORTY, CBSE);
        assertThat(pass.percent()).isEqualByComparingTo("33.00");
        assertThat(pass.passed()).isTrue();
        assertThat(pass.band().grade()).isEqualTo("D");
        GradeMath.SubjectScore fail = GradeMath.subject(marks("13"), FORTY, CBSE);
        assertThat(fail.percent()).isEqualByComparingTo("32.50");
        assertThat(fail.passed()).isFalse();
        assertThat(fail.band().grade()).isEqualTo("E");
        // Rounding decides: 32.995% rounds to 33.00 and passes.
        GradeMath.Max big = new GradeMath.Max(new BigDecimal("200"), null, null);
        assertThat(GradeMath.subject(marks("65.99"), big, CBSE).passed()).isTrue();
        assertThat(GradeMath.subject(marks("65.98"), big, CBSE).passed()).isFalse();
    }

    @Test
    void componentsAddUpAndAMissingComponentLeavesTheSubjectMissing() {
        GradeMath.SubjectScore s = GradeMath.subject(new GradeMath.Entry(new BigDecimal("60"), new BigDecimal("18"),
                new BigDecimal("9.5"), null), HUNDRED_SPLIT, CBSE);
        assertThat(s.obtained()).isEqualByComparingTo("87.5");
        assertThat(s.max()).isEqualByComparingTo("100");
        assertThat(s.percent()).isEqualByComparingTo("87.50");
        assertThat(s.band().grade()).isEqualTo("A2");
        GradeMath.SubjectScore partial = GradeMath.subject(new GradeMath.Entry(new BigDecimal("60"), null,
                new BigDecimal("9"), null), HUNDRED_SPLIT, CBSE);
        assertThat(partial.missing()).isTrue();
        assertThat(partial.passed()).isNull();
        assertThat(GradeMath.subject(null, FORTY, CBSE).missing()).isTrue();
        // A component with maximum 0 is not part of the paper.
        assertThat(GradeMath.subject(new GradeMath.Entry(new BigDecimal("30"), new BigDecimal("5"), null, null),
                FORTY, CBSE).obtained()).isEqualByComparingTo("30");
    }

    @Test
    void absentCountsAsZeroWhileMedicalAndExemptAreLeftOut() {
        GradeMath.SubjectScore absent = GradeMath.subject(code(MarkCode.AB), FORTY, CBSE);
        assertThat(absent.obtained()).isEqualByComparingTo("0");
        assertThat(absent.max()).isEqualByComparingTo("40");
        assertThat(absent.passed()).isFalse();
        assertThat(absent.counted()).isTrue();
        GradeMath.SubjectScore medical = GradeMath.subject(code(MarkCode.ML), FORTY, CBSE);
        assertThat(medical.obtained()).isNull();
        assertThat(medical.passed()).isNull();
        assertThat(medical.counted()).isFalse();
        assertThat(GradeMath.subject(code(MarkCode.EX), FORTY, CBSE).counted()).isFalse();

        // English 37/40, Maths ML, Hindi AB: totals over English and Hindi only.
        GradeMath.Overall o = GradeMath.overall(List.of(GradeMath.subject(marks("37"), FORTY, CBSE), medical,
                absent), CBSE);
        assertThat(o.obtained()).isEqualByComparingTo("37");
        assertThat(o.max()).isEqualByComparingTo("80");
        assertThat(o.percent()).isEqualByComparingTo("46.25");
        assertThat(o.result()).isEqualTo(OverallResult.NEEDS_IMPROVEMENT);
        // GPA over the counted subjects: A1 (10) and E (no point, 0).
        assertThat(o.gpa()).isEqualByComparingTo("5.0");
    }

    @Test
    void theOverallResultNeedsEverySubjectAndTheTotalAtThePassMark() {
        GradeMath.SubjectScore english = GradeMath.subject(marks("30"), FORTY, CBSE);
        GradeMath.SubjectScore maths = GradeMath.subject(marks("14"), FORTY, CBSE);
        GradeMath.Overall pass = GradeMath.overall(List.of(english, maths), CBSE);
        assertThat(pass.percent()).isEqualByComparingTo("55.00");
        assertThat(pass.result()).isEqualTo(OverallResult.PASS);
        assertThat(pass.band().grade()).isEqualTo("C1");
        // B1 (8) and D (4).
        assertThat(pass.gpa()).isEqualByComparingTo("6.0");

        // One subject below the pass mark: needs improvement even though the total is well above it.
        GradeMath.Overall oneFail = GradeMath.overall(List.of(GradeMath.subject(marks("40"), FORTY, CBSE),
                GradeMath.subject(marks("13"), FORTY, CBSE)), CBSE);
        assertThat(oneFail.percent()).isEqualByComparingTo("66.25");
        assertThat(oneFail.result()).isEqualTo(OverallResult.NEEDS_IMPROVEMENT);

        // Every subject passed but the overall pass mark is higher.
        GradeMath.Scale strict = new GradeMath.Scale(CBSE.bands(), new BigDecimal("33"), new BigDecimal("40"));
        GradeMath.Overall below = GradeMath.overall(List.of(GradeMath.subject(marks("14"), FORTY, strict),
                GradeMath.subject(marks("15"), FORTY, strict)), strict);
        assertThat(below.percent()).isEqualByComparingTo("36.25");
        assertThat(below.result()).isEqualTo(OverallResult.NEEDS_IMPROVEMENT);

        // Missing marks or nothing counted: incomplete.
        assertThat(GradeMath.overall(List.of(english, GradeMath.subject(null, FORTY, CBSE)), CBSE).result())
                .isEqualTo(OverallResult.INCOMPLETE);
        GradeMath.Overall exempt = GradeMath.overall(List.of(GradeMath.subject(code(MarkCode.EX), FORTY, CBSE)),
                CBSE);
        assertThat(exempt.result()).isEqualTo(OverallResult.INCOMPLETE);
        assertThat(exempt.percent()).isNull();
        assertThat(exempt.obtained()).isNull();
    }

    @Test
    void schemesWithoutGradePointsHaveNoGpa() {
        GradeMath.Scale icse = new GradeMath.Scale(List.of(band(90, "A+", null), band(33, "E", null),
                band(0, "F", null)), new BigDecimal("33"), new BigDecimal("33"));
        assertThat(icse.usesGradePoints()).isFalse();
        assertThat(GradeMath.overall(List.of(GradeMath.subject(marks("38"), FORTY, icse)), icse).gpa()).isNull();
        assertThat(CBSE.usesGradePoints()).isTrue();
    }

    @Test
    void weightageReweightsWhatIsThereAndFallsBackToAPlainAverage() {
        // Periodic test 20%, half-yearly 80%.
        assertThat(GradeMath.weighted(List.of(weighted("80", "20"), weighted("60", "80"))))
                .isEqualByComparingTo("64.00");
        // The half-yearly is not held yet: the test alone.
        assertThat(GradeMath.weighted(List.of(weighted("80", "20"), new GradeMath.Weighted(null,
                new BigDecimal("80"))))).isEqualByComparingTo("80.00");
        // Weights not set yet (all 0): a plain average.
        assertThat(GradeMath.weighted(List.of(weighted("70", "0"), weighted("75", "0"))))
                .isEqualByComparingTo("72.50");
        assertThat(GradeMath.weighted(List.of(weighted("70", null), weighted("71", null))))
                .isEqualByComparingTo("70.50");
        // Thirds round once.
        assertThat(GradeMath.weighted(List.of(weighted("100", "1"), weighted("0", "2"))))
                .isEqualByComparingTo("33.33");
        assertThat(GradeMath.weighted(List.of(new GradeMath.Weighted(null, BigDecimal.TEN)))).isNull();
        assertThat(GradeMath.weighted(List.of())).isNull();
    }

    @Test
    void weightsMustAddUpToExactlyAHundred() {
        assertThat(GradeMath.totals100(List.of(new BigDecimal("20"), new BigDecimal("80")))).isTrue();
        assertThat(GradeMath.totals100(List.of(new BigDecimal("33.33"), new BigDecimal("33.33"),
                new BigDecimal("33.34")))).isTrue();
        assertThat(GradeMath.totals100(List.of(new BigDecimal("33.33"), new BigDecimal("33.33"),
                new BigDecimal("33.33")))).isFalse();
        assertThat(GradeMath.totals100(List.of(new BigDecimal("100.00")))).isTrue();
        assertThat(GradeMath.totals100(List.of(new BigDecimal("60"), new BigDecimal("50")))).isFalse();
        assertThat(GradeMath.totals100(List.of())).isFalse();
    }

    @Test
    void termAndYearResultsUseThePassMarks() {
        assertThat(GradeMath.result(List.of(new BigDecimal("33"), new BigDecimal("90")), new BigDecimal("61.5"),
                CBSE)).isEqualTo(OverallResult.PASS);
        assertThat(GradeMath.result(List.of(new BigDecimal("32.99"), new BigDecimal("90")), new BigDecimal("61.5"),
                CBSE)).isEqualTo(OverallResult.NEEDS_IMPROVEMENT);
        assertThat(GradeMath.result(java.util.Arrays.asList(null, new BigDecimal("50")), new BigDecimal("50"),
                CBSE)).isEqualTo(OverallResult.PASS);
        assertThat(GradeMath.result(List.of(), null, CBSE)).isEqualTo(OverallResult.INCOMPLETE);
    }

    @Test
    void ranksShareTiesAndSkip() {
        Map<String, BigDecimal> percents = new LinkedHashMap<>();
        percents.put("asha", new BigDecimal("91.5"));
        percents.put("bala", new BigDecimal("88"));
        percents.put("chitra", new BigDecimal("88.00"));
        percents.put("dev", new BigDecimal("70"));
        percents.put("esha", null);
        Map<String, Integer> ranks = GradeMath.ranks(percents);
        assertThat(ranks).containsEntry("asha", 1).containsEntry("bala", 2).containsEntry("chitra", 2)
                .containsEntry("dev", 4).doesNotContainKey("esha");
    }

    @Test
    void classLevelsAreReadFromCommonClassNames() {
        assertThat(ClassLevel.of("Class 5")).isEqualTo(5);
        assertThat(ClassLevel.of("Grade 10")).isEqualTo(10);
        assertThat(ClassLevel.of("12")).isEqualTo(12);
        assertThat(ClassLevel.of("Class V")).isEqualTo(5);
        assertThat(ClassLevel.of("Std IX")).isEqualTo(9);
        assertThat(ClassLevel.of("Class I")).isEqualTo(1);
        assertThat(ClassLevel.of("LKG")).isZero();
        assertThat(ClassLevel.of("UKG")).isZero();
        assertThat(ClassLevel.of("Nursery")).isZero();
        assertThat(ClassLevel.of("Pre-KG")).isZero();
        assertThat(ClassLevel.of("Class 13")).isNull();
        assertThat(ClassLevel.of("Science club")).isNull();
        assertThat(ClassLevel.of(null)).isNull();
    }

    private static GradeMath.Band band(int min, String grade, Integer point) {
        return new GradeMath.Band(BigDecimal.valueOf(min), grade, point == null ? null : BigDecimal.valueOf(point),
                null);
    }

    private static GradeMath.Entry marks(String theory) {
        return new GradeMath.Entry(new BigDecimal(theory), null, null, null);
    }

    private static GradeMath.Entry code(MarkCode code) {
        return new GradeMath.Entry(null, null, null, code);
    }

    private static GradeMath.Weighted weighted(String percent, String weight) {
        return new GradeMath.Weighted(new BigDecimal(percent), weight == null ? null : new BigDecimal(weight));
    }
}
