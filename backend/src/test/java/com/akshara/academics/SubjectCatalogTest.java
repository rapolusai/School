package com.akshara.academics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.akshara.platform.Board;

class SubjectCatalogTest {

    @ParameterizedTest
    @CsvSource({
            "Nursery, -2", "Pre-KG, -2", "LKG, -1", "PP1, -1", "Jr KG, -1", "UKG, 0", "PP 2, 0",
            "Class 1, 1", "1, 1", "Class 5, 5", "Grade 5, 5", "Std. 10th, 10", "Standard 9, 9", "Class V, 5",
            "IX, 9", "XII, 12", "class 12, 12"})
    void readsTheLevelFromCommonClassNames(String name, int level) {
        assertThat(SubjectCatalog.levelOf(name)).isEqualTo(level);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Rose", "Class 13", "Class 0", "Senior", "", "Class 5 A"})
    void leavesOtherNamesWithoutALevel(String name) {
        assertThat(SubjectCatalog.levelOf(name)).isNull();
    }

    @ParameterizedTest
    @EnumSource(Board.class)
    void everyBoardSuggestsSubjectsForEveryLevel(Board board) {
        for (int level = SubjectCatalog.NURSERY; level <= 12; level++) {
            assertThat(SubjectCatalog.forLevel(board, level).stream().filter(e -> !e.optional()))
                    .as("%s level %d", board, level).isNotEmpty();
        }
    }

    @ParameterizedTest
    @EnumSource(Board.class)
    void namesAndCodesFitTheSubjectRules(Board board) {
        Set<String> names = new HashSet<>();
        Set<String> codes = new HashSet<>();
        for (SubjectCatalog.Entry entry : SubjectCatalog.entries(board)) {
            assertThat(names.add(entry.name().toLowerCase(Locale.ROOT))).as(entry.name()).isTrue();
            assertThat(codes.add(entry.code().toLowerCase(Locale.ROOT))).as(entry.code()).isTrue();
            assertThat(entry.name()).hasSizeLessThanOrEqualTo(100);
            assertThat(entry.code()).matches("^[A-Za-z0-9-]{1,20}$");
            assertThat(entry.from()).isLessThanOrEqualTo(entry.to());
        }
    }
}
