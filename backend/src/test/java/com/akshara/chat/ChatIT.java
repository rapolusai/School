package com.akshara.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.DayOfWeek;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.akshara.support.TestApi;
import com.akshara.support.TestApi.School;
import com.akshara.support.TestApi.Session;

/** Parent-teacher messages through the API: who can reach whom, reading, deleting, oversight and safety. */
class ChatIT extends ChatTestBase {

    @Test
    void aParentWritesToTheClassTeacherAndTheTeacherReplies() throws Exception {
        World w = world();
        api.get("/api/me/chat/contacts", w.lata().accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contacts.length()").value(2))
                .andExpect(jsonPath("$.contacts[0].personName").value("Ravi Kumar"))
                .andExpect(jsonPath("$.contacts[0].detail").value("Class teacher"))
                .andExpect(jsonPath("$.contacts[0].studentName").value("Asha Rao"))
                .andExpect(jsonPath("$.contacts[0].classLabel").value("Class 5 A"))
                .andExpect(jsonPath("$.contacts[1].personName").value("Meera Das"))
                .andExpect(jsonPath("$.contacts[1].detail").value("Science"))
                .andExpect(content().string(not(containsString("@"))));

        String id = TestApi.read(api.post("/api/me/chat/conversations", w.lata().accessToken(), """
                {"studentId":"%s","teacherUserId":"%s"}""".formatted(w.asha(), w.raviId()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mySide").value("PARENT"))
                .andExpect(jsonPath("$.staffName").value("Ravi Kumar"))
                .andExpect(jsonPath("$.staffDetail").value("Class teacher"))
                .andExpect(jsonPath("$.readOnly").value(false))
                .andExpect(jsonPath("$.messages").isEmpty()), "$.id");
        // Starting again opens the same conversation.
        api.post("/api/me/chat/conversations", w.lata().accessToken(), """
                {"studentId":"%s","teacherUserId":"%s"}""".formatted(w.asha(), w.raviId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));
        // Ravi does not see it until Lata writes.
        api.get("/api/chat/conversations", w.ravi().accessToken()).andExpect(jsonPath("$").isEmpty());

        send(w.lata(), true, id, "Hello Sir, how is Asha doing in Maths?")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message.mine").value(true))
                .andExpect(jsonPath("$.message.side").value("PARENT"))
                .andExpect(jsonPath("$.message.senderName").value("Lata Rao"))
                .andExpect(jsonPath("$.message.canDelete").value(true))
                .andExpect(jsonPath("$.message.readAt").value(nullValue()))
                .andExpect(jsonPath("$.staffAvailability.days").isArray());
        api.get("/api/chat/unread", w.ravi().accessToken())
                .andExpect(jsonPath("$.unread").value(1))
                .andExpect(jsonPath("$.conversations").value(1));
        api.get("/api/chat/conversations", w.ravi().accessToken())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].withName").value("Lata Rao"))
                .andExpect(jsonPath("$[0].student.name").value("Asha Rao"))
                .andExpect(jsonPath("$[0].student.classLabel").value("Class 5 A"))
                .andExpect(jsonPath("$[0].unread").value(1))
                .andExpect(jsonPath("$[0].last.text").value("Hello Sir, how is Asha doing in Maths?"))
                .andExpect(jsonPath("$[0].last.mine").value(false))
                .andExpect(content().string(not(containsString(LATA_PHONE))))
                .andExpect(content().string(not(containsString("@"))));

        // Opening it marks Lata's message read: her read receipt.
        api.get("/api/chat/conversations/" + id, w.ravi().accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mySide").value("STAFF"))
                .andExpect(jsonPath("$.parentName").value("Lata Rao"))
                .andExpect(jsonPath("$.messages[0].mine").value(false))
                .andExpect(content().string(not(containsString(LATA_PHONE))));
        api.get("/api/chat/unread", w.ravi().accessToken()).andExpect(jsonPath("$.unread").value(0));
        String serverTime = TestApi.read(api.get("/api/me/chat/conversations/" + id, w.lata().accessToken())
                .andExpect(jsonPath("$.messages[0].readAt").isNotEmpty()), "$.serverTime");

        // Ravi replies with an attachment; Lata's open chat picks it up and she can download the file.
        send(w.ravi(), false, id, "She is doing well. Here is her worksheet.", "worksheet.txt")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message.attachments[0].name").value("worksheet.txt"));
        api.get("/api/me/chat/unread", w.lata().accessToken()).andExpect(jsonPath("$.unread").value(1));
        String file = TestApi.read(api.get("/api/me/chat/conversations/" + id + "/changes?since=" + serverTime,
                w.lata().accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages[?(@.side == 'STAFF')].body")
                        .value(org.hamcrest.Matchers.contains("She is doing well. Here is her worksheet."))),
                "$.messages[?(@.side == 'STAFF')].attachments[0].id").replaceAll("[\\[\\]\"]", "");
        api.get("/api/me/chat/unread", w.lata().accessToken()).andExpect(jsonPath("$.unread").value(0));
        api.get("/api/files/" + file, w.lata().accessToken()).andExpect(status().isOk());
        api.get("/api/files/" + file, w.ravi().accessToken()).andExpect(status().isOk());
        api.get("/api/files/" + file, w.kamala().accessToken()).andExpect(status().isNotFound());
        api.get("/api/files/" + file, w.accountant().accessToken()).andExpect(status().isNotFound());
        api.get("/api/files/" + file, w.principal().accessToken()).andExpect(status().isOk());
        api.get("/api/me/chat/conversations", w.lata().accessToken())
                .andExpect(jsonPath("$[0].withName").value("Ravi Kumar"))
                .andExpect(jsonPath("$[0].withDetail").value("Class teacher"))
                .andExpect(jsonPath("$[0].last.attachments").value(1))
                .andExpect(content().string(not(containsString("@"))));
    }

    @Test
    void aParentReachesOnlyTheirOwnChildsTeachers() throws Exception {
        World w = world();
        // Another family's child, a teacher who does not teach Asha, someone who is not a teacher.
        api.post("/api/me/chat/conversations", w.lata().accessToken(), """
                {"studentId":"%s","teacherUserId":"%s"}""".formatted(w.bala(), w.meeraId()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.studentId").exists());
        api.post("/api/me/chat/conversations", w.lata().accessToken(), """
                {"studentId":"%s","teacherUserId":"%s"}""".formatted(w.asha(), w.sunilId()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.teacherUserId").exists());
        api.post("/api/me/chat/conversations", w.lata().accessToken(), """
                {"studentId":"%s","teacherUserId":"%s"}""".formatted(w.asha(), w.kamalaId()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.teacherUserId").exists());
        api.post("/api/me/chat/conversations", w.lata().accessToken(), """
                {"studentId":"%s"}""".formatted(w.asha()))
                .andExpect(status().isBadRequest());
        String id = lataTo(w, w.raviId());
        send(w.lata(), true, id, "Hello").andExpect(status().isCreated());
        // Nobody else sees it: not another parent, not another teacher, and not through the other side's API.
        api.get("/api/me/chat/conversations/" + id, w.kamala().accessToken()).andExpect(status().isNotFound());
        api.get("/api/chat/conversations/" + id, w.meera().accessToken()).andExpect(status().isNotFound());
        send(w.kamala(), true, id, "Hi").andExpect(status().isNotFound());
        api.get("/api/me/chat/conversations/" + UUID.randomUUID(), w.lata().accessToken())
                .andExpect(status().isNotFound());
        // Parents have no staff messages; staff without chat.use have none either.
        api.get("/api/chat/conversations", w.lata().accessToken()).andExpect(status().isForbidden());
        api.get("/api/chat/conversations", w.accountant().accessToken()).andExpect(status().isForbidden());
        api.get("/api/me/chat/conversations", w.accountant().accessToken()).andExpect(status().isForbidden());
    }

    @Test
    void aTeacherReachesOnlyParentsOfStudentsTheyTeach() throws Exception {
        World w = world();
        api.get("/api/chat/contacts", w.ravi().accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sections.length()").value(1))
                .andExpect(jsonPath("$.sections[0].label").value("Class 5 A"))
                .andExpect(jsonPath("$.sectionId").value(w.sectionA()))
                .andExpect(jsonPath("$.contacts[*].personName", org.hamcrest.Matchers.containsInAnyOrder(
                        "Lata Rao", "Priya Nair")))
                .andExpect(content().string(not(containsString(LATA_PHONE))));
        api.get("/api/chat/contacts?sectionId=" + w.sectionB(), w.ravi().accessToken())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.sectionId").exists());
        // Meera teaches Science in 5 A and leads 5 B.
        api.get("/api/chat/contacts", w.meera().accessToken()).andExpect(jsonPath("$.sections.length()").value(2));
        api.get("/api/chat/contacts", w.sunil().accessToken())
                .andExpect(jsonPath("$.sections").isEmpty())
                .andExpect(jsonPath("$.contacts").isEmpty());

        api.post("/api/chat/conversations", w.ravi().accessToken(), """
                {"studentId":"%s","parentUserId":"%s"}""".formatted(w.bala(), w.kamalaId()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.studentId").exists());
        api.post("/api/chat/conversations", w.ravi().accessToken(), """
                {"studentId":"%s","parentUserId":"%s"}""".formatted(w.asha(), w.kamalaId()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.parentUserId").exists());
        api.post("/api/chat/conversations", w.ravi().accessToken(), """
                {"studentId":"%s","parentUserId":"%s"}""".formatted(UUID.randomUUID(), w.lataId()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.studentId").exists());
        String id = TestApi.read(api.post("/api/chat/conversations", w.ravi().accessToken(), """
                {"studentId":"%s","parentUserId":"%s"}""".formatted(w.asha(), w.lataId()))
                .andExpect(status().isCreated()), "$.id");
        api.get("/api/me/chat/conversations", w.lata().accessToken()).andExpect(jsonPath("$").isEmpty());
        send(w.ravi(), false, id, "Asha did very well in the quiz today.").andExpect(status().isCreated());
        api.get("/api/me/chat/conversations", w.lata().accessToken())
                .andExpect(jsonPath("$[0].id").value(id))
                .andExpect(jsonPath("$[0].unread").value(1));
        // The Principal (chat.oversee) can write to any parent.
        api.post("/api/chat/conversations", w.principal().accessToken(), """
                {"studentId":"%s","parentUserId":"%s"}""".formatted(w.bala(), w.kamalaId()))
                .andExpect(status().isCreated());
        api.get("/api/chat/contacts?sectionId=" + w.sectionB(), w.principal().accessToken())
                .andExpect(jsonPath("$.contacts[0].personName").value("Kamala Devi"));
    }

    @Test
    void theSenderCanDeleteForBothWithinTenMinutes() throws Exception {
        World w = world();
        String id = lataTo(w, w.raviId());
        String message = TestApi.read(send(w.lata(), true, id, "Sorry, wrong child").andExpect(status().isCreated()),
                "$.message.id");
        api.delete("/api/chat/conversations/" + id + "/messages/" + message, w.ravi().accessToken())
                .andExpect(status().isForbidden());
        api.delete("/api/me/chat/conversations/" + id + "/messages/" + message, w.lata().accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true))
                .andExpect(jsonPath("$.body").value(nullValue()));
        api.delete("/api/me/chat/conversations/" + id + "/messages/" + message, w.lata().accessToken())
                .andExpect(status().isConflict());
        api.delete("/api/me/chat/conversations/" + id + "/messages/" + UUID.randomUUID(), w.lata().accessToken())
                .andExpect(status().isNotFound());
        // The placeholder stays for both; the text does not.
        api.get("/api/chat/conversations/" + id, w.ravi().accessToken())
                .andExpect(jsonPath("$.messages.length()").value(1))
                .andExpect(jsonPath("$.messages[0].deleted").value(true))
                .andExpect(content().string(not(containsString("wrong child"))));
        api.get("/api/chat/unread", w.ravi().accessToken()).andExpect(jsonPath("$.unread").value(0));

        // After ten minutes it stays.
        String old = TestApi.read(send(w.lata(), true, id, "An older message").andExpect(status().isCreated()),
                "$.message.id");
        try (Connection owner = ownerConnection();
                PreparedStatement s = owner.prepareStatement(
                        "update chat.message set created_at = now() - interval '11 minutes' where id = ?")) {
            s.setObject(1, UUID.fromString(old));
            s.executeUpdate();
        }
        api.delete("/api/me/chat/conversations/" + id + "/messages/" + old, w.lata().accessToken())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors.messageId").value(containsString("10 minutes")));
        api.get("/api/audit-events?limit=200", w.admin().accessToken())
                .andExpect(jsonPath("$[?(@.action == 'chat_message.deleted')]", hasSize(1)))
                .andExpect(jsonPath("$[?(@.action == 'chat_message.sent')]", hasSize(2)))
                .andExpect(content().string(not(containsString("wrong child"))));
    }

    @Test
    void oversightReadsEveryConversationAndEachReadingIsAudited() throws Exception {
        World w = world();
        String id = lataTo(w, w.raviId());
        String message = TestApi.read(send(w.lata(), true, id, "Original text").andExpect(status().isCreated()),
                "$.message.id");
        api.delete("/api/me/chat/conversations/" + id + "/messages/" + message, w.lata().accessToken())
                .andExpect(status().isOk());
        send(w.ravi(), false, id, "Noted.").andExpect(status().isCreated());

        // The list has no content and is not audited.
        api.get("/api/chat/oversight/conversations", w.principal().accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].parentName").value("Lata Rao"))
                .andExpect(jsonPath("$.items[0].staffName").value("Ravi Kumar"))
                .andExpect(jsonPath("$.items[0].messageCount").value(2))
                .andExpect(content().string(not(containsString("Original text"))))
                .andExpect(content().string(not(containsString("Noted."))));
        api.get("/api/chat/oversight/conversations?filter=nonsense", w.principal().accessToken())
                .andExpect(status().isBadRequest());
        api.get("/api/chat/oversight/conversations?q=kamala", w.principal().accessToken())
                .andExpect(jsonPath("$.total").value(0));
        // Reading shows deleted messages as they were, and is audited every time.
        for (int i = 0; i < 2; i++) {
            api.get("/api/chat/oversight/conversations/" + id, w.principal().accessToken())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messages[0].body").value("Original text"))
                    .andExpect(jsonPath("$.messages[0].deleted").value(true))
                    .andExpect(jsonPath("$.messages[1].body").value("Noted."))
                    .andExpect(content().string(not(containsString(LATA_PHONE))));
        }
        api.get("/api/audit-events?limit=200", w.admin().accessToken())
                .andExpect(jsonPath("$[?(@.action == 'chat_conversation.oversight_read')]", hasSize(2)));
        // Teachers and parents have no oversight.
        api.get("/api/chat/oversight/conversations", w.ravi().accessToken()).andExpect(status().isForbidden());
        api.get("/api/chat/oversight/conversations/" + id, w.lata().accessToken()).andExpect(status().isForbidden());
        api.get("/api/chat/oversight/conversations/" + UUID.randomUUID(), w.principal().accessToken())
                .andExpect(status().isNotFound());

        // Closing makes it read-only for both; reopening opens it again.
        api.post("/api/chat/oversight/conversations/" + id + "/close", w.principal().accessToken(), "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.reason").exists());
        api.post("/api/chat/oversight/conversations/" + id + "/close", w.principal().accessToken(), """
                {"reason":"Please speak to the office instead."}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.closed").value(true))
                .andExpect(jsonPath("$.readOnlyReason").value("CLOSED"));
        api.post("/api/chat/oversight/conversations/" + id + "/close", w.principal().accessToken(), """
                {"reason":"Again"}""").andExpect(status().isConflict());
        send(w.lata(), true, id, "Hello?").andExpect(status().isConflict());
        api.get("/api/me/chat/conversations/" + id, w.lata().accessToken())
                .andExpect(jsonPath("$.readOnly").value(true))
                .andExpect(jsonPath("$.readOnlyReason").value("CLOSED"))
                .andExpect(jsonPath("$.closeReason").value("Please speak to the office instead."));
        api.post("/api/chat/oversight/conversations/" + id + "/reopen", w.principal().accessToken(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.closed").value(false));
        api.post("/api/chat/oversight/conversations/" + id + "/reopen", w.principal().accessToken(), null)
                .andExpect(status().isConflict());
        send(w.lata(), true, id, "Thank you.").andExpect(status().isCreated());
    }

    @Test
    void reportsGoToThePrincipal() throws Exception {
        World w = world();
        String id = lataTo(w, w.raviId());
        send(w.lata(), true, id, "Hello").andExpect(status().isCreated());
        api.post("/api/me/chat/conversations/" + id + "/reports", w.lata().accessToken(), """
                {"reason":""}""").andExpect(status().isBadRequest());
        api.post("/api/me/chat/conversations/" + id + "/reports", w.lata().accessToken(), """
                {"reason":"The reply was rude."}""")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.reporterSide").value("PARENT"));
        api.post("/api/me/chat/conversations/" + id + "/reports", w.lata().accessToken(), """
                {"reason":"Again"}""").andExpect(status().isConflict());
        api.get("/api/me/chat/conversations/" + id, w.lata().accessToken())
                .andExpect(jsonPath("$.reportedByMe").value(true));
        api.post("/api/chat/conversations/" + id + "/reports", w.ravi().accessToken(), """
                {"reason":"The parent keeps writing at night."}""").andExpect(status().isCreated());

        String report = TestApi.read(api.get("/api/chat/oversight/reports", w.principal().accessToken())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].reason").value("The reply was rude.")), "$[1].id");
        api.get("/api/chat/oversight/conversations?filter=reported", w.principal().accessToken())
                .andExpect(jsonPath("$.items[0].openReports").value(2))
                .andExpect(jsonPath("$.openReports").value(2));
        api.post("/api/chat/oversight/reports/" + report + "/review", w.principal().accessToken(), """
                {"note":"Spoke to both."}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVIEWED"))
                .andExpect(jsonPath("$.reviewedByName").value("Lakshmi Iyer"));
        api.post("/api/chat/oversight/reports/" + report + "/review", w.principal().accessToken(), "{}")
                .andExpect(status().isConflict());
        api.get("/api/chat/oversight/reports", w.principal().accessToken()).andExpect(jsonPath("$.length()").value(1));
        api.get("/api/chat/oversight/reports?status=all", w.principal().accessToken())
                .andExpect(jsonPath("$.length()").value(2));
        api.post("/api/chat/oversight/reports/" + UUID.randomUUID() + "/review", w.principal().accessToken(), "{}")
                .andExpect(status().isNotFound());
    }

    @Test
    void messagesHaveLimits() throws Exception {
        World w = world();
        String id = lataTo(w, w.raviId());
        send(w.lata(), true, id, null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.body").exists());
        send(w.lata(), true, id, "   ").andExpect(status().isBadRequest());
        char[] tooLong = new char[ChatRules.MAX_LENGTH + 1];
        Arrays.fill(tooLong, 'a');
        send(w.lata(), true, id, new String(tooLong)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.body").exists());
        send(w.lata(), true, id, "Four files", "a.txt", "b.txt", "c.txt", "d.txt").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.files").exists());
        send(w.lata(), true, id, null, "a.txt", "b.txt", "c.txt").andExpect(status().isCreated())
                .andExpect(jsonPath("$.message.attachments.length()").value(3));
        // A file that is not what its name says is refused (type and size are checked).
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .multipart("/api/me/chat/conversations/" + id + "/messages")
                .file(new org.springframework.mock.web.MockMultipartFile("files", "photo.png", "image/png",
                        "not a picture".getBytes()))
                .header("Authorization", "Bearer " + w.lata().accessToken()))
                .andExpect(status().isBadRequest());
        // About 20 messages an hour per person.
        for (int i = 2; i <= ChatRules.MAX_PER_HOUR; i++) {
            send(w.lata(), true, id, "Message " + i).andExpect(status().isCreated());
        }
        send(w.lata(), true, id, "One too many").andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.errors.body").value(containsString("Wait")));
        // The teacher still can.
        send(w.ravi(), false, id, "Please call the office.").andExpect(status().isCreated());
    }

    @Test
    void outsideTheTeachersHoursTheNoticeWaitsAndTheParentIsTold() throws Exception {
        World w = world();
        // Every day but today, so it is outside the hours now.
        DayOfWeek today = today().getDayOfWeek();
        String days = String.join(",", Arrays.stream(DayOfWeek.values()).filter(d -> d != today)
                .map(d -> "\"" + d + "\"").toList());
        api.put("/api/chat/settings", w.ravi().accessToken(), """
                {"availableDays":[%s],"availableFrom":"08:00","availableTo":"18:00","notifyNewMessages":true}"""
                .formatted(days)).andExpect(status().isForbidden());
        api.put("/api/chat/settings", w.principal().accessToken(), """
                {"availableDays":[%s],"availableFrom":"18:00","availableTo":"08:00","notifyNewMessages":true}"""
                .formatted(days)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.availableTo").exists());
        api.put("/api/chat/settings", w.principal().accessToken(), """
                {"availableDays":[],"availableFrom":"08:00","availableTo":"18:00","notifyNewMessages":true}""")
                .andExpect(status().isBadRequest());
        api.put("/api/chat/settings", w.principal().accessToken(), """
                {"availableDays":[%s],"availableFrom":"08:00","availableTo":"18:00","notifyNewMessages":true}"""
                .formatted(days)).andExpect(status().isOk())
                .andExpect(jsonPath("$.availableDays.length()").value(6));
        api.get("/api/chat/settings", w.ravi().accessToken()).andExpect(jsonPath("$.availableDays.length()").value(6));

        String id = lataTo(w, w.raviId());
        send(w.lata(), true, id, "Can we meet tomorrow?")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.staffAvailability.openNow").value(false))
                .andExpect(jsonPath("$.staffAvailability.nextOpenAt").isNotEmpty());
        send(w.lata(), true, id, "Any time after 10 works.").andExpect(status().isCreated());
        // One notice for the run of unread messages, by email to the teacher, held until his next working hour.
        String nextAttempt = TestApi.read(api.get("/api/messages?relatedId=" + id, w.admin().accessToken())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].templateKey").value("chat.to_staff"))
                .andExpect(jsonPath("$.items[0].channel").value("EMAIL"))
                .andExpect(jsonPath("$.items[0].status").value("QUEUED"))
                .andExpect(jsonPath("$.items[0].relatedLabel").value("Asha Rao")), "$.items[0].nextAttemptAt");
        assertThat(Instant.parse(nextAttempt)).isAfter(Instant.now().plusSeconds(60));
        String notice = TestApi.read(api.get("/api/messages?relatedId=" + id, w.admin().accessToken()),
                "$.items[0].id");
        api.get("/api/messages/" + notice, w.admin().accessToken())
                .andExpect(jsonPath("$.body").value(containsString("Lata Rao")))
                .andExpect(jsonPath("$.body").value(not(containsString("tomorrow"))));
        // Reading it in the app drops the notice still waiting.
        api.get("/api/chat/conversations/" + id, w.ravi().accessToken()).andExpect(status().isOk());
        api.get("/api/messages?relatedId=" + id + "&status=SKIPPED", w.admin().accessToken())
                .andExpect(jsonPath("$.total").value(1));
        // A reply notifies the parent on the school's alert channel, straight away (quiet hours are the
        // dispatcher's).
        send(w.ravi(), false, id, "Yes, 10:30 in the staff room.").andExpect(status().isCreated());
        api.get("/api/messages?relatedId=" + id + "&status=QUEUED", w.admin().accessToken())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].templateKey").value("chat.to_parent"))
                .andExpect(jsonPath("$.items[0].recipient").value(not(LATA_PHONE)));
        // Notices can be switched off; the unread count still moves.
        api.put("/api/chat/settings", w.principal().accessToken(), """
                {"availableDays":[%s],"availableFrom":"08:00","availableTo":"18:00","notifyNewMessages":false}"""
                .formatted(days)).andExpect(status().isOk());
        api.get("/api/me/chat/conversations/" + id, w.lata().accessToken()).andExpect(status().isOk());
        send(w.ravi(), false, id, "See you.").andExpect(status().isCreated());
        api.get("/api/messages?relatedId=" + id + "&status=QUEUED", w.admin().accessToken())
                .andExpect(jsonPath("$.total").value(0));
        api.get("/api/me/chat/unread", w.lata().accessToken()).andExpect(jsonPath("$.unread").value(1));
    }

    @Test
    void aTeacherCanBeAway() throws Exception {
        World w = world();
        api.get("/api/chat/away", w.ravi().accessToken()).andExpect(jsonPath("$.away").value(false));
        api.put("/api/chat/away", w.ravi().accessToken(), """
                {"returnsOn":"%s"}""".formatted(today())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.returnsOn").exists());
        api.put("/api/chat/away", w.ravi().accessToken(), """
                {"returnsOn":"%s"}""".formatted(today().plusDays(91))).andExpect(status().isBadRequest());
        api.put("/api/chat/away", w.ravi().accessToken(), """
                {"returnsOn":"%s","note":"At a training in Pune."}""".formatted(today().plusDays(3)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.away").value(true));
        api.get("/api/me/chat/contacts", w.lata().accessToken())
                .andExpect(jsonPath("$.contacts[0].awayUntil").value(today().plusDays(3).toString()));
        String id = lataTo(w, w.raviId());
        api.get("/api/me/chat/conversations/" + id, w.lata().accessToken())
                .andExpect(jsonPath("$.staffAvailability.openNow").value(false))
                .andExpect(jsonPath("$.staffAvailability.awayUntil").value(today().plusDays(3).toString()))
                .andExpect(jsonPath("$.staffAvailability.awayNote").value("At a training in Pune."));
        send(w.lata(), true, id, "Hello").andExpect(status().isCreated());
        String nextAttempt = TestApi.read(api.get("/api/messages?relatedId=" + id, w.admin().accessToken()),
                "$.items[0].nextAttemptAt");
        assertThat(Instant.parse(nextAttempt)).isAfter(today().plusDays(3).atStartOfDay(ChatRules.INDIA)
                .toInstant().minusSeconds(1));
        api.get("/api/me/chat/conversations", w.lata().accessToken()).andExpect(jsonPath("$[0].away").value(true));
        api.put("/api/chat/away", w.ravi().accessToken(), "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.away").value(false));
        api.put("/api/chat/away", w.lata().accessToken(), "{}").andExpect(status().isForbidden());
    }

    @Test
    void aConversationIsReadOnlyOnceTheTeacherOrTheStudentLeaves() throws Exception {
        World w = world();
        String id = lataTo(w, w.raviId());
        send(w.lata(), true, id, "Hello").andExpect(status().isCreated());
        api.post("/api/staff/" + w.raviId() + "/leaving", w.admin().accessToken(), """
                {"leftOn":"%s","reason":"Moved to another city"}""".formatted(today())).andExpect(status().isOk());
        api.get("/api/me/chat/conversations/" + id, w.lata().accessToken())
                .andExpect(jsonPath("$.readOnly").value(true))
                .andExpect(jsonPath("$.readOnlyReason").value("STAFF_LEFT"))
                .andExpect(jsonPath("$.messages.length()").value(1));
        send(w.lata(), true, id, "Are you there?").andExpect(status().isConflict());
        api.get("/api/me/chat/contacts", w.lata().accessToken())
                .andExpect(jsonPath("$.contacts.length()").value(1))
                .andExpect(jsonPath("$.contacts[0].personName").value("Meera Das"));

        String kamalaId = TestApi.read(api.post("/api/me/chat/conversations", w.kamala().accessToken(), """
                {"studentId":"%s","teacherUserId":"%s"}""".formatted(w.bala(), w.meeraId()))
                .andExpect(status().isCreated()), "$.id");
        send(w.kamala(), true, kamalaId, "Hello Madam").andExpect(status().isCreated());
        api.post("/api/students/" + w.bala() + "/leave", w.admin().accessToken(), """
                {"status":"TRANSFERRED","leftOn":"%s","reason":"Family moved"}""".formatted(today()))
                .andExpect(status().isOk());
        api.get("/api/chat/conversations/" + kamalaId, w.meera().accessToken())
                .andExpect(jsonPath("$.readOnlyReason").value("STUDENT_LEFT"));
        send(w.meera(), false, kamalaId, "Best wishes").andExpect(status().isConflict());
        // Closing and reopening cannot open it either.
        api.post("/api/chat/oversight/conversations/" + kamalaId + "/close", w.principal().accessToken(), """
                {"reason":"Student left"}""").andExpect(status().isOk());
        api.post("/api/chat/oversight/conversations/" + kamalaId + "/reopen", w.principal().accessToken(), null)
                .andExpect(status().isConflict());
    }

    @Test
    void erasingTheStudentsDataClearsTheirConversations() throws Exception {
        World w = world();
        String id = TestApi.read(api.post("/api/me/chat/conversations", w.kamala().accessToken(), """
                {"studentId":"%s","teacherUserId":"%s"}""".formatted(w.bala(), w.meeraId())), "$.id");
        String file = TestApi.read(send(w.kamala(), true, id, "Bala's medical note", "note.txt")
                .andExpect(status().isCreated()), "$.message.attachments[0].id");
        send(w.meera(), false, id, "Thank you, noted.").andExpect(status().isCreated());
        api.post("/api/me/chat/conversations/" + id + "/reports", w.kamala().accessToken(), """
                {"reason":"Bala's details were shared"}""").andExpect(status().isCreated());
        String request = TestApi.read(api.post("/api/me/privacy/requests", w.kamala().accessToken(), """
                {"type":"ERASURE","subject":"CHILD","studentId":"%s","details":null}""".formatted(w.bala()))
                .andExpect(status().isCreated()), "$.id");
        api.post("/api/students/" + w.bala() + "/leave", w.admin().accessToken(), """
                {"status":"TRANSFERRED","leftOn":"%s","reason":"Family moved"}""".formatted(today()))
                .andExpect(status().isOk());
        api.post("/api/privacy/requests/" + request + "/erase", w.admin().accessToken(), """
                {"confirmAdmissionNo":"C-3"}""").andExpect(status().isOk());

        api.get("/api/chat/conversations/" + id, w.meera().accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readOnlyReason").value("ERASED"))
                .andExpect(jsonPath("$.messages[0].erased").value(true))
                .andExpect(jsonPath("$.messages[0].senderName").value("Parent"))
                .andExpect(content().string(not(containsString("medical note"))))
                .andExpect(content().string(not(containsString("Kamala"))));
        api.get("/api/chat/oversight/conversations/" + id, w.principal().accessToken())
                .andExpect(jsonPath("$.messages[0].body").value(nullValue()))
                .andExpect(jsonPath("$.messages[1].body").value(nullValue()))
                .andExpect(jsonPath("$.reports[0].reason").value("(erased)"))
                .andExpect(content().string(not(containsString("Thank you, noted."))));
        api.get("/api/files/" + file, w.principal().accessToken()).andExpect(status().isNotFound());
    }

    @Test
    void anotherSchoolSeesNothing() throws Exception {
        World w = world();
        String id = lataTo(w, w.raviId());
        String file = TestApi.read(send(w.lata(), true, id, "Hello", "note.txt").andExpect(status().isCreated()),
                "$.message.attachments[0].id");
        School other = api.signup();
        Session outsider = api.login(other);
        api.get("/api/chat/oversight/conversations/" + id, outsider.accessToken()).andExpect(status().isNotFound());
        api.get("/api/chat/oversight/conversations", outsider.accessToken())
                .andExpect(jsonPath("$.total").value(0));
        api.get("/api/chat/conversations/" + id, outsider.accessToken()).andExpect(status().isNotFound());
        api.post("/api/chat/oversight/conversations/" + id + "/close", outsider.accessToken(), """
                {"reason":"x"}""").andExpect(status().isNotFound());
        api.get("/api/files/" + file, outsider.accessToken()).andExpect(status().isNotFound());
        // Ids of the first school in a body are refused as a field error.
        api.post("/api/chat/conversations", outsider.accessToken(), """
                {"studentId":"%s","parentUserId":"%s"}""".formatted(w.asha(), w.lataId()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.studentId").exists());
    }
}
