package com.akshara.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

/** The messages about a new online test (lessons), to a parent and to the student, in English and Hindi. */
class LessonsMessageTemplatesTest {

    static final Map<String, String> PARAMS = Map.of("guardian", "Anitha Sharma", "student", "Arjun Sharma",
            "class", "Class 5 A", "school", "Akshara Demo School", "subject", "Mathematics",
            "title", "Fractions practice test", "date", "2026-10-18", "time", "17:00");

    @Test
    void theParentsMessageReadsAsAgreed() {
        assertThat(MessageTemplates.render(MessageTemplates.ONLINE_TEST, "en", PARAMS)).isEqualTo(
                "Dear Anitha Sharma, a new Mathematics online test for Arjun Sharma (Class 5 A) at Akshara Demo"
                        + " School: Fractions practice test. It closes on 18 Oct 2026 at 17:00.");
        assertThat(MessageTemplates.render(MessageTemplates.ONLINE_TEST, "hi", PARAMS))
                .startsWith("प्रिय Anitha Sharma,").contains("Fractions practice test").contains("17:00")
                .doesNotContain("{").doesNotContain("Oct");
    }

    @Test
    void theStudentsMessageReadsAsAgreed() {
        assertThat(MessageTemplates.render(MessageTemplates.ONLINE_TEST_STUDENT, "en", PARAMS)).isEqualTo(
                "Dear Arjun Sharma, a new Mathematics online test is ready for you at Akshara Demo School:"
                        + " Fractions practice test. Take it in the school app before 18 Oct 2026 at 17:00.");
        assertThat(MessageTemplates.render(MessageTemplates.ONLINE_TEST_STUDENT, "hi", PARAMS))
                .startsWith("प्रिय Arjun Sharma,").doesNotContain("{").doesNotContain("Oct");
        assertThat(MessageTemplates.exists(MessageTemplates.ONLINE_TEST)).isTrue();
        assertThat(MessageTemplates.exists(MessageTemplates.ONLINE_TEST_STUDENT)).isTrue();
    }
}
