package com.akshara.govreport;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import com.akshara.support.SchoolFixtures;
import com.akshara.support.TestApi;
import com.akshara.support.TestApi.School;
import com.akshara.support.TestApi.Session;

/**
 * A school for the government reporting tests: a current year (2026-27), Class 1 A with three students (Asha Rao,
 * whose mother Lata has a sign-in), Class 9 A with one student, a principal (manages), a front-office clerk (reads
 * only), a teacher (neither) and a parent.
 */
final class GovReportSchool {

    final TestApi api;
    final School school;
    final Session admin;
    final Session principal;
    final Session office;
    final Session teacher;
    final Session parent;
    final String yearId;
    final String classId;
    final String section;
    final String class9;
    final String section9;
    final String asha;
    final String bala;
    final String chitra;
    final String dev;

    GovReportSchool(TestApi api) throws Exception {
        this.api = api;
        school = api.signup();
        admin = api.login(school);
        SchoolFixtures fixtures = new SchoolFixtures(api);
        yearId = fixtures.currentYear(admin);
        classId = fixtures.schoolClass(admin, "Class 1");
        section = fixtures.section(admin, classId, "A", 40);
        class9 = fixtures.schoolClass(admin, "Class 9");
        section9 = fixtures.section(admin, class9, "A", 40);
        asha = fixtures.student(admin, section, "GOV-1", "Asha", "Rao", "Lata Rao", "9876501001");
        bala = fixtures.student(admin, section, "GOV-2", "Bala", "Iyer", "Uma Iyer", "9876501002");
        chitra = fixtures.student(admin, section, "GOV-3", "Chitra", "Das", "Mala Das", "9876501003");
        dev = fixtures.student(admin, section9, "GOV-9", "Dev", "Nair", "Kala Nair", "9876501009");
        String domain = "@" + school.code() + ".akshara.test";
        api.createUser(admin, "Prem Principal", "prem" + domain, List.of("PRINCIPAL"));
        principal = api.login(school.code(), "prem" + domain, TestApi.PASSWORD);
        api.createUser(admin, "Olive Office", "olive" + domain, List.of("FRONT_OFFICE"));
        office = api.login(school.code(), "olive" + domain, TestApi.PASSWORD);
        api.createUser(admin, "Tara Teacher", "tara" + domain, List.of("TEACHER"));
        teacher = api.login(school.code(), "tara" + domain, TestApi.PASSWORD);
        String guardian = TestApi.read(api.get("/api/students/" + asha, admin.accessToken()), "$.guardians[0].id");
        api.post("/api/students/" + asha + "/guardians/" + guardian + "/sign-in", admin.accessToken(), """
                {"mode":"CREATE","email":"%s","password":"%s"}""".formatted("lata" + domain, TestApi.PASSWORD))
                .andExpect(status().isOk());
        parent = api.login(school.code(), "lata" + domain, TestApi.PASSWORD);
    }

    /** An admissions application for Class 1 in the current year; returns its id. */
    String application(String firstName, String phone) throws Exception {
        return TestApi.read(api.post("/api/admissions/applications", admin.accessToken(), """
                {"stage":"APPLICATION","firstName":"%s","lastName":"Kumar","dateOfBirth":"2020-05-01",
                 "gender":"FEMALE","classId":"%s","academicYearId":"%s","source":"WALK_IN",
                 "guardians":[{"name":"Meena %s","relation":"MOTHER","phone":"%s","primary":true}]}"""
                .formatted(firstName, classId, yearId, firstName, phone)).andExpect(status().isCreated()), "$.id");
    }

    /** An RTE intake for Class 1 with the given seats; returns its id. */
    String intake(int totalSeats, Integer rteSeats) throws Exception {
        return TestApi.read(api.post("/api/govreport/rte/intakes", principal.accessToken(), """
                {"academicYearId":"%s","entryClassId":"%s","totalSeats":%d,"rteSeats":%s,"stateCode":"TS"}"""
                .formatted(yearId, classId, totalSeats, rteSeats)).andExpect(status().isCreated()), "$.id");
    }

    /** Adds an application to the intake and screens it; returns the RTE application id. */
    String screened(String intakeId, String applicationId, String eligibility, String km) throws Exception {
        String id = TestApi.read(api.post("/api/govreport/rte/intakes/" + intakeId + "/applications",
                principal.accessToken(), """
                {"applicationId":"%s"}""".formatted(applicationId)).andExpect(status().isCreated()), "$.id");
        api.put("/api/govreport/rte/applications/" + id, principal.accessToken(), """
                {"categoryCode":"SC","annualIncomePaise":5000000,"incomeDeclared":true,"distanceKm":%s,
                 "documents":{"BIRTH_CERT":true},"eligibility":"%s","ineligibleReason":%s}"""
                .formatted(km, eligibility, eligibility.equals("INELIGIBLE") ? "\"Outside the neighbourhood\"" : "null"))
                .andExpect(status().isOk());
        return id;
    }
}
