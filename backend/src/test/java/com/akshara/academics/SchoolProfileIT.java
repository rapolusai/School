package com.akshara.academics;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.akshara.support.IntegrationTest;
import com.akshara.support.TestApi;
import com.akshara.support.TestApi.School;
import com.akshara.support.TestApi.Session;

class SchoolProfileIT extends IntegrationTest {

    private School school;
    private Session admin;

    @BeforeEach
    void school() throws Exception {
        school = api.signup();
        admin = api.login(school);
    }

    @Test
    void adminsKeepTheContactDetailsUpToDate() throws Exception {
        api.get("/api/school/profile", admin.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(school.code()))
                .andExpect(jsonPath("$.board").value("CBSE"))
                .andExpect(jsonPath("$.city").value("Pune"))
                .andExpect(jsonPath("$.address").isEmpty());

        api.put("/api/school/profile", admin.accessToken(), """
                {"address":"12 MG Road, Pune 411001","phone":"+91 20 2612 3456","contactEmail":"office@school.test",
                 "udiseCode":"27251234567"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.address").value("12 MG Road, Pune 411001"))
                .andExpect(jsonPath("$.phone").value("+91 20 2612 3456"))
                .andExpect(jsonPath("$.udiseCode").value("27251234567"));
        api.get("/api/school/profile", admin.accessToken())
                .andExpect(jsonPath("$.contactEmail").value("office@school.test"));
        api.get("/api/audit-events?limit=50", admin.accessToken())
                .andExpect(jsonPath("$[*].action", hasItem("school.profile_updated")));

        // Empty fields clear the value.
        api.put("/api/school/profile", admin.accessToken(), """
                {"address":"","phone":"","contactEmail":"","udiseCode":""}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.address").isEmpty())
                .andExpect(jsonPath("$.udiseCode").isEmpty());
    }

    @Test
    void badValuesAreRefusedFieldByField() throws Exception {
        api.put("/api/school/profile", admin.accessToken(), """
                {"phone":"call me","contactEmail":"not-an-email","udiseCode":"1234"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.phone").exists())
                .andExpect(jsonPath("$.errors.contactEmail").exists())
                .andExpect(jsonPath("$.errors.udiseCode").value("A UDISE code has 11 digits."));
    }

    @Test
    void everyoneInTheSchoolCanReadItButOnlyAdminsChangeIt() throws Exception {
        for (String role : List.of("TEACHER", "PARENT")) {
            String email = role.toLowerCase() + "@" + school.code() + ".akshara.test";
            api.createUser(admin, "Person " + role, email, List.of(role));
            Session session = api.login(school.code(), email, TestApi.PASSWORD);
            api.get("/api/school/profile", session.accessToken()).andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(school.code()));
            api.put("/api/school/profile", session.accessToken(), """
                    {"address":"Elsewhere"}""").andExpect(status().isForbidden());
        }
        Session platform = api.platformLogin(PLATFORM_EMAIL, PLATFORM_PASSWORD);
        api.get("/api/school/profile", platform.accessToken()).andExpect(status().isForbidden());
        api.get("/api/school/profile", null).andExpect(status().isUnauthorized());
    }
}
