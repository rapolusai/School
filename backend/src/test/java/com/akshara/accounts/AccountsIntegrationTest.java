package com.akshara.accounts;

import java.util.List;
import java.util.Map;

import org.springframework.test.web.servlet.ResultActions;

import com.akshara.support.AccountsFixtures;
import com.akshara.support.IntegrationTest;
import com.akshara.support.TestApi;
import com.akshara.support.TestApi.School;
import com.akshara.support.TestApi.Session;
import com.jayway.jsonpath.JsonPath;

/**
 * The accounts integration tests' base: a school with its admin, an accountant (reads and manages), a principal
 * (reads and approves) and a teacher (no accounts access), plus the audit trail.
 */
abstract class AccountsIntegrationTest extends IntegrationTest {

    /** A school with the four people signed in and the standard chart of heads. */
    final class AccountsSchool {

        final School school;
        final Session admin;
        final Session accountant;
        final Session principal;
        final Session teacher;
        final String adminId;
        final String accountantId;
        final String principalId;
        final AccountsFixtures fixtures;
        final String electricity;
        final String repairs;
        final String salaries;
        final String donations;
        final String rentals;

        AccountsSchool() throws Exception {
            school = api.signup();
            admin = api.login(school);
            fixtures = new AccountsFixtures(api);
            accountantId = api.createUser(admin, "Meena Reddy", email("meena"), List.of("ACCOUNTANT"));
            principalId = api.createUser(admin, "Lakshmi Iyer", email("lakshmi"), List.of("PRINCIPAL"));
            api.createUser(admin, "Ravi Kumar", email("ravi"), List.of("TEACHER"));
            accountant = api.login(school.code(), email("meena"), TestApi.PASSWORD);
            principal = api.login(school.code(), email("lakshmi"), TestApi.PASSWORD);
            teacher = api.login(school.code(), email("ravi"), TestApi.PASSWORD);
            adminId = TestApi.read(api.get("/api/me", admin.accessToken()), "$.id");
            electricity = fixtures.standardHead(accountant, "ELECTRICITY");
            ResultActions heads = api.get("/api/accounts/heads", accountant.accessToken());
            repairs = AccountsFixtures.first(heads, "$[?(@.systemCode == 'REPAIRS')].id");
            salaries = AccountsFixtures.first(heads, "$[?(@.systemCode == 'SALARIES')].id");
            donations = AccountsFixtures.first(heads, "$[?(@.systemCode == 'DONATIONS')].id");
            rentals = AccountsFixtures.first(heads, "$[?(@.systemCode == 'RENTALS')].id");
        }

        String email(String mailbox) {
            return mailbox + "@" + school.code() + ".akshara.test";
        }

        /** The principal approves anything up to the limit; the admin has no limit. */
        void limits(long principalLimitPaise) throws Exception {
            fixtures.approvers(admin, """
                    {"approvers":[{"userId":"%s","limitPaise":%d},{"userId":"%s","limitPaise":null}]}"""
                    .formatted(principalId, principalLimitPaise, adminId));
        }
    }

    /** The school's latest audit events with this action, newest first. */
    List<Map<String, Object>> audit(Session admin, String action) throws Exception {
        String json = api.get("/api/audit-events?limit=200", admin.accessToken()).andReturn().getResponse()
                .getContentAsString();
        return JsonPath.read(json, "$[?(@.action == '" + action + "')]");
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> details(Map<String, Object> event) {
        return (Map<String, Object>) event.get("details");
    }

    static String body(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString();
    }
}
