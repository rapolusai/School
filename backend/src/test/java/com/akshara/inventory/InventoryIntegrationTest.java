package com.akshara.inventory;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.springframework.test.web.servlet.ResultActions;

import com.akshara.support.AccountsFixtures;
import com.akshara.support.IntegrationTest;
import com.akshara.support.InventoryFixtures;
import com.akshara.support.TestApi;
import com.akshara.support.TestApi.School;
import com.akshara.support.TestApi.Session;
import com.jayway.jsonpath.JsonPath;

/**
 * The inventory integration tests' base: a school with its admin, a storekeeper (reads and manages), a principal
 * (reads and approves), a teacher in the Science department (reads, raises requisitions) whose department head is
 * another teacher, and an accountant (reads only), plus a vendor, a category and a store.
 */
abstract class InventoryIntegrationTest extends IntegrationTest {

    final class InventorySchool {

        final School school;
        final Session admin;
        final Session keeper;
        final Session principal;
        final Session teacher;
        final Session head;
        final Session accountant;
        final String adminId;
        final String keeperId;
        final String principalId;
        final String teacherId;
        final String headId;
        final String science;
        final String vendor;
        final String stationery;
        final String store;
        final InventoryFixtures fixtures;

        InventorySchool() throws Exception {
            school = api.signup();
            admin = api.login(school);
            fixtures = new InventoryFixtures(api);
            adminId = TestApi.read(api.get("/api/me", admin.accessToken()), "$.id");
            keeperId = api.createUser(admin, "Mohan Das", email("mohan"), List.of("STOREKEEPER"));
            principalId = api.createUser(admin, "Lakshmi Iyer", email("lakshmi"), List.of("PRINCIPAL"));
            api.createUser(admin, "Meena Reddy", email("meena"), List.of("ACCOUNTANT"));
            science = TestApi.read(api.post("/api/staff/departments", admin.accessToken(), """
                    {"name":"Science"}""").andExpect(status().isCreated()), "$.id");
            headId = staff("Anjali Deshmukh", "anjali", science);
            teacherId = staff("Rahul Verma", "rahul", science);
            api.put("/api/staff/departments/" + science, admin.accessToken(), """
                    {"name":"Science","headUserId":"%s"}""".formatted(headId)).andExpect(status().isOk());
            keeper = api.login(school.code(), email("mohan"), TestApi.PASSWORD);
            principal = api.login(school.code(), email("lakshmi"), TestApi.PASSWORD);
            teacher = api.login(school.code(), email("rahul"), TestApi.PASSWORD);
            head = api.login(school.code(), email("anjali"), TestApi.PASSWORD);
            accountant = api.login(school.code(), email("meena"), TestApi.PASSWORD);
            vendor = new AccountsFixtures(api).vendor(admin, "Vijaya Stationers");
            stationery = fixtures.category(keeper, "Stationery", 1500);
            store = fixtures.location(keeper, "Main store", "STORE");
        }

        String staff(String name, String mailbox, String department) throws Exception {
            return TestApi.read(api.post("/api/staff", admin.accessToken(), """
                    {"name":"%s","email":"%s","password":"%s","roles":["TEACHER"],"employeeCode":"%s",
                     "designation":"Teacher","departmentId":"%s","employmentType":"PERMANENT",
                     "dateOfJoining":"2020-06-01","mobile":"9876501003"}"""
                    .formatted(name, email(mailbox), TestApi.PASSWORD, mailbox.toUpperCase(), department))
                    .andExpect(status().isCreated()), "$.userId");
        }

        String email(String mailbox) {
            return mailbox + "@" + school.code() + ".akshara.test";
        }

        /** The principal approves anything up to the limit; the admin has no limit. */
        void limits(long principalLimitPaise) throws Exception {
            fixtures.approvers(admin, """
                    {"approvers":[{"userId":"%s","limitPaise":%d},{"userId":"%s","limitPaise":null}]}"""
                    .formatted(principalId, principalLimitPaise, adminId)).andExpect(status().isOk());
        }

        String consumable(String name, int reorder) throws Exception {
            return fixtures.item(keeper, name, stationery, "CONSUMABLE", reorder, vendor);
        }
    }

    /** The school's latest audit events with this action, newest first. */
    List<Map<String, Object>> audit(Session admin, String action) throws Exception {
        String json = api.get("/api/audit-events?limit=200", admin.accessToken()).andReturn().getResponse()
                .getContentAsString();
        return JsonPath.read(json, "$[?(@.action == '" + action + "')]");
    }

    static String body(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString();
    }
}
