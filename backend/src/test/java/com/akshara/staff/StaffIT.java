package com.akshara.staff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.akshara.support.IntegrationTest;
import com.akshara.support.TestApi;
import com.akshara.support.TestApi.School;
import com.akshara.support.TestApi.Session;

/** Adding staff, the directory and its filters, departments, recording leaving, permissions and isolation. */
class StaffIT extends IntegrationTest {

    private School school;
    private Session admin;
    private StaffFixtures staff;

    @BeforeEach
    void school() throws Exception {
        school = api.signup();
        admin = api.login(school);
        staff = new StaffFixtures(api, school, admin);
    }

    @Test
    void addingStaffCreatesTheSignInAndTheProfileTogether() throws Exception {
        String science = staff.department("Science");
        String anjali = TestApi.read(api.post("/api/staff", admin.accessToken(), """
                {"name":"Anjali Deshmukh","email":"%s","password":"%s","roles":["TEACHER"],
                 "employeeCode":"SCI-01","designation":"Post Graduate Teacher","departmentId":"%s",
                 "employmentType":"PERMANENT","dateOfJoining":"2014-06-02","mobile":"+91 98480 10102",
                 "qualifications":"M.Sc. Chemistry, B.Ed.","emergencyContactName":"Arun Deshmukh",
                 "emergencyContactMobile":"098480-20102"}""".formatted(staff.email("anjali"), TestApi.PASSWORD,
                science))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Anjali Deshmukh"))
                .andExpect(jsonPath("$.roles", contains("TEACHER")))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.profileComplete").value(true))
                .andExpect(jsonPath("$.profile.employeeCode").value("SCI-01"))
                .andExpect(jsonPath("$.profile.department.name").value("Science"))
                .andExpect(jsonPath("$.profile.mobile").value("9848010102"))
                .andExpect(jsonPath("$.profile.emergencyContactMobile").value("9848020102"))
                .andExpect(jsonPath("$.leaveApprover.routing").value("SCHOOL")), "$.userId");
        // The new person signs in with the password the admin set.
        staff.login("anjali");
        api.get("/api/staff/" + anjali, admin.accessToken()).andExpect(jsonPath("$.email").value(
                staff.email("anjali")));

        // A taken email: nothing is saved.
        api.post("/api/staff", admin.accessToken(), staff.staffJson("Someone Else", "anjali", "TEACHER", "SCI-02",
                null, LocalDate.of(2020, 1, 6)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors.email").exists());
        // A taken employee code (any case): the sign-in created first is rolled back with it.
        api.post("/api/staff", admin.accessToken(), staff.staffJson("Rahul Verma", "rahul", "TEACHER", "sci-01",
                null, LocalDate.of(2020, 1, 6)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors.employeeCode").exists());
        api.get("/api/users", admin.accessToken())
                .andExpect(jsonPath("$[*].email", not(hasItem(staff.email("rahul")))));
        api.post("/api/auth/login", null, """
                {"schoolCode":"%s","email":"%s","password":"%s"}""".formatted(school.code(), staff.email("rahul"),
                TestApi.PASSWORD)).andExpect(status().isUnauthorized());
        api.get("/api/staff", admin.accessToken()).andExpect(jsonPath("$.total").value(2));

        // Field checks mirror the form.
        api.post("/api/staff", admin.accessToken(), """
                {"name":"X","email":"x@example.test","password":"short","roles":["TEACHER"],"employeeCode":"X-1",
                 "designation":"Teacher","employmentType":"PERMANENT","dateOfJoining":"2020-01-01",
                 "mobile":"9848000001"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.password").exists());
        api.post("/api/staff", admin.accessToken(), staff.staffJson("Anitha Sharma", "anitha", "PARENT", "P-1", null,
                LocalDate.of(2020, 1, 6)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.roles").exists());
        api.post("/api/staff", admin.accessToken(), """
                {"name":"X","email":"x@example.test","password":"long-enough-1","roles":["TEACHER"],
                 "employeeCode":"X-1","designation":"Teacher","employmentType":"PERMANENT",
                 "dateOfJoining":"2020-01-01","mobile":"12345"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.mobile").exists());
        api.post("/api/staff", admin.accessToken(), """
                {"name":"X","email":"x@example.test","password":"long-enough-1","roles":["TEACHER"],
                 "employeeCode":"X-1","designation":"Teacher","employmentType":"PERMANENT",
                 "dateOfJoining":"2020-01-01","mobile":"9848000001","emergencyContactName":"Y"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.emergencyContactMobile").exists());
        api.get("/api/audit-events?limit=200", admin.accessToken())
                .andExpect(jsonPath("$[*].action", hasItems("staff.created", "department.created")));
    }

    @Test
    void theDirectoryFiltersSearchesAndMasksMobiles() throws Exception {
        String science = staff.department("Science");
        String primary = staff.department("Primary");
        staff.staff("Anjali Deshmukh", "anjali", "TEACHER", "SCI-01", science);
        staff.staff("Rahul Verma", "rahul", "TEACHER", "SCI-02", science);
        String meena = staff.staff("Meena Reddy", "meena", "ACCOUNTANT", "ACC-01", null);
        api.put("/api/staff/" + meena + "/profile", admin.accessToken(), """
                {"employeeCode":"ACC-01","designation":"Accountant","employmentType":"CONTRACT",
                 "dateOfJoining":"2018-07-02","mobile":"9848010201"}""").andExpect(status().isOk());
        // A front office sign-in made on the Users page has no profile yet.
        String suresh = api.createUser(admin, "Suresh Rao", staff.email("suresh"), List.of("FRONT_OFFICE"));
        // Parents are not staff.
        api.createUser(admin, "Anitha Sharma", staff.email("anitha"), List.of("PARENT"));

        api.get("/api/staff", admin.accessToken())
                .andExpect(jsonPath("$.total").value(5))
                .andExpect(jsonPath("$.incompleteProfiles").value(2))
                .andExpect(jsonPath("$.items[*].name", contains("Anjali Deshmukh", "Asha Admin", "Meena Reddy",
                        "Rahul Verma", "Suresh Rao")))
                .andExpect(jsonPath("$.items[2].mobile").value("98480•••01"))
                .andExpect(jsonPath("$.items[4].profileComplete").value(false));
        api.get("/api/staff?departmentId=" + science, admin.accessToken())
                .andExpect(jsonPath("$.items[*].name", contains("Anjali Deshmukh", "Rahul Verma")));
        api.get("/api/staff?departmentId=" + primary, admin.accessToken()).andExpect(jsonPath("$.total").value(0));
        api.get("/api/staff?designation=accountant", admin.accessToken())
                .andExpect(jsonPath("$.items[*].name", contains("Meena Reddy")));
        api.get("/api/staff?role=FRONT_OFFICE", admin.accessToken())
                .andExpect(jsonPath("$.items[*].name", contains("Suresh Rao")));
        api.get("/api/staff?q=sci-02", admin.accessToken())
                .andExpect(jsonPath("$.items[*].name", contains("Rahul Verma")));
        api.get("/api/staff?q=REDDY", admin.accessToken())
                .andExpect(jsonPath("$.items[*].name", contains("Meena Reddy")));
        api.get("/api/staff?incomplete=true", admin.accessToken())
                .andExpect(jsonPath("$.items[*].name", contains("Asha Admin", "Suresh Rao")));
        api.get("/api/staff?status=LEFT", admin.accessToken()).andExpect(jsonPath("$.total").value(0));
        api.get("/api/staff?size=2&page=1", admin.accessToken())
                .andExpect(jsonPath("$.items[*].name", contains("Meena Reddy", "Rahul Verma")))
                .andExpect(jsonPath("$.total").value(5));
        api.get("/api/staff/designations", admin.accessToken())
                .andExpect(jsonPath("$", containsInAnyOrder("Accountant", "Teacher")));
        api.get("/api/staff/roles", admin.accessToken())
                .andExpect(jsonPath("$[*].code", not(hasItem("PARENT"))))
                .andExpect(jsonPath("$[*].code", hasItems("TEACHER", "SCHOOL_ADMIN")));

        // Completing the profile.
        api.put("/api/staff/" + suresh + "/profile", admin.accessToken(), """
                {"employeeCode":"FO-01","designation":"Front Office Executive","employmentType":"CONTRACT",
                 "dateOfJoining":"2023-01-02","mobile":"9848010202"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileComplete").value(true));
        api.get("/api/staff", admin.accessToken()).andExpect(jsonPath("$.incompleteProfiles").value(1));
        // Another person's employee code is refused.
        api.put("/api/staff/" + suresh + "/profile", admin.accessToken(), """
                {"employeeCode":"acc-01","designation":"Front Office Executive","employmentType":"CONTRACT",
                 "dateOfJoining":"2023-01-02","mobile":"9848010202"}""")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors.employeeCode").exists());
        api.get("/api/audit-events?limit=200", admin.accessToken())
                .andExpect(jsonPath("$[*].action", hasItems("staff.profile_created", "staff.profile_updated")));
    }

    @Test
    void leavingDisablesSignInButKeepsTheRecord() throws Exception {
        String rahul = staff.staff("Rahul Verma", "rahul", "TEACHER", "SCI-02", null);
        Session session = staff.login("rahul");

        api.post("/api/staff/" + rahul + "/leaving", admin.accessToken(), """
                {"leftOn":"%s","reason":"Moved to Pune"}""".formatted(staff.today.plusDays(1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.leftOn").exists());
        api.post("/api/staff/" + rahul + "/leaving", admin.accessToken(), """
                {"leftOn":"2019-01-01","reason":"Moved to Pune"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.leftOn").exists());
        api.post("/api/staff/" + rahul + "/leaving", admin.accessToken(), """
                {"leftOn":"%s","reason":"Moved to Pune"}""".formatted(staff.today))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LEFT"))
                .andExpect(jsonPath("$.accountActive").value(false))
                .andExpect(jsonPath("$.profile.dateOfLeaving").value(staff.today.toString()))
                .andExpect(jsonPath("$.profile.leavingReason").value("Moved to Pune"));

        // They can no longer sign in, and their open sessions cannot be renewed.
        api.post("/api/auth/login", null, """
                {"schoolCode":"%s","email":"%s","password":"%s"}""".formatted(school.code(), staff.email("rahul"),
                TestApi.PASSWORD)).andExpect(status().isUnauthorized());
        api.refresh(session.refreshToken()).andExpect(status().isUnauthorized());
        // The record stays, under "left".
        api.get("/api/staff?status=LEFT", admin.accessToken())
                .andExpect(jsonPath("$.items[*].name", contains("Rahul Verma")));
        api.get("/api/staff?status=ACTIVE", admin.accessToken())
                .andExpect(jsonPath("$.items[*].name", not(hasItem("Rahul Verma"))));
        api.get("/api/users/" + rahul, admin.accessToken()).andExpect(jsonPath("$.status").value("DISABLED"));

        api.post("/api/staff/" + rahul + "/leaving", admin.accessToken(), """
                {"leftOn":"%s","reason":"Again"}""".formatted(staff.today)).andExpect(status().isConflict());
        String me = TestApi.read(api.get("/api/staff?q=Asha", admin.accessToken()), "$.items[0].userId");
        api.put("/api/staff/" + me + "/profile", admin.accessToken(), """
                {"employeeCode":"ADM-1","designation":"School Administrator","employmentType":"PERMANENT",
                 "dateOfJoining":"2015-06-01","mobile":"9848010001"}""").andExpect(status().isOk());
        api.post("/api/staff/" + me + "/leaving", admin.accessToken(), """
                {"leftOn":"%s","reason":"Retired"}""".formatted(staff.today)).andExpect(status().isConflict());
        api.get("/api/audit-events?limit=200", admin.accessToken())
                .andExpect(jsonPath("$[*].action", hasItems("staff.left", "user.disabled")));
    }

    @Test
    void departmentsHaveUniqueNamesAndActiveHeads() throws Exception {
        String science = staff.department("Science");
        api.post("/api/staff/departments", admin.accessToken(), """
                {"name":"science"}""").andExpect(status().isConflict()).andExpect(jsonPath("$.errors.name").exists());
        String anjali = staff.staff("Anjali Deshmukh", "anjali", "TEACHER", "SCI-01", science);
        String parent = api.createUser(admin, "Anitha Sharma", staff.email("anitha"), List.of("PARENT"));
        api.put("/api/staff/departments/" + science, admin.accessToken(), """
                {"name":"Science","headUserId":"%s"}""".formatted(parent))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.headUserId").exists());
        staff.head(science, "Science", anjali);
        api.get("/api/staff/departments", admin.accessToken())
                .andExpect(jsonPath("$[0].head.name").value("Anjali Deshmukh"))
                .andExpect(jsonPath("$[0].staffCount").value(1));
        // A department with staff cannot be deleted; an empty one can.
        api.delete("/api/staff/departments/" + science, admin.accessToken()).andExpect(status().isConflict());
        String languages = staff.department("Languages");
        api.delete("/api/staff/departments/" + languages, admin.accessToken()).andExpect(status().isNoContent());
        api.get("/api/audit-events?limit=200", admin.accessToken()).andExpect(jsonPath("$[*].action",
                hasItems("department.created", "department.updated", "department.deleted")));
    }

    @Test
    void onlyTheRightRolesSeeAndChangeStaffRecords() throws Exception {
        String science = staff.department("Science");
        staff.staff("Lakshmi Iyer", "principal", "PRINCIPAL", "PR-1", null);
        String ravi = staff.staff("Ravi Kumar", "ravi", "TEACHER", "T-1", science);
        staff.staff("Meena Reddy", "meena", "ACCOUNTANT", "A-1", null);
        Session principal = staff.login("principal");
        Session teacher = staff.login("ravi");
        Session accountant = staff.login("meena");

        api.get("/api/staff", principal.accessToken()).andExpect(status().isOk());
        api.get("/api/staff/" + ravi, principal.accessToken()).andExpect(status().isOk());
        api.post("/api/staff", principal.accessToken(), staff.staffJson("X Y", "xy", "TEACHER", "T-9", null,
                LocalDate.of(2020, 1, 6))).andExpect(status().isForbidden());
        api.post("/api/staff/departments", principal.accessToken(), """
                {"name":"Arts"}""").andExpect(status().isForbidden());

        api.get("/api/staff", teacher.accessToken()).andExpect(status().isForbidden());
        api.get("/api/staff/" + ravi, teacher.accessToken()).andExpect(status().isForbidden());
        api.get("/api/staff", accountant.accessToken()).andExpect(status().isForbidden());
        api.get("/api/staff/departments", accountant.accessToken()).andExpect(status().isForbidden());
        api.post("/api/staff/" + ravi + "/leaving", accountant.accessToken(), """
                {"leftOn":"%s","reason":"x"}""".formatted(staff.today)).andExpect(status().isForbidden());
        // Everyone on the staff has their own leave and check-in.
        api.get("/api/leave/me", teacher.accessToken()).andExpect(status().isOk());
        api.get("/api/leave/me", accountant.accessToken()).andExpect(status().isOk());
        api.get("/api/staff-attendance/me/today", accountant.accessToken()).andExpect(status().isOk());
    }

    @Test
    void anotherSchoolsStaffAreInvisible() throws Exception {
        School otherSchool = api.signup();
        Session otherAdmin = api.login(otherSchool);
        StaffFixtures other = new StaffFixtures(api, otherSchool, otherAdmin);
        String otherDepartment = other.department("Science");
        String otherTeacher = other.staff("Kiran Joshi", "kiran", "TEACHER", "T-1", otherDepartment);

        api.get("/api/staff/" + otherTeacher, admin.accessToken()).andExpect(status().isNotFound());
        api.put("/api/staff/" + otherTeacher + "/profile", admin.accessToken(), """
                {"employeeCode":"T-2","designation":"Teacher","employmentType":"PERMANENT",
                 "dateOfJoining":"2020-01-01","mobile":"9848000001"}""").andExpect(status().isNotFound());
        api.post("/api/staff/" + otherTeacher + "/leaving", admin.accessToken(), """
                {"leftOn":"%s","reason":"x"}""".formatted(staff.today)).andExpect(status().isNotFound());
        api.get("/api/staff/" + otherTeacher + "/leave", admin.accessToken()).andExpect(status().isNotFound());
        api.get("/api/staff/" + otherTeacher + "/attendance", admin.accessToken()).andExpect(status().isNotFound());
        api.put("/api/staff/departments/" + otherDepartment, admin.accessToken(), """
                {"name":"Mine"}""").andExpect(status().isNotFound());
        api.delete("/api/staff/departments/" + otherDepartment, admin.accessToken())
                .andExpect(status().isNotFound());
        // Another school's department in a body is a field error.
        api.post("/api/staff", admin.accessToken(), staff.staffJson("Ravi Kumar", "ravi", "TEACHER", "T-1",
                otherDepartment, LocalDate.of(2020, 1, 6)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.departmentId").exists());
        String mine = staff.department("Science");
        api.put("/api/staff/departments/" + mine, admin.accessToken(), """
                {"name":"Science","headUserId":"%s"}""".formatted(otherTeacher))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.headUserId").exists());
        // The same employee code is fine in another school.
        staff.staff("Ravi Kumar", "ravi", "TEACHER", "T-1", mine);
        api.get("/api/staff", admin.accessToken())
                .andExpect(jsonPath("$.items[*].name", not(hasItem("Kiran Joshi"))));
        assertThat(TestApi.read(api.get("/api/staff", otherAdmin.accessToken()), "$.total")).isEqualTo("2");
    }
}
