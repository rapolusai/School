package com.akshara.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import com.akshara.support.IntegrationTest;
import com.akshara.support.TestApi;
import com.akshara.support.TestApi.School;
import com.akshara.support.TestApi.Session;

/** "Forgot password" by emailed code, and changing your own password while signed in. */
class PasswordResetIT extends IntegrationTest {

    private static final Pattern CODE = Pattern.compile("code is (\\d{6})");

    @Test
    void aCodeFromTheEmailSetsANewPasswordAndEndsEveryOpenSession() throws Exception {
        School school = api.signup();
        Session before = api.login(school);

        requestCode(school.code(), school.adminEmail());
        String code = latestCode(school.adminEmail());

        confirm(school.code(), school.adminEmail(), wrong(code), "Brand-new-password-1")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.code").exists());
        confirm(school.code(), school.adminEmail(), code, "Brand-new-password-1")
                .andExpect(status().isNoContent());

        api.refresh(before.refreshToken()).andExpect(status().isUnauthorized());
        api.post("/api/auth/login", null, login(school.code(), school.adminEmail(), TestApi.PASSWORD))
                .andExpect(status().isUnauthorized());
        api.login(school.code(), school.adminEmail(), "Brand-new-password-1");

        // A code works once.
        confirm(school.code(), school.adminEmail(), code, "Another-password-22").andExpect(status().isBadRequest());
    }

    @Test
    void unknownSchoolsAndEmailsGetTheSameAnswerAndNoMessage() throws Exception {
        School school = api.signup();
        String known = requestCode(school.code(), school.adminEmail());
        String unknownEmail = requestCode(school.code(), "nobody@" + school.code() + ".akshara.test");
        String unknownSchool = requestCode("no-such-school-x", school.adminEmail());

        assertThat(unknownEmail).isEqualTo(known);
        assertThat(unknownSchool).isEqualTo(known);
        assertThat(countMessagesTo("nobody@" + school.code() + ".akshara.test")).isZero();
    }

    @Test
    void theFifthWrongTryUsesTheCodeUpAndANewRequestReplacesTheOldCode() throws Exception {
        School school = api.signup();
        requestCode(school.code(), school.adminEmail());
        String first = latestCode(school.adminEmail());
        for (int i = 0; i < PasswordResetService.MAX_ATTEMPTS; i++) {
            confirm(school.code(), school.adminEmail(), wrong(first), "Brand-new-password-1")
                    .andExpect(status().isBadRequest());
        }
        confirm(school.code(), school.adminEmail(), first, "Brand-new-password-1").andExpect(status().isBadRequest());

        requestCode(school.code(), school.adminEmail());
        String second = latestCode(school.adminEmail());
        requestCode(school.code(), school.adminEmail());
        String third = latestCode(school.adminEmail());
        if (!second.equals(third)) {
            confirm(school.code(), school.adminEmail(), second, "Brand-new-password-1")
                    .andExpect(status().isBadRequest());
        }
        confirm(school.code(), school.adminEmail(), third, "Brand-new-password-1").andExpect(status().isNoContent());
    }

    @Test
    void theMessageLogNeverShowsTheCode() throws Exception {
        School school = api.signup();
        Session admin = api.login(school);
        requestCode(school.code(), school.adminEmail());
        String code = latestCode(school.adminEmail());

        String id = TestApi.read(api.get("/api/messages", admin.accessToken()), "$.items[0].id");
        String detail = api.get("/api/messages/" + id, admin.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.templateKey").value("identity.password_reset"))
                .andReturn().getResponse().getContentAsString();
        assertThat(detail).doesNotContain(code).contains("hidden");
    }

    @Test
    void aSignedInPersonChangesTheirPasswordAndOtherDevicesAreSignedOut() throws Exception {
        School school = api.signup();
        Session otherDevice = api.login(school);
        Session thisDevice = api.login(school);

        api.post("/api/auth/password", thisDevice.accessToken(), change("Wrong-current-1", "Brand-new-password-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.currentPassword").exists());
        api.post("/api/auth/password", thisDevice.accessToken(), change(TestApi.PASSWORD, TestApi.PASSWORD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.newPassword").exists());
        api.post("/api/auth/password", thisDevice.accessToken(), change(TestApi.PASSWORD, "short"))
                .andExpect(status().isBadRequest());

        MockHttpServletResponse changed = api.post("/api/auth/password", thisDevice.accessToken(),
                change(TestApi.PASSWORD, "Brand-new-password-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value(school.adminEmail()))
                .andReturn().getResponse();
        Session fresh = TestApi.session(changed);

        api.refresh(otherDevice.refreshToken()).andExpect(status().isUnauthorized());
        api.refresh(thisDevice.refreshToken()).andExpect(status().isUnauthorized());
        api.refresh(fresh.refreshToken()).andExpect(status().isOk());
        api.login(school.code(), school.adminEmail(), "Brand-new-password-1");
    }

    @Test
    void changingAPasswordNeedsASignIn() throws Exception {
        api.post("/api/auth/password", null, change(TestApi.PASSWORD, "Brand-new-password-1"))
                .andExpect(status().isUnauthorized());
    }

    private String requestCode(String schoolCode, String email) throws Exception {
        return api.post("/api/auth/password-reset", null, """
                {"schoolCode":"%s","email":"%s"}
                """.formatted(schoolCode, email))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
    }

    private org.springframework.test.web.servlet.ResultActions confirm(String schoolCode, String email, String code,
            String password) throws Exception {
        return api.post("/api/auth/password-reset/confirm", null, """
                {"schoolCode":"%s","email":"%s","code":"%s","newPassword":"%s"}
                """.formatted(schoolCode, email, code, password));
    }

    /** The code in the newest queued message to this address (read as the owner, like a mailbox would). */
    private static String latestCode(String email) throws Exception {
        try (Connection owner = ownerConnection();
                PreparedStatement select = owner.prepareStatement("select body from notifications.message "
                        + "where lower(recipient) = lower(?) and template_key = 'identity.password_reset' "
                        + "order by created_at desc limit 1")) {
            select.setString(1, email);
            try (ResultSet rows = select.executeQuery()) {
                assertThat(rows.next()).as("a reset message was queued").isTrue();
                Matcher m = CODE.matcher(rows.getString(1));
                assertThat(m.find()).isTrue();
                return m.group(1);
            }
        }
    }

    private static int countMessagesTo(String email) throws Exception {
        try (Connection owner = ownerConnection();
                PreparedStatement select = owner.prepareStatement(
                        "select count(*) from notifications.message where lower(recipient) = lower(?)")) {
            select.setString(1, email);
            try (ResultSet rows = select.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    private static String wrong(String code) {
        return code.equals("000000") ? "111111" : "000000";
    }

    private static String login(String code, String email, String password) {
        return """
                {"schoolCode":"%s","email":"%s","password":"%s"}
                """.formatted(code, email, password);
    }

    private static String change(String current, String next) {
        return """
                {"currentPassword":"%s","newPassword":"%s"}
                """.formatted(current, next);
    }
}
