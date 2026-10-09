package com.akshara.communication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

import com.akshara.notifications.MessageDispatcher;
import com.akshara.support.IntegrationTest;
import com.akshara.support.TestApi;
import com.akshara.support.TestApi.School;
import com.akshara.support.TestApi.Session;

/**
 * Circulars: who an audience reaches, the messages they queue, approval, teachers' limits, scheduling, withdrawing,
 * notice boards with read receipts, permissions and isolation between schools.
 */
class CircularsIT extends IntegrationTest {

    static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    @Autowired
    MessageDispatcher dispatcher;

    @Autowired
    CommunicationScheduler scheduler;

    private final LocalDate today = LocalDate.now(INDIA);
    private CommunicationSchool s;
    private Session admin;

    @BeforeEach
    void school() throws Exception {
        s = new CommunicationSchool(api, today);
        admin = s.admin;
    }

    @Test
    void audiencesCountEachPersonAndNumberOnce() throws Exception {
        // Asha's parents: Rekha (signed in, also Bala's mother) and Suresh. One SMS each.
        estimate(admin, audience("[]", "[\"" + s.s5A + "\"]", "[\"PARENT\"]"), "[\"SMS\"]")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parents").value(2))
                .andExpect(jsonPath("$.students").value(0))
                .andExpect(jsonPath("$.staff").value(0))
                .andExpect(jsonPath("$.inApp").value(1))
                .andExpect(jsonPath("$.phones").value(2))
                .andExpect(jsonPath("$.channels[0].channel").value("SMS"))
                .andExpect(jsonPath("$.channels[0].messages").value(2))
                .andExpect(jsonPath("$.smsParts").value(1))
                .andExpect(jsonPath("$.costPaise").value(40));
        // The whole class: both sections' parents and Asha, who has a sign-in.
        estimate(admin, audience("[\"" + s.class5 + "\"]", "[]", "[\"PARENT\",\"STUDENT\"]"),
                "[\"SMS\",\"WHATSAPP\",\"EMAIL\"]")
                .andExpect(jsonPath("$.parents").value(3))
                .andExpect(jsonPath("$.students").value(1))
                .andExpect(jsonPath("$.inApp").value(2))
                .andExpect(jsonPath("$.phones").value(3))
                .andExpect(jsonPath("$.emails").value(1))
                .andExpect(jsonPath("$.channels[*].channel", contains("SMS", "WHATSAPP", "EMAIL")))
                .andExpect(jsonPath("$.costPaise").value(3 * 20 + 3 * 12));
        // A staff role reaches the people with it; the whole school reaches everyone once.
        estimate(admin, audience("[]", "[]", "[\"TEACHER\"]"), "[]")
                .andExpect(jsonPath("$.staff").value(2))
                .andExpect(jsonPath("$.parents").value(0));
        estimate(admin, "{\"wholeSchool\":true}", "[]")
                .andExpect(jsonPath("$.staff").value(5))
                .andExpect(jsonPath("$.parents").value(4))
                .andExpect(jsonPath("$.students").value(1))
                .andExpect(jsonPath("$.inApp").value(5 + 2 + 1))
                .andExpect(jsonPath("$.phones").value(4));
        // Every parent in the school, by role alone.
        estimate(admin, audience("[]", "[]", "[\"PARENT\"]"), "[]").andExpect(jsonPath("$.parents").value(4));
        // Nothing chosen yet: nothing to estimate.
        estimate(admin, audience("[]", "[]", "[]"), "[]").andExpect(jsonPath("$.inApp").value(0));
        // Unknown or incomplete audiences are refused.
        estimate(admin, audience("[]", "[]", "[\"WIZARD\"]"), "[]")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.audience").exists());
        estimate(admin, audience("[]", "[\"" + UUID.randomUUID() + "\"]", "[\"PARENT\"]"), "[]")
                .andExpect(status().isBadRequest());
        estimate(admin, audience("[\"" + s.class5 + "\"]", "[]", "[]"), "[]")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.audience").exists());
        api.post("/api/notices", admin.accessToken(), circular("Hello", audience("[]", "[]", "[]"), "[]", null))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.audience").exists());
        api.post("/api/notices", admin.accessToken(), circular(" ", audience("[]", "[]", "[\"PARENT\"]"), "[]",
                null)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.title").exists());
    }

    @Test
    void channelsQueueOneSimulatedMessagePerNumberAndWaitForQuietHours() throws Exception {
        String id = create(admin, circular("Fee reminder", audience("[\"" + s.class5 + "\"]", "[]",
                "[\"PARENT\"]"), "[\"SMS\",\"WHATSAPP\",\"EMAIL\"]", null));
        api.post("/api/notices/" + id + "/submit", admin.accessToken(), "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SENT"))
                .andExpect(jsonPath("$.delivery.inApp").value(1))
                .andExpect(jsonPath("$.delivery.parents").value(3));
        // Rekha is the mother of two children in the audience: still one SMS, one WhatsApp and one email.
        api.get("/api/messages?relatedId=" + id + "&size=50", admin.accessToken())
                .andExpect(jsonPath("$.total").value(3 + 3 + 1))
                .andExpect(jsonPath("$.items[*].templateKey", hasItem("communication.circular")))
                .andExpect(jsonPath("$.items[*].status", hasItem("QUEUED")));
        api.get("/api/messages?relatedId=" + id + "&channel=SMS", admin.accessToken())
                .andExpect(jsonPath("$.total").value(3));

        // At 22:30 the school's quiet hours hold everything; in the morning the simulator sends it all.
        Instant late = today.atTime(22, 30).atZone(INDIA).toInstant();
        assertThat(dispatcher.dispatchSchool(s.school.tenantId(), late)).isZero();
        Instant morning = today.plusDays(1).atTime(10, 0).atZone(INDIA).toInstant();
        assertThat(dispatcher.dispatchSchool(s.school.tenantId(), morning)).isEqualTo(7);
        api.get("/api/notices/" + id, admin.accessToken())
                .andExpect(jsonPath("$.delivery.messages[0].channel").value("SMS"))
                .andExpect(jsonPath("$.delivery.messages[0].byStatus.SIMULATED").value(3))
                .andExpect(jsonPath("$.delivery.messages[2].channel").value("EMAIL"))
                .andExpect(jsonPath("$.delivery.messages[2].total").value(1));
        String body = TestApi.read(api.get("/api/messages/" + TestApi.read(api.get("/api/messages?relatedId=" + id
                + "&channel=SMS", admin.accessToken()), "$.items[0].id"), admin.accessToken()), "$.body");
        assertThat(body).startsWith("Circular from Test School " + s.school.code() + ": Fee reminder. ");
    }

    @Test
    void teachersCircularsWaitForThePrincipal() throws Exception {
        Session ravi = s.login("ravi");
        Session principal = s.login("principal");
        Session rekha = s.login("rekha");
        String id = create(ravi, circular("Class 5 A picnic", audience("[]", "[\"" + s.s5A + "\"]",
                "[\"PARENT\",\"STUDENT\"]"), "[\"SMS\"]", null));
        api.get("/api/notices/" + id, ravi.accessToken())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.needsApproval").value(true))
                .andExpect(jsonPath("$.actions.submit").value(true))
                .andExpect(jsonPath("$.actions.approve").value(false));
        api.post("/api/notices/" + id + "/submit", ravi.accessToken(), "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));
        // Nothing reaches families while it waits, and the teacher cannot approve it.
        api.get("/api/notices/board", rekha.accessToken()).andExpect(jsonPath("$.total").value(0));
        api.get("/api/messages?relatedId=" + id, admin.accessToken()).andExpect(jsonPath("$.total").value(0));
        api.post("/api/notices/" + id + "/approve", ravi.accessToken(), "{}").andExpect(status().isForbidden());
        api.put("/api/notices/" + id, ravi.accessToken(), circular("Changed", audience("[]", "[\"" + s.s5A + "\"]",
                "[\"PARENT\"]"), "[]", null)).andExpect(status().isConflict());

        // The principal sees it waiting, sends it back with a note, then approves the corrected version.
        api.get("/api/notices?status=PENDING_APPROVAL", principal.accessToken())
                .andExpect(jsonPath("$.items[*].id", contains(id)))
                .andExpect(jsonPath("$.counts.PENDING_APPROVAL").value(1));
        api.post("/api/notices/" + id + "/reject", principal.accessToken(), "{\"note\":\" \"}")
                .andExpect(status().isBadRequest());
        api.post("/api/notices/" + id + "/reject", principal.accessToken(), "{\"note\":\"Add the date.\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.reviewOutcome").value("REJECTED"))
                .andExpect(jsonPath("$.reviewNote").value("Add the date."));
        api.put("/api/notices/" + id, ravi.accessToken(), circular("Class 5 A picnic on Friday",
                audience("[]", "[\"" + s.s5A + "\"]", "[\"PARENT\",\"STUDENT\"]"), "[\"SMS\"]", null))
                .andExpect(status().isOk());
        api.post("/api/notices/" + id + "/submit", ravi.accessToken(), "")
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));
        api.post("/api/notices/" + id + "/approve", principal.accessToken(), "{\"note\":\"Fine.\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SENT"))
                .andExpect(jsonPath("$.reviewedByName").value("Lakshmi Iyer"))
                .andExpect(jsonPath("$.sentByName").value("Lakshmi Iyer"));
        api.post("/api/notices/" + id + "/approve", principal.accessToken(), "{}").andExpect(status().isConflict());
        api.get("/api/notices/board", rekha.accessToken())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.unread").value(1))
                .andExpect(jsonPath("$.items[0].title").value("Class 5 A picnic on Friday"));
        api.get("/api/messages?relatedId=" + id, admin.accessToken()).andExpect(jsonPath("$.total").value(2));
        api.get("/api/audit-events?limit=50", admin.accessToken())
                .andExpect(jsonPath("$[*].action", hasItems("circular.created", "circular.submitted",
                        "circular.rejected", "circular.approved", "circular.sent")));

        // With approval switched off, a teacher's circular goes straight out.
        api.put("/api/notices/settings", admin.accessToken(), """
                {"teacherCircularsNeedApproval":false,"enquiryAckEnabled":true,"enquiryAckChannel":"SMS"}""")
                .andExpect(status().isOk());
        String direct = create(ravi, circular("Homework", audience("[]", "[\"" + s.s5A + "\"]", "[\"PARENT\"]"),
                "[]", null));
        api.post("/api/notices/" + direct + "/submit", ravi.accessToken(), "")
                .andExpect(jsonPath("$.status").value("SENT"));
    }

    @Test
    void teachersAddressOnlyTheSectionsTheyTeach() throws Exception {
        Session ravi = s.login("ravi");
        Session sita = s.login("sita");
        api.get("/api/notices/audience-options", ravi.accessToken())
                .andExpect(jsonPath("$.canApprove").value(false))
                .andExpect(jsonPath("$.canAddressWholeSchool").value(false))
                .andExpect(jsonPath("$.needsApproval").value(true))
                .andExpect(jsonPath("$.classes.length()").value(1))
                .andExpect(jsonPath("$.classes[0].sections[*].label", contains("Class 5 A")))
                .andExpect(jsonPath("$.staffRoles.length()").value(0));
        for (String other : new String[] {audience("[]", "[\"" + s.s5B + "\"]", "[\"PARENT\"]"),
                audience("[\"" + s.class5 + "\"]", "[]", "[\"PARENT\"]"), "{\"wholeSchool\":true}",
                audience("[]", "[\"" + s.s5A + "\"]", "[\"PARENT\",\"TEACHER\"]"),
                audience("[]", "[]", "[\"PARENT\"]")}) {
            api.post("/api/notices", ravi.accessToken(), circular("Not mine", other, "[]", null))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.errors.audience").exists());
        }
        String own = create(ravi, circular("Mine", audience("[]", "[\"" + s.s5A + "\"]", "[\"PARENT\"]"), "[]",
                null));
        // Another teacher cannot see it; the principal can, and may address any section.
        api.get("/api/notices/" + own, sita.accessToken()).andExpect(status().isNotFound());
        api.get("/api/notices", sita.accessToken()).andExpect(jsonPath("$.items.length()").value(0));
        Session principal = s.login("principal");
        api.get("/api/notices/" + own, principal.accessToken()).andExpect(status().isOk());
        create(principal, circular("Any section", audience("[]", "[\"" + s.s5B + "\"]", "[\"PARENT\"]"), "[]",
                null));
        api.get("/api/notices/audience-options", principal.accessToken())
                .andExpect(jsonPath("$.canAddressWholeSchool").value(true))
                .andExpect(jsonPath("$.needsApproval").value(false))
                .andExpect(jsonPath("$.classes.length()").value(2))
                .andExpect(jsonPath("$.staffRoles[*].code", hasItems("TEACHER", "PRINCIPAL", "ACCOUNTANT")));
    }

    @Test
    void scheduledCircularsGoOutWhenTheirTimeComes() throws Exception {
        Session rekha = s.login("rekha");
        Instant now = Instant.now();
        api.post("/api/notices", admin.accessToken(), circular("Too late", audience("[]", "[\"" + s.s5A + "\"]",
                "[\"PARENT\"]"), "[]", now.minus(Duration.ofMinutes(5)).toString()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.scheduledAt").exists());
        Instant at = now.plus(Duration.ofHours(1)).truncatedTo(ChronoUnit.SECONDS);
        String id = create(admin, circular("Sports day", audience("[]", "[\"" + s.s5A + "\"]", "[\"PARENT\"]"),
                "[\"SMS\"]", at.toString()));
        api.post("/api/notices/" + id + "/submit", admin.accessToken(), "")
                .andExpect(jsonPath("$.status").value("SCHEDULED"))
                .andExpect(jsonPath("$.actions.cancel").value(true));
        api.get("/api/notices/board", rekha.accessToken()).andExpect(jsonPath("$.total").value(0));

        assertThat(scheduler.sendDueCirculars(s.school.tenantId(), now.plus(Duration.ofMinutes(30)))).isZero();
        assertThat(scheduler.sendDueCirculars(s.school.tenantId(), at.plusSeconds(60))).isEqualTo(1);
        assertThat(scheduler.sendDueCirculars(s.school.tenantId(), at.plusSeconds(120))).isZero();
        api.get("/api/notices/" + id, admin.accessToken())
                .andExpect(jsonPath("$.status").value("SENT"))
                .andExpect(jsonPath("$.sentByName").value("Asha Admin"));
        api.get("/api/notices/board", rekha.accessToken()).andExpect(jsonPath("$.total").value(1));
        api.get("/api/messages?relatedId=" + id, admin.accessToken()).andExpect(jsonPath("$.total").value(2));

        // A scheduled circular can be taken back to a draft before it goes.
        String later = create(admin, circular("Later", audience("[]", "[\"" + s.s5A + "\"]", "[\"PARENT\"]"), "[]",
                at.toString()));
        api.post("/api/notices/" + later + "/submit", admin.accessToken(), "")
                .andExpect(jsonPath("$.status").value("SCHEDULED"));
        api.post("/api/notices/" + later + "/cancel", admin.accessToken(), "")
                .andExpect(jsonPath("$.status").value("DRAFT"));
        assertThat(scheduler.sendDueCirculars(s.school.tenantId(), at.plusSeconds(300))).isZero();
    }

    @Test
    void withdrawingTakesACircularOffBoardsAndStopsWaitingMessages() throws Exception {
        Session rekha = s.login("rekha");
        String id = create(admin, circular("Wrong date", audience("[\"" + s.class5 + "\"]", "[]", "[\"PARENT\"]"),
                "[\"SMS\"]", null));
        api.post("/api/notices/" + id + "/submit", admin.accessToken(), "").andExpect(status().isOk());
        api.get("/api/notices/board", rekha.accessToken()).andExpect(jsonPath("$.total").value(1));
        // Sent circulars cannot be changed or deleted.
        api.put("/api/notices/" + id, admin.accessToken(), circular("Right date", audience("[\"" + s.class5
                + "\"]", "[]", "[\"PARENT\"]"), "[]", null)).andExpect(status().isConflict());
        api.delete("/api/notices/" + id, admin.accessToken()).andExpect(status().isConflict());
        api.post("/api/notices/" + id + "/withdraw", admin.accessToken(), "{\"reason\":\"\"}")
                .andExpect(status().isBadRequest());
        api.post("/api/notices/" + id + "/withdraw", admin.accessToken(), "{\"reason\":\"The date was wrong.\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WITHDRAWN"))
                .andExpect(jsonPath("$.withdrawReason").value("The date was wrong."))
                .andExpect(jsonPath("$.withdrawnByName").value("Asha Admin"));
        api.get("/api/notices/board", rekha.accessToken()).andExpect(jsonPath("$.total").value(0));
        api.get("/api/notices/board/" + id, rekha.accessToken()).andExpect(status().isNotFound());
        api.get("/api/messages?relatedId=" + id + "&status=SKIPPED", admin.accessToken())
                .andExpect(jsonPath("$.total").value(3));
        // A draft can be deleted.
        String draft = create(admin, circular("Draft", audience("[]", "[]", "[\"PARENT\"]"), "[]", null));
        api.delete("/api/notices/" + draft, admin.accessToken()).andExpect(status().isNoContent());
        api.get("/api/notices/" + draft, admin.accessToken()).andExpect(status().isNotFound());
    }

    @Test
    void boardsShowOnlyWhatIsAddressedToThePersonWithReadReceipts() throws Exception {
        Session rekha = s.login("rekha");
        Session uma = s.login("uma");
        Session asha = s.login("asha");
        Session ravi = s.login("ravi");
        String classFive = send(circular("Class 5 parents", audience("[\"" + s.class5 + "\"]", "[]",
                "[\"PARENT\"]"), "[]", null));
        String classSix = send(circular("Class 6 parents and students", audience("[]", "[\"" + s.s6A + "\"]",
                "[\"PARENT\",\"STUDENT\"]"), "[]", null));
        String urgent = send("{\"title\":\"Closed tomorrow\",\"body\":\"Heavy rain.\\nStay safe.\","
                + "\"category\":\"URGENT\",\"audience\":{\"wholeSchool\":true}}");

        api.get("/api/notices/board", rekha.accessToken())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[*].id", contains(urgent, classFive)))
                .andExpect(jsonPath("$.items[0].pinned").value(true))
                .andExpect(jsonPath("$.items[0].body").value("Heavy rain.\nStay safe."))
                .andExpect(jsonPath("$.items[1].pinned").value(false));
        api.get("/api/notices/board", uma.accessToken())
                .andExpect(jsonPath("$.items[*].id", containsInAnyOrder(urgent, classSix)));
        api.get("/api/notices/board", asha.accessToken())
                .andExpect(jsonPath("$.items[*].id", contains(urgent)));
        api.get("/api/notices/board", ravi.accessToken())
                .andExpect(jsonPath("$.items[*].id", contains(urgent)));
        api.get("/api/notices/board/" + classSix, rekha.accessToken()).andExpect(status().isNotFound());

        api.post("/api/notices/board/" + classFive + "/read", rekha.accessToken(), "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.read").value(true));
        api.post("/api/notices/board/" + classFive + "/read", rekha.accessToken(), "").andExpect(status().isOk());
        api.get("/api/notices/board?unreadOnly=true", rekha.accessToken())
                .andExpect(jsonPath("$.items[*].id", contains(urgent)))
                .andExpect(jsonPath("$.unread").value(1));
        api.get("/api/notices/" + classFive, admin.accessToken())
                .andExpect(jsonPath("$.delivery.inApp").value(1))
                .andExpect(jsonPath("$.delivery.read").value(1))
                .andExpect(jsonPath("$.delivery.readPercent").value(100.0));
        api.get("/api/notices", admin.accessToken())
                .andExpect(jsonPath("$.items[?(@.id == '" + classFive + "')].read", contains(1)));
        api.post("/api/notices/board/read-all", rekha.accessToken(), "")
                .andExpect(jsonPath("$.marked").value(1));
        api.get("/api/notices/board", rekha.accessToken()).andExpect(jsonPath("$.unread").value(0));

        // Another school sees none of it.
        School other = api.signup();
        Session otherAdmin = api.login(other);
        api.get("/api/notices/" + classFive, otherAdmin.accessToken()).andExpect(status().isNotFound());
        api.get("/api/notices/board/" + classFive, otherAdmin.accessToken()).andExpect(status().isNotFound());
        api.post("/api/notices/board/" + classFive + "/read", otherAdmin.accessToken(), "")
                .andExpect(status().isNotFound());
        api.post("/api/notices/" + classFive + "/withdraw", otherAdmin.accessToken(), "{\"reason\":\"x\"}")
                .andExpect(status().isNotFound());
        api.get("/api/notices", otherAdmin.accessToken()).andExpect(jsonPath("$.items.length()").value(0));
        api.get("/api/notices/board", otherAdmin.accessToken()).andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void onlyPeopleWhoMaySendCanWriteCirculars() throws Exception {
        Session accounts = s.login("accounts");
        Session rekha = s.login("rekha");
        Session asha = s.login("asha");
        String body = circular("Hi", audience("[]", "[]", "[\"PARENT\"]"), "[]", null);
        for (Session who : new Session[] {accounts, rekha, asha}) {
            api.post("/api/notices", who.accessToken(), body).andExpect(status().isForbidden());
            api.get("/api/notices", who.accessToken()).andExpect(status().isForbidden());
            api.get("/api/notices/audience-options", who.accessToken()).andExpect(status().isForbidden());
            estimate(who, audience("[]", "[]", "[\"PARENT\"]"), "[]").andExpect(status().isForbidden());
            // Everyone has a notice board.
            api.get("/api/notices/board", who.accessToken()).andExpect(status().isOk());
        }
        String id = create(admin, body);
        api.post("/api/notices/" + id + "/approve", rekha.accessToken(), "{}").andExpect(status().isForbidden());
        api.post("/api/notices/" + id + "/approve", s.login("ravi").accessToken(), "{}")
                .andExpect(status().isForbidden());
        api.get("/api/notices/settings", s.login("principal").accessToken()).andExpect(status().isForbidden());
        api.get("/api/notices/settings", admin.accessToken())
                .andExpect(jsonPath("$.teacherCircularsNeedApproval").value(true))
                .andExpect(jsonPath("$.enquiryAckChannel").value("SMS"));
    }

    // ------------------------------------------------------------------ helpers

    private String send(String json) throws Exception {
        String id = create(admin, json);
        api.post("/api/notices/" + id + "/submit", admin.accessToken(), "")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SENT"));
        return id;
    }

    private String create(Session who, String json) throws Exception {
        return TestApi.read(api.post("/api/notices", who.accessToken(), json).andExpect(status().isCreated()),
                "$.id");
    }

    private ResultActions estimate(Session who, String audience,
            String channels) throws Exception {
        return api.post("/api/notices/estimate", who.accessToken(), """
                {"title":"Sports day","body":"Sports day is on Friday.","audience":%s,"channels":%s}"""
                .formatted(audience, channels));
    }

    static String audience(String classIds, String sectionIds, String roles) {
        return "{\"classIds\":%s,\"sectionIds\":%s,\"roles\":%s}".formatted(classIds, sectionIds, roles);
    }

    static String circular(String title, String audience, String channels, String scheduledAt) {
        return """
                {"title":"%s","body":"Dear parents,\\nPlease note.","category":"GENERAL","audience":%s,
                 "channels":%s,"scheduledAt":%s}""".formatted(title, audience, channels,
                scheduledAt == null ? "null" : "\"" + scheduledAt + "\"");
    }
}
