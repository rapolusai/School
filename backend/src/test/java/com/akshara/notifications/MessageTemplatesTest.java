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
        assertThatThrownBy(() -> MessageTemplates.render("no.such.template", "en", PARAMS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(MessageTemplates.exists(MessageTemplates.ABSENCE_ALERT)).isTrue();
    }

    @Test
    void aDateThatIsNotIsoIsShownAsGiven() {
        assertThat(MessageTemplates.formatDate("yesterday", "en")).isEqualTo("yesterday");
        assertThat(MessageTemplates.formatDate("2026-01-05", "en")).isEqualTo("05 Jan 2026");
    }

    @Test
    void feeReceiptAndReminderTextsReadAsAgreed() {
        Map<String, String> receipt = Map.of("guardian", "Anitha Sharma", "student", "Arjun Sharma",
                "school", "Akshara Demo School", "amount", "₹12,500", "receiptNo", "RCPT/2026-27/000042",
                "date", "2026-10-09");
        assertThat(MessageTemplates.render(MessageTemplates.FEE_RECEIPT, "en", receipt)).isEqualTo(
                "Dear Anitha Sharma, Akshara Demo School received ₹12,500 towards the fees of Arjun Sharma on"
                        + " 09 Oct 2026. Receipt no. RCPT/2026-27/000042. Thank you.");
        assertThat(MessageTemplates.render(MessageTemplates.FEE_RECEIPT, "hi", receipt))
                .contains("₹12,500").contains("RCPT/2026-27/000042").doesNotContain("{").doesNotContain("Oct");

        Map<String, String> reminder = Map.of("guardian", "Anitha Sharma", "student", "Arjun Sharma",
                "school", "Akshara Demo School", "amount", "₹1,84,300.50", "date", "2026-09-10");
        assertThat(MessageTemplates.render(MessageTemplates.FEE_REMINDER, "en", reminder)).isEqualTo(
                "Dear Anitha Sharma, fees of ₹1,84,300.50 for Arjun Sharma at Akshara Demo School are overdue since"
                        + " 10 Sep 2026. Please pay at the school office or online. Ignore this if you have already"
                        + " paid.");
        assertThat(MessageTemplates.render(MessageTemplates.FEE_REMINDER, "hi", reminder))
                .contains("₹1,84,300.50").doesNotContain("{").doesNotContain("Sep");
    }
}
