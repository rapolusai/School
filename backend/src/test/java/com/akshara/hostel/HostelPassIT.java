package com.akshara.hostel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;

import com.akshara.support.IntegrationTest;
import com.akshara.support.TestApi;

/** Outing and home-leave passes: the parent asks, the warden decides and works the gate; the parent is told. */
class HostelPassIT extends IntegrationTest {

    @Test
    void parentRequestsWardenApprovesAndMarksOutAndIn() throws Exception {
        HostelSchool s = new HostelSchool(api);
        // A day scholar cannot ask.
        api.post("/api/me/children/" + s.asha + "/hostel-passes", s.parent.accessToken(), """
                {"kind":"OUTING","fromDate":"%s","reason":"Lunch"}""".formatted(HostelSchool.TODAY))
                .andExpect(status().isConflict());
        s.allot(s.asha, s.bedA);
        s.allot(s.bala, s.bedB);
        // Dates: not in the past, an outing is one day, home leave has an end.
        api.post("/api/me/children/" + s.asha + "/hostel-passes", s.parent.accessToken(), """
                {"kind":"OUTING","fromDate":"%s","reason":"Lunch"}""".formatted(HostelSchool.TODAY.minusDays(1)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.fromDate").exists());
        api.post("/api/me/children/" + s.asha + "/hostel-passes", s.parent.accessToken(), """
                {"kind":"HOME_LEAVE","fromDate":"%s","reason":"Wedding"}""".formatted(HostelSchool.TODAY))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.toDate").exists());
        api.post("/api/me/children/" + s.asha + "/hostel-passes", s.parent.accessToken(), """
                {"kind":"OUTING","fromDate":"%s","reason":""}""".formatted(HostelSchool.TODAY))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.reason").exists());
        // Another parent's child answers 404.
        api.post("/api/me/children/" + s.bala + "/hostel-passes", s.parent.accessToken(), """
                {"kind":"OUTING","fromDate":"%s","reason":"Lunch"}""".formatted(HostelSchool.TODAY))
                .andExpect(status().isNotFound());
        String pass = TestApi.read(api.post("/api/me/children/" + s.asha + "/hostel-passes",
                s.parent.accessToken(), """
                {"kind":"OUTING","fromDate":"%s","reason":"Family lunch"}""".formatted(HostelSchool.TODAY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REQUESTED"))
                .andExpect(jsonPath("$.source").value("PARENT_APP"))
                .andExpect(jsonPath("$.toDate").value(HostelSchool.TODAY.toString()))
                .andExpect(jsonPath("$.canCancel").value(true))
                .andExpect(jsonPath("$.canDecide").value(false)), "$.id");
        // Overlapping passes are refused.
        api.post("/api/me/children/" + s.asha + "/hostel-passes", s.parent.accessToken(), """
                {"kind":"HOME_LEAVE","fromDate":"%s","toDate":"%s","reason":"Wedding"}"""
                .formatted(HostelSchool.TODAY, HostelSchool.TODAY.plusDays(3))).andExpect(status().isConflict());
        api.get("/api/me/children/" + s.asha + "/hostel", s.parent.accessToken())
                .andExpect(jsonPath("$.boarder").value(true))
                .andExpect(jsonPath("$.hostelName").value("Boys Hostel"))
                .andExpect(jsonPath("$.roomNumber").value("101"))
                .andExpect(jsonPath("$.bedLabel").value("A"))
                .andExpect(jsonPath("$.wardenName").value("Wasim Warden"))
                .andExpect(jsonPath("$.nextPass.id").value(pass))
                .andExpect(jsonPath("$.passes.length()").value(1));
        // The right warden sees it and may decide; the other warden and a teacher may not.
        api.get("/api/hostel/passes", s.warden.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].studentName").value("Asha Rao"))
                .andExpect(jsonPath("$[0].canDecide").value(true))
                .andExpect(jsonPath("$[0].canMarkOut").value(false));
        api.get("/api/hostel/passes", s.warden2.accessToken()).andExpect(jsonPath("$.length()").value(0));
        api.get("/api/hostel/passes/" + pass, s.warden2.accessToken()).andExpect(status().isNotFound());
        api.get("/api/hostel/passes/" + pass, s.principal.accessToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.canDecide").value(false));
        api.get("/api/hostel/passes", s.teacher.accessToken()).andExpect(status().isForbidden());
        api.post("/api/hostel/passes/" + pass + "/approve", s.warden2.accessToken(), null)
                .andExpect(status().isNotFound());
        api.post("/api/hostel/passes/" + pass + "/approve", s.principal.accessToken(), null)
                .andExpect(status().isForbidden());
        api.post("/api/hostel/passes/" + pass + "/out", s.warden.accessToken(), null)
                .andExpect(status().isConflict());
        api.post("/api/hostel/passes/" + pass + "/approve", s.warden.accessToken(), """
                {"note":"Back by 6"}""").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.decidedByName").value("Wasim Warden"))
                .andExpect(jsonPath("$.canMarkOut").value(true));
        api.post("/api/hostel/passes/" + pass + "/approve", s.warden.accessToken(), null)
                .andExpect(status().isConflict());
        // The mother was told through the outbox (simulated); the message never carries her number back.
        String message = TestApi.read(api.get("/api/messages?relatedId=" + pass, s.admin.accessToken())
                .andExpect(jsonPath("$.total").value(1)), "$.items[0].id");
        api.get("/api/messages/" + message, s.admin.accessToken())
                .andExpect(jsonPath("$.templateKey").value("hostel.pass_decided"))
                .andExpect(jsonPath("$.body").value(org.hamcrest.Matchers.containsString("approved")));
        // The gate: out, then in.
        api.post("/api/hostel/passes/" + pass + "/in", s.warden.accessToken(), null).andExpect(status().isConflict());
        api.post("/api/hostel/passes/" + pass + "/out", s.warden.accessToken(), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OUT"))
                .andExpect(jsonPath("$.outByName").value("Wasim Warden"))
                .andExpect(jsonPath("$.canMarkIn").value(true));
        api.post("/api/me/children/" + s.asha + "/hostel-passes/" + pass + "/cancel", s.parent.accessToken(), null)
                .andExpect(status().isConflict());
        api.post("/api/hostel/passes/" + pass + "/in", s.warden.accessToken(), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RETURNED"))
                .andExpect(jsonPath("$.inAt").exists())
                .andExpect(jsonPath("$.late").isBoolean());
        api.get("/api/me/children/" + s.asha + "/hostel-passes", s.parent.accessToken())
                .andExpect(jsonPath("$[0].status").value("RETURNED"))
                .andExpect(jsonPath("$[0].canCancel").value(false));
        api.get("/api/hostel/passes?from=" + HostelSchool.TODAY + "&to=" + HostelSchool.TODAY, s.admin.accessToken())
                .andExpect(jsonPath("$.length()").value(1));
        api.get("/api/audit-events?limit=50", s.admin.accessToken())
                .andExpect(jsonPath("$[*].action", hasItems("hostel_pass.requested", "hostel_pass.approved",
                        "hostel_pass.out", "hostel_pass.in")));
    }

    @Test
    void wardenRaisesRejectsAndParentCancels() throws Exception {
        HostelSchool s = new HostelSchool(api);
        s.allot(s.asha, s.bedA);
        // The warden raises home leave for a boarder of their hostel only.
        api.post("/api/hostel/passes", s.warden.accessToken(), """
                {"studentId":"%s","kind":"HOME_LEAVE","fromDate":"%s","toDate":"%s","reason":"Diwali"}"""
                .formatted(s.bala, HostelSchool.TODAY.plusDays(2), HostelSchool.TODAY.plusDays(5)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.studentId").exists());
        api.post("/api/hostel/passes", s.warden2.accessToken(), """
                {"studentId":"%s","kind":"HOME_LEAVE","fromDate":"%s","toDate":"%s","reason":"Diwali"}"""
                .formatted(s.asha, HostelSchool.TODAY.plusDays(2), HostelSchool.TODAY.plusDays(5)))
                .andExpect(status().isNotFound());
        api.post("/api/hostel/passes", s.warden.accessToken(), """
                {"studentId":"%s","kind":"HOME_LEAVE","fromDate":"%s","toDate":"%s","reason":"Diwali"}"""
                .formatted(s.asha, HostelSchool.TODAY.plusDays(2), HostelSchool.TODAY.plusDays(40)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.toDate").exists());
        String leave = TestApi.read(api.post("/api/hostel/passes", s.warden.accessToken(), """
                {"studentId":"%s","kind":"HOME_LEAVE","fromDate":"%s","toDate":"%s","reason":"Diwali"}"""
                .formatted(s.asha, HostelSchool.TODAY.plusDays(2), HostelSchool.TODAY.plusDays(5)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.source").value("WARDEN"))
                .andExpect(jsonPath("$.kind").value("HOME_LEAVE")), "$.id");
        // Refusing needs a reason; it tells the parent.
        api.post("/api/hostel/passes/" + leave + "/reject", s.warden.accessToken(), null)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.note").exists());
        api.post("/api/hostel/passes/" + leave + "/reject", s.warden.accessToken(), """
                {"note":"Exams that week"}""").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.decisionNote").value("Exams that week"));
        String body = api.get("/api/messages?relatedId=" + leave, s.admin.accessToken())
                .andExpect(jsonPath("$.total").value(1)).andReturn().getResponse().getContentAsString();
        assertThat(body).contains("hostel.pass_decided");
        // The parent cancels their own request while it waits.
        String outing = s.requestOuting();
        api.post("/api/me/children/" + s.asha + "/hostel-passes/" + outing + "/cancel", s.parent.accessToken(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        api.post("/api/hostel/passes/" + outing + "/approve", s.warden.accessToken(), null)
                .andExpect(status().isConflict());
        // A cancelled pass no longer blocks the date.
        String again = s.requestOuting();
        api.get("/api/hostel/passes", s.warden.accessToken()).andExpect(jsonPath("$[0].id").value(again));
        api.get("/api/audit-events?limit=50", s.admin.accessToken())
                .andExpect(jsonPath("$[*].action", hasItems("hostel_pass.rejected", "hostel_pass.cancelled")));
    }
}
