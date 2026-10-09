package com.akshara.support;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.akshara.support.TestApi.Session;

/** Builds a school's academic setup and students through the API, as an admin would. */
public class SchoolFixtures {

    private final TestApi api;

    public SchoolFixtures(TestApi api) {
        this.api = api;
    }

    public String year(Session admin, String name, String startsOn, String endsOn, boolean current) throws Exception {
        return TestApi.read(api.post("/api/academics/years", admin.accessToken(), """
                {"name":"%s","startsOn":"%s","endsOn":"%s","current":%s}
                """.formatted(name, startsOn, endsOn, current)).andExpect(status().isCreated()), "$.id");
    }

    /** The 2026-27 year, made current. */
    public String currentYear(Session admin) throws Exception {
        return year(admin, "2026-27", "2026-06-01", "2027-03-31", true);
    }

    public String schoolClass(Session admin, String name) throws Exception {
        return TestApi.read(api.post("/api/academics/classes", admin.accessToken(), """
                {"name":"%s"}
                """.formatted(name)).andExpect(status().isCreated()), "$.id");
    }

    public String section(Session admin, String classId, String name, Integer capacity) throws Exception {
        return TestApi.read(api.post("/api/academics/classes/" + classId + "/sections", admin.accessToken(), """
                {"name":"%s","capacity":%s}
                """.formatted(name, capacity)).andExpect(status().isCreated()), "$.id");
    }

    /** Admits a student with one guardian (the primary contact) and returns the student's id. */
    public String student(Session admin, String sectionId, String admissionNo, String firstName, String lastName,
            String guardianName, String guardianPhone) throws Exception {
        return TestApi.read(api.post("/api/students", admin.accessToken(),
                studentJson(sectionId, admissionNo, firstName, lastName, guardianName, guardianPhone, null))
                .andExpect(status().isCreated()), "$.id");
    }

    public static String studentJson(String sectionId, String admissionNo, String firstName, String lastName,
            String guardianName, String guardianPhone, Integer rollNo) {
        return """
                {"admissionNo":"%s","firstName":"%s","lastName":%s,"dateOfBirth":"2016-04-12","gender":"FEMALE",
                 "admissionDate":"2026-06-02","sectionId":"%s","rollNo":%s,
                 "guardians":[{"name":"%s","relation":"MOTHER","phone":"%s","primary":true}]}
                """.formatted(admissionNo, firstName, lastName == null ? "null" : "\"" + lastName + "\"", sectionId,
                rollNo, guardianName, guardianPhone);
    }
}
