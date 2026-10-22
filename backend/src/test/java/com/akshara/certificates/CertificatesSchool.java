package com.akshara.certificates;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import com.akshara.support.SchoolFixtures;
import com.akshara.support.TestApi;
import com.akshara.support.TestApi.School;
import com.akshara.support.TestApi.Session;

/**
 * A school for the certificate tests: a current year, Class 5 A with three students (Asha Rao, whose mother Lata
 * has a sign-in), a front-office clerk (prepares), a principal (signs), a teacher (neither) and one member of staff
 * with a profile (Tara, for staff certificates).
 */
final class CertificatesSchool {

    final TestApi api;
    final School school;
    final Session admin;
    final Session office;
    final Session principal;
    final Session teacher;
    final Session parent;
    final String teacherId;
    final String classId;
    final String section;
    final String asha;
    final String bala;
    final String chitra;

    CertificatesSchool(TestApi api) throws Exception {
        this.api = api;
        school = api.signup();
        admin = api.login(school);
        SchoolFixtures fixtures = new SchoolFixtures(api);
        fixtures.currentYear(admin);
        classId = fixtures.schoolClass(admin, "Class 5");
        section = fixtures.section(admin, classId, "A", 40);
        asha = fixtures.student(admin, section, "CRT-1", "Asha", "Rao", "Lata Rao", "9876501001");
        bala = fixtures.student(admin, section, "CRT-2", "Bala", "Iyer", "Uma Iyer", "9876501002");
        chitra = fixtures.student(admin, section, "CRT-3", "Chitra", "Das", "Mala Das", "9876501003");
        String domain = "@" + school.code() + ".akshara.test";
        api.createUser(admin, "Olive Office", "olive" + domain, List.of("FRONT_OFFICE"));
        office = api.login(school.code(), "olive" + domain, TestApi.PASSWORD);
        api.createUser(admin, "Prem Principal", "prem" + domain, List.of("PRINCIPAL"));
        principal = api.login(school.code(), "prem" + domain, TestApi.PASSWORD);
        teacherId = TestApi.read(api.post("/api/staff", admin.accessToken(), """
                {"name":"Tara Teacher","email":"tara%s","password":"%s","roles":["TEACHER"],"employeeCode":"EMP-7",
                 "designation":"PGT Mathematics","employmentType":"PERMANENT","dateOfJoining":"2021-06-01",
                 "mobile":"9848012345","emergencyContactName":"Tarun","emergencyContactMobile":"9848054321"}"""
                .formatted(domain, TestApi.PASSWORD)).andExpect(status().isCreated()), "$.userId");
        teacher = api.login(school.code(), "tara" + domain, TestApi.PASSWORD);
        String guardian = TestApi.read(api.get("/api/students/" + asha, admin.accessToken()), "$.guardians[0].id");
        api.post("/api/students/" + asha + "/guardians/" + guardian + "/sign-in", admin.accessToken(), """
                {"mode":"CREATE","email":"%s","password":"%s"}""".formatted("lata" + domain, TestApi.PASSWORD))
                .andExpect(status().isOk());
        parent = api.login(school.code(), "lata" + domain, TestApi.PASSWORD);
    }

    /** The office prepares a certificate for a student and returns its id. */
    String prepare(String type, String studentId, String purpose) throws Exception {
        return TestApi.read(api.post("/api/certificates", office.accessToken(), """
                {"type":"%s","studentId":"%s","purpose":"%s"}""".formatted(type, studentId, purpose))
                .andExpect(status().isCreated()), "$.id");
    }

    /** The principal signs a prepared certificate and returns its serial number. */
    String sign(String id) throws Exception {
        return TestApi.read(api.post("/api/certificates/" + id + "/sign", principal.accessToken(), "")
                .andExpect(status().isOk()), "$.serialNo");
    }

    /** A bonafide certificate for a student, prepared and signed; returns its verification token. */
    String issued(String studentId) throws Exception {
        String id = prepare("BONAFIDE", studentId, "Passport");
        sign(id);
        return TestApi.read(api.get("/api/certificates/" + id, office.accessToken()), "$.verifyToken");
    }
}
