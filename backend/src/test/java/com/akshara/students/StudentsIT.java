package com.akshara.students;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.akshara.support.IntegrationTest;
import com.akshara.support.SchoolFixtures;
import com.akshara.support.TestApi;
import com.akshara.support.TestApi.School;
import com.akshara.support.TestApi.Session;

/** Admissions, the student list, profile changes, guardians and leaving. */
class StudentsIT extends IntegrationTest {

    private School school;
    private Session admin;
    private SchoolFixtures fixtures;
    private String classOne;
    private String sectionA;
    private String sectionB;

    @BeforeEach
    void schoolWithAClass() throws Exception {
        school = api.signup();
        admin = api.login(school);
        fixtures = new SchoolFixtures(api);
        fixtures.currentYear(admin);
        classOne = fixtures.schoolClass(admin, "Class 1");
        sectionA = fixtures.section(admin, classOne, "A", 30);
        sectionB = fixtures.section(admin, classOne, "B", 2);
    }

    @Test
    void anAdmittedStudentAppearsInTheListAndDetail() throws Exception {
        String id = TestApi.read(api.post("/api/students", admin.accessToken(), """
                {"admissionNo":"AKS/2026/001","firstName":"Arjun","lastName":"Sharma","dateOfBirth":"2019-05-14",
                 "gender":"MALE","admissionDate":"2026-06-02","bloodGroup":"B+","address":"Banjara Hills, Hyderabad",
                 "apaarId":"123456789012","sectionId":"%s","rollNo":7,
                 "guardians":[
                   {"name":"Anitha Sharma","relation":"MOTHER","phone":"+91 98765 00001","email":"anitha@family.test",
                    "occupation":"Architect","primary":true},
                   {"name":"Rakesh Sharma","relation":"FATHER","phone":"98765-00002","primary":false}]}
                """.formatted(sectionA))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fullName").value("Arjun Sharma"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.currentEnrollment.className").value("Class 1"))
                .andExpect(jsonPath("$.currentEnrollment.sectionName").value("A"))
                .andExpect(jsonPath("$.currentEnrollment.academicYearName").value("2026-27"))
                .andExpect(jsonPath("$.currentEnrollment.rollNo").value(7))
                .andExpect(jsonPath("$.guardians.length()").value(2))
                .andExpect(jsonPath("$.guardians[0].name").value("Anitha Sharma"))
                .andExpect(jsonPath("$.guardians[0].primary").value(true))
                // Phone numbers are stored as 10 digits.
                .andExpect(jsonPath("$.guardians[0].phone").value("9876500001"))
                .andExpect(jsonPath("$.guardians[1].phone").value("9876500002"))
                .andExpect(jsonPath("$.hasSignIn").value(false)), "$.id");
        fixtures.student(admin, sectionB, "AKS/2026/002", "Diya", "Rao", "Lakshmi Rao", "9876501001");
        fixtures.student(admin, sectionA, "AKS/2026/003", "Kabir", null, "Imran Khan", "9876501002");

        api.get("/api/students/" + id, admin.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.admissionNo").value("AKS/2026/001"))
                .andExpect(jsonPath("$.bloodGroup").value("B+"))
                .andExpect(jsonPath("$.enrollments.length()").value(1));

        api.get("/api/students", admin.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.items[*].fullName", contains("Arjun Sharma", "Diya Rao", "Kabir")))
                .andExpect(jsonPath("$.items[0].guardianName").value("Anitha Sharma"))
                .andExpect(jsonPath("$.items[0].guardianPhone").value("9876500001"))
                .andExpect(jsonPath("$.items[0].className").value("Class 1"));
        api.get("/api/students?q=shar", admin.accessToken())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(id));
        api.get("/api/students?q=aks/2026/002", admin.accessToken())
                .andExpect(jsonPath("$.items[0].fullName").value("Diya Rao"));
        api.get("/api/students?q=100%25", admin.accessToken()).andExpect(jsonPath("$.total").value(0));
        api.get("/api/students?sectionId=" + sectionA, admin.accessToken())
                .andExpect(jsonPath("$.total").value(2))
                // A section's list is in roll-number order; students without one come last.
                .andExpect(jsonPath("$.items[0].fullName").value("Arjun Sharma"));
        api.get("/api/students?classId=" + classOne + "&size=1&page=1", admin.accessToken())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].fullName").value("Diya Rao"));
        api.get("/api/students?size=500", admin.accessToken()).andExpect(status().isBadRequest());

        api.get("/api/audit-events?limit=50", admin.accessToken())
                .andExpect(jsonPath("$[*].action", hasItem("student.created")));
    }

    @Test
    void admissionFormsAreCheckedFieldByField() throws Exception {
        api.post("/api/students", admin.accessToken(), """
                {"admissionNo":"","firstName":"","dateOfBirth":"2099-01-01","gender":"MALE",
                 "admissionDate":"2026-06-02","bloodGroup":"C+","apaarId":"12","sectionId":null,"guardians":[]}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.admissionNo").exists())
                .andExpect(jsonPath("$.errors.firstName").exists())
                .andExpect(jsonPath("$.errors.dateOfBirth").exists())
                .andExpect(jsonPath("$.errors.bloodGroup").exists())
                .andExpect(jsonPath("$.errors.apaarId").value("An APAAR ID has 12 digits."))
                .andExpect(jsonPath("$.errors.sectionId").value("Pick a section."))
                .andExpect(jsonPath("$.errors.guardians").value("Add at least one parent or guardian."));

        api.post("/api/students", admin.accessToken(), """
                {"admissionNo":"A-1","firstName":"Asha","dateOfBirth":"2018-01-01","gender":"FEMALE",
                 "admissionDate":"2026-06-02","sectionId":"%s",
                 "guardians":[{"name":"Lata","relation":"MOTHER","phone":"12345","primary":true}]}"""
                .formatted(sectionA))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['guardians[0].phone']").value("Enter a 10-digit Indian mobile number."));

        api.post("/api/students", admin.accessToken(), """
                {"admissionNo":"A-1","firstName":"Asha","dateOfBirth":"2026-07-01","gender":"FEMALE",
                 "admissionDate":"2026-06-02","sectionId":"%s",
                 "guardians":[{"name":"Lata","relation":"MOTHER","phone":"9876501003","primary":true}]}"""
                .formatted(sectionA))
                .andExpect(status().isBadRequest());

        api.post("/api/students", admin.accessToken(), """
                {"admissionNo":"A-1","firstName":"Asha","dateOfBirth":"2018-01-01","gender":"FEMALE",
                 "admissionDate":"2026-06-02","sectionId":"%s",
                 "guardians":[{"name":"Lata","relation":"MOTHER","phone":"9876501003","primary":true},
                              {"name":"Ravi","relation":"FATHER","phone":"9876501004","primary":true}]}"""
                .formatted(sectionA))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.guardians").exists());

        fixtures.student(admin, sectionA, "A-1", "Asha", null, "Lata Rao", "9876501003");
        api.post("/api/students", admin.accessToken(),
                SchoolFixtures.studentJson(sectionA, "a-1", "Other", null, "Lata Rao", "9876501003", null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors.admissionNo").exists());

        api.post("/api/students", admin.accessToken(),
                SchoolFixtures.studentJson(sectionA, "A-2", "Ravi", null, "Mohan", "9876501005", 4))
                .andExpect(status().isCreated());
        api.post("/api/students", admin.accessToken(),
                SchoolFixtures.studentJson(sectionA, "A-3", "Sita", null, "Mohan", "9876501006", 4))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors.rollNo").exists());

        // Section B holds two students.
        fixtures.student(admin, sectionB, "B-1", "One", null, "Parent One", "9876501007");
        fixtures.student(admin, sectionB, "B-2", "Two", null, "Parent Two", "9876501008");
        api.post("/api/students", admin.accessToken(),
                SchoolFixtures.studentJson(sectionB, "B-3", "Three", null, "Parent Three", "9876501009", null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors.sectionId", containsString("full")));
    }

    @Test
    void admissionNeedsACurrentAcademicYear() throws Exception {
        School fresh = api.signup();
        Session freshAdmin = api.login(fresh);
        String classId = fixtures.schoolClass(freshAdmin, "Class 1");
        String section = fixtures.section(freshAdmin, classId, "A", null);
        api.post("/api/students", freshAdmin.accessToken(),
                SchoolFixtures.studentJson(section, "X-1", "Asha", null, "Lata", "9876501010", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.sectionId", containsString("academic year")));
        api.get("/api/students", freshAdmin.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.academicYearId").isEmpty());
    }

    @Test
    void profileChangesAndSectionMovesAreRecorded() throws Exception {
        String id = fixtures.student(admin, sectionA, "U-1", "Meera", "Nair", "Sreeja Nair", "9876501011");
        api.put("/api/students/" + id, admin.accessToken(), """
                {"admissionNo":"U-1","firstName":"Meera","lastName":"Nair-Menon","dateOfBirth":"2016-04-12",
                 "gender":"FEMALE","admissionDate":"2026-06-02","bloodGroup":"O+","sectionId":"%s","rollNo":2}"""
                .formatted(sectionB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastName").value("Nair-Menon"))
                .andExpect(jsonPath("$.bloodGroup").value("O+"))
                .andExpect(jsonPath("$.currentEnrollment.sectionName").value("B"))
                .andExpect(jsonPath("$.currentEnrollment.rollNo").value(2));
        api.put("/api/students/" + id, admin.accessToken(), """
                {"admissionNo":"U-1","firstName":"","dateOfBirth":"2016-04-12","gender":"FEMALE",
                 "admissionDate":"2026-06-02"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.firstName").exists());
        api.get("/api/audit-events?limit=50", admin.accessToken())
                .andExpect(jsonPath("$[?(@.action == 'student.updated')].details.changed[*]",
                        hasItems("lastName", "bloodGroup", "section")));
    }

    @Test
    void transferredAndWithdrawnStudentsKeepTheirRecord() throws Exception {
        String id = fixtures.student(admin, sectionA, "L-1", "Rohan", "Das", "Bijoy Das", "9876501012");
        String other = fixtures.student(admin, sectionA, "L-2", "Tara", "Bose", "Mitali Bose", "9876501013");

        api.post("/api/students/" + id + "/leave", admin.accessToken(), """
                {"status":"TRANSFERRED","leftOn":"2026-01-01","reason":"Family moved"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.leftOn").exists());
        api.post("/api/students/" + id + "/leave", admin.accessToken(), """
                {"status":"ALUMNI","leftOn":"2026-09-01","reason":"Family moved"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.status").exists());
        api.post("/api/students/" + id + "/leave", admin.accessToken(), """
                {"status":"TRANSFERRED","leftOn":"2026-09-01","reason":""}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.reason").exists());
        api.post("/api/students/" + id + "/leave", admin.accessToken(), """
                {"status":"TRANSFERRED","leftOn":"2026-09-01","reason":"Family moved to Pune"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TRANSFERRED"))
                .andExpect(jsonPath("$.leftOn").value("2026-09-01"))
                .andExpect(jsonPath("$.leavingReason").value("Family moved to Pune"));
        api.post("/api/students/" + id + "/leave", admin.accessToken(), """
                {"status":"WITHDRAWN","leftOn":"2026-09-02","reason":"Again"}""")
                .andExpect(status().isConflict());
        api.post("/api/students/" + other + "/leave", admin.accessToken(), """
                {"status":"WITHDRAWN","leftOn":"2026-09-02","reason":"Health"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WITHDRAWN"));

        api.get("/api/students?status=ACTIVE", admin.accessToken()).andExpect(jsonPath("$.total").value(0));
        api.get("/api/students?status=TRANSFERRED", admin.accessToken())
                .andExpect(jsonPath("$.items[0].id").value(id));
        // A student who left cannot be moved to another section.
        api.put("/api/students/" + id, admin.accessToken(), """
                {"admissionNo":"L-1","firstName":"Rohan","lastName":"Das","dateOfBirth":"2016-04-12",
                 "gender":"MALE","admissionDate":"2026-06-02","sectionId":"%s"}""".formatted(sectionB))
                .andExpect(status().isConflict());
        api.get("/api/audit-events?limit=50", admin.accessToken())
                .andExpect(jsonPath("$[*].action", hasItems("student.transferred", "student.withdrawn")));
    }

    @Test
    void guardiansAreSharedBetweenSiblings() throws Exception {
        String elder = fixtures.student(admin, sectionA, "G-1", "Arjun", "Sharma", "Anitha Sharma", "9876500001");
        String younger = fixtures.student(admin, sectionB, "G-2", "Diya", "Sharma", "Anitha Sharma", "98765 00001");
        String mother = TestApi.read(api.get("/api/students/" + elder, admin.accessToken())
                .andExpect(jsonPath("$.siblings[0].id").value(younger))
                .andExpect(jsonPath("$.siblings[0].className").value("Class 1")), "$.guardians[0].id");
        api.get("/api/students/" + younger, admin.accessToken())
                .andExpect(jsonPath("$.guardians[0].id").value(mother))
                .andExpect(jsonPath("$.siblings[0].id").value(elder));

        String father = TestApi.read(api.post("/api/students/" + elder + "/guardians", admin.accessToken(), """
                {"name":"Rakesh Sharma","relation":"FATHER","phone":"9876500002","primary":true}""")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.primary").value(true)), "$.id");
        api.post("/api/students/" + elder + "/guardians", admin.accessToken(), """
                {"name":"Rakesh Sharma","relation":"FATHER","phone":"9876500002"}""")
                .andExpect(status().isConflict());
        api.post("/api/students/" + elder + "/guardians", admin.accessToken(), """
                {"name":"Rakesh","relation":"UNCLE","phone":"9876500002"}""")
                .andExpect(status().isBadRequest());
        api.get("/api/students/" + elder, admin.accessToken())
                .andExpect(jsonPath("$.guardians[?(@.primary == true)].id", contains(father)));

        // Editing the shared mother record shows for both children.
        api.put("/api/students/" + elder + "/guardians/" + mother, admin.accessToken(), """
                {"name":"Anitha Sharma","relation":"MOTHER","phone":"9876500001","occupation":"Architect",
                 "primary":true}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.occupation").value("Architect"))
                .andExpect(jsonPath("$.primary").value(true));
        api.get("/api/students/" + younger, admin.accessToken())
                .andExpect(jsonPath("$.guardians[0].occupation").value("Architect"));

        api.delete("/api/students/" + younger + "/guardians/" + mother, admin.accessToken())
                .andExpect(status().isConflict());
        api.delete("/api/students/" + elder + "/guardians/" + father, admin.accessToken())
                .andExpect(status().isNoContent());
        api.get("/api/students/" + elder, admin.accessToken())
                .andExpect(jsonPath("$.guardians.length()").value(1))
                .andExpect(jsonPath("$.guardians[0].primary").value(true));
        // The father had no other children, so his record is gone.
        api.put("/api/students/" + younger + "/guardians/" + father, admin.accessToken(), """
                {"name":"Rakesh Sharma","relation":"FATHER","phone":"9876500002"}""")
                .andExpect(status().isNotFound());
        api.get("/api/audit-events?limit=50", admin.accessToken())
                .andExpect(jsonPath("$[*].action", hasItems("guardian.added", "guardian.updated",
                        "guardian.removed")));
    }

    @Test
    void staffPermissionsDecideWhoReadsAndWhoChanges() throws Exception {
        String id = fixtures.student(admin, sectionA, "P-1", "Asha", null, "Lata", "9876501014");
        Session teacher = staff("TEACHER");
        Session accountant = staff("ACCOUNTANT");
        Session frontOffice = staff("FRONT_OFFICE");

        for (Session reader : List.of(teacher, accountant)) {
            api.get("/api/students", reader.accessToken()).andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(1));
            api.get("/api/students/" + id, reader.accessToken()).andExpect(status().isOk());
            api.post("/api/students", reader.accessToken(),
                    SchoolFixtures.studentJson(sectionA, "P-9", "No", null, "Lata", "9876501014", null))
                    .andExpect(status().isForbidden());
            api.post("/api/students/" + id + "/leave", reader.accessToken(), """
                    {"status":"WITHDRAWN","leftOn":"2026-09-02","reason":"x"}""").andExpect(status().isForbidden());
            api.post("/api/students/import?dryRun=true", reader.accessToken(), """
                    {"csv":"admission_no"}""").andExpect(status().isForbidden());
        }
        // Front office handles admissions.
        api.post("/api/students", frontOffice.accessToken(),
                SchoolFixtures.studentJson(sectionA, "P-2", "Kiran", null, "Lata", "9876501014", null))
                .andExpect(status().isCreated());
        // But it cannot change the school setup.
        api.post("/api/academics/classes", frontOffice.accessToken(), """
                {"name":"Class 2"}""").andExpect(status().isForbidden());

        for (String role : List.of("PARENT", "STUDENT")) {
            Session family = staff(role);
            api.get("/api/students", family.accessToken()).andExpect(status().isForbidden());
            api.get("/api/students/" + id, family.accessToken()).andExpect(status().isForbidden());
        }
        api.get("/api/students", null).andExpect(status().isUnauthorized());
    }

    @Test
    void anotherSchoolsStudentsAreInvisible() throws Exception {
        String id = fixtures.student(admin, sectionA, "C-1", "Asha", null, "Lata Rao", "9876501015");
        String guardian = TestApi.read(api.get("/api/students/" + id, admin.accessToken()), "$.guardians[0].id");

        School other = api.signup();
        Session adminB = api.login(other);
        fixtures.currentYear(adminB);
        String classB = fixtures.schoolClass(adminB, "Class 1");
        String sectionOfB = fixtures.section(adminB, classB, "A", null);
        // Same guardian name and phone in another school is a separate record.
        String studentB = fixtures.student(adminB, sectionOfB, "C-1", "Ravi", null, "Lata Rao", "9876501015");

        api.get("/api/students", adminB.accessToken())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[*].id", not(hasItem(id))));
        api.get("/api/students/" + studentB, adminB.accessToken())
                .andExpect(jsonPath("$.siblings.length()").value(0))
                .andExpect(jsonPath("$.guardians[0].id", not(guardian)));
        api.get("/api/students/" + id, adminB.accessToken()).andExpect(status().isNotFound());
        api.put("/api/students/" + id, adminB.accessToken(), """
                {"admissionNo":"C-1","firstName":"Hacked","dateOfBirth":"2016-04-12","gender":"MALE",
                 "admissionDate":"2026-06-02"}""").andExpect(status().isNotFound());
        api.post("/api/students/" + id + "/leave", adminB.accessToken(), """
                {"status":"WITHDRAWN","leftOn":"2026-09-02","reason":"x"}""").andExpect(status().isNotFound());
        api.post("/api/students/" + id + "/guardians", adminB.accessToken(), """
                {"name":"Intruder","relation":"GUARDIAN","phone":"9876501016"}""").andExpect(status().isNotFound());
        api.delete("/api/students/" + id + "/guardians/" + guardian, adminB.accessToken())
                .andExpect(status().isNotFound());
        api.get("/api/students?sectionId=" + sectionA, adminB.accessToken()).andExpect(status().isNotFound());
        // Ids from school A in a request body are refused as field errors.
        api.post("/api/students", adminB.accessToken(),
                SchoolFixtures.studentJson(sectionA, "C-2", "Asha", null, "Lata", "9876501015", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.sectionId").exists());

        api.get("/api/students/" + id, admin.accessToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.firstName").value("Asha"));
    }

    private Session staff(String role) throws Exception {
        String email = role.toLowerCase() + "@" + school.code() + ".akshara.test";
        api.createUser(admin, "Person " + role, email, List.of(role));
        return api.login(school.code(), email, TestApi.PASSWORD);
    }
}
