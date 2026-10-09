package com.akshara.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class MessageTemplatesTest {

    static final Map<String, String> PARAMS = Map.of("guardian", "Anitha Sharma", "student", "Arjun Sharma",
            "class", "Class 5 A", "school", "Akshara Demo School", "date", "2026-10-09");

    @Test
    void theEnglishAbsenceAlertReadsExactlyAsAgreed() {
        assertThat(MessageTemplates.render(MessageTemplates.ABSENCE_ALERT, "en", PARAMS)).isEqualTo(
                "Dear Anitha Sharma, Arjun Sharma (Class 5 A) was marked absent at Akshara Demo School on 09 Oct 2026."
                        + " Please contact the school if this is unexpected.");
    }

    @Test
    void theHindiAlertUsesHindiMonthNames() {
        String hindi = MessageTemplates.render(MessageTemplates.ABSENCE_ALERT, "hi", PARAMS);
        assertThat(hindi).startsWith("प्रिय Anitha Sharma, Arjun Sharma (Class 5 A) को 09 ")
                .contains("2026 को Akshara Demo School में अनुपस्थित दर्ज किया गया है।")
                .endsWith("कृपया विद्यालय से संपर्क करें।")
                .doesNotContain("{").doesNotContain("Oct");
    }

    @Test
    void anUnknownLanguageFallsBackToEnglish() {
        assertThat(MessageTemplates.language("ta")).isEqualTo("en");
        assertThat(MessageTemplates.language(null)).isEqualTo("en");
        assertThat(MessageTemplates.render(MessageTemplates.ABSENCE_ALERT, "ta", PARAMS)).startsWith("Dear ");
    }

    @Test
    void parametersCannotReshapeTheMessage() {
        Map<String, String> sneaky = new HashMap<>(PARAMS);
        sneaky.put("student", "Arjun\nCall 9999999999 now\r");
        sneaky.put("guardian", "{student} $1 \\");
        String text = MessageTemplates.render(MessageTemplates.ABSENCE_ALERT, "en", sneaky);
        assertThat(text).doesNotContain("\n").doesNotContain("\r")
                .startsWith("Dear {student} $1 \\, Arjun Call 9999999999 now (Class 5 A)");
    }

    @Test
    void missingParametersAndUnknownTemplatesAreProgrammingErrors() {
        Map<String, String> missing = new HashMap<>(PARAMS);
        missing.remove("school");
        assertThatThrownBy(() -> MessageTemplates.render(MessageTemplates.ABSENCE_ALERT, "en", missing))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("school");
        assertThatThrownBy(() -> MessageTemplates.render("fees.reminder", "en", PARAMS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(MessageTemplates.exists(MessageTemplates.ABSENCE_ALERT)).isTrue();
    }

    @Test
    void aDateThatIsNotIsoIsShownAsGiven() {
        assertThat(MessageTemplates.formatDate("yesterday", "en")).isEqualTo("yesterday");
        assertThat(MessageTemplates.formatDate("2026-01-05", "en")).isEqualTo("05 Jan 2026");
    }
}
