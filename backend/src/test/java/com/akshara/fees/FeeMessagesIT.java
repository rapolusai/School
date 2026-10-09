package com.akshara.fees;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.akshara.notifications.MessageTemplates;
import com.akshara.support.FeeFixtures;
import com.akshara.support.FeeFixtures.Heads;
import com.akshara.support.IntegrationTest;
import com.akshara.support.SchoolFixtures;
import com.akshara.support.TestApi;
import com.akshara.support.TestApi.School;
import com.akshara.support.TestApi.Session;

/** Receipt and overdue-reminder messages to parents, queued in the notifications outbox. */
class FeeMessagesIT extends IntegrationTest {

    private Session admin;
    private Session accounts;
    private FeeFixtures fees;
    private String asha;
    private String financialYear;

    @BeforeEach
    void schoolWithFees() throws Exception {
        School school = api.signup();
        admin = api.login(school);
        api.createUser(admin, "Meena Reddy", "meena@" + school.code() + ".akshara.test", List.of("ACCOUNTANT"));
        accounts = api.login(school.code(), "meena@" + school.code() + ".akshara.test", TestApi.PASSWORD);
        SchoolFixtures fixtures = new SchoolFixtures(api);
        fees = new FeeFixtures(api, mvc);
        String yearId = fixtures.currentYear(admin);
        String class5 = fixtures.schoolClass(admin, "Class 5");
        String class5A = fixtures.section(admin, class5, "A", 40);
        asha = fixtures.student(admin, class5A, "P-1", "Asha", "Rao", "Lata Rao", "9876501001");
        Heads heads = fees.defaultHeads(accounts);
        fees.publishedStructure(accounts, yearId, class5, heads);
        financialYear = FeeMath.financialYear(FeeFixtures.today());
    }

    @Test
    void aReceiptMessageGoesToTheParentWithTheAmountAndReceiptNumber() throws Exception {
        String receipt = fees.cash(accounts, asha, 5_000_00);

        String id = TestApi.read(api.get("/api/messages?relatedId=" + asha, admin.accessToken())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].templateKey").value(MessageTemplates.FEE_RECEIPT))
                .andExpect(jsonPath("$.items[0].status").value("QUEUED"))
                .andExpect(jsonPath("$.items[0].channel").value("WHATSAPP"))
                .andExpect(jsonPath("$.items[0].recipientName").value("Lata Rao"))
                .andExpect(jsonPath("$.items[0].relatedLabel").value("Asha Rao (Class 5 A)")), "$.items[0].id");
        String body = TestApi.read(api.get("/api/messages/" + id, admin.accessToken()), "$.body");
        assertThat(body).startsWith("Dear Lata Rao, ")
                .contains("received ₹5,000 towards the fees of Asha Rao")
                .contains("Receipt no. RCPT/" + financialYear + "/000001.");

        // Cancelling the receipt does not queue a second message; neither does a refused payment.
        api.post("/api/fees/receipts/" + receipt + "/cancel", accounts.accessToken(), """
                {"reason":"Entered against the wrong student."}""").andExpect(status().isOk());
        api.post("/api/fees/students/" + asha + "/payments", accounts.accessToken(), """
                {"amountPaise":999999900,"mode":"CASH"}""").andExpect(status().isBadRequest());
        api.get("/api/messages?relatedId=" + asha, admin.accessToken()).andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void aReminderMessageFollowsTheSchoolsChannelAndLanguage() throws Exception {
        api.put("/api/notifications/settings", admin.accessToken(), """
                {"absenceAlertsEnabled":true,"absenceAlertChannel":"SMS","alertLanguage":"hi",
                 "quietHoursEnabled":false,"quietHoursStart":"21:00","quietHoursEnd":"07:00"}""")
                .andExpect(status().isOk());

        api.post("/api/fees/reminders", accounts.accessToken(), """
                {"studentIds":["%s"]}""".formatted(asha))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requested").value(1));

        String id = TestApi.read(api.get("/api/messages?relatedId=" + asha, admin.accessToken())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].templateKey").value(MessageTemplates.FEE_REMINDER))
                .andExpect(jsonPath("$.items[0].channel").value("SMS")), "$.items[0].id");
        api.get("/api/messages/" + id, admin.accessToken())
                .andExpect(jsonPath("$.language").value("hi"))
                .andExpect(jsonPath("$.body").value(org.hamcrest.Matchers.startsWith("प्रिय Lata Rao, ")))
                // Two quarters of ₹11,500 are overdue.
                .andExpect(jsonPath("$.body").value(org.hamcrest.Matchers.containsString("₹23,000")));

        // Each request is its own reminder.
        api.post("/api/fees/reminders", accounts.accessToken(), """
                {"studentIds":["%s"]}""".formatted(asha)).andExpect(jsonPath("$.requested").value(1));
        api.get("/api/messages?relatedId=" + asha, admin.accessToken()).andExpect(jsonPath("$.total").value(2));
    }
}
