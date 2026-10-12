package com.akshara.certificates;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Map;

import org.junit.jupiter.api.Test;

class PlaceholdersTest {

    @Test
    void fillsPlaceholdersAndWritesDatesInWords() {
        String out = Placeholders.render("This is to certify that {student}, {relation} {father}, born on {dob}, "
                + "studies in {class}.", Map.of("student", "Asha Rao", "relation", "daughter of", "father",
                "Ravi Rao", "dob", "2016-04-12", "class", "Class 5 A"));
        assertThat(out).isEqualTo("This is to certify that Asha Rao, daughter of Ravi Rao, born on 12 Apr 2016, "
                + "studies in Class 5 A.");
    }

    @Test
    void missingAndBlankValuesBecomeADashNeverARawPlaceholder() {
        String out = Placeholders.render("{student} / {mother} / {unknown}", Map.of("student", "Asha", "mother", " "));
        assertThat(out).isEqualTo("Asha / — / —");
        assertThat(out).doesNotContain("{");
    }

    @Test
    void leavesTextWithoutPlaceholdersAloneAndHandlesNull() {
        assertThat(Placeholders.render("No fields here.", Map.of())).isEqualTo("No fields here.");
        assertThat(Placeholders.render(null, Map.of())).isEmpty();
        assertThat(Placeholders.render("{a}", null)).isEqualTo("—");
    }

    @Test
    void valuesWithDollarSignsAndBackslashesAreCopiedAsIs() {
        assertThat(Placeholders.render("Fee {amount}", Map.of("amount", "$1 \\ ₹2"))).isEqualTo("Fee $1 \\ ₹2");
    }

    @Test
    void rupeesUseIndianGrouping() {
        assertThat(Placeholders.rupees(18_430_000L)).isEqualTo("₹1,84,300");
        assertThat(Placeholders.rupees(18_430_050L)).isEqualTo("₹1,84,300.50");
        assertThat(Placeholders.rupees(99_900L)).isEqualTo("₹999");
        assertThat(Placeholders.rupees(100_000L)).isEqualTo("₹1,000");
        assertThat(Placeholders.rupees(1_234_567_800L)).isEqualTo("₹1,23,45,678");
        assertThat(Placeholders.rupees(-5_000L)).isEqualTo("-₹50");
        assertThat(Placeholders.rupees(0)).isEqualTo("₹0");
    }

    @Test
    void listsThePlaceholderNamesOfATemplateOnce() {
        assertThat(Placeholders.namesIn("{student} of {class}, {student}")).containsExactly("student", "class");
        assertThat(Placeholders.namesIn(null)).isEmpty();
        assertThat(Placeholders.date(LocalDate.of(2026, 10, 11))).isEqualTo("11 Oct 2026");
    }
}
