package com.akshara.fees;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.akshara.support.FeeFixtures;
import com.akshara.support.FeeFixtures.Heads;
import com.akshara.support.IntegrationTest;
import com.akshara.support.SchoolFixtures;
import com.akshara.support.TestApi;
import com.akshara.support.TestApi.School;
import com.akshara.support.TestApi.Session;
import com.jayway.jsonpath.JsonPath;

/** Who may do what with fees, and that schools never see or touch each other's fee records. */
class FeeAccessIT extends IntegrationTest {

    @Test
    void eachRoleGetsOnlyItsFeePermissions() throws Exception {
        School school = api.signup();
        Session admin = api.login(school);
        SchoolFixtures fixtures = new SchoolFixtures(api);
        FeeFixtures fees = new FeeFixtures(api, mvc);
        String yearId = fixtures.currentYear(admin);
        String classId = fixtures.schoolClass(admin, "Class 3");
        String student = fixtures.student(admin, fixtures.section(admin, classId, "A", 40), "A-1", "Ira", "Bose",
                "Mitali Bose", "9876501011");
        Heads heads = fees.defaultHeads(admin);
        String structure = fees.publishedStructure(admin, yearId, classId, heads);
        String receipt = fees.cash(admin, student, 1_000_00);

        Session teacher = session(school, admin, "teacher", "TEACHER");
        Session principal = session(school, admin, "principal", "PRINCIPAL");
        Session accounts = session(school, admin, "accounts", "ACCOUNTANT");
        Session frontOffice = session(school, admin, "front", "FRONT_OFFICE");

        // Built-in roles: the accountant also manages fees; the principal only reads them.
        api.get("/api/roles", admin.accessToken())
                .andExpect(jsonPath("$[?(@.code == 'ACCOUNTANT')].permissions[*]", hasItem("fees.manage")))
                .andExpect(jsonPath("$[?(@.code == 'SCHOOL_ADMIN')].permissions[*]", hasItem("fees.manage")))
                .andExpect(jsonPath("$[?(@.code == 'PRINCIPAL')].permissions[*]", not(hasItem("fees.manage"))));

        for (Session outsider : List.of(teacher, frontOffice)) {
            api.get("/api/fees/heads", outsider.accessToken()).andExpect(status().isForbidden());
            api.get("/api/fees/students/" + student, outsider.accessToken()).andExpect(status().isForbidden());
            api.get("/api/fees/reports/overview", outsider.accessToken()).andExpect(status().isForbidden());
            api.get("/api/fees/receipts/" + receipt, outsider.accessToken()).andExpect(status().isForbidden());
            api.post("/api/fees/students/" + student + "/payments", outsider.accessToken(), """
                    {"amountPaise":100,"mode":"CASH"}""").andExpect(status().isForbidden());
            api.get("/api/me/children/" + student + "/fees", outsider.accessToken())
                    .andExpect(status().isForbidden());
        }

        // The principal can look at everything and change nothing.
        api.get("/api/fees/reports/overview", principal.accessToken()).andExpect(status().isOk());
        api.get("/api/fees/structures/" + structure, principal.accessToken()).andExpect(status().isOk());
        api.get("/api/fees/students/" + student, principal.accessToken()).andExpect(status().isOk());
        api.get("/api/fees/receipts", principal.accessToken()).andExpect(status().isOk());
        api.get("/api/fees/reports/overdue", principal.accessToken()).andExpect(status().isOk());
        api.post("/api/fees/students/" + student + "/payments", principal.accessToken(), """
                {"amountPaise":100,"mode":"CASH"}""").andExpect(status().isForbidden());
        api.post("/api/fees/heads", principal.accessToken(), """
                {"name":"Sports fee","kind":"OTHER"}""").andExpect(status().isForbidden());
        api.post("/api/fees/structures/" + structure + "/publish", principal.accessToken(), null)
                .andExpect(status().isForbidden());
        api.post("/api/fees/receipts/" + receipt + "/cancel", principal.accessToken(), """
                {"reason":"Mistake"}""").andExpect(status().isForbidden());
        api.put("/api/fees/late-fee-rule", principal.accessToken(), """
                {"mode":"NONE"}""").andExpect(status().isForbidden());
        api.post("/api/fees/reminders", principal.accessToken(), """
                {"studentIds":["%s"]}""".formatted(student)).andExpect(status().isForbidden());
        api.post("/api/fees/concessions", principal.accessToken(), """
                {"studentId":"%s","type":"RTE","reason":"RTE"}""".formatted(student))
                .andExpect(status().isForbidden());

        // The accountant collects and manages.
        api.post("/api/fees/students/" + student + "/payments", accounts.accessToken(), """
                {"amountPaise":100,"mode":"CASH"}""").andExpect(status().isCreated());
        api.post("/api/fees/heads", accounts.accessToken(), """
                {"name":"Sports fee","kind":"OTHER"}""").andExpect(status().isCreated());
        api.post("/api/fees/receipts/" + receipt + "/cancel", accounts.accessToken(), """
                {"reason":"Entered twice"}""").andExpect(status().isOk());
        api.put("/api/fees/late-fee-rule", accounts.accessToken(), """
                {"mode":"NONE"}""").andExpect(status().isOk());

        // Webhooks are public, but only signed ones do anything.
        fees.webhook("sandbox", "{}", null).andExpect(status().isBadRequest());
    }

    @Test
    void schoolsNeverSeeOrTouchEachOthersFees() throws Exception {
        School school = api.signup();
        Session admin = api.login(school);
        SchoolFixtures fixtures = new SchoolFixtures(api);
        FeeFixtures fees = new FeeFixtures(api, mvc);
        String yearId = fixtures.currentYear(admin);
        String classId = fixtures.schoolClass(admin, "Class 3");
        String student = fixtures.student(admin, fixtures.section(admin, classId, "A", 40), "A-1", "Ira", "Bose",
                "Mitali Bose", "9876501011");
        Heads heads = fees.defaultHeads(admin);
        String structure = fees.publishedStructure(admin, yearId, classId, heads);
        String receipt = fees.cash(admin, student, 1_000_00);
        String concession = TestApi.read(api.post("/api/fees/concessions", admin.accessToken(), """
                {"studentId":"%s","type":"RTE","reason":"RTE quota"}""".formatted(student)), "$.id");
        String instalment = fees.instalmentIds(admin, student).get(1);
        String order = api.post("/api/fees/students/" + student + "/orders", admin.accessToken(), """
                {"instalmentIds":["%s"]}""".formatted(instalment)).andReturn().getResponse().getContentAsString();
        String orderId = JsonPath.read(order, "$.id");
        String gatewayOrderId = JsonPath.read(order, "$.gatewayOrderId");

        School other = api.signup();
        Session intruder = api.login(other);
        String otherYear = fixtures.currentYear(intruder);
        String otherClass = fixtures.schoolClass(intruder, "Class 3");
        String otherStudent = fixtures.student(intruder, fixtures.section(intruder, otherClass, "A", 40), "A-1",
                "Om", "Rao", "Lata Rao", "9876501012");
        Heads otherHeads = fees.defaultHeads(intruder);
        fees.publishedStructure(intruder, otherYear, otherClass, otherHeads);

        // Ids of the other school in the path are simply not found.
        String token = intruder.accessToken();
        api.get("/api/fees/structures/" + structure, token).andExpect(status().isNotFound());
        api.put("/api/fees/structures/" + structure, token, FeeFixtures.structureJson(otherYear, otherClass,
                otherHeads, 1_00, 0, FeeFixtures.quarters())).andExpect(status().isNotFound());
        api.post("/api/fees/structures/" + structure + "/publish", token, null).andExpect(status().isNotFound());
        api.put("/api/fees/heads/" + heads.tuition(), token, """
                {"name":"Mine now","kind":"OTHER"}""").andExpect(status().isNotFound());
        api.get("/api/fees/students/" + student, token).andExpect(status().isNotFound());
        api.post("/api/fees/students/" + student + "/payments", token, """
                {"amountPaise":100,"mode":"CASH"}""").andExpect(status().isNotFound());
        api.post("/api/fees/students/" + student + "/orders", token, """
                {"instalmentIds":["%s"]}""".formatted(instalment)).andExpect(status().isNotFound());
        api.get("/api/fees/receipts/" + receipt, token).andExpect(status().isNotFound());
        api.post("/api/fees/receipts/" + receipt + "/cancel", token, """
                {"reason":"Mine now"}""").andExpect(status().isNotFound());
        api.post("/api/fees/concessions/" + concession + "/revoke", token, """
                {"reason":"Mine now"}""").andExpect(status().isNotFound());
        api.get("/api/fees/orders/" + orderId, token).andExpect(status().isNotFound());
        api.get("/api/payments/sandbox/orders/" + gatewayOrderId, token).andExpect(status().isNotFound());
        api.get("/api/fees/concessions?studentId=" + student, token).andExpect(status().isNotFound());

        // Ids of the other school in a request body are a 400 on that field.
        api.post("/api/fees/concessions", token, """
                {"studentId":"%s","type":"RTE","reason":"RTE quota"}""".formatted(student))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.studentId").exists());
        api.post("/api/fees/reminders", token, """
                {"studentIds":["%s"]}""".formatted(student))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.studentIds").exists());
        api.post("/api/fees/students/" + otherStudent + "/payments", token, """
                {"amountPaise":100,"mode":"CASH","instalmentIds":["%s"]}""".formatted(instalment))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.instalmentIds").exists());
        api.post("/api/fees/students/" + otherStudent + "/late-fee-waivers", token, """
                {"instalmentId":"%s","reason":"Kind"}""".formatted(instalment))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.instalmentId").exists());

        // Lists and reports show only the school's own records.
        api.get("/api/fees/receipts", token).andExpect(jsonPath("$.total").value(0));
        api.get("/api/fees/concessions", token).andExpect(jsonPath("$.length()").value(0));
        api.get("/api/fees/reports/overdue", token)
                .andExpect(jsonPath("$.rows[*].fullName", not(hasItem("Ira Bose"))));
        api.get("/api/fees/heads", token).andExpect(jsonPath("$.length()").value(6));
        api.get("/api/fees/students?q=Ira", token).andExpect(jsonPath("$.length()").value(0));

        // And nothing of the first school changed.
        api.get("/api/fees/receipts/" + receipt, admin.accessToken()).andExpect(jsonPath("$.status").value("ISSUED"));
        api.get("/api/fees/heads", admin.accessToken()).andExpect(jsonPath("$[0].name").value("Tuition fee"));
    }

    private Session session(School school, Session admin, String name, String role) throws Exception {
        String email = name + "@" + school.code() + ".akshara.test";
        api.createUser(admin, name, email, List.of(role));
        return api.login(school.code(), email, TestApi.PASSWORD);
    }
}
