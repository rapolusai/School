package com.akshara.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.akshara.support.IntegrationTest;
import com.akshara.support.TestApi;
import com.akshara.support.TestApi.School;
import com.akshara.support.TestApi.Session;

/**
 * The school's audit page: filters by action, person and days, "load more" with a cursor, the filter lists, and the
 * CSV export (which is itself audited; {@link AuditCsvTest} checks formulas are defused). Only people with audit.read see it,
 * and only their own school's trail.
 */
class AuditTrailIT extends IntegrationTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Kolkata"));

    @Test
    void filtersPagingFacetsAndExport() throws Exception {
        School school = api.signup();
        Session admin = api.login(school);
        String adminId = TestApi.read(api.get("/api/me", admin.accessToken()), "$.id");
        for (int i = 1; i <= 5; i++) {
            api.createUser(admin, "Teacher " + i, "t" + i + "@" + school.code() + ".akshara.test", List.of("TEACHER"));
        }

        // The five teachers and the admin added at sign-up.
        api.get("/api/audit-events?action=user.created", admin.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(6)))
                .andExpect(jsonPath("$[*].action", everyItem(is("user.created"))));
        api.get("/api/audit-events?actorId=" + adminId + "&from=" + TODAY + "&to=" + TODAY, admin.accessToken())
                .andExpect(jsonPath("$[*].actorName", everyItem(is("Asha Admin"))))
                .andExpect(jsonPath("$[?(@.action == 'user.created')].details").value(
                        org.hamcrest.Matchers.hasSize(org.hamcrest.Matchers.greaterThanOrEqualTo(5))));
        api.get("/api/audit-events?action=user.created&from=" + TODAY.plusDays(1), admin.accessToken())
                .andExpect(jsonPath("$", hasSize(0)));
        api.get("/api/audit-events?from=" + TODAY.plusDays(1) + "&to=" + TODAY, admin.accessToken())
                .andExpect(status().isBadRequest());

        // Load more: two at a time, newest first, no entry twice.
        String page1 = api.get("/api/audit-events?action=user.created&limit=2", admin.accessToken())
                .andReturn().getResponse().getContentAsString();
        String lastAt = com.jayway.jsonpath.JsonPath.read(page1, "$[1].at");
        String lastId = com.jayway.jsonpath.JsonPath.read(page1, "$[1].id");
        String firstId = com.jayway.jsonpath.JsonPath.read(page1, "$[0].id");
        api.get("/api/audit-events?action=user.created&limit=2&before=" + lastAt + "&beforeId=" + lastId,
                admin.accessToken())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].id", not(hasItem(firstId))))
                .andExpect(jsonPath("$[*].id", not(hasItem(lastId))));

        api.get("/api/audit-events/facets", admin.accessToken())
                .andExpect(jsonPath("$.actions", hasItem("user.created")))
                .andExpect(jsonPath("$.actions", hasItem("school.created")))
                .andExpect(jsonPath("$.people[*].id", hasItem(adminId)));

        byte[] csv = api.get("/api/audit-events/export.csv?action=user.created", admin.accessToken())
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("audit-trail.csv")))
                .andReturn().getResponse().getContentAsByteArray();
        String text = new String(csv, StandardCharsets.UTF_8);
        assertThat(text).startsWith("﻿Time (India),Person,Action");
        assertThat(text.lines().filter(l -> l.contains("user.created")).count()).isEqualTo(6);
        api.get("/api/audit-events?action=audit.exported", admin.accessToken())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].details.rows").value(6))
                .andExpect(jsonPath("$[0].details.action").value("user.created"));

        // Another school sees none of it; a teacher sees nothing at all.
        School other = api.signup();
        Session otherAdmin = api.login(other);
        api.get("/api/audit-events?action=user.created", otherAdmin.accessToken())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].details.email").value(other.adminEmail()));
        api.get("/api/audit-events?actorId=" + adminId, otherAdmin.accessToken()).andExpect(jsonPath("$", hasSize(0)));
        Session teacher = api.login(school.code(), "t1@" + school.code() + ".akshara.test", TestApi.PASSWORD);
        api.get("/api/audit-events", teacher.accessToken()).andExpect(status().isForbidden());
        api.get("/api/audit-events/facets", teacher.accessToken()).andExpect(status().isForbidden());
        api.get("/api/audit-events/export.csv", teacher.accessToken()).andExpect(status().isForbidden());
    }
}
