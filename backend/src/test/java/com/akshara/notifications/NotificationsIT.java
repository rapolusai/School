package com.akshara.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;

import com.akshara.support.IntegrationTest;
import com.akshara.support.SchoolFixtures;
import com.akshara.support.TestApi;
import com.akshara.support.TestApi.School;
import com.akshara.support.TestApi.Session;

import tools.jackson.databind.json.JsonMapper;

/** Absence alerts through the outbox: queueing, dedupe, skipping, settings, quiet hours, sending and retries. */
class NotificationsIT extends IntegrationTest {

    static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    @Autowired
    MessageDispatcher dispatcher;

    @Autowired
    MessageRepository messages;

    @Autowired
    NotificationSettingsRepository settingsRepository;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    JsonMapper json;

    private final LocalDate today = LocalDate.now(INDIA);
    private School school;
    private Session admin;
    private String section;
    private String asha;
    private String bala;

    @BeforeEach
    void schoolWithStudents() throws Exception {
        school = api.signup();
        admin = api.login(school);
        SchoolFixtures fixtures = new SchoolFixtures(api);
        fixtures.year(admin, "This year", today.minusDays(100).toString(), today.plusDays(200).toString(), true);
        section = fixtures.section(admin, fixtures.schoolClass(admin, "Class 5"), "A", null);
        asha = TestApi.read(api.post("/api/students", admin.accessToken(), SchoolFixtures.studentJson(section, "N-1",
                "Asha", "Rao", "Lata Rao", "9876500001", 1)).andExpect(status().isCreated()), "$.id");
        bala = TestApi.read(api.post("/api/students", admin.accessToken(), SchoolFixtures.studentJson(section, "N-2",
                "Bala", "Iyer", "Uma Iyer", "+91 98765 00002", 2)).andExpect(status().isCreated()), "$.id");
    }

    @Test
    void anAbsenceQueuesOneAlertToThePrimaryGuardian() throws Exception {
        mark(today, "ABSENT", "PRESENT").andExpect(jsonPath("$.alertsQueued").value(1));
        // Saving again, with or without other changes, never sends a second alert for the same day.
        mark(today, "ABSENT", "PRESENT").andExpect(jsonPath("$.alertsQueued").value(0));
        mark(today, "ABSENT", "LATE").andExpect(jsonPath("$.alertsQueued").value(0));

        String list = api.get("/api/messages", admin.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].channel").value("WHATSAPP"))
                .andExpect(jsonPath("$.items[0].status").value("QUEUED"))
                .andExpect(jsonPath("$.items[0].recipient").value("98765•••01"))
                .andExpect(jsonPath("$.items[0].recipientName").value("Lata Rao"))
                .andExpect(jsonPath("$.items[0].templateKey").value("attendance.absence"))
                .andExpect(jsonPath("$.items[0].relatedType").value("student"))
                .andExpect(jsonPath("$.items[0].relatedId").value(asha))
                .andExpect(jsonPath("$.items[0].relatedLabel").value("Asha Rao (Class 5 A)"))
                .andExpect(jsonPath("$.items[0].nextAttemptAt").exists())
                .andReturn().getResponse().getContentAsString();
        assertThat(list).doesNotContain("9876500001").doesNotContain("was marked absent");
        String id = TestApi.read(api.get("/api/messages", admin.accessToken()), "$.items[0].id");

        String date = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH).format(today);
        api.get("/api/messages/" + id, admin.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipient").value("98765•••01"))
                .andExpect(jsonPath("$.language").value("en"))
                .andExpect(jsonPath("$.fallbackChannel").value("SMS"))
                .andExpect(jsonPath("$.body").value("Dear Lata Rao, Asha Rao (Class 5 A) was marked absent at Test School "
                        + school.code() + " on " + date + ". Please contact the school if this is unexpected."))
                .andExpect(content().string(not(containsString("9876500001"))));

        // Another day is another alert.
        mark(today.minusDays(1), "ABSENT", "ABSENT").andExpect(jsonPath("$.alertsQueued").value(2));
        api.get("/api/messages", admin.accessToken()).andExpect(jsonPath("$.total").value(3));
        api.get("/api/messages?relatedId=" + bala, admin.accessToken())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].recipient").value("98765•••02"));
    }

    @Test
    void markingPresentBeforeTheAlertIsSentSkipsIt() throws Exception {
        mark(today, "ABSENT", "PRESENT").andExpect(jsonPath("$.alertsQueued").value(1));
        mark(today, "PRESENT", "PRESENT").andExpect(jsonPath("$.alertsCancelled").value(1));
        api.get("/api/messages", admin.accessToken())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].status").value("SKIPPED"))
                .andExpect(jsonPath("$.items[0].lastError").value("Marked present before the alert was sent"))
                .andExpect(jsonPath("$.items[0].nextAttemptAt").value(nullValue()));
        // A skipped alert is not sent by the dispatcher.
        dispatcher.dispatchSchool(school.tenantId(), at(today.plusDays(1), 10, 0));
        api.get("/api/messages", admin.accessToken()).andExpect(jsonPath("$.items[0].status").value("SKIPPED"));

        // Absent again: the same alert is queued again rather than a second one.
        mark(today, "ABSENT", "PRESENT").andExpect(jsonPath("$.alertsQueued").value(1));
        api.get("/api/messages", admin.accessToken())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].status").value("QUEUED"));

        // Once sent (simulated), it stays sent whatever the register says next.
        assertThat(dispatcher.dispatchSchool(school.tenantId(), at(today.plusDays(1), 10, 0))).isEqualTo(1);
        api.get("/api/messages", admin.accessToken())
                .andExpect(jsonPath("$.items[0].status").value("SIMULATED"))
                .andExpect(jsonPath("$.items[0].attempts").value(1))
                .andExpect(jsonPath("$.items[0].sentAt").exists());
        mark(today, "LATE", "PRESENT").andExpect(jsonPath("$.alertsCancelled").value(0));
        mark(today, "ABSENT", "PRESENT").andExpect(jsonPath("$.alertsQueued").value(0));
        api.get("/api/messages", admin.accessToken())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].status").value("SIMULATED"));
    }

    @Test
    void schoolsCanSwitchAbsenceAlertsOffAndChooseChannelAndLanguage() throws Exception {
        api.get("/api/notifications/settings", admin.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.absenceAlertsEnabled").value(true))
                .andExpect(jsonPath("$.absenceAlertChannel").value("WHATSAPP_SMS"))
                .andExpect(jsonPath("$.alertLanguage").value("en"))
                .andExpect(jsonPath("$.quietHoursEnabled").value(true))
                .andExpect(jsonPath("$.quietHoursStart").value("21:00"))
                .andExpect(jsonPath("$.quietHoursEnd").value("07:00"));
        api.put("/api/notifications/settings", admin.accessToken(), settings(false, "WHATSAPP_SMS", "en", true,
                "21:00", "07:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.absenceAlertsEnabled").value(false));
        mark(today, "ABSENT", "ABSENT").andExpect(jsonPath("$.alertsQueued").value(0));
        api.get("/api/messages", admin.accessToken()).andExpect(jsonPath("$.total").value(0));

        // SMS only, in Hindi.
        api.put("/api/notifications/settings", admin.accessToken(), settings(true, "SMS", "hi", false, "22:00",
                "06:30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quietHoursStart").value("22:00"));
        mark(today.minusDays(1), "ABSENT", "PRESENT").andExpect(jsonPath("$.alertsQueued").value(1));
        String id = TestApi.read(api.get("/api/messages", admin.accessToken())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].channel").value("SMS")), "$.items[0].id");
        String body = TestApi.read(api.get("/api/messages/" + id, admin.accessToken())
                .andExpect(jsonPath("$.language").value("hi"))
                .andExpect(jsonPath("$.fallbackChannel").value(nullValue())), "$.body");
        assertThat(body).startsWith("प्रिय Lata Rao, Asha Rao (Class 5 A) को ").contains("अनुपस्थित");

        api.get("/api/audit-events?limit=10", admin.accessToken())
                .andExpect(jsonPath("$[*].action", hasItem("notification_settings.updated")));

        // Validation.
        api.put("/api/notifications/settings", admin.accessToken(), settings(true, "SMS", "fr", true, "21:00",
                "07:00")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.alertLanguage").exists());
        api.put("/api/notifications/settings", admin.accessToken(), settings(true, "SMS", "en", true, "25:00",
                "07:00")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.quietHoursStart").exists());
        api.put("/api/notifications/settings", admin.accessToken(), settings(true, "SMS", "en", true, "07:00",
                "07:00")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.quietHoursEnd").exists());
        api.put("/api/notifications/settings", admin.accessToken(), settings(true, "EMAIL", "en", true, "21:00",
                "07:00")).andExpect(status().isBadRequest());

        // Only settings.manage reads or changes them.
        api.createUser(admin, "Lakshmi Iyer", email("principal"), List.of("PRINCIPAL"));
        Session principal = api.login(school.code(), email("principal"), TestApi.PASSWORD);
        api.get("/api/notifications/settings", principal.accessToken()).andExpect(status().isForbidden());
        api.put("/api/notifications/settings", principal.accessToken(), settings(true, "SMS", "en", true, "21:00",
                "07:00")).andExpect(status().isForbidden());
    }

    @Test
    void quietHoursHoldMessagesUntilMorning() throws Exception {
        mark(today, "ABSENT", "PRESENT");
        LocalDate tomorrow = today.plusDays(1);
        // 22:30 is inside 21:00-07:00: nothing goes out, the message waits until 07:00.
        assertThat(dispatcher.dispatchSchool(school.tenantId(), at(tomorrow, 22, 30))).isZero();
        api.get("/api/messages", admin.accessToken())
                .andExpect(jsonPath("$.items[0].status").value("QUEUED"))
                .andExpect(jsonPath("$.items[0].attempts").value(0))
                .andExpect(jsonPath("$.items[0].nextAttemptAt").value(at(tomorrow.plusDays(1), 7, 0).toString()));
        assertThat(dispatcher.dispatchSchool(school.tenantId(), at(tomorrow.plusDays(1), 6, 59))).isZero();
        api.get("/api/messages", admin.accessToken()).andExpect(jsonPath("$.items[0].status").value("QUEUED"));
        assertThat(dispatcher.dispatchSchool(school.tenantId(), at(tomorrow.plusDays(1), 7, 30))).isEqualTo(1);
        api.get("/api/messages", admin.accessToken()).andExpect(jsonPath("$.items[0].status").value("SIMULATED"));

        // With quiet hours off, late evening is fine.
        api.put("/api/notifications/settings", admin.accessToken(), settings(true, "WHATSAPP_SMS", "en", false,
                "21:00", "07:00")).andExpect(status().isOk());
        mark(today.minusDays(1), "PRESENT", "ABSENT");
        assertThat(dispatcher.dispatchSchool(school.tenantId(), at(tomorrow, 23, 0))).isEqualTo(1);
    }

    @Test
    void theScheduledRoundFindsEverySchoolWithDueMessages() throws Exception {
        mark(today, "ABSENT", "ABSENT");
        Instant tenAm = at(today.plusDays(1), 10, 0);
        // Other test schools may have messages waiting too; each round takes a few schools, oldest first.
        for (int round = 0; round < 100 && queued() > 0; round++) {
            dispatcher.dispatchDue(tenAm);
        }
        api.get("/api/messages?status=SIMULATED", admin.accessToken()).andExpect(jsonPath("$.total").value(2));
    }

    @Test
    void failedSendsAreRetriedWithBackoffThenFallBackToSms() throws Exception {
        TestSender sender = new TestSender();
        sender.failWhatsApp = new MessageSendException("Provider unavailable", true);
        MessageDispatcher flaky = dispatcher(sender, 3);
        assertThat(flaky.backoff(1)).isEqualTo(Duration.ofMinutes(1));
        assertThat(flaky.backoff(2)).isEqualTo(Duration.ofMinutes(2));
        assertThat(flaky.backoff(8)).isEqualTo(Duration.ofHours(1));

        mark(today, "ABSENT", "PRESENT");
        Instant t0 = at(today.plusDays(1), 10, 0);
        assertThat(flaky.dispatchSchool(school.tenantId(), t0)).isZero();
        assertThat(sender.sent).hasSize(1);
        MessageSender.OutgoingMessage first = sender.sent.getFirst();
        assertThat(first.channel()).isEqualTo(Channel.WHATSAPP);
        assertThat(first.recipient()).isEqualTo("9876500001");
        assertThat(first.tenantId()).isEqualTo(school.tenantId());
        assertThat(first.params()).containsEntry("student", "Asha Rao").containsEntry("guardian", "Lata Rao");
        assertThat(first.body()).startsWith("Dear Lata Rao, Asha Rao (Class 5 A) was marked absent");
        api.get("/api/messages", admin.accessToken())
                .andExpect(jsonPath("$.items[0].status").value("QUEUED"))
                .andExpect(jsonPath("$.items[0].attempts").value(1))
                .andExpect(jsonPath("$.items[0].lastError").value("Provider unavailable"))
                .andExpect(jsonPath("$.items[0].nextAttemptAt").value(t0.plus(Duration.ofMinutes(1)).toString()));

        // Not due yet.
        flaky.dispatchSchool(school.tenantId(), t0.plusSeconds(30));
        assertThat(sender.sent).hasSize(1);
        flaky.dispatchSchool(school.tenantId(), t0.plus(Duration.ofMinutes(1)));
        api.get("/api/messages", admin.accessToken())
                .andExpect(jsonPath("$.items[0].attempts").value(2))
                .andExpect(jsonPath("$.items[0].nextAttemptAt").value(t0.plus(Duration.ofMinutes(3)).toString()));
        // The third attempt is the last: WhatsApp gives up and the same text is queued as an SMS.
        assertThat(flaky.dispatchSchool(school.tenantId(), t0.plus(Duration.ofMinutes(3)))).isEqualTo(1);
        assertThat(sender.sent).hasSize(3);
        api.get("/api/messages?status=FAILED", admin.accessToken())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].channel").value("WHATSAPP"))
                .andExpect(jsonPath("$.items[0].attempts").value(3));
        api.get("/api/messages?channel=SMS", admin.accessToken())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].status").value("QUEUED"))
                .andExpect(jsonPath("$.items[0].relatedId").value(asha));

        assertThat(flaky.dispatchSchool(school.tenantId(), t0.plus(Duration.ofMinutes(4)))).isEqualTo(1);
        assertThat(sender.sent.getLast().channel()).isEqualTo(Channel.SMS);
        String smsId = TestApi.read(api.get("/api/messages?channel=SMS", admin.accessToken())
                .andExpect(jsonPath("$.items[0].status").value("SENT")), "$.items[0].id");
        api.get("/api/messages/" + smsId, admin.accessToken())
                .andExpect(jsonPath("$.fallbackChannel").value(nullValue()))
                .andExpect(jsonPath("$.body").value(first.body()));
        // Marking present now changes nothing: the alert has gone.
        mark(today, "PRESENT", "PRESENT").andExpect(jsonPath("$.alertsCancelled").value(0));
    }

    @Test
    void permanentFailuresStopAtOnceAndUnexpectedErrorsKeepNoDetails() throws Exception {
        TestSender sender = new TestSender();
        sender.failWhatsApp = new MessageSendException("Number is not on WhatsApp", false);
        sender.failSms = new IllegalStateException("gateway said no to 9876500001");
        MessageDispatcher flaky = dispatcher(sender, 5);
        mark(today, "ABSENT", "PRESENT");
        Instant t0 = at(today.plusDays(1), 10, 0);
        assertThat(flaky.dispatchSchool(school.tenantId(), t0)).isEqualTo(1);
        api.get("/api/messages?channel=WHATSAPP", admin.accessToken())
                .andExpect(jsonPath("$.items[0].status").value("FAILED"))
                .andExpect(jsonPath("$.items[0].attempts").value(1))
                .andExpect(jsonPath("$.items[0].lastError").value("Number is not on WhatsApp"));
        flaky.dispatchSchool(school.tenantId(), t0.plusSeconds(1));
        String sms = api.get("/api/messages?channel=SMS", admin.accessToken())
                .andExpect(jsonPath("$.items[0].status").value("QUEUED"))
                .andExpect(jsonPath("$.items[0].attempts").value(1))
                .andExpect(jsonPath("$.items[0].lastError").value("Unexpected error (IllegalStateException)"))
                .andReturn().getResponse().getContentAsString();
        assertThat(sms).doesNotContain("9876500001");
    }

    @Test
    void theMessageLogIsFilteredMaskedAndForAdminsOnly() throws Exception {
        mark(today.minusDays(2), "ABSENT", "PRESENT");
        mark(today, "PRESENT", "ABSENT");
        api.get("/api/messages?q=bala", admin.accessToken())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].relatedLabel").value("Bala Iyer (Class 5 A)"));
        api.get("/api/messages?q=Lata", admin.accessToken()).andExpect(jsonPath("$.total").value(1));
        api.get("/api/messages?q=nobody", admin.accessToken()).andExpect(jsonPath("$.total").value(0));
        api.get("/api/messages?channel=WHATSAPP&status=QUEUED", admin.accessToken())
                .andExpect(jsonPath("$.total").value(2));
        api.get("/api/messages?channel=EMAIL", admin.accessToken()).andExpect(jsonPath("$.total").value(0));
        // Dates are when the alert was queued (both marked today, for two different days).
        api.get("/api/messages?from=" + today + "&to=" + today, admin.accessToken())
                .andExpect(jsonPath("$.total").value(2));
        api.get("/api/messages?from=" + today.plusDays(1), admin.accessToken())
                .andExpect(jsonPath("$.total").value(0));
        api.get("/api/messages?size=1&page=1", admin.accessToken())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items.length()").value(1));
        api.get("/api/messages?size=101", admin.accessToken()).andExpect(status().isBadRequest());
        api.get("/api/messages?status=LOST", admin.accessToken()).andExpect(status().isBadRequest());
        api.get("/api/messages/" + UUID.randomUUID(), admin.accessToken()).andExpect(status().isNotFound());

        api.createUser(admin, "Lakshmi Iyer", email("principal"), List.of("PRINCIPAL"));
        api.createUser(admin, "Ravi Kumar", email("ravi"), List.of("TEACHER"));
        api.createUser(admin, "Meena Reddy", email("accounts"), List.of("ACCOUNTANT"));
        api.get("/api/messages", api.login(school.code(), email("principal"), TestApi.PASSWORD).accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2));
        api.get("/api/messages", api.login(school.code(), email("ravi"), TestApi.PASSWORD).accessToken())
                .andExpect(status().isForbidden());
        api.get("/api/messages", api.login(school.code(), email("accounts"), TestApi.PASSWORD).accessToken())
                .andExpect(status().isForbidden());

        // Another school sees none of it, not even by id.
        String id = TestApi.read(api.get("/api/messages", admin.accessToken()), "$.items[0].id");
        School other = api.signup();
        Session adminB = api.login(other);
        api.get("/api/messages", adminB.accessToken()).andExpect(jsonPath("$.total").value(0));
        api.get("/api/messages/" + id, adminB.accessToken()).andExpect(status().isNotFound());
        api.get("/api/notifications/settings", adminB.accessToken())
                .andExpect(jsonPath("$.absenceAlertsEnabled").value(true));
    }

    // ------------------------------------------------------------------ helpers

    /** Records what it was asked to send; fails WhatsApp or SMS when told to. */
    static class TestSender implements MessageSender {

        final List<OutgoingMessage> sent = new ArrayList<>();
        Exception failWhatsApp;
        Exception failSms;

        @Override
        public boolean supports(Channel channel) {
            return channel != Channel.EMAIL;
        }

        @Override
        public SendResult send(OutgoingMessage message) throws MessageSendException {
            sent.add(message);
            Exception failure = message.channel() == Channel.WHATSAPP ? failWhatsApp : failSms;
            if (failure instanceof MessageSendException e) {
                throw e;
            }
            if (failure instanceof RuntimeException e) {
                throw e;
            }
            return SendResult.sent("test-" + sent.size());
        }
    }

    private MessageDispatcher dispatcher(MessageSender sender, int maxAttempts) {
        NotificationProperties properties = new NotificationProperties(new NotificationProperties.Dispatch(true,
                Duration.ofSeconds(15), 50, 20, maxAttempts, Duration.ofMinutes(1), Duration.ofHours(1)));
        return new MessageDispatcher(messages, settingsRepository, List.of(sender), properties, transactions, json);
    }

    private ResultActions mark(LocalDate date, String ashaMark,
            String balaMark) throws Exception {
        return api.put("/api/attendance/registers/" + section + "/" + date, admin.accessToken(), """
                {"entries":[{"studentId":"%s","status":"%s"},{"studentId":"%s","status":"%s"}]}"""
                .formatted(asha, ashaMark, bala, balaMark)).andExpect(status().isOk());
    }

    private long queued() throws Exception {
        return Long.parseLong(TestApi.read(api.get("/api/messages?status=QUEUED", admin.accessToken()), "$.total"));
    }

    private static String settings(boolean enabled, String channel, String language, boolean quiet, String start,
            String end) {
        return """
                {"absenceAlertsEnabled":%s,"absenceAlertChannel":"%s","alertLanguage":"%s","quietHoursEnabled":%s,
                 "quietHoursStart":"%s","quietHoursEnd":"%s"}""".formatted(enabled, channel, language, quiet, start,
                end);
    }

    static Instant at(LocalDate day, int hour, int minute) {
        return day.atTime(hour, minute).atZone(INDIA).toInstant();
    }

    private String email(String name) {
        return name + "@" + school.code() + ".akshara.test";
    }
}
