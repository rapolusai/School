package com.akshara.communication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.akshara.admissions.AdmissionsService;
import com.akshara.admissions.EnquiryReceived;
import com.akshara.support.IntegrationTest;
import com.akshara.support.SchoolFixtures;
import com.akshara.support.TestApi;
import com.akshara.support.TestApi.School;
import com.akshara.support.TestApi.Session;

/** Families who enquire through the public form get one thank-you message, as the school prefers. */
class EnquiryAcknowledgementIT extends IntegrationTest {

    @Autowired
    ApplicationEventPublisher events;

    private School school;
    private Session admin;
    private String classOne;
    private String yearId;

    @BeforeEach
    void schoolWithAClass() throws Exception {
        school = api.signup();
        admin = api.login(school);
        SchoolFixtures fixtures = new SchoolFixtures(api);
        yearId = fixtures.currentYear(admin);
        classOne = fixtures.schoolClass(admin, "Class 1");
    }

    @Test
    void anEnquiryIsAcknowledgedOnceBySms() throws Exception {
        enquire("Anaya").andExpect(status().isCreated());
        String application = TestApi.read(api.get("/api/admissions/applications", admin.accessToken()),
                "$.items[0].id");
        String message = TestApi.read(api.get("/api/messages?relatedId=" + application, admin.accessToken())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].channel").value("SMS"))
                .andExpect(jsonPath("$.items[0].templateKey").value("admissions.enquiry_ack"))
                .andExpect(jsonPath("$.items[0].relatedType").value("application"))
                .andExpect(jsonPath("$.items[0].recipient").value(org.hamcrest.Matchers.not("+919876054321"))),
                "$.items[0].id");
        api.get("/api/messages/" + message, admin.accessToken())
                .andExpect(jsonPath("$.body").value("Thank you for your enquiry at Test School " + school.code()
                        + " for Anaya Kulkarni (Class 1). Our admissions team will contact you soon."))
                .andExpect(jsonPath("$.fallbackChannel").isEmpty());

        // The same enquiry announced again (for example by a retry) is not acknowledged twice.
        events.publishEvent(new EnquiryReceived(school.tenantId(), UUID.fromString(application),
                UUID.fromString(classOne), UUID.fromString(yearId), Instant.now()));
        api.get("/api/messages?relatedId=" + application, admin.accessToken())
                .andExpect(jsonPath("$.total").value(1));

        // Enquiries the office enters itself are not acknowledged automatically.
        api.post("/api/admissions/applications", admin.accessToken(), """
                {"firstName":"Ravi","dateOfBirth":"2020-05-01","classId":"%s","academicYearId":"%s",
                 "source":"WALK_IN","guardians":[{"name":"Meena Rao","relation":"MOTHER","phone":"9876501002"}]}"""
                .formatted(classOne, yearId)).andExpect(status().isCreated());
        api.get("/api/messages", admin.accessToken()).andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void theSchoolChoosesTheChannelOrSwitchesItOff() throws Exception {
        api.put("/api/notices/settings", admin.accessToken(), """
                {"teacherCircularsNeedApproval":true,"enquiryAckEnabled":true,"enquiryAckChannel":"WHATSAPP_SMS"}""")
                .andExpect(status().isOk());
        enquire("Anaya").andExpect(status().isCreated());
        String message = TestApi.read(api.get("/api/messages", admin.accessToken())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].channel").value("WHATSAPP")), "$.items[0].id");
        api.get("/api/messages/" + message, admin.accessToken())
                .andExpect(jsonPath("$.fallbackChannel").value("SMS"));

        api.put("/api/notices/settings", admin.accessToken(), """
                {"teacherCircularsNeedApproval":true,"enquiryAckEnabled":false,"enquiryAckChannel":"SMS"}""")
                .andExpect(status().isOk());
        enquire("Kabir").andExpect(status().isCreated());
        api.get("/api/admissions/applications", admin.accessToken()).andExpect(jsonPath("$.total").value(2));
        api.get("/api/messages", admin.accessToken()).andExpect(jsonPath("$.total").value(1));
        api.get("/api/audit-events?limit=20", admin.accessToken())
                .andExpect(jsonPath("$[0].action").value("application.enquiry_received"));
        assertThat(EnquiryAcknowledgements.dedupeKey(new EnquiryReceived(school.tenantId(), UUID.randomUUID(),
                null, null, Instant.now()))).startsWith("enquiry-ack:");
    }

    private ResultActions enquire(String child) throws Exception {
        String json = """
                {"parentName":"Sneha Kulkarni","relation":"MOTHER","mobile":"+91 98760 54321",
                 "email":"sneha@family.test","childFirstName":"%s","childLastName":"Kulkarni",
                 "dateOfBirth":"2021-07-04","className":"Class 1","academicYear":"CURRENT","consent":true,
                 "consentVersion":"%s","website":""}""".formatted(child, AdmissionsService.CONSENT_VERSION);
        ThreadLocalRandom r = ThreadLocalRandom.current();
        String ip = "10." + r.nextInt(256) + "." + r.nextInt(256) + "." + (1 + r.nextInt(254));
        return mvc.perform(MockMvcRequestBuilders.post("/api/public/schools/" + school.code() + "/enquiries")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                }));
    }
}
