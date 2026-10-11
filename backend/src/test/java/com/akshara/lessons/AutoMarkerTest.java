package com.akshara.lessons;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Auto-marking of every question type: full, partial and no marks, never negative, two decimals. */
class AutoMarkerTest {

    static final UUID A = UUID.randomUUID();
    static final UUID B = UUID.randomUUID();
    static final UUID C = UUID.randomUUID();
    static final UUID D = UUID.randomUUID();
    static final BigDecimal TWO = new BigDecimal("2");
    static final BigDecimal THREE = new BigDecimal("3");

    static BigDecimal mark(QuestionType type, BigDecimal marks, List<UUID> correct, String answer, List<UUID> picked,
            String response) {
        return AutoMarker.mark(type, marks, correct, answer, picked, response);
    }

    @Nested
    class SingleAnswerChoice {

        @Test
        void theCorrectOptionScoresFullMarks() {
            BigDecimal marks = mark(QuestionType.MCQ_SINGLE, TWO, List.of(A), null, List.of(A), null);
            assertThat(marks).isEqualByComparingTo("2.00");
            assertThat(marks.scale()).isEqualTo(2);
        }

        @Test
        void aWrongOptionOrNoneScoresZero() {
            assertThat(mark(QuestionType.MCQ_SINGLE, TWO, List.of(A), null, List.of(B), null))
                    .isEqualByComparingTo("0");
            assertThat(mark(QuestionType.MCQ_SINGLE, TWO, List.of(A), null, List.of(), null))
                    .isEqualByComparingTo("0");
        }

        @Test
        void pickingTheCorrectOptionAndAnotherScoresZero() {
            assertThat(mark(QuestionType.MCQ_SINGLE, TWO, List.of(A), null, List.of(A, B), null))
                    .isEqualByComparingTo("0");
        }
    }

    @Nested
    class SeveralAnswerChoice {

        @Test
        void everyCorrectOptionAndNoOtherScoresFullMarks() {
            assertThat(mark(QuestionType.MCQ_MULTI, THREE, List.of(A, B, C), null, List.of(C, B, A), null))
                    .isEqualByComparingTo("3.00");
        }

        @Test
        void someCorrectOptionsScorePartMarks() {
            // Two of three right: 3 x 2 / 3.
            assertThat(mark(QuestionType.MCQ_MULTI, THREE, List.of(A, B, C), null, List.of(A, B), null))
                    .isEqualByComparingTo("2.00");
            // One of three right: 2 x 1 / 3 = 0.666... rounds half up to 0.67.
            assertThat(mark(QuestionType.MCQ_MULTI, TWO, List.of(A, B, C), null, List.of(A), null))
                    .isEqualByComparingTo("0.67");
        }

        @Test
        void aWrongPickCancelsARightOne() {
            // Two right, one wrong, of two correct: 2 x (2 - 1) / 2.
            assertThat(mark(QuestionType.MCQ_MULTI, TWO, List.of(A, B), null, List.of(A, B, C), null))
                    .isEqualByComparingTo("1.00");
        }

        @Test
        void marksNeverGoBelowZero() {
            assertThat(mark(QuestionType.MCQ_MULTI, TWO, List.of(A, B), null, List.of(A, C, D), null))
                    .isEqualByComparingTo("0").isNotNegative();
            assertThat(mark(QuestionType.MCQ_MULTI, TWO, List.of(A), null, List.of(B, C, D), null))
                    .isEqualByComparingTo("0").isNotNegative();
        }

        @Test
        void pickingEveryOptionScoresZeroUnlessAllAreCorrect() {
            assertThat(mark(QuestionType.MCQ_MULTI, TWO, List.of(A, B), null, List.of(A, B, C, D), null))
                    .isEqualByComparingTo("0");
            assertThat(mark(QuestionType.MCQ_MULTI, TWO, List.of(A, B, C, D), null, List.of(A, B, C, D), null))
                    .isEqualByComparingTo("2");
        }

        @Test
        void nothingPickedScoresZero() {
            assertThat(mark(QuestionType.MCQ_MULTI, TWO, List.of(A, B), null, List.of(), null))
                    .isEqualByComparingTo("0");
        }
    }

    @Nested
    class TrueOrFalse {

        @Test
        void theMatchingAnswerScoresFullMarks() {
            assertThat(mark(QuestionType.TRUE_FALSE, BigDecimal.ONE, List.of(), "TRUE", List.of(), "TRUE"))
                    .isEqualByComparingTo("1");
            assertThat(mark(QuestionType.TRUE_FALSE, BigDecimal.ONE, List.of(), "FALSE", List.of(), " false "))
                    .isEqualByComparingTo("1");
            assertThat(mark(QuestionType.TRUE_FALSE, BigDecimal.ONE, List.of(), "TRUE", List.of(), "yes"))
                    .isEqualByComparingTo("1");
        }

        @Test
        void theOtherAnswerOrNoneScoresZero() {
            assertThat(mark(QuestionType.TRUE_FALSE, BigDecimal.ONE, List.of(), "TRUE", List.of(), "FALSE"))
                    .isEqualByComparingTo("0");
            assertThat(mark(QuestionType.TRUE_FALSE, BigDecimal.ONE, List.of(), "TRUE", List.of(), null))
                    .isEqualByComparingTo("0");
            assertThat(mark(QuestionType.TRUE_FALSE, BigDecimal.ONE, List.of(), "TRUE", List.of(), "maybe"))
                    .isEqualByComparingTo("0");
        }

        @Test
        void readsTheCommonSpellings() {
            assertThat(AutoMarker.trueFalse("t")).isEqualTo("TRUE");
            assertThat(AutoMarker.trueFalse("No")).isEqualTo("FALSE");
            assertThat(AutoMarker.trueFalse("")).isNull();
            assertThat(AutoMarker.trueFalse(null)).isNull();
        }
    }

    @Nested
    class FillInTheBlank {

        @Test
        void anyAcceptedAnswerScoresIgnoringCaseAndSpaces() {
            String accepted = "New Delhi\nDelhi";
            assertThat(mark(QuestionType.FILL_BLANK, TWO, List.of(), accepted, List.of(), "  new   delhi "))
                    .isEqualByComparingTo("2");
            assertThat(mark(QuestionType.FILL_BLANK, TWO, List.of(), accepted, List.of(), "DELHI"))
                    .isEqualByComparingTo("2");
            assertThat(mark(QuestionType.FILL_BLANK, TWO, List.of(), "7|seven", List.of(), "Seven"))
                    .isEqualByComparingTo("2");
        }

        @Test
        void otherOrBlankAnswersScoreZero() {
            assertThat(mark(QuestionType.FILL_BLANK, TWO, List.of(), "Delhi", List.of(), "Mumbai"))
                    .isEqualByComparingTo("0");
            assertThat(mark(QuestionType.FILL_BLANK, TWO, List.of(), "Delhi", List.of(), "   "))
                    .isEqualByComparingTo("0");
            assertThat(mark(QuestionType.FILL_BLANK, TWO, List.of(), "Delhi", List.of(), null))
                    .isEqualByComparingTo("0");
        }

        @Test
        void blankLinesAreNotAcceptedAnswers() {
            assertThat(AutoMarker.accepted("Delhi\n\n | ")).containsExactly("delhi");
            assertThat(AutoMarker.acceptedList("b\na")).containsExactly("a", "b");
            assertThat(AutoMarker.accepted(null)).isEmpty();
        }
    }

    @Nested
    class ShortAnswer {

        @Test
        void isLeftForTheTeacher() {
            assertThat(mark(QuestionType.SHORT_ANSWER, TWO, List.of(), "Model answer", List.of(), "My answer"))
                    .isNull();
        }

        @Test
        void aBlankAnswerScoresZeroWithoutMarking() {
            assertThat(mark(QuestionType.SHORT_ANSWER, TWO, List.of(), null, List.of(), "  "))
                    .isEqualByComparingTo("0");
            assertThat(mark(QuestionType.SHORT_ANSWER, TWO, List.of(), null, List.of(), null))
                    .isEqualByComparingTo("0");
        }
    }

    @Test
    void fractionalMarksAreKeptToTwoDecimals() {
        assertThat(mark(QuestionType.MCQ_SINGLE, new BigDecimal("0.25"), List.of(A), null, List.of(A), null))
                .isEqualByComparingTo("0.25");
        // 1.5 x 1 / 4 = 0.375 rounds half up to 0.38.
        assertThat(mark(QuestionType.MCQ_MULTI, new BigDecimal("1.5"), List.of(A, B, C, D), null, List.of(A),
                null)).isEqualByComparingTo("0.38");
    }

    @Test
    void everyTypeIsAutoMarkedExceptShortAnswer() {
        for (QuestionType type : QuestionType.values()) {
            assertThat(type.autoMarked()).as(type.name()).isEqualTo(type != QuestionType.SHORT_ANSWER);
        }
    }
}
