package com.akshara.payroll;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import com.akshara.support.IntegrationTest;
import com.akshara.support.TestApi.Session;
import com.jayway.jsonpath.JsonPath;

/** The payroll integration tests' base: the owner connection for test setup and the audit trail. */
abstract class PayrollIntegrationTest extends IntegrationTest {

    static Connection owner() throws SQLException {
        return ownerConnection();
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
}
