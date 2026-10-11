package com.akshara.payroll;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
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

/**
 * A school ready for payroll, built through the API as its School Admin and accountant would: an academic year around
 * today, two departments, four staff (a teacher, the accountant, a part-time teacher who joined in the middle of last
 * month and the principal, who has no payroll access), Telangana settings, the standard components, pay details and
 * salary structures. Months are relative to today, so the tests do not depend on the date they run.
 */
final class PayrollSchool {

    static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    static final String RAVI_ACCOUNT = "000000123456";
    static final String RAVI_PAN = "ABCDE1234F";

    final TestApi api;
    final School school;
    final Session admin;
    final LocalDate today = LocalDate.now(INDIA);
    final YearMonth thisMonth = YearMonth.from(today);
    final YearMonth lastMonth = thisMonth.minusMonths(1);
    final YearMonth twoMonthsAgo = thisMonth.minusMonths(2);

    String primary;
    String office;
    String ravi;
    String meena;
    String sita;
    String lakshmi;
    Map<String, String> components;
    Session accountant;
    Session raviSession;
    Session principal;

    PayrollSchool(TestApi api) throws Exception {
        this.api = api;
        this.school = api.signup();
        this.admin = api.login(school);
    }

    String email(String mailbox) {
        return mailbox + "@" + school.code() + ".akshara.test";
    }

    Session login(String mailbox) throws Exception {
        return api.login(school.code(), email(mailbox), TestApi.PASSWORD);
    }

    /** Staff, settings, components, pay details and structures; nobody signed in yet except the admin. */
    PayrollSchool build() throws Exception {
        api.post("/api/academics/years", admin.accessToken(), """
                {"name":"This year","startsOn":"%s","endsOn":"%s","current":true}"""
                .formatted(today.minusDays(150), today.plusDays(200))).andExpect(status().isCreated());
        primary = department("Primary");
        office = department("Office");
        ravi = staff("Ravi Kumar", "ravi", "TEACHER", "T-1", primary, LocalDate.of(2020, 1, 6), "Teacher");
        meena = staff("Meena Reddy", "meena", "ACCOUNTANT", "A-1", office, LocalDate.of(2021, 6, 1), "Accountant");
        sita = staff("Sita Devi", "sita", "TEACHER", "T-2", primary, lastMonth.atDay(16), "Hindi Teacher");
        lakshmi = staff("Lakshmi Iyer", "lakshmi", "PRINCIPAL", "PR-1", null, LocalDate.of(2019, 4, 1), "Principal");
        settings();
        components = standardComponents();
        profile(ravi, RAVI_ACCOUNT, RAVI_PAN, "100000000001", null);
        profile(meena, "000000222333", "PQRST6789K", null, null);
        profile(sita, "000000444555", null, "100000000003", "1000000003");
        // Ravi: ₹50,000 with a ₹1,000 loan recovery. Meena: ₹36,000. Sita: ₹18,000 (ESI applies).
        structure(ravi, twoMonthsAgo, Map.of("BASIC", 30_000_00L, "HRA", 12_000_00L, "SPECIAL", 8_000_00L,
                "LOAN", 1_000_00L));
        structure(meena, twoMonthsAgo, Map.of("BASIC", 20_000_00L, "HRA", 8_000_00L, "CONVEYANCE", 1_600_00L,
                "SPECIAL", 6_400_00L));
        structure(sita, lastMonth, Map.of("BASIC", 9_000_00L, "HRA", 4_500_00L, "SPECIAL", 4_500_00L));
        accountant = login("meena");
        raviSession = login("ravi");
        principal = login("lakshmi");
        return this;
    }

    String department(String name) throws Exception {
        return TestApi.read(api.post("/api/staff/departments", admin.accessToken(), """
                {"name":"%s"}""".formatted(name)).andExpect(status().isCreated()), "$.id");
    }

    String staff(String name, String mailbox, String role, String code, String departmentId, LocalDate joined,
            String designation) throws Exception {
        return TestApi.read(api.post("/api/staff", admin.accessToken(), """
                {"name":"%s","email":"%s","password":"%s","roles":["%s"],"employeeCode":"%s",
                 "designation":"%s","departmentId":%s,"employmentType":"PERMANENT","dateOfJoining":"%s",
                 "mobile":"98480%05d"}""".formatted(name, email(mailbox), TestApi.PASSWORD, role, code, designation,
                departmentId == null ? "null" : "\"" + departmentId + "\"", joined, code.hashCode() & 0xFFFF))
                .andExpect(status().isCreated()), "$.userId");
    }

    void settings() throws Exception {
        api.put("/api/payroll/settings", admin.accessToken(), """
                {"payDay":1,"prorationBasis":"CALENDAR_DAYS","pfEnabled":true,"pfOnFullWages":false,
                 "esiEnabled":true,"ptEnabled":true,"ptState":"TG","pfEstablishmentCode":"TSHYD0012345000",
                 "esiEmployerCode":"52000123450000999","ptRegistration":"PT123","tan":"HYDA12345B",
                 "bankName":"Akshara Test Bank","bankBranch":"Banjara Hills","bankAccountNumber":"000000900001",
                 "bankIfsc":"AKSH0000001"}""").andExpect(status().isOk());
    }

    Map<String, String> standardComponents() throws Exception {
        String body = api.post("/api/payroll/components/standard", admin.accessToken(), null)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> list = JsonPath.read(body, "$");
        Map<String, String> ids = new HashMap<>();
        list.forEach(c -> ids.put((String) c.get("code"), (String) c.get("id")));
        return ids;
    }

    void profile(String userId, String account, String pan, String uan, String esi) throws Exception {
        api.put("/api/payroll/staff/" + userId + "/pay-profile", admin.accessToken(), """
                {"bankAccountNumber":%s,"bankIfsc":%s,"pan":%s,"uan":%s,"esiNumber":%s,"pfMember":true,
                 "ptWomenSlab":false}""".formatted(json(account), account == null ? "null" : "\"AKSH0000001\"",
                json(pan), json(uan), json(esi))).andExpect(status().isOk());
    }

    ResultActions structureRequest(Session who, String userId, YearMonth from, Map<String, Long> amounts)
            throws Exception {
        StringBuilder lines = new StringBuilder();
        amounts.forEach((code, paise) -> {
            if (!lines.isEmpty()) {
                lines.append(',');
            }
            lines.append("{\"componentId\":\"").append(components.get(code)).append("\",\"monthlyPaise\":")
                    .append(paise).append('}');
        });
        return api.post("/api/payroll/staff/" + userId + "/structures", who.accessToken(), """
                {"effectiveFrom":"%s","note":"Test","lines":[%s]}""".formatted(from, lines));
    }

    String structure(String userId, YearMonth from, Map<String, Long> amounts) throws Exception {
        return TestApi.read(structureRequest(admin, userId, from, amounts).andExpect(status().isCreated()), "$.id");
    }

    /** Creates last month's run as the accountant; returns its id. */
    String lastMonthRun() throws Exception {
        return run(lastMonth);
    }

    String run(YearMonth month) throws Exception {
        return TestApi.read(api.post("/api/payroll/runs", accountant.accessToken(), """
                {"month":"%s"}""".formatted(month)).andExpect(status().isCreated()), "$.id");
    }

    void approve(String runId) throws Exception {
        api.post("/api/payroll/runs/" + runId + "/approve", admin.accessToken(), null).andExpect(status().isOk());
    }

    /** Ravi's approved loss-of-pay leave on the first Monday and Tuesday of last month (two working days). */
    LocalDate lossOfPayForRavi() throws Exception {
        String body = api.post("/api/leave/types/standard", admin.accessToken(), null)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<String> lop = JsonPath.read(body, "$[?(@.code == 'LOP')].id");
        LocalDate monday = lastMonth.atDay(1).with(TemporalAdjusters.firstInMonth(DayOfWeek.MONDAY));
        String request = TestApi.read(api.post("/api/leave/requests", raviSession.accessToken(), """
                {"leaveTypeId":"%s","fromDate":"%s","toDate":"%s","halfDay":false,"reason":"Personal work"}"""
                .formatted(lop.getFirst(), monday, monday.plusDays(1))).andExpect(status().isCreated()), "$.id");
        api.post("/api/leave/requests/" + request + "/approve", admin.accessToken(), null)
                .andExpect(status().isOk());
        return monday;
    }

    /**
     * Kiran Rao, a teacher on ₹15,000 (within ESI) with full pay details, who left on the 10th of last month. Returns
     * the user id.
     */
    String leaver() throws Exception {
        String kiran = staff("Kiran Rao", "kiran", "TEACHER", "T-3", primary, LocalDate.of(2022, 7, 1), "Teacher");
        profile(kiran, "000000666777", null, "100000000004", "1000000004");
        structure(kiran, twoMonthsAgo, Map.of("BASIC", 8_000_00L, "HRA", 4_000_00L, "SPECIAL", 3_000_00L));
        api.post("/api/staff/" + kiran + "/leaving", admin.accessToken(), """
                {"leftOn":"%s","reason":"Moved to another city"}""".formatted(lastMonth.atDay(10)))
                .andExpect(status().isOk());
        return kiran;
    }

    /** Gives the principal's role payroll.read only, as a school could; the principal must sign in again. */
    void principalCanReadPayroll() throws Exception {
        try (Connection owner = PayrollIntegrationTest.owner();
                PreparedStatement s = owner.prepareStatement("""
                        update identity.role set permissions = array_append(permissions, 'payroll.read')
                        where tenant_id = ? and code = 'PRINCIPAL'""")) {
            s.setObject(1, school.tenantId());
            s.executeUpdate();
        }
        principal = login("lakshmi");
    }

    static String json(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    static String body(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString();
    }

    /** The first value at a JSON path; a filter returns a list. */
    static Object first(ResultActions result, String path) throws Exception {
        Object value = JsonPath.read(body(result), path);
        if (value instanceof List<?> list) {
            return list.isEmpty() ? null : list.getFirst();
        }
        return value;
    }
}
