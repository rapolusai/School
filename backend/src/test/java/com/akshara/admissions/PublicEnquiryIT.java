package com.akshara.admissions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.akshara.support.IntegrationTest;
import com.akshara.support.SchoolFixtures;
import com.akshara.support.TestApi;
import com.akshara.support.TestApi.School;
import com.akshara.support.TestApi.Session;

import tools.jackson.databind.json.JsonMapper;

/** The public enquiry form: what it reveals, what it accepts, and how it is protected. */
@RecordApplicationEvents
class PublicEnquiryIT extends IntegrationTest {

    @Autowired
    private ApplicationEvents events;

    private School school;
    private Session admin;
    private SchoolFixtures fixtures;
    private String classOne;
    /** Each test sends from its own address, so the per-IP limits of one test never affect another. */
    private String ip;

    @BeforeEach
    void schoolWithClasses() throws Exception {
        school = api.signup();
        admin = api.login(school);
        fixtures = new SchoolFixtures(api);
        fixtures.currentYear(admin);
        classOne = fixtures.schoolClass(admin, "Class 1");
        fixtures.schoolClass(admin, "Nursery");
        ip = randomIp();
    }

    @Test
    void theInfoEndpointRevealsOnlyWhatTheFormNeeds() throws Exception {
        String body = info(school.code(), ip)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Test School " + school.code()))
                .andExpect(jsonPath("$.board").value("CBSE"))
                .andExpect(jsonPath("$.city").value("Pune"))
                .andExpect(jsonPath("$.classes", hasItem("Class 1")))
                .andExpect(jsonPath("$.classes", hasItem("Nursery")))
                .andExpect(jsonPath("$.years[*].code", contains("CURRENT")))
                .andExpect(jsonPath("$.years[0].name").value("2026-27"))
                .andExpect(jsonPath("$.consentVersion").value(AdmissionsService.CONSENT_VERSION))
                .andReturn().getResponse().getContentAsString();
        Map<?, ?> json = JsonMapper.builder().build().readValue(body, Map.class);
        assertThat(json.keySet()).map(Object::toString)
                .containsExactlyInAnyOrder("name", "board", "city", "classes", "years", "consentVersion");
        assertThat(body).doesNotContain(school.tenantId().toString()).doesNotContain(classOne)
                .doesNotContain("admin@");

        // Next year's intake is offered once that year is set up.
        fixtures.year(admin, "2027-28", "2027-04-01", "2028-03-31", false);
        info(school.code(), ip)
                .andExpect(jsonPath("$.years[*].code", contains("CURRENT", "NEXT")))
                .andExpect(jsonPath("$.years[1].name").value("2027-28"));
        // School codes are not case sensitive, like at sign-in.
        info(school.code().toUpperCase(), ip).andExpect(status().isOk());
    }

    @Test
    void aValidEnquiryReachesTheSchoolAsAWebsiteEnquiry() throws Exception {
        enquire(school.code(), enquiry(Map.of("message", "\"Is there a school bus from Kothrud?\"")), ip)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.received").value(true))
                .andExpect(jsonPath("$.id").doesNotExist());

        String id = TestApi.read(api.get("/api/admissions/applications", admin.accessToken())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].childName").value("Anaya Kulkarni"))
                .andExpect(jsonPath("$.items[0].stage").value("ENQUIRY"))
                .andExpect(jsonPath("$.items[0].source").value("WEBSITE"))
                .andExpect(jsonPath("$.items[0].className").value("Class 1"))
                .andExpect(jsonPath("$.items[0].contactPhone").value("98•••••321")), "$.items[0].id");
        api.get("/api/admissions/applications/" + id, admin.accessToken())
                .andExpect(jsonPath("$.message").value("Is there a school bus from Kothrud?"))
                .andExpect(jsonPath("$.consentVersion").value(AdmissionsService.CONSENT_VERSION))
                .andExpect(jsonPath("$.consentAt").exists())
                .andExpect(jsonPath("$.academicYearName").value("2026-27"))
                .andExpect(jsonPath("$.guardians.length()").value(1))
                .andExpect(jsonPath("$.guardians[0].name").value("Sneha Kulkarni"))
                .andExpect(jsonPath("$.guardians[0].relation").value("MOTHER"))
                .andExpect(jsonPath("$.guardians[0].phone").value("9876054321"))
                .andExpect(jsonPath("$.guardians[0].email").value("sneha@family.test"))
                .andExpect(jsonPath("$.guardians[0].primary").value(true))
                .andExpect(jsonPath("$.timeline[0].kind").value("CREATED"))
                .andExpect(jsonPath("$.timeline[0].actorName").doesNotExist())
                .andExpect(jsonPath("$.timeline[0].details.publicForm").value(true));
        api.get("/api/admissions/summary", admin.accessToken()).andExpect(jsonPath("$.openEnquiries").value(1));
        api.get("/api/audit-events?limit=50", admin.accessToken())
                .andExpect(jsonPath("$[*].action", hasItem("application.enquiry_received")));

        assertThat(events.stream(EnquiryReceived.class).filter(e -> e.tenantId().equals(school.tenantId())))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.applicationId()).hasToString(id);
                    assertThat(e.classId()).hasToString(classOne);
                });

        // Next year's intake, picked by name in any case.
        fixtures.year(admin, "2027-28", "2027-04-01", "2028-03-31", false);
        enquire(school.code(), enquiry(Map.of("academicYear", "\"NEXT\"", "className", "\"nursery\"")), ip)
                .andExpect(status().isCreated());
        api.get("/api/admissions/applications?q=anaya", admin.accessToken())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].className").value("Nursery"))
                .andExpect(jsonPath("$.items[0].academicYearName").value("2027-28"));
    }

    @Test
    void enquiriesAreCheckedFieldByField() throws Exception {
        enquire(school.code(), """
                {"parentName":"","relation":null,"mobile":"12345","email":"not-an-email","childFirstName":"",
                 "dateOfBirth":"2099-01-01","className":"","academicYear":null,"consent":true}""", ip)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.parentName").exists())
                .andExpect(jsonPath("$.errors.relation").exists())
                .andExpect(jsonPath("$.errors.mobile").exists())
                .andExpect(jsonPath("$.errors.email").exists())
                .andExpect(jsonPath("$.errors.childFirstName").exists())
                .andExpect(jsonPath("$.errors.dateOfBirth").exists())
                .andExpect(jsonPath("$.errors.className").exists())
                .andExpect(jsonPath("$.errors.academicYear").exists());
        enquire(school.code(), enquiry(Map.of("message", "\"" + "x".repeat(1001) + "\"")), ip)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.message").exists());
        enquire(school.code(), enquiry(Map.of("parentName", "\"" + "x".repeat(201) + "\"")), ip)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.parentName").exists());
        enquire(school.code(), enquiry(Map.of("className", "\"Class 12\"")), ip)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.className").value("Pick a class from the list."));
        // No next year is set up yet.
        enquire(school.code(), enquiry(Map.of("academicYear", "\"NEXT\"")), ip)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.academicYear").exists());
        enquire(school.code(), enquiry(Map.of("academicYear", "\"LAST\"")), ip)
                .andExpect(status().isBadRequest());
        enquire(school.code(), enquiry(Map.of("dateOfBirth", "\"1990-01-01\"")), ip)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.dateOfBirth").exists());
        enquire(school.code(), enquiry(Map.of("mobile", "\"5876054321\"")), ip)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.mobile").exists());

        api.get("/api/admissions/applications", admin.accessToken()).andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void consentIsRequired() throws Exception {
        enquire(school.code(), enquiry(Map.of("consent", "null")), ip)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.consent").exists());
        enquire(school.code(), enquiry(Map.of("consent", "false")), ip)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.consent").exists());
        // Consent given to an older text does not count.
        enquire(school.code(), enquiry(Map.of("consentVersion", "\"enquiry-2020-01\"")), ip)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.consent").exists());
        api.get("/api/admissions/applications", admin.accessToken()).andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void unknownAndSuspendedSchoolsAreNotFound() throws Exception {
        info("no-such-school", ip).andExpect(status().isNotFound());
        enquire("no-such-school", enquiry(Map.of()), ip).andExpect(status().isNotFound());
        info("x".repeat(60), ip).andExpect(status().isNotFound());

        try (Connection owner = ownerConnection();
                PreparedStatement s = owner.prepareStatement(
                        "update platform.tenant set status = 'SUSPENDED' where id = ?")) {
            s.setObject(1, school.tenantId());
            s.executeUpdate();
        }
        info(school.code(), ip)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("School was not found."));
        enquire(school.code(), enquiry(Map.of()), ip).andExpect(status().isNotFound());
        assertThat(countApplications(school.tenantId())).isZero();
    }

    @Test
    void aFilledHoneypotIsQuietlyDropped() throws Exception {
        enquire(school.code(), enquiry(Map.of("website", "\"http://spam.example\"")), ip)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.received").value(true));
        assertThat(countApplications(school.tenantId())).isZero();
        assertThat(events.stream(EnquiryReceived.class)).isEmpty();
    }

    @Test
    void enquiriesAreRateLimitedPerAddressAndPerSchool() throws Exception {
        for (int i = 0; i < 20; i++) {
            enquire(school.code(), enquiry(Map.of()), ip).andExpect(status().isCreated());
        }
        enquire(school.code(), enquiry(Map.of()), ip)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.title").value("Too many requests"));
        // The same address is also limited for other schools; another address is not.
        enquire("no-such-school", enquiry(Map.of()), ip).andExpect(status().isTooManyRequests());
        enquire(school.code(), enquiry(Map.of()), randomIp()).andExpect(status().isCreated());
        assertThat(countApplications(school.tenantId())).isEqualTo(21);

        // One school takes at most 100 enquiries an hour, from any number of addresses. Bot submissions count too.
        for (int i = 0; i < 79; i++) {
            enquire(school.code(), enquiry(Map.of("website", "\"bot\"")), randomIp()).andExpect(status().isCreated());
        }
        enquire(school.code(), enquiry(Map.of()), randomIp()).andExpect(status().isTooManyRequests());
        assertThat(countApplications(school.tenantId())).isEqualTo(21);

        // Another school is unaffected.
        School other = api.signup();
        Session otherAdmin = api.login(other);
        fixtures.currentYear(otherAdmin);
        fixtures.schoolClass(otherAdmin, "Class 1");
        enquire(other.code(), enquiry(Map.of()), randomIp()).andExpect(status().isCreated());
    }

    @Test
    void pageLoadsAreRateLimitedPerAddress() throws Exception {
        for (int i = 0; i < 120; i++) {
            info(school.code(), ip).andExpect(status().isOk());
        }
        info(school.code(), ip).andExpect(status().isTooManyRequests());
        info(school.code(), randomIp()).andExpect(status().isOk());
    }

    @Test
    void anEnquiryForOneSchoolIsNeverVisibleToAnother() throws Exception {
        School other = api.signup();
        Session otherAdmin = api.login(other);
        enquire(school.code(), enquiry(Map.of()), ip).andExpect(status().isCreated());
        String id = TestApi.read(api.get("/api/admissions/applications", admin.accessToken()), "$.items[0].id");

        api.get("/api/admissions/applications", otherAdmin.accessToken()).andExpect(jsonPath("$.total").value(0));
        api.get("/api/admissions/board", otherAdmin.accessToken()).andExpect(jsonPath("$.lanes[0].total").value(0));
        api.get("/api/admissions/summary", otherAdmin.accessToken()).andExpect(jsonPath("$.openEnquiries").value(0));
        api.get("/api/admissions/applications/" + id, otherAdmin.accessToken()).andExpect(status().isNotFound());
        assertThat(countApplications(other.tenantId())).isZero();
        assertThat(countApplications(school.tenantId())).isEqualTo(1);

        // A signed-in user of another school sending the form still files it with the school in the address.
        enquire(school.code(), enquiry(Map.of()), ip, otherAdmin.accessToken()).andExpect(status().isCreated());
        assertThat(countApplications(other.tenantId())).isZero();
        assertThat(countApplications(school.tenantId())).isEqualTo(2);
    }

    // ------------------------------------------------------------------ helpers

    /** A valid enquiry for Class 1 this year; {@code overrides} replace fields with raw JSON values. */
    private static String enquiry(Map<String, String> overrides) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("parentName", "\"Sneha Kulkarni\"");
        fields.put("relation", "\"MOTHER\"");
        fields.put("mobile", "\"+91 98760 54321\"");
        fields.put("email", "\"sneha@family.test\"");
        fields.put("childFirstName", "\"Anaya\"");
        fields.put("childLastName", "\"Kulkarni\"");
        fields.put("dateOfBirth", "\"2021-07-04\"");
        fields.put("className", "\"Class 1\"");
        fields.put("academicYear", "\"CURRENT\"");
        fields.put("consent", "true");
        fields.put("consentVersion", "\"" + AdmissionsService.CONSENT_VERSION + "\"");
        fields.put("website", "\"\"");
        fields.putAll(overrides);
        StringBuilder json = new StringBuilder("{");
        fields.forEach((k, v) -> json.append(json.length() > 1 ? "," : "").append('"').append(k).append("\":").append(v));
        return json.append('}').toString();
    }

    private ResultActions info(String code, String from) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get("/api/public/schools/" + code + "/admission-info")
                .with(request -> {
                    request.setRemoteAddr(from);
                    return request;
                }));
    }

    private ResultActions enquire(String code, String json, String from) throws Exception {
        return enquire(code, json, from, null);
    }

    private ResultActions enquire(String code, String json, String from, String token) throws Exception {
        var request = MockMvcRequestBuilders.post("/api/public/schools/" + code + "/enquiries")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .with(r -> {
                    r.setRemoteAddr(from);
                    return r;
                });
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return mvc.perform(request);
    }

    private static String randomIp() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        return "10." + r.nextInt(256) + "." + r.nextInt(256) + "." + (1 + r.nextInt(254));
    }

    private static long countApplications(UUID tenantId) throws Exception {
        try (Connection owner = ownerConnection();
                PreparedStatement s = owner.prepareStatement(
                        "select count(*) from admissions.application where tenant_id = ?")) {
            s.setObject(1, tenantId);
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }
}
