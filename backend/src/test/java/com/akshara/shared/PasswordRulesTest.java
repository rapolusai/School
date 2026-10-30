package com.akshara.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PasswordRulesTest {

    @ParameterizedTest
    @ValueSource(strings = {"1111111111", "aaaaaaaaaaaa", "1212121212", "abcabcabca", "ZZZZZZZZZZ"})
    void refusesAFewRepeatedCharacters(String password) {
        assertThat(PasswordRules.problem(password)).contains("repeats");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Password@123", "PASSWORD123", "welcome@123", "Admin@12345", "qwerty@123", "School@123"})
    void refusesCommonPasswordsWhateverTheCase(String password) {
        assertThat(PasswordRules.problem(password)).contains("first people try");
    }

    @ParameterizedTest
    @ValueSource(strings = {"1234567890", "0123456789", "9876543210", "abcdefghij", "QWERTYUIOP", "asdfghjklz",
            "1qaz2wsx3edc"})
    void refusesStraightRunsOfKeys(String password) {
        assertThat(PasswordRules.problem(password)).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Correct-Horse-42", "Meera-Starts-2026", "Parent-temp-2026", "long-enough-1",
            "Brand-new-password-1", "mango tree river 7"})
    void acceptsOrdinaryGoodPasswords(String password) {
        assertThat(PasswordRules.problem(password)).isNull();
    }

    @Test
    void checkThrowsA400OnTheField() {
        assertThatThrownBy(() -> PasswordRules.check("1111111111", "newPassword"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).status().value()).isEqualTo(400);
        assertThatCode(() -> PasswordRules.check(null, "password")).doesNotThrowAnyException();
    }
}
