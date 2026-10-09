package com.akshara.students;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.akshara.support.IntegrationTest;
import com.akshara.support.SchoolFixtures;
import com.akshara.support.TestApi.School;
import com.akshara.support.TestApi.Session;

import tools.jackson.databind.json.JsonMapper;

/** Bulk admission from CSV: a dry run reports, a real run writes every row or none. */
class StudentImportIT extends IntegrationTest {

    static final String HEADER = "admission_no,first_name,last_name,date_of_birth,gender,class,section,"
            + "guardian_name,guardian_relation,guardian_phone,guardian_email";

    private final JsonMapper json = JsonMapper.builder().build();

    private School school;
    private Session admin;
    private SchoolFixtures fixtures;
    private String sectionA;

    @BeforeEach
    void schoolWithAClass() throws Exception {
        school = api.signup();
        admin = api.login(school);
        fixtures = new SchoolFixtures(api);
        fixtures.currentYear(admin);
        String classFive = fixtures.schoolClass(admin, "Class 5");
        sectionA = fixtures.section(admin, classFive, "A", 40);
        fixtures.section(admin, classFive, "B", 1);
    }

    @Test
    void aDryRunChecksWithoutWritingAndACommitWritesEveryRow() throws Exception {
        String csv = "﻿" + HEADER + ",roll_no,house\r\n"
                + "IMP/1,Arjun,Sharma,2015-05-14,M,Class 5,A,Anitha Sharma,MOTHER,+91 98765 00001,,3,Blue\r\n"
                + "IMP/2,Diya,Sharma,14/09/2018,female,5,A,Anitha Sharma,Mother,9876500001,anitha@family.test,,Red\r\n"
                + "IMP/3,\"Kabir, Jr\",,01-01-2016,M,Class 5,A,\"Imran \"\"Bhai\"\" Khan\",FATHER,9876500003,,,\r\n"
                + "\r\n";

        api.post("/api/students/import", admin.accessToken(), body(csv))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dryRun").value(true))
                .andExpect(jsonPath("$.committed").value(false))
                .andExpect(jsonPath("$.totalRows").value(3))
                .andExpect(jsonPath("$.validRows").value(3))
                .andExpect(jsonPath("$.invalidRows").value(0))
                .andExpect(jsonPath("$.created").value(0))
                .andExpect(jsonPath("$.ignoredColumns[0]").value("house"));
        api.get("/api/students", admin.accessToken()).andExpect(jsonPath("$.total").value(0));

        api.post("/api/students/import?dryRun=false", admin.accessToken(), body(csv))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.committed").value(true))
                .andExpect(jsonPath("$.created").value(3));
        api.get("/api/students?sectionId=" + sectionA, admin.accessToken())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.items[0].firstName").value("Arjun"))
                .andExpect(jsonPath("$.items[0].rollNo").value(3));
        String diya = com.akshara.support.TestApi.read(api.get("/api/students?q=diya", admin.accessToken()),
                "$.items[0].id");
        // The two Sharma children share one mother record, so they show as siblings.
        api.get("/api/students/" + diya, admin.accessToken())
                .andExpect(jsonPath("$.dateOfBirth").value("2018-09-14"))
                .andExpect(jsonPath("$.guardians[0].email").value("anitha@family.test"))
                .andExpect(jsonPath("$.siblings[0].fullName").value("Arjun Sharma"));
        api.get("/api/students?q=kabir", admin.accessToken())
                .andExpect(jsonPath("$.items[0].fullName").value("Kabir, Jr"))
                .andExpect(jsonPath("$.items[0].guardianName").value("Imran \"Bhai\" Khan"));
        api.get("/api/audit-events?limit=50", admin.accessToken())
                .andExpect(jsonPath("$[*].action", hasItem("students.imported")));

        // The same file again clashes with the admission numbers now in use.
        api.post("/api/students/import", admin.accessToken(), body(csv))
                .andExpect(jsonPath("$.invalidRows").value(3))
                .andExpect(jsonPath("$.errors[0].column").value("admission_no"));
    }

    @Test
    void oneBadRowMeansNothingIsImported() throws Exception {
        String csv = HEADER + ",roll_no\n"
                + "OK/1,Asha,Rao,2015-01-01,F,Class 5,A,Lata Rao,MOTHER,9876501001,,\n"
                + "OK/2,Bala,,2015-02-02,M,Class 5,C,Mohan,FATHER,12345,not-an-email,\n"
                + "OK/1,Chitra,,31-02-2015,X,Class 5,A,,AUNT,9876501003,,0\n"
                + "OK/4,Deepa,,2015-03-03,F,Class 5,B,Kala,MOTHER,9876501004,,\n"
                + "OK/5,Esha,,2015-03-04,F,Class 5,B,Kala,MOTHER,9876501004,,\n";

        api.post("/api/students/import?dryRun=false", admin.accessToken(), body(csv))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Check the file"))
                .andExpect(jsonPath("$.result.committed").value(false))
                .andExpect(jsonPath("$.result.totalRows").value(5))
                .andExpect(jsonPath("$.result.validRows").value(2))
                .andExpect(jsonPath("$.result.invalidRows").value(3))
                .andExpect(jsonPath("$.result.errors[?(@.row == 3)].column",
                        org.hamcrest.Matchers.hasItems("section", "guardian_phone", "guardian_email")))
                .andExpect(jsonPath("$.result.errors[?(@.row == 4)].column",
                        org.hamcrest.Matchers.hasItems("admission_no", "date_of_birth", "gender", "guardian_name",
                                "guardian_relation", "roll_no")))
                .andExpect(jsonPath("$.result.errors[?(@.row == 6)].message",
                        hasItem(containsString("is full"))));
        api.get("/api/students", admin.accessToken()).andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void theFileItselfMustBeUsable() throws Exception {
        api.post("/api/students/import", admin.accessToken(), body(""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.csv").exists());
        api.post("/api/students/import", admin.accessToken(), body(HEADER + "\n"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.csv", containsString("no students")));
        api.post("/api/students/import", admin.accessToken(), body("admission_no,first_name\nA,B\n"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.csv", containsString("missing")));
        api.post("/api/students/import", admin.accessToken(), body(HEADER + "\n\"A,B\n"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.csv", containsString("Line 2")));

        StringBuilder big = new StringBuilder(HEADER).append('\n');
        for (int i = 0; i < StudentImportService.MAX_ROWS + 1; i++) {
            big.append("B/").append(i).append(",Name,,2015-01-01,F,Class 5,A,Parent,MOTHER,9876501001,\n");
        }
        api.post("/api/students/import", admin.accessToken(), body(big.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.csv", containsString("2,000")));
    }

    @Test
    void importNeedsACurrentYearAndTheRightPermission() throws Exception {
        School fresh = api.signup();
        Session freshAdmin = api.login(fresh);
        api.post("/api/students/import", freshAdmin.accessToken(),
                body(HEADER + "\nA/1,Asha,,2015-01-01,F,Class 5,A,Lata,MOTHER,9876501001,\n"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.csv", containsString("academic year")));

        // Class and section names from another school do not resolve.
        fixtures.currentYear(freshAdmin);
        api.post("/api/students/import", freshAdmin.accessToken(),
                body(HEADER + "\nA/1,Asha,,2015-01-01,F,Class 5,A,Lata,MOTHER,9876501001,\n"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.invalidRows").value(1))
                .andExpect(jsonPath("$.errors[0].column").value("section"));
    }

    private String body(String csv) throws Exception {
        return json.writeValueAsString(java.util.Map.of("csv", csv));
    }
}
