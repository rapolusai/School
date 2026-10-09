package com.akshara.platform;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;

import com.akshara.support.IntegrationTest;
import com.akshara.support.TestApi;
import com.akshara.support.TestApi.School;
import com.akshara.support.TestApi.Session;

class PlatformConsoleIT extends IntegrationTest {

    @Test
    void superAdminSeesEverySchoolWithUserCountsButNoSchoolData() throws Exception {
        School school = api.signup();
        Session root = api.platformLogin(PLATFORM_EMAIL, PLATFORM_PASSWORD);

        api.get("/api/me", root.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.platformAdmin").value(true))
                .andExpect(jsonPath("$.tenant").isEmpty())
                .andExpect(jsonPath("$.permissions[0]").value("platform.admin"));
        api.get("/api/platform/tenants", root.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code == '%s')].userCount".formatted(school.code())).value(hasItem(1)));
        api.get("/api/users", root.accessToken()).andExpect(status().isForbidden());
        api.get("/api/audit-events", root.accessToken()).andExpect(status().isForbidden());
    }

    @Test
    void superAdminCanAddASchoolThatStartsActive() throws Exception {
        Session root = api.platformLogin(PLATFORM_EMAIL, PLATFORM_PASSWORD);
        String code = TestApi.randomCode();
        String email = "head@" + code + ".akshara.test";
        api.post("/api/platform/tenants", root.accessToken(), """
                {"schoolName":"Sales Led School","schoolCode":"%s","board":"ICSE","city":"Chennai","plan":"GROWTH",
                 "adminName":"Hari Head","adminEmail":"%s","password":"%s"}
                """.formatted(code, email, TestApi.PASSWORD))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.plan").value("GROWTH"))
                .andExpect(jsonPath("$.userCount").value(1));

        Session admin = api.login(code, email, TestApi.PASSWORD);
        api.get("/api/audit-events", admin.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.action == 'school.created')].actorName").value(hasItem("Test Platform Admin")));
    }

    @Test
    void platformSignInFailuresAreGeneric() throws Exception {
        api.post("/api/platform/auth/login", null, """
                {"email":"%s","password":"wrong-password"}""".formatted(PLATFORM_EMAIL))
                .andExpect(status().isUnauthorized());
        api.post("/api/platform/auth/login", null, """
                {"email":"nobody@akshara.test","password":"wrong-password"}""")
                .andExpect(status().isUnauthorized());
    }
}
