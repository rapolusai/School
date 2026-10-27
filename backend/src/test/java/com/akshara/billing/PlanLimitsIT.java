package com.akshara.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.akshara.support.BillingFixtures;
import com.akshara.support.FeeFixtures;
import com.akshara.support.IntegrationTest;
import com.akshara.support.SchoolFixtures;
import com.akshara.support.TestApi;
import com.akshara.support.TestApi.School;
import com.akshara.support.TestApi.Session;

import tools.jackson.databind.json.JsonMapper;

/**
 * The school's plan enforced: the student limit on every way students are added, and the features a plan leaves out
 * refused by the API. Neither applies during the trial.
 */
class PlanLimitsIT extends IntegrationTest {

    static final String CSV_HEADER = "admission_no,first_name,last_name,date_of_birth,gender,class,section,"
            + "guardian_name,guardian_relation,guardian_phone,guardian_email";

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void aStarterSchoolCannotGoPastThreeHundredStudentsOnRollHoweverTheyAreAdded() throws Exception {
        BillingFixtures billing = new BillingFixtures(api);
        Session root = billing.root();
        School school = api.signup();
        Session admin = api.login(school);
        SchoolFixtures fixtures = new SchoolFixtures(api);
        String yearId = fixtures.currentYear(admin);
        String classFive = fixtures.schoolClass(admin, "Class 5");
        String sectionA = fixtures.section(admin, classFive, "A", 400);

        // In the trial nothing is limited: 299 by file and one by hand.
        api.post("/api/students/import?dryRun=false", admin.accessToken(), csv(1, 299))
                .andExpect(status().isOk()).andExpect(jsonPath("$.created").value(299));
        String last = fixtures.student(admin, sectionA, "CAP/300", "Last", "One", "Parent Three", "9700000300");

        billing.details(root, school.tenantId(), "36", null);
        billing.start(root, school.tenantId(), "STARTER", "YEARLY", 300, null);
        api.get("/api/billing/plan-access", admin.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan").value("STARTER"))
                .andExpect(jsonPath("$.maxStudents").value(300))
                .andExpect(jsonPath("$.activeStudents").value(300));

        // One more by hand, by file or from admissions: 409 with the numbers, and nothing written.
        api.post("/api/students", admin.accessToken(), SchoolFixtures.studentJson(sectionA, "CAP/301", "One", "More",
                "Parent", "9700000301", null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(PlanLimits.STUDENT_LIMIT_TYPE))
                .andExpect(jsonPath("$.maxStudents").value(300))
                .andExpect(jsonPath("$.activeStudents").value(300))
                .andExpect(jsonPath("$.adding").value(1));
        api.post("/api/students/import?dryRun=false", admin.accessToken(), csv(400, 2))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(PlanLimits.STUDENT_LIMIT_TYPE))
                .andExpect(jsonPath("$.adding").value(2));
        String application = TestApi.read(api.post("/api/admissions/applications", admin.accessToken(), """
                {"stage":"APPLICATION","firstName":"Asha","dateOfBirth":"2020-01-15","classId":"%s",
                 "academicYearId":"%s","source":"WALK_IN",
                 "guardians":[{"name":"Rekha","relation":"MOTHER","phone":"9700000999","primary":true}]}"""
                .formatted(classFive, yearId)).andExpect(status().isCreated()), "$.id");
        api.post("/api/admissions/applications/" + application + "/stage", admin.accessToken(), """
                {"stage":"OFFERED"}""").andExpect(status().isOk());
        String admit = """
                {"sectionId":"%s","admissionNo":"CAP/ADM","gender":"FEMALE"}""".formatted(sectionA);
        api.post("/api/admissions/applications/" + application + "/admit", admin.accessToken(), admit)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(PlanLimits.STUDENT_LIMIT_TYPE));
        api.get("/api/students?size=1", admin.accessToken()).andExpect(jsonPath("$.total").value(300));

        // A student leaves: there is room for one again.
        api.post("/api/students/" + last + "/leave", admin.accessToken(), """
                {"status":"TRANSFERRED","leftOn":"%s","reason":"Family moved"}""".formatted(FeeFixtures.today()))
                .andExpect(status().isOk());
        api.post("/api/admissions/applications/" + application + "/admit", admin.accessToken(), admit)
                .andExpect(status().isOk());
        api.get("/api/billing/plan-access", admin.accessToken()).andExpect(jsonPath("$.activeStudents").value(300));
    }

    @Test
    void featuresTheStarterPlanLeavesOutAreRefusedOnceTheSchoolPays() throws Exception {
        BillingFixtures billing = new BillingFixtures(api);
        Session root = billing.root();
        School school = api.signup();
        Session admin = api.login(school);
        List<String> gated = List.of("/api/transport/vehicles", "/api/library/rules", "/api/inventory/lookups",
                "/api/me/library");

        // The trial tries everything.
        for (String path : gated) {
            api.get(path, admin.accessToken()).andExpect(jsonPath("$.type").doesNotExist());
        }
        api.get("/api/billing/plan-access", admin.accessToken())
                .andExpect(jsonPath("$.status").value("TRIAL"))
                .andExpect(jsonPath("$.enforced").value(false))
                .andExpect(jsonPath("$.lockedModules.length()").value(0))
                .andExpect(jsonPath("$.excludedFeatures[0]").value("transport_library"))
                .andExpect(jsonPath("$.excludedFeatures[1]").value("report_cards"));

        billing.details(root, school.tenantId(), "36", null);
        billing.start(root, school.tenantId(), "STARTER", "YEARLY", 20, null);
        for (String path : gated) {
            api.get(path, admin.accessToken())
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.type").value(PlanFeatures.TYPE))
                    .andExpect(jsonPath("$.feature").value("transport_library"))
                    .andExpect(jsonPath("$.plan").value("STARTER"));
        }
        // Report cards, whatever the parameters; writes too.
        api.get("/api/exams/report-cards?sectionId=x", admin.accessToken())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.feature").value("report_cards"));
        api.post("/api/transport/vehicles", admin.accessToken(), "{}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(PlanFeatures.TYPE));
        // Someone without the module's permission gets the plan answer too (the plan is checked first).
        String email = "accountant@" + school.code() + ".akshara.test";
        api.createUser(admin, "Accountant Member", email, List.of("ACCOUNTANT"));
        Session accountant = api.login(school.code(), email, TestApi.PASSWORD);
        api.get("/api/library/rules", accountant.accessToken())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(PlanFeatures.TYPE));
        api.get("/api/billing/plan-access", accountant.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enforced").value(true))
                .andExpect(jsonPath("$.lockedFeatures.length()").value(2))
                .andExpect(jsonPath("$.lockedModules[0]").value("transport"))
                .andExpect(jsonPath("$.lockedModules[1]").value("library"))
                .andExpect(jsonPath("$.lockedModules[2]").value("inventory"))
                .andExpect(jsonPath("$.lockedModules[3]").value("report_cards"));
        // The rest of the app is not affected, nor are bus devices (public, signed by the device).
        api.get("/api/students", admin.accessToken()).andExpect(status().isOk());
        api.post("/api/public/transport/vehicles/00000000-0000-0000-0000-000000000000/positions", null, "{}")
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .doesNotContain(PlanFeatures.TYPE));
    }

    @Test
    void aGrowthSchoolHasTransportLibraryAndReportCardsAndNoOtherSchoolIsAffected() throws Exception {
        BillingFixtures billing = new BillingFixtures(api);
        Session root = billing.root();
        School growth = api.signup();
        Session admin = api.login(growth);
        billing.paying(root, growth.tenantId(), "36");
        for (String path : List.of("/api/transport/vehicles", "/api/library/rules", "/api/inventory/lookups")) {
            api.get(path, admin.accessToken()).andExpect(status().isOk());
        }
        api.get("/api/exams/report-cards?sectionId=x", admin.accessToken())
                .andExpect(status().isBadRequest());
        api.get("/api/billing/plan-access", admin.accessToken())
                .andExpect(jsonPath("$.plan").value("GROWTH"))
                .andExpect(jsonPath("$.enforced").value(true))
                .andExpect(jsonPath("$.lockedModules.length()").value(0))
                .andExpect(jsonPath("$.maxStudents").value(1500));

        // A Starter school paying next door does not change what this one may use.
        School starter = api.signup();
        billing.details(root, starter.tenantId(), "36", null);
        billing.start(root, starter.tenantId(), "STARTER", "YEARLY", 20, null);
        api.get("/api/library/rules", admin.accessToken()).andExpect(status().isOk());
        api.get("/api/library/rules", api.login(starter).accessToken()).andExpect(status().isForbidden());
        // The platform console has no school plan.
        api.get("/api/billing/plan-access", root.accessToken()).andExpect(status().isNotFound());
    }

    private String csv(int from, int count) throws Exception {
        StringBuilder csv = new StringBuilder(CSV_HEADER).append('\n');
        for (int n = from; n < from + count; n++) {
            csv.append(String.format(Locale.ROOT, "CAP/%d,Student%d,,2015-01-01,M,Class 5,A,Parent %d,MOTHER,98%08d,%n",
                    n, n, n, n));
        }
        return json.writeValueAsString(Map.of("csv", csv.toString()));
    }
}
