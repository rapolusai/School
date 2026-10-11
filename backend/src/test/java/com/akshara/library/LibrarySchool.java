package com.akshara.library;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;

import com.akshara.support.SchoolFixtures;
import com.akshara.support.TestApi;
import com.akshara.support.TestApi.School;
import com.akshara.support.TestApi.Session;

/**
 * A school set up for the library tests through the API: a librarian, a teacher (who may only search the catalogue),
 * Class 5 A with three students, and the first student's mother with a sign-in.
 */
final class LibrarySchool {

    final TestApi api;
    final School school;
    final Session admin;
    final Session librarian;
    final Session teacher;
    final String teacherId;
    final String classId;
    final String section;
    /** Asha Rao, whose mother Lata has a sign-in. */
    final String asha;
    final String bala;
    final String chitra;
    final Session parent;

    LibrarySchool(TestApi api) throws Exception {
        this.api = api;
        school = api.signup();
        admin = api.login(school);
        SchoolFixtures fixtures = new SchoolFixtures(api);
        fixtures.currentYear(admin);
        classId = fixtures.schoolClass(admin, "Class 5");
        section = fixtures.section(admin, classId, "A", 40);
        asha = fixtures.student(admin, section, "LIB-1", "Asha", "Rao", "Lata Rao", "9876501001");
        bala = fixtures.student(admin, section, "LIB-2", "Bala", "Iyer", "Uma Iyer", "9876501002");
        chitra = fixtures.student(admin, section, "LIB-3", "Chitra", "Das", "Mala Das", "9876501003");
        String domain = "@" + school.code() + ".akshara.test";
        api.createUser(admin, "Libby Librarian", "libby" + domain, List.of("LIBRARIAN"));
        librarian = api.login(school.code(), "libby" + domain, TestApi.PASSWORD);
        teacherId = api.createUser(admin, "Tara Teacher", "tara" + domain, List.of("TEACHER"));
        teacher = api.login(school.code(), "tara" + domain, TestApi.PASSWORD);
        String guardian = TestApi.read(api.get("/api/students/" + asha, admin.accessToken()), "$.guardians[0].id");
        api.post("/api/students/" + asha + "/guardians/" + guardian + "/sign-in", admin.accessToken(), """
                {"mode":"CREATE","email":"%s","password":"%s"}""".formatted("lata" + domain, TestApi.PASSWORD))
                .andExpect(status().isOk());
        parent = api.login(school.code(), "lata" + domain, TestApi.PASSWORD);
    }

    /** Adds a title with this many copies and returns its id. */
    String title(String name, int copies) throws Exception {
        return TestApi.read(api.post("/api/library/titles", librarian.accessToken(), """
                {"title":"%s","authors":"Test Author","copies":%d,"pricePaise":25000}""".formatted(
                name.replace("\"", "\\\""), copies))
                .andExpect(status().isCreated()), "$.id");
    }

    /** The accession number of a title's n-th copy (0-based). */
    String accession(String titleId, int index) throws Exception {
        return TestApi.read(api.get("/api/library/titles/" + titleId, librarian.accessToken()),
                "$.copies[" + index + "].accessionNo");
    }

    /** Lends a copy (issued on the given day, or today when null) and returns the loan id. */
    String issue(String accessionNo, String memberType, String memberId, LocalDate issuedOn) throws Exception {
        return TestApi.read(api.post("/api/library/loans", librarian.accessToken(), """
                {"accessionNo":"%s","memberType":"%s","memberId":"%s","issuedOn":%s}""".formatted(accessionNo,
                memberType, memberId, issuedOn == null ? "null" : "\"" + issuedOn + "\""))
                .andExpect(status().isCreated()), "$.id");
    }

    static LocalDate today() {
        return LoanMath.today();
    }
}
