package com.akshara.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.akshara.notifications.Channel;
import com.akshara.notifications.MessageDispatcher;
import com.akshara.notifications.MessageTemplates;
import com.akshara.notifications.NotificationQueue;
import com.akshara.notifications.NotificationQueue.Recipient;
import com.akshara.notifications.NotificationQueue.Related;
import com.akshara.shared.TenantContext;
import com.akshara.support.TestApi;
import com.akshara.support.TestApi.Session;

/**
 * What happens in other modules when a child's data is erased or WhatsApp consent changes: the message log forgets the
 * child and the deleted parents' contacts (counts and statuses stay), sign-ins that no longer stand for a child at
 * school stop working (a parent kept for a sibling keeps theirs), and a parent without WhatsApp consent gets SMS.
 */
class ErasureFollowUpIT extends PrivacyTestBase {

    @Autowired
    NotificationQueue queue;

    @Autowired
    MessageDispatcher dispatcher;

    @Autowired
    PlatformTransactionManager transactions;

    @Test
    void erasureClearsTheMessageLogAndEndsSignInsThatStandForNoChild() throws Exception {
        Session kabirOwn = studentSignIn(kabir, "kabir");
        UUID aboutKabir = queueAbout(kabir, "Kabir Khan", Channel.SMS, FARAH_PHONE, "student", "absence-k");
        UUID toFarahOther = queueAbout(UUID.randomUUID().toString(), "Kabir Khan", Channel.SMS, FARAH_PHONE,
                "homework", "homework-k");
        UUID aboutArjun = queueAbout(arjun, "Arjun Sharma", Channel.SMS, ANITHA_PHONE, "student", "absence-a");
        dispatcher.dispatchSchool(school.tenantId(), daytime());
        // One more waiting, not yet sent.
        UUID waiting = queueAbout(kabir, "Kabir Khan", Channel.SMS, FARAH_PHONE, "student", "absence-k2");
        long before = messageCount();

        erase(otherParent, kabir, "P-3");

        assertThat(messageCount()).isEqualTo(before);
        Map<String, Object> erased = message(aboutKabir);
        assertThat(erased.get("recipient")).isEqualTo("erased");
        assertThat(erased.get("recipient_name")).isNull();
        assertThat(erased.get("body")).isEqualTo("");
        assertThat(erased.get("params")).isEqualTo("{}");
        assertThat(erased.get("related_label")).isEqualTo("Erased student");
        assertThat(erased.get("status")).isEqualTo("SIMULATED");
        assertThat(erased.get("channel")).isEqualTo("SMS");
        Map<String, Object> other = message(toFarahOther);
        assertThat(other.get("recipient")).isEqualTo("erased");
        assertThat((String) other.get("body")).isEmpty();
        assertThat(message(waiting).get("status")).isEqualTo("SKIPPED");
        assertThat(message(waiting).get("recipient")).isEqualTo("erased");
        // Another family's log is untouched.
        Map<String, Object> kept = message(aboutArjun);
        assertThat(kept.get("recipient")).isEqualTo(ANITHA_PHONE);
        assertThat((String) kept.get("body")).contains("Arjun Sharma");

        // Farah had no other child and Kabir's own sign-in pointed at the erased record: both stop working.
        login("farah").andExpect(status().isUnauthorized());
        login("kabir").andExpect(status().isUnauthorized());
        api.refresh(otherParent.refreshToken()).andExpect(status().isUnauthorized());
        api.refresh(kabirOwn.refreshToken()).andExpect(status().isUnauthorized());
        // Anitha is untouched.
        login("anitha").andExpect(status().isOk());
        api.get("/api/audit-events?limit=100", admin.accessToken())
                .andExpect(jsonPath("$[?(@.action == 'user.disabled')]").isNotEmpty());
    }

    @Test
    void aParentKeepsTheirSignInWhileABrotherOrSisterIsAtSchool() throws Exception {
        String meera = fixtures.student(admin, sectionId, "P-4", "Meera", "Sharma", "Anitha Sharma", ANITHA_PHONE);
        api.get("/api/me/children", parent.accessToken()).andExpect(jsonPath("$.length()").value(3));

        // Diya leaves and is erased: Anitha still has Arjun and Meera at school, so her sign-in stays.
        erase(parent, diya, "P-2");
        login("anitha").andExpect(status().isOk());
        api.get("/api/me/children", parent.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
        api.refresh(parent.refreshToken()).andExpect(status().isOk());

        // Arjun leaves (his record stays), then Meera is erased: Anitha's record is kept for Arjun, but no child of
        // hers is at school any more, so her sign-in stops.
        api.post("/api/students/" + arjun + "/leave", admin.accessToken(), """
                {"status":"TRANSFERRED","leftOn":"%s","reason":"Moved"}""".formatted(today()))
                .andExpect(status().isOk());
        erase(parent, meera, "P-4");
        api.get("/api/students/" + arjun, admin.accessToken()).andExpect(jsonPath("$.guardians.length()").value(1));
        login("anitha").andExpect(status().isUnauthorized());
    }

    @Test
    void aParentWithoutWhatsAppConsentGetsSmsInstead() throws Exception {
        int version = publish(null);
        acceptForBoth(version);
        UUID first = queueAbout(arjun, "Arjun Sharma", Channel.WHATSAPP, ANITHA_PHONE, "student", "wa-1");
        dispatcher.dispatchSchool(school.tenantId(), daytime());
        assertThat(message(first).get("status")).isEqualTo("SIMULATED");

        // Anitha withdraws WhatsApp for Diya: her phone gets no more WhatsApp, for either child.
        api.put("/api/me/privacy/children/" + diya + "/consents/WHATSAPP", parent.accessToken(), """
                {"given":false}""").andExpect(status().isOk());
        UUID second = queueAbout(arjun, "Arjun Sharma", Channel.WHATSAPP, "+91 " + ANITHA_PHONE, "student", "wa-2");
        Instant noon = daytime();
        dispatcher.dispatchSchool(school.tenantId(), noon);
        Map<String, Object> skipped = message(second);
        assertThat(skipped.get("status")).isEqualTo("SKIPPED");
        assertThat((String) skipped.get("last_error")).contains("SMS");
        dispatcher.dispatchSchool(school.tenantId(), noon);
        List<Map<String, Object>> sms = messagesWithKey("wa-2:sms");
        assertThat(sms).hasSize(1);
        assertThat(sms.getFirst().get("channel")).isEqualTo("SMS");
        assertThat(sms.getFirst().get("status")).isEqualTo("SIMULATED");
        assertThat(sms.getFirst().get("body")).isEqualTo(skipped.get("body"));

        // Farah's WhatsApp is not affected by Anitha's choice.
        api.post("/api/privacy/students/" + kabir + "/consents", admin.accessToken(), """
                {"givenByName":"Farah Khan","signedOn":"%s","photos":false,"whatsapp":true}""".formatted(today()))
                .andExpect(status().isCreated());
        UUID farah = queueAbout(kabir, "Kabir Khan", Channel.WHATSAPP, FARAH_PHONE, "student", "wa-3");
        dispatcher.dispatchSchool(school.tenantId(), noon);
        assertThat(message(farah).get("status")).isEqualTo("SIMULATED");

        // Giving it again brings WhatsApp back.
        api.put("/api/me/privacy/children/" + diya + "/consents/WHATSAPP", parent.accessToken(), """
                {"given":true}""").andExpect(status().isOk());
        UUID third = queueAbout(arjun, "Arjun Sharma", Channel.WHATSAPP, ANITHA_PHONE, "student", "wa-4");
        dispatcher.dispatchSchool(school.tenantId(), noon);
        assertThat(message(third).get("status")).isEqualTo("SIMULATED");
        assertThat(message(third).get("channel")).isEqualTo("WHATSAPP");
    }

    // ------------------------------------------------------------------ helpers

    private void erase(Session requester, String studentId, String admissionNo) throws Exception {
        String request = submit(requester, "ERASURE", "CHILD", studentId, "We have moved.");
        api.post("/api/students/" + studentId + "/leave", admin.accessToken(), """
                {"status":"TRANSFERRED","leftOn":"%s","reason":"Moved"}""".formatted(today()))
                .andExpect(status().isOk());
        api.post("/api/privacy/requests/" + request + "/erase", admin.accessToken(), """
                {"confirmAdmissionNo":"%s"}""".formatted(admissionNo)).andExpect(status().isOk());
    }

    private Session studentSignIn(String studentId, String who) throws Exception {
        api.post("/api/students/" + studentId + "/sign-in", admin.accessToken(), """
                {"mode":"CREATE","email":"%s","password":"%s"}""".formatted(email(who), TestApi.PASSWORD))
                .andExpect(status().isOk());
        return api.login(school.code(), email(who), TestApi.PASSWORD);
    }

    private org.springframework.test.web.servlet.ResultActions login(String who) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"schoolCode":"%s","email":"%s","password":"%s"}"""
                        .formatted(school.code(), email(who), TestApi.PASSWORD)));
    }

    /** Queues one absence alert the way the attendance module does, and returns its id. */
    private UUID queueAbout(String studentId, String name, Channel channel, String phone, String relatedType,
            String key) {
        TransactionTemplate tx = new TransactionTemplate(transactions);
        return TenantContext.runAs(school.tenantId(), () -> tx.execute(s -> {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("student", name);
            params.put("class", "Class 5 A");
            params.put("school", "Test School");
            params.put("date", today().toString());
            String address = phone.replace(" ", "");
            return queue.enqueue(MessageTemplates.ABSENCE_ALERT, "en", params,
                    new Related(relatedType, UUID.fromString(studentId), name + " (Class 5 A)"),
                    List.of(new Recipient(channel, address, "Guardian", Map.of("guardian", "Guardian"), null,
                            key + "-" + school.tenantId())))
                    .getFirst().messageId();
        }));
    }

    /** Noon India time tomorrow: outside quiet hours and after every message queued so far. */
    private static Instant daytime() {
        return ZonedDateTime.now(ZoneId.of("Asia/Kolkata")).plusDays(1).with(LocalTime.NOON).toInstant();
    }

    private long messageCount() throws Exception {
        try (Connection owner = ownerConnection(); PreparedStatement s = owner.prepareStatement(
                "select count(*) from notifications.message where tenant_id = ?")) {
            s.setObject(1, school.tenantId());
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private Map<String, Object> message(UUID id) throws Exception {
        List<Map<String, Object>> rows = rows("select * from notifications.message where id = ?", id);
        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }

    private List<Map<String, Object>> messagesWithKey(String suffix) throws Exception {
        return rows("select * from notifications.message where tenant_id = ? and dedupe_key like ?",
                school.tenantId(), "wa-2-%" + suffix.substring(suffix.indexOf(':')));
    }

    private List<Map<String, Object>> rows(String sql, Object... args) throws Exception {
        try (Connection owner = ownerConnection(); PreparedStatement s = owner.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                s.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = s.executeQuery()) {
                List<Map<String, Object>> out = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int c = 1; c <= rs.getMetaData().getColumnCount(); c++) {
                        Object value = rs.getObject(c);
                        row.put(rs.getMetaData().getColumnLabel(c), value == null ? null : value instanceof String
                                || value instanceof Number ? value : value.toString());
                    }
                    out.add(row);
                }
                return out;
            }
        }
    }
}
