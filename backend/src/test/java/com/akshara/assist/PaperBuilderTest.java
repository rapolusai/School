package com.akshara.assist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.akshara.assist.AssistViews.Blueprint;
import com.akshara.assist.AssistViews.BlueprintSection;
import com.akshara.assist.AssistViews.PaperQuestion;
import com.akshara.assist.PaperBuilder.Built;
import com.akshara.lessons.BloomLevel;
import com.akshara.lessons.Difficulty;
import com.akshara.lessons.LessonsViews.QuestionView;
import com.akshara.lessons.QuestionType;
import com.akshara.shared.ApiException;

/** Picking questions for a blueprint: marks, difficulty mix, chapters, order and the warnings. */
class PaperBuilderTest {

    private static final UUID CHAPTER_1 = UUID.randomUUID();
    private static final UUID CHAPTER_2 = UUID.randomUUID();

    private static QuestionView q(String text, Difficulty difficulty, int marks, UUID topic, BloomLevel bloom) {
        return new QuestionView(UUID.nameUUIDFromBytes(text.getBytes()), UUID.randomUUID(), "Class 5",
                UUID.randomUUID(), "Mathematics", topic, "Chapter", QuestionType.SHORT_ANSWER, difficulty, bloom,
                text, null, null, BigDecimal.valueOf(marks), true, List.of(), "Ravi", Instant.now(), true, 0, false);
    }

    private static List<QuestionView> bank() {
        List<QuestionView> bank = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            bank.add(q("Easy " + i, Difficulty.EASY, 1, CHAPTER_1, BloomLevel.REMEMBER));
        }
        for (int i = 1; i <= 4; i++) {
            bank.add(q("Medium " + i, Difficulty.MEDIUM, 1, CHAPTER_2, BloomLevel.APPLY));
        }
        bank.add(q("Hard 1", Difficulty.HARD, 1, CHAPTER_2, BloomLevel.ANALYSE));
        bank.add(q("Long 1", Difficulty.MEDIUM, 5, CHAPTER_1, null));
        bank.add(q("Long 2", Difficulty.HARD, 5, CHAPTER_2, null));
        return bank;
    }

    @Test
    void fillsSectionsInTheDifficultyMixEasiestFirst() {
        Blueprint blueprint = PaperBuilder.check(new Blueprint(List.of(new BlueprintSection("Section A", 5,
                BigDecimal.ONE), new BlueprintSection("Section B", 2, BigDecimal.valueOf(5))), 40, 40, 20, null,
                null));
        Built built = PaperBuilder.build(bank(), blueprint);
        assertThat(built.warnings()).isEmpty();
        assertThat(built.totalMarks()).isEqualByComparingTo("15");
        List<PaperQuestion> a = built.sections().get(0).questions();
        assertThat(a).extracting(PaperQuestion::difficulty).containsExactly(Difficulty.EASY, Difficulty.EASY,
                Difficulty.MEDIUM, Difficulty.MEDIUM, Difficulty.HARD);
        assertThat(a).extracting(PaperQuestion::text).containsExactly("Easy 1", "Easy 2", "Medium 1", "Medium 2",
                "Hard 1");
        assertThat(built.sections().get(1).questions()).extracting(PaperQuestion::text)
                .containsExactly("Long 1", "Long 2");
        // Deterministic.
        assertThat(PaperBuilder.build(bank(), blueprint)).isEqualTo(built);
    }

    @Test
    void keepsToTheChaptersAndBloomLevelsAskedForAndWarnsWhenTheBankFallsShort() {
        Blueprint blueprint = PaperBuilder.check(new Blueprint(List.of(new BlueprintSection("A", 4,
                BigDecimal.ONE)), 0, 0, 0, List.of(BloomLevel.APPLY, BloomLevel.ANALYSE), List.of(CHAPTER_2)));
        Built built = PaperBuilder.build(bank(), blueprint);
        assertThat(built.sections().getFirst().questions()).extracting(PaperQuestion::text)
                .containsExactly("Medium 1", "Medium 2", "Medium 3", "Medium 4");
        Built short1 = PaperBuilder.build(bank(), PaperBuilder.check(new Blueprint(List.of(new BlueprintSection("A",
                3, BigDecimal.valueOf(5))), 0, 0, 0, null, null)));
        assertThat(short1.sections().getFirst().questions()).hasSize(3);
        assertThat(short1.warnings()).containsExactly("A: 1 question carries different marks from 5 (the bank had no "
                + "more worth that).");
        Built short2 = PaperBuilder.build(bank(), PaperBuilder.check(new Blueprint(List.of(new BlueprintSection("A",
                20, BigDecimal.ONE)), 0, 0, 0, null, null)));
        assertThat(short2.sections().getFirst().questions()).hasSize(13);
        assertThat(short2.warnings()).contains("A: only 13 of 20 questions found in the bank.");
    }

    @Test
    void checksTheBlueprint() {
        assertThatThrownBy(() -> PaperBuilder.check(new Blueprint(List.of(), 40, 40, 20, null, null)))
                .isInstanceOf(ApiException.class).hasMessageContaining("at least one section");
        assertThatThrownBy(() -> PaperBuilder.check(new Blueprint(List.of(new BlueprintSection(" ", 4,
                BigDecimal.ONE)), 40, 40, 20, null, null))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> PaperBuilder.check(new Blueprint(List.of(new BlueprintSection("A", 4,
                BigDecimal.ONE)), 50, 40, 20, null, null))).isInstanceOf(ApiException.class)
                .hasMessageContaining("100%");
        assertThat(PaperBuilder.check(new Blueprint(List.of(new BlueprintSection("A", 4, BigDecimal.ONE)), 0, 0, 0,
                null, null)).easyPercent()).isEqualTo(40);
    }
}
