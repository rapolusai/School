package com.akshara.staff;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.test.web.servlet.ResultActions;

import com.akshara.support.TestApi;
import com.akshara.support.TestApi.School;
import com.akshara.support.TestApi.Session;
import com.jayway.jsonpath.JsonPath;

/** Builds a school's staff through the API, as a School Admin would. */
final class StaffFixtures {

    static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    final TestApi api;
    final School school;
    final Session admin;
    final LocalDate today = LocalDate.now(INDIA);
    private int mobiles;

    StaffFixtures(TestApi api, School school, Session admin) {
        this.api = api;
        this.school = school;
        this.admin = admin;
    }

    String email(String mailbox) {
        return mailbox + "@" + school.code() + ".akshara.test";
    }

    Session login(String mailbox) throws Exception {
        return api.login(school.code(), email(mailbox), TestApi.PASSWORD);
    }

    /** "This year" (current, about five months old) and "Last year" before it. Returns {current, previous}. */
    String[] years() throws Exception {
        String previous = year("Last year", today.minusDays(515), today.minusDays(151), false);
        String current = year("This year", today.minusDays(150), today.plusDays(200), true);
        return new String[] {current, previous};
    }

    String year(String name, LocalDate startsOn, LocalDate endsOn, boolean current) throws Exception {
        return TestApi.read(api.post("/api/academics/years", admin.accessToken(), """
                {"name":"%s","startsOn":"%s","endsOn":"%s","current":%s}
                """.formatted(name, startsOn, endsOn, current)).andExpect(status().isCreated()), "$.id");
    }

    String department(String name) throws Exception {
        return TestApi.read(api.post("/api/staff/departments", admin.accessToken(), """
                {"name":"%s"}""".formatted(name)).andExpect(status().isCreated()), "$.id");
    }

    void head(String departmentId, String name, String headUserId) throws Exception {
        api.put("/api/staff/departments/" + departmentId, admin.accessToken(), """
                {"name":"%s","headUserId":"%s"}""".formatted(name, headUserId)).andExpect(status().isOk());
    }

    /** Adds a staff member (sign-in and profile) who joined in 2020; returns their user id. */
    String staff(String name, String mailbox, String role, String code, String departmentId) throws Exception {
        return staff(name, mailbox, role, code, departmentId, LocalDate.of(2020, 1, 6));
    }

    String staff(String name, String mailbox, String role, String code, String departmentId, LocalDate joined)
            throws Exception {
        return TestApi.read(api.post("/api/staff", admin.accessToken(),
                staffJson(name, mailbox, role, code, departmentId, joined)).andExpect(status().isCreated()),
                "$.userId");
    }

    String staffJson(String name, String mailbox, String role, String code, String departmentId, LocalDate joined) {
        mobiles++;
        return """
                {"name":"%s","email":"%s","password":"%s","roles":["%s"],"employeeCode":"%s",
                 "designation":"Teacher","departmentId":%s,"employmentType":"PERMANENT","dateOfJoining":"%s",
                 "mobile":"98480%05d"}""".formatted(name, email(mailbox), TestApi.PASSWORD, role, code,
                departmentId == null ? "null" : "\"" + departmentId + "\"", joined, mobiles);
    }

    /** Adds the standard leave types; returns their ids by code (CL, SL, EL, ML, PL, LOP). */
    Map<String, String> standardTypes() throws Exception {
        String body = api.post("/api/leave/types/standard", admin.accessToken(), null)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> types = JsonPath.read(body, "$");
        Map<String, String> ids = new HashMap<>();
        types.forEach(t -> ids.put((String) t.get("code"), (String) t.get("id")));
        return ids;
    }

    ResultActions apply(Session who, String typeId, LocalDate from, LocalDate to, boolean halfDay) throws Exception {
        return api.post("/api/leave/requests", who.accessToken(), """
                {"leaveTypeId":"%s","fromDate":"%s","toDate":"%s","halfDay":%s,"reason":"Family function"}"""
                .formatted(typeId, from, to, halfDay));
    }

    String applied(Session who, String typeId, LocalDate from, LocalDate to, boolean halfDay) throws Exception {
        return TestApi.read(apply(who, typeId, from, to, halfDay).andExpect(status().isCreated()), "$.id");
    }

    ResultActions decide(Session who, String requestId, String action, String comment) throws Exception {
        return api.post("/api/leave/requests/" + requestId + "/" + action, who.accessToken(),
                comment == null ? null : """
                        {"comment":"%s"}""".formatted(comment));
    }

    /** The Monday {@code weeks} weeks after the coming one (0: the coming Monday). */
    LocalDate monday(int weeks) {
        return today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)).plusWeeks(weeks);
    }

    /** The Monday one to two weeks ago. */
    LocalDate pastMonday() {
        return today.with(TemporalAdjusters.previous(DayOfWeek.MONDAY)).minusWeeks(1);
    }

    /** The most recent working day before today. */
    LocalDate lastWorkingDay() {
        LocalDate d = today.minusDays(1);
        return d.getDayOfWeek() == DayOfWeek.SUNDAY ? d.minusDays(1) : d;
    }

    /** The first value at a JSON path (a filter returns a list). */
    static Object first(ResultActions result, String path) throws Exception {
        Object value = JsonPath.read(result.andReturn().getResponse().getContentAsString(), path);
        if (value instanceof List<?> list) {
            return list.isEmpty() ? null : list.getFirst();
        }
        return value;
    }
}
