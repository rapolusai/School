package com.akshara.bulkimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.akshara.reports.Xlsx;
import com.akshara.support.BillingFixtures;
import com.akshara.support.IntegrationTest;
import com.akshara.support.SchoolFixtures;
import com.akshara.support.TestApi;
import com.akshara.support.TestApi.School;
import com.akshara.support.TestApi.Session;

/**
 * The school import: one workbook with a Staff sheet and a sheet per class and section. A check reports every
 * problem by sheet and row; an import writes everything in one transaction or nothing.
 */
class SchoolImportIT extends IntegrationTest {

    private School school;
    private Session admin;
    private SchoolFixtures fixtures;
    private String sectionA;

    @BeforeEach
    void schoolWithClassFive() throws Exception {
        school = api.signup();
        admin = api.login(school);
        fixtures = new SchoolFixtures(api);
        fixtures.currentYear(admin);
        String classFive = fixtures.schoolClass(admin, "Class 5");
        sectionA = fixtures.section(admin, classFive, "A", 40);
        fixtures.section(admin, classFive, "B", 2);
    }

    // ------------------------------------------------------------------ workbooks the school downloads

    @Test
    void theTemplateHasASheetPerSectionAndTheSampleHoldsTwoThousandStudents() throws Exception {
        byte[] template = download("/api/school-import/template.xlsx", admin);
        List<XlsxReader.Sheet> sheets = new XlsxReader().read(template);
        assertThat(sheets).extracting(XlsxReader.Sheet::name)
                .containsExactly("Instructions", "Staff", "Class 5 - A", "Class 5 - B");
        assertThat(sheets.get(1).rows().getFirst().cells()).containsExactly("Name", "Email", "Mobile", "Role",
                "Designation", "Department", "Employment type", "Joining date", "Employee code");
        assertThat(sheets.get(2).rows()).hasSize(1);
        assertThat(sheets.get(2).rows().getFirst().cells()).startsWith("Admission no", "Roll no", "First name");
        String instructions = String.join("\n", sheets.getFirst().rows().stream()
                .map(r -> String.join(" ", r.cells())).toList()).toLowerCase(Locale.ROOT);
        assertThat(instructions).contains("without a password").contains("aadhaar").doesNotContain("password:");

        // A school with no classes yet gets two example sheets.
        School fresh = api.signup();
        Session freshAdmin = api.login(fresh);
        assertThat(new XlsxReader().read(download("/api/school-import/template.xlsx", freshAdmin)))
                .extracting(XlsxReader.Sheet::name).containsExactly("Instructions", "Staff", "Class 1 - A",
                        "Class 1 - B");

        List<XlsxReader.Sheet> sample = new XlsxReader().read(download("/api/school-import/sample.xlsx", admin));
        int students = sample.stream().filter(s -> s.name().startsWith("Class "))
                .mapToInt(s -> s.rows().size() - 1).sum();
        assertThat(students).isEqualTo(2_000);
        assertThat(sample.get(1).rows()).hasSize(31);
        assertThat(sample.stream().filter(s -> s.name().startsWith("Class ")).count()).isEqualTo(20);
    }

    @Test
    void theFreshlySignedUpSchoolCanImportTheWholeSample() throws Exception {
        School fresh = api.signup();
        Session freshAdmin = api.login(fresh);
        fixtures.currentYear(freshAdmin);
        byte[] sample = download("/api/school-import/sample.xlsx", freshAdmin);

        upload(freshAdmin, sample, true, false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ready").value(false))
                .andExpect(jsonPath("$.sheets[2].section").value("MISSING"));
        upload(freshAdmin, sample, true, true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ready").value(true))
                .andExpect(jsonPath("$.studentRows").value(2000))
                .andExpect(jsonPath("$.staffRows").value(30))
                .andExpect(jsonPath("$.toCreate.classes.length()").value(10))
                .andExpect(jsonPath("$.toCreate.sections.length()").value(20))
                .andExpect(jsonPath("$.toCreate.departments", hasItems("Mathematics", "Science", "Administration")));

        upload(freshAdmin, sample, false, true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.committed").value(true))
                .andExpect(jsonPath("$.studentsCreated").value(2000))
                .andExpect(jsonPath("$.staffCreated").value(30));
        api.get("/api/students?size=1", freshAdmin.accessToken()).andExpect(jsonPath("$.total").value(2000));
        api.get("/api/staff?size=1", freshAdmin.accessToken()).andExpect(jsonPath("$.total").value(31));
        api.get("/api/academics/classes", freshAdmin.accessToken())
                .andExpect(jsonPath("$.length()").value(10))
                .andExpect(jsonPath("$[0].name").value("Class 1"))
                .andExpect(jsonPath("$[9].name").value("Class 10"))
                .andExpect(jsonPath("$[9].sections[1].studentCount").value(100));
        // Siblings in the sample share one guardian record.
        String younger = TestApi.read(api.get("/api/students?q=SMP/0020", freshAdmin.accessToken()), "$.items[0].id");
        api.get("/api/students/" + younger, freshAdmin.accessToken())
                .andExpect(jsonPath("$.siblings.length()").value(1));
    }

    // ------------------------------------------------------------------ check, then import

    @Test
    void aCheckWritesNothingAndAnImportWritesStudentsAndStaffTogether() throws Exception {
        byte[] file = workbook(
                staffSheet(new Object[] {"Meera Iyer", "meera@school.test", "+91 98765 11111", "Teacher",
                        "PGT Maths", "", "permanent", LocalDate.of(2019, 6, 3), "EMP-1"},
                        new Object[] {"Ravi Kumar", "ravi@school.test", 9876511112L, "Accountant, Front office",
                                "Accountant", "", "Part time", "03/06/2020", "EMP-2"}),
                studentSheet("Class 5 - A",
                        new Object[] {"IMP/1", 3, "Arjun", "Sharma", LocalDate.of(2015, 5, 14), "M",
                                "Anitha Sharma", "Mother", 9876500001L, "", LocalDate.of(2026, 6, 2)},
                        new Object[] {"IMP/2", null, "Diya", "Sharma", "14/09/2016", "female", "Anitha Sharma",
                                "MOTHER", "9876500001", "anitha@family.test", null}),
                studentSheet("5 - B", new Object[] {"IMP/3", 1, "Kabir", "", "2016-01-01", "MALE", "Imran Khan",
                        "FATHER", "9876500003", "", ""}),
                new Xlsx.Sheet("Instructions").line("ignored"));

        upload(admin, file, true, false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dryRun").value(true))
                .andExpect(jsonPath("$.committed").value(false))
                .andExpect(jsonPath("$.ready").value(true))
                .andExpect(jsonPath("$.studentRows").value(3))
                .andExpect(jsonPath("$.staffRows").value(2))
                .andExpect(jsonPath("$.validRows").value(5))
                .andExpect(jsonPath("$.sheets[0].kind").value("STAFF"))
                .andExpect(jsonPath("$.sheets[1].className").value("Class 5"))
                .andExpect(jsonPath("$.sheets[1].sectionName").value("A"))
                .andExpect(jsonPath("$.sheets[1].section").value("EXISTS"))
                .andExpect(jsonPath("$.sheets[1].rows").value(2))
                .andExpect(jsonPath("$.sheets[2].className").value("Class 5"))
                .andExpect(jsonPath("$.sheets[3].kind").value("IGNORED"));
        api.get("/api/students", admin.accessToken()).andExpect(jsonPath("$.total").value(0));
        api.get("/api/staff", admin.accessToken()).andExpect(jsonPath("$.total").value(1));

        upload(admin, file, false, false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.committed").value(true))
                .andExpect(jsonPath("$.studentsCreated").value(3))
                .andExpect(jsonPath("$.staffCreated").value(2));

        api.get("/api/students?sectionId=" + sectionA, admin.accessToken())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].firstName").value("Arjun"))
                .andExpect(jsonPath("$.items[0].rollNo").value(3));
        String diya = TestApi.read(api.get("/api/students?q=diya", admin.accessToken()), "$.items[0].id");
        api.get("/api/students/" + diya, admin.accessToken())
                .andExpect(jsonPath("$.dateOfBirth").value("2016-09-14"))
                .andExpect(jsonPath("$.guardians[0].phone").value("9876500001"))
                .andExpect(jsonPath("$.guardians[0].hasSignIn").value(false))
                .andExpect(jsonPath("$.siblings[0].fullName").value("Arjun Sharma"));

        String meera = TestApi.read(api.get("/api/staff?q=meera", admin.accessToken()), "$.items[0].userId");
        api.get("/api/staff/" + meera, admin.accessToken())
                .andExpect(jsonPath("$.roles[0]").value("TEACHER"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.profileComplete").value(true))
                .andExpect(jsonPath("$.profile.employeeCode").value("EMP-1"))
                .andExpect(jsonPath("$.profile.mobile").value("9876511111"))
                .andExpect(jsonPath("$.profile.dateOfJoining").value("2019-06-03"))
                .andExpect(jsonPath("$.signInReady").value(false));
        String ravi = TestApi.read(api.get("/api/staff?q=ravi", admin.accessToken()), "$.items[0].userId");
        api.get("/api/staff/" + ravi, admin.accessToken())
                .andExpect(jsonPath("$.roles", hasItems("ACCOUNTANT", "FRONT_OFFICE")))
                .andExpect(jsonPath("$.profile.employmentType").value("PART_TIME"));

        // Imported staff cannot sign in until the School Admin sets a password.
        api.post("/api/auth/login", null, """
                {"schoolCode":"%s","email":"meera@school.test","password":"!no-password"}
                """.formatted(school.code())).andExpect(status().isUnauthorized());
        api.put("/api/staff/" + meera + "/password", admin.accessToken(), "{\"password\":\"short\"}")
                .andExpect(status().isBadRequest());
        api.put("/api/staff/" + meera + "/password", admin.accessToken(), """
                {"password":"Meera-Starts-2026"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.signInReady").value(true));
        Session meeraSession = api.login(school.code(), "meera@school.test", "Meera-Starts-2026");
        api.put("/api/staff/" + ravi + "/password", meeraSession.accessToken(), """
                {"password":"Someone-Else-2026"}""").andExpect(status().isForbidden());

        String audit = api.get("/api/audit-events?limit=100", admin.accessToken())
                .andExpect(jsonPath("$[*].action", hasItems("school.bulk_imported", "students.imported",
                        "staff.imported", "user.password_set")))
                .andReturn().getResponse().getContentAsString();
        // The import is audited with counts, never the people's details.
        assertThat(audit).doesNotContain("9876500001").doesNotContain("Anitha").doesNotContain("Meera-Starts-2026");

        // The same file again clashes with what is now in use, on every row.
        upload(admin, file, true, false)
                .andExpect(jsonPath("$.ready").value(false))
                .andExpect(jsonPath("$.invalidRows").value(5))
                .andExpect(jsonPath("$.sheets[0].problems[*].column", hasItems("Email", "Employee code")))
                .andExpect(jsonPath("$.sheets[1].problems[*].column", hasItem("Admission no")));
    }

    @Test
    void oneProblemAnywhereMeansNothingIsImportedAndEveryProblemIsListedBySheetAndRow() throws Exception {
        byte[] file = workbook(
                staffSheet(new Object[] {"Good Person", "good@school.test", "9876522221", "Teacher", "TGT", "",
                        "PERMANENT", "2020-06-01", "E-1"},
                        new Object[] {"", "not-an-email", "123", "Parent", "", "Nowhere", "Forever", "someday",
                                "E-1"}),
                studentSheet("Class 5 - A",
                        new Object[] {"DUP/1", 1, "Asha", "Rao", "2015-01-01", "F", "Lata Rao", "MOTHER",
                                "9876501001", "", ""},
                        new Object[] {"OK/2", 1, "Bala", "", "31-02-2015", "X", "", "AUNT", "12345", "bad", ""}),
                studentSheet("Class 5 - B",
                        new Object[] {"DUP/1", null, "Chitra", "", "2015-03-03", "F", "Kala", "MOTHER",
                                "9876501004", "", ""},
                        new Object[] {"OK/4", null, "Deepa", "", "2015-03-03", "F", "Kala", "MOTHER",
                                "9876501004", "", ""},
                        new Object[] {"OK/5", null, "Esha", "", "2015-03-04", "F", "Kala", "MOTHER",
                                "9876501004", "", ""}),
                studentSheet("Sheet1", new Object[] {"X/1", null, "Lost", "", "2015-03-04", "F", "Kala",
                        "MOTHER", "9876501004", "", ""}),
                new Xlsx.Sheet("Class 6 - A").header("First name").row("Nobody"));

        String body = upload(admin, file, false, false)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Check the file"))
                .andExpect(jsonPath("$.result.committed").value(false))
                .andExpect(jsonPath("$.result.ready").value(false))
                .andExpect(jsonPath("$.result.studentRows").value(5))
                .andExpect(jsonPath("$.result.staffRows").value(2))
                // Staff row 3: every column has a problem, and E-1 is also on row 2.
                .andExpect(jsonPath("$.result.sheets[0].invalidRows").value(1))
                .andExpect(jsonPath("$.result.sheets[0].problems[?(@.row == 3)].column", hasItems("Name", "Email",
                        "Mobile", "Role", "Designation", "Department", "Employment type", "Joining date",
                        "Employee code")))
                .andExpect(jsonPath("$.result.sheets[0].problems[?(@.row == 3 && @.column == 'Employee code')]"
                        + ".message", hasItem("Also used on row 2 of Staff.")))
                .andExpect(jsonPath("$.result.sheets[1].validRows").value(1))
                .andExpect(jsonPath("$.result.sheets[1].problems[?(@.row == 3)].column", hasItems("Date of birth",
                        "Gender", "Guardian name", "Relation", "Guardian mobile", "Guardian email")))
                // The same admission number on two sheets names the first one.
                .andExpect(jsonPath("$.result.sheets[2].problems[?(@.row == 2)].message",
                        hasItem("Also used on row 2 of Class 5 - A.")))
                // Class 5 - B has 2 places: the third student does not fit.
                .andExpect(jsonPath("$.result.sheets[2].problems[?(@.row == 4)].message",
                        hasItem(containsString("is full"))))
                .andExpect(jsonPath("$.result.sheets[3].problems[0].row").doesNotExist())
                .andExpect(jsonPath("$.result.sheets[3].problems[0].message", containsString("Class 5 - A")))
                .andExpect(jsonPath("$.result.sheets[4].problems[*].message",
                        hasItem(containsString("Missing: Admission no"))))
                .andExpect(jsonPath("$.result.sheetProblems").value(3))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("Aadhaar");

        api.get("/api/students", admin.accessToken()).andExpect(jsonPath("$.total").value(0));
        api.get("/api/staff", admin.accessToken()).andExpect(jsonPath("$.total").value(1));
        api.get("/api/academics/classes", admin.accessToken()).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void sheetsForClassesAndSectionsNotSetUpAreCreatedOnlyWhenAsked() throws Exception {
        byte[] file = workbook(
                staffSheet(new Object[] {"Lab Head", "lab@school.test", "9876533331", "TEACHER", "HOD Science",
                        "Science", "CONTRACT", "2021-06-01", "E-9"}),
                studentSheet("5 - C", new Object[] {"NEW/1", 1, "Asha", "", "2015-01-01", "F", "Lata", "MOTHER",
                        "9876501001", "", "", "Aadhaar number goes here"}),
                studentSheet("Class 9 - A", new Object[] {"NEW/2", 1, "Bala", "", "2011-01-01", "M", "Mohan",
                        "FATHER", "9876501002", "", ""}),
                studentSheet("Class 9 - B", new Object[] {"NEW/3", 1, "Chitra", "", "2011-02-01", "F", "Mohan",
                        "FATHER", "9876501002", "", ""}));

        upload(admin, file, true, false)
                .andExpect(jsonPath("$.ready").value(false))
                .andExpect(jsonPath("$.sheets[1].section").value("MISSING"))
                .andExpect(jsonPath("$.sheets[1].className").value("Class 5"))
                .andExpect(jsonPath("$.sheets[1].problems[0].message", containsString("not set up")))
                .andExpect(jsonPath("$.sheets[2].section").value("MISSING"))
                .andExpect(jsonPath("$.sheets[0].problems[0].column").value("Department"))
                .andExpect(jsonPath("$.toCreate.classes.length()").value(0));

        upload(admin, file, true, true)
                .andExpect(jsonPath("$.ready").value(true))
                .andExpect(jsonPath("$.createMissing").value(true))
                .andExpect(jsonPath("$.sheets[1].section").value("NEW_SECTION"))
                .andExpect(jsonPath("$.sheets[2].section").value("NEW_CLASS"))
                .andExpect(jsonPath("$.sheets[1].ignoredColumns[0]").value("Notes"))
                .andExpect(jsonPath("$.toCreate.classes[0]").value("Class 9"))
                .andExpect(jsonPath("$.toCreate.sections", hasItems("Class 5 - C", "Class 9 - A", "Class 9 - B")))
                .andExpect(jsonPath("$.toCreate.departments[0]").value("Science"));
        api.get("/api/academics/classes", admin.accessToken()).andExpect(jsonPath("$.length()").value(1));

        upload(admin, file, false, true).andExpect(status().isOk()).andExpect(jsonPath("$.studentsCreated").value(3));
        api.get("/api/academics/classes", admin.accessToken())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].sections[*].name", hasItems("A", "B", "C")))
                .andExpect(jsonPath("$[1].name").value("Class 9"))
                .andExpect(jsonPath("$[1].sections[*].studentCount", hasItems(1, 1)));
        api.get("/api/staff/departments", admin.accessToken())
                .andExpect(jsonPath("$[*].name", hasItem("Science")));
        String bala = TestApi.read(api.get("/api/students?q=bala", admin.accessToken()), "$.items[0].id");
        api.get("/api/students/" + bala, admin.accessToken())
                .andExpect(jsonPath("$.siblings[0].fullName").value("Chitra"))
                .andExpect(content -> assertThat(content.getResponse().getContentAsString())
                        .doesNotContain("Aadhaar number goes here"));
    }

    // ------------------------------------------------------------------ who may, and whose data

    @Test
    void onlySomeoneWhoManagesBothStudentsAndStaffMayImport() throws Exception {
        byte[] file = workbook(studentSheet("Class 5 - A", new Object[] {"P/1", 1, "Asha", "", "2015-01-01", "F",
                "Lata", "MOTHER", "9876501001", "", ""}));
        api.createUser(admin, "Tara Teacher", "tara@" + school.code() + ".test", List.of("TEACHER"));
        Session teacher = api.login(school.code(), "tara@" + school.code() + ".test", TestApi.PASSWORD);
        api.createUser(admin, "Om Office", "om@" + school.code() + ".test", List.of("FRONT_OFFICE"));
        Session office = api.login(school.code(), "om@" + school.code() + ".test", TestApi.PASSWORD);

        upload(teacher, file, true, false).andExpect(status().isForbidden());
        upload(office, file, true, false).andExpect(status().isForbidden());
        mvc.perform(MockMvcRequestBuilders.get("/api/school-import/template.xlsx")
                .header("Authorization", "Bearer " + teacher.accessToken())).andExpect(status().isForbidden());
        mvc.perform(MockMvcRequestBuilders.multipart("/api/school-import")
                .file(new MockMultipartFile("file", "s.xlsx", Xlsx.CONTENT_TYPE, file)))
                .andExpect(status().isUnauthorized());

        // Students and staff, but not School setup: may import, may not create classes and sections.
        try (Connection owner = ownerConnection(); PreparedStatement update = owner.prepareStatement(
                "update identity.role set permissions = array_append(permissions, 'staff.manage') "
                        + "where tenant_id = ? and code = 'FRONT_OFFICE'")) {
            update.setObject(1, school.tenantId());
            assertThat(update.executeUpdate()).isEqualTo(1);
        }
        Session officeWithStaff = api.login(school.code(), "om@" + school.code() + ".test", TestApi.PASSWORD);
        upload(officeWithStaff, file, true, false).andExpect(status().isOk()).andExpect(jsonPath("$.ready").value(true));
        upload(officeWithStaff, file, true, true).andExpect(status().isForbidden());
        api.get("/api/students", admin.accessToken()).andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void eachSchoolOnlyEverSeesItsOwnImport() throws Exception {
        byte[] file = workbook(
                staffSheet(new Object[] {"Same Name", "same@school.test", "9876544441", "Teacher", "TGT", "",
                        "PERMANENT", "2020-06-01", "E-1"}),
                studentSheet("Class 5 - A", new Object[] {"ISO/1", 1, "Asha", "", "2015-01-01", "F", "Lata",
                        "MOTHER", "9876501001", "", ""}));
        upload(admin, file, false, false).andExpect(status().isOk());

        School other = api.signup();
        Session otherAdmin = api.login(other);
        fixtures.currentYear(otherAdmin);
        api.get("/api/students", otherAdmin.accessToken()).andExpect(jsonPath("$.total").value(0));
        api.get("/api/staff?q=same", otherAdmin.accessToken()).andExpect(jsonPath("$.total").value(0));
        // The other school's template knows nothing of this school's sections.
        assertThat(new XlsxReader().read(download("/api/school-import/template.xlsx", otherAdmin)))
                .extracting(XlsxReader.Sheet::name).doesNotContain("Class 5 - A");
        // The same admission numbers, emails and codes are free in the other school (they are per school).
        upload(otherAdmin, file, true, true)
                .andExpect(jsonPath("$.ready").value(true))
                .andExpect(jsonPath("$.sheets[1].section").value("NEW_CLASS"));
        upload(otherAdmin, file, false, true).andExpect(status().isOk());
        api.get("/api/students", otherAdmin.accessToken()).andExpect(jsonPath("$.total").value(1));
        api.get("/api/students", admin.accessToken()).andExpect(jsonPath("$.total").value(1));
    }

    // ------------------------------------------------------------------ the file itself

    @Test
    void theFileItselfMustBeAUsableWorkbookWithinTheLimits() throws Exception {
        multipart(admin, "students.csv", "admission_no,first_name\n".getBytes(), "?dryRun=true")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.file", containsString("not an Excel")));
        multipart(admin, "empty.xlsx", new byte[0], "")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.file").exists());
        upload(admin, workbook(studentSheet("Class 5 - A"), staffSheet()), true, false)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.file", containsString("no students or staff")));
        multipart(admin, "big.xlsx", new byte[5 * 1024 * 1024 + 1], "")
                .andExpect(status().isContentTooLarge());

        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 2_501; i++) {
            rows.add(studentRow("BIG/" + i, i));
        }
        byte[] tooMany = workbook(studentSheet("Class 6 - A", rows.toArray(Object[][]::new)),
                studentSheet("Class 5 - B", rows.stream().map(r -> {
                    Object[] copy = r.clone();
                    copy[0] = "BIGB/" + copy[0];
                    return copy;
                }).toArray(Object[][]::new)));
        upload(admin, tooMany, true, false)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.file", containsString("at most 5,000 students")));

        // No current academic year: nothing can be checked.
        School fresh = api.signup();
        Session freshAdmin = api.login(fresh);
        upload(freshAdmin, workbook(studentSheet("Class 5 - A", studentRow("Y/1", 1))), true, true)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.file", containsString("academic year")));
    }

    @Test
    void aFileOfTwoAndAHalfThousandStudentsIsImportedInOneGo() throws Exception {
        List<Xlsx.Sheet> sheets = new ArrayList<>();
        sheets.add(staffSheet());
        int n = 0;
        for (int grade = 6; grade <= 10; grade++) {
            for (String section : List.of("A", "B", "C", "D", "E")) {
                List<Object[]> rows = new ArrayList<>();
                for (int roll = 1; roll <= 100; roll++) {
                    n++;
                    rows.add(studentRow("BULK/" + n, n));
                }
                sheets.add(studentSheet("Class " + grade + " - " + section, rows.toArray(Object[][]::new)));
            }
        }
        byte[] file = Xlsx.write(sheets);
        assertThat(file.length).isLessThan(5 * 1024 * 1024);

        upload(admin, file, true, true)
                .andExpect(jsonPath("$.ready").value(true))
                .andExpect(jsonPath("$.studentRows").value(2500));
        long started = System.nanoTime();
        upload(admin, file, false, true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.studentsCreated").value(2500));
        System.out.printf(Locale.ROOT, "Imported 2,500 students in %d ms%n", (System.nanoTime() - started) / 1_000_000);
        api.get("/api/students?size=1", admin.accessToken()).andExpect(jsonPath("$.total").value(2500));
        api.get("/api/academics/classes", admin.accessToken())
                .andExpect(jsonPath("$.length()").value(6))
                .andExpect(jsonPath("$[*].name", not(hasItem("1"))));
    }

    @Test
    void aPayingStarterSchoolCannotImportPastItsPlansStudentLimit() throws Exception {
        List<Object[]> rows = new ArrayList<>();
        for (int n = 1; n <= 300; n++) {
            rows.add(studentRow("CAP/" + n, n));
        }
        // In the trial the limit does not apply.
        upload(admin, workbook(staffSheet(), studentSheet("Class 6 - A", rows.toArray(Object[][]::new))), false, true)
                .andExpect(status().isOk()).andExpect(jsonPath("$.studentsCreated").value(300));
        BillingFixtures billing = new BillingFixtures(api);
        Session root = billing.root();
        billing.details(root, school.tenantId(), "36", null);
        billing.start(root, school.tenantId(), "STARTER", "YEARLY", 300, null);

        byte[] one = workbook(staffSheet(), studentSheet("Class 6 - A", studentRow("CAP/301", 301)));
        upload(admin, one, true, true).andExpect(jsonPath("$.ready").value(true));
        upload(admin, one, false, true)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:akshara:problem:plan-student-limit"))
                .andExpect(jsonPath("$.maxStudents").value(300));
        api.get("/api/students?size=1", admin.accessToken()).andExpect(jsonPath("$.total").value(300));
    }

    // ------------------------------------------------------------------ helpers

    private static Object[] studentRow(String admissionNo, int n) {
        return new Object[] {admissionNo, null, "Student" + n, "", "2015-01-01", n % 2 == 0 ? "F" : "M",
                "Parent " + n, "MOTHER", String.format(Locale.ROOT, "98%08d", n), "", ""};
    }

    private static Xlsx.Sheet staffSheet(Object[]... rows) {
        Xlsx.Sheet sheet = ImportWorkbooks.staffSheet();
        for (Object[] row : rows) {
            sheet.row(row);
        }
        return sheet;
    }

    /** A student sheet; a row with a 12th value gets an extra "Notes" column. */
    private static Xlsx.Sheet studentSheet(String name, Object[]... rows) {
        boolean notes = rows.length > 0 && rows[0].length > 11;
        Xlsx.Sheet sheet = new Xlsx.Sheet(name);
        List<String> headings = new ArrayList<>(ImportColumns.STUDENT.stream().map(ImportColumns.Column::heading)
                .toList());
        if (notes) {
            headings.add("Notes");
        }
        sheet.header(headings.toArray(String[]::new));
        for (Object[] row : rows) {
            sheet.row(row);
        }
        return sheet;
    }

    private static byte[] workbook(Xlsx.Sheet... sheets) {
        return Xlsx.write(List.of(sheets));
    }

    private byte[] download(String path, Session session) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get(path).header("Authorization", "Bearer " + session.accessToken()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", Xlsx.CONTENT_TYPE))
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andReturn().getResponse().getContentAsByteArray();
    }

    private ResultActions upload(Session session, byte[] file, boolean dryRun, boolean createMissing)
            throws Exception {
        return multipart(session, "school.xlsx", file, "?dryRun=" + dryRun + "&createMissing=" + createMissing);
    }

    private ResultActions multipart(Session session, String name, byte[] file, String query) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.multipart("/api/school-import" + query)
                .file(new MockMultipartFile("file", name, Xlsx.CONTENT_TYPE, file))
                .header("Authorization", "Bearer " + session.accessToken()));
    }
}
