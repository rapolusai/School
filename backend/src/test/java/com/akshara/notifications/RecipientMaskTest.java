package com.akshara.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RecipientMaskTest {

    @Test
    void phoneNumbersKeepTheFirstFiveAndLastTwoDigits() {
        assertThat(RecipientMask.mask("9876500001")).isEqualTo("98765•••01");
        assertThat(RecipientMask.mask("+91 98765 43210")).isEqualTo("91987•••10");
        assertThat(RecipientMask.mask(" 9876543210 ")).isEqualTo("98765•••10");
    }

    @Test
    void shortOrOddValuesShowNothing() {
        assertThat(RecipientMask.mask("12345")).isEqualTo("•••");
        assertThat(RecipientMask.mask("call me")).isEqualTo("•••");
        assertThat(RecipientMask.mask(null)).isEmpty();
        assertThat(RecipientMask.mask("  ")).isEmpty();
    }

    @Test
    void emailsKeepTwoLettersAndTheDomain() {
        assertThat(RecipientMask.mask("anitha.sharma@example.in")).isEqualTo("an•••@example.in");
        assertThat(RecipientMask.mask("ab@example.in")).isEqualTo("a•••@example.in");
        assertThat(RecipientMask.mask("@example.in")).isEqualTo("•••@example.in");
    }

    @Test
    void theFullNumberNeverSurvives() {
        String masked = RecipientMask.mask("9876543210");
        assertThat(masked).doesNotContain("9876543210").doesNotContain("43210");
    }
}
