package com.akshara.frontoffice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.akshara.support.IntegrationTest;
import com.akshara.support.TestApi;

/** The call log, the enquiry log with its follow-up queue and conversion, and the postal and courier register. */
class EnquiryIT extends IntegrationTest {

    @Test
    void callsAndEnquiriesFeedTheFollowUpQueueAndTheDashboard() throws Exception {
        FrontOfficeSchool s = new FrontOfficeSchool(api);
        String office = s.office.accessToken();
        LocalDate today = FrontOfficeText.today();
        api.post("/api/frontoffice/calls", office, """
                {"direction":"INCOMING","callerName":"","about":"x"}""").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.callerName").exists());
        api.post("/api/frontoffice/calls", office, """
                {"direction":"INCOMING","callerName":"Vikram Singh","phone":"9876513001","about":"Bonafide",
                 "assignedToUserId":"%s"}""".formatted(UUID.randomUUID()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.assignedToUserId").exists());
        String call = TestApi.read(api.post("/api/frontoffice/calls", office, """
                {"direction":"INCOMING","callerName":"Vikram Singh","phone":"9876513001",
                 "about":"Asked for a bonafide certificate.","followUpOn":"%s","assignedToUserId":"%s"}"""
                .formatted(today.minusDays(2), s.teacherId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.phoneMasked").value("98765•••01"))
                .andExpect(jsonPath("$.assignedToName").value("Tara Teacher"))
                .andExpect(jsonPath("$.loggedByName").value("Olivia Office")), "$.id");
        String json = api.get("/api/frontoffice/calls?date=" + today, office)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(call))
                .andReturn().getResponse().getContentAsString();
        assertThat(json).doesNotContain("9876513001");
        api.get("/api/frontoffice/calls?date=" + today.minusDays(1), office).andExpect(jsonPath("$.length()").value(0));
        String enquiry = TestApi.read(api.post("/api/frontoffice/enquiries", office, """
                {"source":"WALK_IN","topic":"ADMISSION","name":"Kavita Menon","phone":"9876512001",
                 "childName":"Aarav Menon","className":"Class 5","details":"Admission for next year.",
                 "followUpOn":"%s","assignedToUserId":"%s"}""".formatted(today, s.officeId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FOLLOW_UP"))
                .andExpect(jsonPath("$.phoneMasked").value("98765•••01")), "$.id");
        api.post("/api/frontoffice/enquiries", office, """
                {"source":"PHONE","topic":"FEES","name":"Rajiv Bhatia","details":""}""")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.details").exists());
        String fees = TestApi.read(api.post("/api/frontoffice/enquiries", office, """
                {"source":"PHONE","topic":"FEES","name":"Rajiv Bhatia","phone":"9876512002",
                 "details":"Can the term fee be paid in two parts?"}""")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("OPEN")), "$.id");
        api.get("/api/frontoffice/enquiries", office).andExpect(jsonPath("$.length()").value(2));
        api.get("/api/frontoffice/enquiries?date=" + today, office).andExpect(jsonPath("$.length()").value(2));
        api.get("/api/frontoffice/enquiries/" + enquiry, s.teacher.accessToken()).andExpect(status().isForbidden());
        // The queue: the call is two days overdue, the admission enquiry is due today.
        api.get("/api/frontoffice/follow-ups", office)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dueToday").value(1))
                .andExpect(jsonPath("$.overdue").value(1))
                .andExpect(jsonPath("$.items[0].kind").value("CALL"))
                .andExpect(jsonPath("$.items[0].daysOverdue").value(2))
                .andExpect(jsonPath("$.items[1].kind").value("ENQUIRY"))
                .andExpect(jsonPath("$.items[1].name").value("Kavita Menon"));
        api.get("/api/dashboard", office)
                .andExpect(jsonPath("$.frontOffice.followUpsDue").value(1))
                .andExpect(jsonPath("$.frontOffice.followUpsOverdue").value(1));
        api.get("/api/dashboard", s.teacher.accessToken()).andExpect(jsonPath("$.frontOffice").doesNotExist());
        api.get("/api/frontoffice/reports/follow-ups", s.admin.accessToken())
                .andExpect(jsonPath("$.overdue").value(1))
                .andExpect(jsonPath("$.rows.length()").value(2));
        byte[] csv = api.get("/api/frontoffice/reports/follow-ups.csv", s.admin.accessToken())
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(csv[0] & 0xFF).isEqualTo(0xEF);
        assertThat(new String(csv, StandardCharsets.UTF_8)).contains("Vikram Singh").doesNotContain("9876513001");
        // Closing the call with its outcome clears it from the queue.
        api.put("/api/frontoffice/calls/" + call, office, """
                {"direction":"INCOMING","callerName":"Vikram Singh","phone":"9876513001",
                 "about":"Asked for a bonafide certificate.","followUpOn":"%s"}""".formatted(today))
                .andExpect(status().isOk()).andExpect(jsonPath("$.assignedToName").doesNotExist());
        api.post("/api/frontoffice/calls/" + call + "/close", office, """
                {"outcome":"Certificate handed over."}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.outcome").value("Certificate handed over."));
        api.post("/api/frontoffice/calls/" + call + "/close", office, null).andExpect(status().isConflict());
        api.get("/api/frontoffice/follow-ups", office)
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.overdue").value(0));
        // Closing and reopening an enquiry.
        api.post("/api/frontoffice/enquiries/" + fees + "/close", office, "{}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.outcome").exists());
        api.post("/api/frontoffice/enquiries/" + fees + "/close", office, """
                {"outcome":"Yes, in two parts by 15 Nov."}""")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CLOSED"));
        api.post("/api/frontoffice/enquiries/" + fees + "/close", office, """
                {"outcome":"again"}""").andExpect(status().isConflict());
        api.post("/api/frontoffice/enquiries/" + enquiry + "/reopen", office, null).andExpect(status().isConflict());
        api.post("/api/frontoffice/enquiries/" + fees + "/reopen", office, """
                {"followUpOn":"%s"}""".formatted(today.plusDays(1)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FOLLOW_UP"));
        api.get("/api/frontoffice/reports/enquiries", s.admin.accessToken())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.bySource[?(@.name == 'WALK_IN')].count").value(contains(1)))
                .andExpect(jsonPath("$.byTopic[?(@.name == 'FEES')].count").value(contains(1)))
                .andExpect(jsonPath("$.byOutcome[?(@.name == 'FOLLOW_UP')].count").value(contains(2)));
        byte[] enquiriesCsv = api.get("/api/frontoffice/reports/enquiries.csv", s.admin.accessToken())
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(enquiriesCsv[0] & 0xFF).isEqualTo(0xEF);
        assertThat(new String(enquiriesCsv, StandardCharsets.UTF_8)).contains("Kavita Menon");
        api.get("/api/audit-events?limit=50", s.admin.accessToken())
                .andExpect(jsonPath("$[*].action", hasItems("call.logged", "call.updated", "call.closed",
                        "enquiry.logged", "enquiry.closed", "enquiry.reopened")));
    }

    @Test
    void anAdmissionEnquiryBecomesAnApplication() throws Exception {
        FrontOfficeSchool s = new FrontOfficeSchool(api);
        String office = s.office.accessToken();
        String enquiry = TestApi.read(api.post("/api/frontoffice/enquiries", office, """
                {"source":"PHONE","topic":"ADMISSION","name":"Kavita Menon","phone":"9876512001",
                 "childName":"Aarav Menon","details":"Admission for Class 5 next year."}""")
                .andExpect(status().isCreated()), "$.id");
        String fees = TestApi.read(api.post("/api/frontoffice/enquiries", office, """
                {"source":"PHONE","topic":"FEES","name":"Rajiv Bhatia","details":"Fees."}""")
                .andExpect(status().isCreated()), "$.id");
        api.post("/api/frontoffice/enquiries/" + fees + "/convert", office, """
                {"classId":"%s","academicYearId":"%s","dateOfBirth":"2016-02-01"}""".formatted(s.classId, s.yearId))
                .andExpect(status().isConflict());
        api.post("/api/frontoffice/enquiries/" + enquiry + "/convert", office, """
                {"classId":"%s","academicYearId":"%s"}""".formatted(s.classId, s.yearId))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.dateOfBirth").exists());
        api.post("/api/frontoffice/enquiries/" + enquiry + "/convert", s.teacher.accessToken(), """
                {"classId":"%s","academicYearId":"%s","dateOfBirth":"2016-02-01"}""".formatted(s.classId, s.yearId))
                .andExpect(status().isForbidden());
        String application = TestApi.read(api.post("/api/frontoffice/enquiries/" + enquiry + "/convert", office, """
                {"classId":"%s","academicYearId":"%s","dateOfBirth":"2016-02-01","relation":"MOTHER"}"""
                .formatted(s.classId, s.yearId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONVERTED"))
                .andExpect(jsonPath("$.applicationId").exists()), "$.applicationId");
        api.get("/api/admissions/applications/" + application, s.admin.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("ENQUIRY"))
                .andExpect(jsonPath("$.firstName").value("Aarav"))
                .andExpect(jsonPath("$.lastName").value("Menon"))
                .andExpect(jsonPath("$.source").value("PHONE"))
                .andExpect(jsonPath("$.guardians[0].name").value("Kavita Menon"))
                .andExpect(jsonPath("$.guardians[0].relation").value("MOTHER"));
        api.post("/api/frontoffice/enquiries/" + enquiry + "/convert", office, """
                {"classId":"%s","academicYearId":"%s","dateOfBirth":"2016-02-01"}""".formatted(s.classId, s.yearId))
                .andExpect(status().isConflict());
        api.put("/api/frontoffice/enquiries/" + enquiry, office, """
                {"source":"PHONE","topic":"ADMISSION","name":"Kavita Menon","details":"Edited."}""")
                .andExpect(status().isConflict());
        api.get("/api/frontoffice/reports/enquiries", s.admin.accessToken())
                .andExpect(jsonPath("$.byOutcome[?(@.name == 'CONVERTED')].count").value(contains(1)));
        api.get("/api/audit-events?limit=50", s.admin.accessToken())
                .andExpect(jsonPath("$[*].action", hasItems("enquiry.converted")));
        // Another school sees nothing of it.
        FrontOfficeSchool other = new FrontOfficeSchool(api);
        api.get("/api/frontoffice/enquiries/" + enquiry, other.admin.accessToken()).andExpect(status().isNotFound());
        api.post("/api/frontoffice/enquiries/" + fees + "/close", other.office.accessToken(), """
                {"outcome":"x"}""").andExpect(status().isNotFound());
        api.get("/api/frontoffice/enquiries", other.office.accessToken()).andExpect(jsonPath("$.length()").value(0));
        api.get("/api/frontoffice/follow-ups", other.office.accessToken())
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void thePostalAndCourierRegister() throws Exception {
        FrontOfficeSchool s = new FrontOfficeSchool(api);
        String office = s.office.accessToken();
        LocalDate today = FrontOfficeText.today();
        api.post("/api/frontoffice/courier", office, """
                {"direction":"INWARD","item":"","sender":"CBSE","receiver":"Principal","itemDate":"%s"}"""
                .formatted(today)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.item").exists());
        String item = TestApi.read(api.post("/api/frontoffice/courier", office, """
                {"direction":"INWARD","item":"CBSE circulars","sender":"CBSE Regional Office","receiver":"Principal",
                 "itemDate":"%s","carrier":"Speed Post","trackingNo":"EK123456789IN","receivedBy":"Olivia Office"}"""
                .formatted(today))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.receivedBy").value("Olivia Office"))
                .andExpect(jsonPath("$.loggedByName").value("Olivia Office")), "$.id");
        api.post("/api/frontoffice/courier", office, """
                {"direction":"OUTWARD","item":"Transfer certificate","sender":"School","receiver":"Mr Verma, Pune",
                 "itemDate":"%s","carrier":"DTDC","trackingNo":"D4009"}""".formatted(today.minusDays(40)))
                .andExpect(status().isCreated());
        api.get("/api/frontoffice/courier", office)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].item", contains("CBSE circulars")));
        api.get("/api/frontoffice/courier?from=" + today.minusDays(60) + "&to=" + today, office)
                .andExpect(jsonPath("$.length()").value(2));
        api.get("/api/frontoffice/courier?from=" + today + "&to=" + today.minusDays(1), office)
                .andExpect(status().isBadRequest());
        api.put("/api/frontoffice/courier/" + item, office, """
                {"direction":"INWARD","item":"CBSE circulars (2 envelopes)","sender":"CBSE","receiver":"Principal",
                 "itemDate":"%s"}""".formatted(today))
                .andExpect(status().isOk()).andExpect(jsonPath("$.item").value("CBSE circulars (2 envelopes)"));
        api.get("/api/frontoffice/courier", s.teacher.accessToken()).andExpect(status().isForbidden());
        api.post("/api/frontoffice/courier", s.security.accessToken(), """
                {"direction":"INWARD","item":"x","sender":"y","receiver":"z","itemDate":"%s"}""".formatted(today))
                .andExpect(status().isForbidden());
        mvc.perform(MockMvcRequestBuilders.get("/api/frontoffice/courier")).andExpect(status().isUnauthorized());
        FrontOfficeSchool other = new FrontOfficeSchool(api);
        api.put("/api/frontoffice/courier/" + item, other.office.accessToken(), """
                {"direction":"INWARD","item":"x","sender":"y","receiver":"z","itemDate":"%s"}""".formatted(today))
                .andExpect(status().isNotFound());
        api.delete("/api/frontoffice/courier/" + item, other.office.accessToken()).andExpect(status().isNotFound());
        api.get("/api/frontoffice/courier", other.office.accessToken()).andExpect(jsonPath("$.length()").value(0));
        api.delete("/api/frontoffice/courier/" + item, office).andExpect(status().isNoContent());
        api.get("/api/frontoffice/courier", office).andExpect(jsonPath("$.length()").value(0));
        api.get("/api/audit-events?limit=50", s.admin.accessToken())
                .andExpect(jsonPath("$[*].action", hasItems("courier.logged", "courier.updated", "courier.removed")));
    }
}
