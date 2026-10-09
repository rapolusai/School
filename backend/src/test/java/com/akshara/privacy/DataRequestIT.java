package com.akshara.privacy;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.akshara.support.TestApi;
import com.akshara.support.TestApi.School;
import com.akshara.support.TestApi.Session;

/** Parents' data requests and the staff queue: due dates, assigning, replies from both sides, closing. */
class DataRequestIT extends PrivacyTestBase {

    @Test
    void aRequestIsAnsweredWithinThirtyDaysWithEveryStepOnItsTimeline() throws Exception {
        Session principal = member("PRINCIPAL", "principal");
        Session teacher = member("TEACHER", "teacher");
        String principalId = TestApi.read(api.get("/api/me", principal.accessToken()), "$.id");
        String teacherId = TestApi.read(api.get("/api/me", teacher.accessToken()), "$.id");

        // A request about a child must name one of the parent's own children; corrections and grievances say what.
        api.post("/api/me/privacy/requests", parent.accessToken(), """
                {"type":"ACCESS","subject":"CHILD","studentId":"%s"}""".formatted(kabir))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.studentId").exists());
        api.post("/api/me/privacy/requests", parent.accessToken(), """
                {"type":"ACCESS","subject":"CHILD"}""")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.studentId").exists());
        api.post("/api/me/privacy/requests", parent.accessToken(), """
                {"type":"CORRECTION","subject":"CHILD","studentId":"%s","details":"  "}""".formatted(arjun))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.details").exists());
        api.post("/api/me/privacy/requests", parent.accessToken(), """
                {"type":"DELETE_EVERYTHING","subject":"SELF"}""").andExpect(status().isBadRequest());

        String access = TestApi.read(api.post("/api/me/privacy/requests", parent.accessToken(), """
                {"type":"ACCESS","subject":"CHILD","studentId":"%s","details":"A copy of Arjun's records."}"""
                .formatted(arjun))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.studentName").value("Arjun Sharma"))
                .andExpect(jsonPath("$.dueOn").value(today().plusDays(30).toString()))
                .andExpect(jsonPath("$.daysLeft").value(30))
                .andExpect(jsonPath("$.overdue").value(false))
                .andExpect(jsonPath("$.assignedTo").doesNotExist())
                .andExpect(jsonPath("$.events[0].kind").value("SUBMITTED"))
                .andExpect(jsonPath("$.events[0].byRequester").value(true)), "$.id");
        String grievance = submit(parent, "GRIEVANCE", "SELF", null, "My number was shared with another parent.");

        api.get("/api/me/privacy/requests", parent.accessToken())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[*].type", contains("GRIEVANCE", "ACCESS")));

        // The queue: open requests by due date, with counts.
        api.get("/api/privacy/requests?status=OPEN", admin.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.counts.open").value(2))
                .andExpect(jsonPath("$.counts.submitted").value(2))
                .andExpect(jsonPath("$.counts.overdue").value(0))
                .andExpect(jsonPath("$.items[0].requesterName").value("Anitha Sharma"));
        api.get("/api/privacy/requests?type=GRIEVANCE", admin.accessToken())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(grievance));
        api.get("/api/privacy/requests?q=anitha", admin.accessToken()).andExpect(jsonPath("$.total").value(2));
        api.get("/api/privacy/requests?q=farah", admin.accessToken()).andExpect(jsonPath("$.total").value(0));
        api.get("/api/privacy/requests?status=LOST", admin.accessToken()).andExpect(status().isBadRequest());

        // Only staff who handle data requests can be assigned.
        api.get("/api/privacy/staff", admin.accessToken())
                .andExpect(jsonPath("$[*].name", hasItems("principal Member")));
        api.post("/api/privacy/requests/" + access + "/assign", admin.accessToken(), """
                {"assigneeId":"%s"}""".formatted(teacherId))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.assigneeId").exists());
        api.post("/api/privacy/requests/" + access + "/assign", admin.accessToken(), """
                {"assigneeId":"%s"}""".formatted(principalId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.assignedTo.name").value("principal Member"));
        api.post("/api/privacy/requests/" + access + "/replies", principal.accessToken(), """
                {"body":"We are collecting the records and will send them this week."}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events[-1].kind").value("STAFF_REPLY"))
                .andExpect(jsonPath("$.events[-1].byRequester").value(false));
        api.post("/api/privacy/requests/" + access + "/replies", principal.accessToken(), """
                {"body":""}""").andExpect(status().isBadRequest());

        // The parent sees the status and the school's reply, and answers.
        api.post("/api/me/privacy/requests/" + access + "/replies", parent.accessToken(), """
                {"body":"Thank you. Please include the fee receipts."}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.assignedTo").doesNotExist())
                .andExpect(jsonPath("$.events[*].kind",
                        contains("SUBMITTED", "ASSIGNED", "STAFF_REPLY", "PARENT_REPLY")));

        // Another parent cannot see or answer it; another school's staff cannot find it.
        api.get("/api/me/privacy/requests/" + access, otherParent.accessToken()).andExpect(status().isNotFound());
        api.post("/api/me/privacy/requests/" + access + "/replies", otherParent.accessToken(), """
                {"body":"Hello"}""").andExpect(status().isNotFound());
        api.get("/api/me/privacy/requests", otherParent.accessToken()).andExpect(jsonPath("$.length()").value(0));
        School other = api.signup();
        Session outsider = api.login(other);
        api.get("/api/privacy/requests/" + access, outsider.accessToken()).andExpect(status().isNotFound());
        api.post("/api/privacy/requests/" + access + "/close", outsider.accessToken(), """
                {"resolution":"COMPLETED"}""").andExpect(status().isNotFound());
        api.get("/api/privacy/requests", outsider.accessToken()).andExpect(jsonPath("$.total").value(0));

        // Past the due date it shows as overdue.
        setDueOn(access, today().minusDays(1));
        api.get("/api/privacy/requests?status=OPEN", admin.accessToken())
                .andExpect(jsonPath("$.counts.overdue").value(1))
                .andExpect(jsonPath("$.items[0].id").value(access))
                .andExpect(jsonPath("$.items[0].overdue").value(true))
                .andExpect(jsonPath("$.items[0].daysLeft").value(-1));

        // Declining needs a reason for the parent.
        api.post("/api/privacy/requests/" + grievance + "/close", admin.accessToken(), """
                {"resolution":"DECLINED"}""")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.note").exists());
        api.post("/api/privacy/requests/" + grievance + "/close", admin.accessToken(), """
                {"resolution":"COMPLETED","note":"We have spoken to the class teacher; it will not happen again."}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.resolution").value("COMPLETED"))
                .andExpect(jsonPath("$.events[-1].kind").value("CLOSED"));
        api.post("/api/privacy/requests/" + grievance + "/replies", admin.accessToken(), """
                {"body":"One more thing"}""").andExpect(status().isConflict());
        api.post("/api/me/privacy/requests/" + grievance + "/replies", parent.accessToken(), """
                {"body":"Thanks"}""").andExpect(status().isConflict());
        api.post("/api/privacy/requests/" + grievance + "/close", admin.accessToken(), """
                {"resolution":"COMPLETED"}""").andExpect(status().isConflict());
        api.get("/api/me/privacy/requests/" + grievance, parent.accessToken())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.closingNote").value(
                        "We have spoken to the class teacher; it will not happen again."));
        api.get("/api/privacy/requests?status=CLOSED", admin.accessToken())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.counts.closed").value(1));

        api.get("/api/audit-events?limit=100", admin.accessToken())
                .andExpect(jsonPath("$[*].action", hasItems("data_request.submitted", "data_request.assigned",
                        "data_request.replied", "data_request.parent_replied", "data_request.closed")));
    }

    @Test
    void aParentCanHaveAtMostTenOpenRequests() throws Exception {
        for (int i = 0; i < DataRequestService.MAX_OPEN_PER_PARENT; i++) {
            submit(parent, "GRIEVANCE", "SELF", null, "Complaint " + i);
        }
        api.post("/api/me/privacy/requests", parent.accessToken(), """
                {"type":"GRIEVANCE","subject":"SELF","details":"One too many"}""")
                .andExpect(status().isConflict());
        // Staff and the other parent are not affected.
        submit(otherParent, "ACCESS", "SELF", null, null);
    }

    private static void setDueOn(String requestId, LocalDate dueOn) throws Exception {
        try (Connection owner = ownerConnection();
                PreparedStatement s = owner.prepareStatement(
                        "update privacy.data_request set due_on = ? where id = ?")) {
            s.setObject(1, dueOn);
            s.setObject(2, UUID.fromString(requestId));
            s.executeUpdate();
        }
    }
}
