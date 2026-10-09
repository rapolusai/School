package com.akshara.identity;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import com.akshara.support.IntegrationTest;
import com.akshara.support.TestApi;
import com.akshara.support.TestApi.School;
import com.akshara.support.TestApi.Session;

/** Every endpoint checks who is calling and what their role allows. */
class AuthorizationIT extends IntegrationTest {

    private School school;
    private Session admin;

    @BeforeEach
    void school() throws Exception {
        school = api.signup();
        admin = api.login(school);
    }

    @Test
    void protectedEndpointsNeedASignedInUser() throws Exception {
        for (String path : List.of("/api/me", "/api/users", "/api/roles", "/api/audit-events",
                "/api/platform/tenants")) {
            api.get(path, null).andExpect(status().isUnauthorized());
            api.get(path, "not.a.jwt").andExpect(status().isUnauthorized());
        }
        api.post("/api/users", null, "{}").andExpect(status().isUnauthorized());
    }

    @Test
    void tokensSignedWithAnotherKeyAreRejected() throws Exception {
        var key = new SecretKeySpec("some-other-key-that-is-long-enough-0123456789".getBytes(StandardCharsets.UTF_8),
                "HmacSHA256");
        String forged = NimbusJwtEncoder.withSecretKey(key).algorithm(MacAlgorithm.HS256).build()
                .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), JwtClaimsSet.builder()
                        .issuer("akshara").subject(UUID.randomUUID().toString())
                        .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600))
                        .claim("typ", "platform").claim("perms", List.of("platform.admin"))
                        .build()))
                .getTokenValue();
        api.get("/api/platform/tenants", forged).andExpect(status().isUnauthorized());
    }

    @Test
    void teachersCannotManagePeopleOrReadTheAuditTrail() throws Exception {
        String email = "teacher@" + school.code() + ".akshara.test";
        api.createUser(admin, "Tara Teacher", email, List.of("TEACHER"));
        Session teacher = api.login(school.code(), email, TestApi.PASSWORD);

        api.get("/api/me", teacher.accessToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions", hasItem("attendance.mark")));
        api.get("/api/users", teacher.accessToken()).andExpect(status().isForbidden());
        api.get("/api/roles", teacher.accessToken()).andExpect(status().isForbidden());
        api.get("/api/audit-events", teacher.accessToken()).andExpect(status().isForbidden());
        api.post("/api/users", teacher.accessToken(), newUser("x@" + school.code() + ".akshara.test", "TEACHER"))
                .andExpect(status().isForbidden());
        api.get("/api/platform/tenants", teacher.accessToken()).andExpect(status().isForbidden());
    }

    @Test
    void principalsCanReadPeopleButNotAddThem() throws Exception {
        String email = "principal@" + school.code() + ".akshara.test";
        api.createUser(admin, "Pallavi Principal", email, List.of("PRINCIPAL"));
        Session principal = api.login(school.code(), email, TestApi.PASSWORD);

        api.get("/api/users", principal.accessToken()).andExpect(status().isOk());
        api.get("/api/audit-events", principal.accessToken()).andExpect(status().isOk());
        api.post("/api/users", principal.accessToken(), newUser("y@" + school.code() + ".akshara.test", "TEACHER"))
                .andExpect(status().isForbidden());
    }

    @Test
    void schoolAdminsCannotReachThePlatformConsole() throws Exception {
        api.get("/api/platform/tenants", admin.accessToken()).andExpect(status().isForbidden());
        api.post("/api/platform/tenants", admin.accessToken(), "{}").andExpect(status().isForbidden());
    }

    @Test
    void addingPeopleValidatesEmailRolesAndPassword() throws Exception {
        String email = "dup@" + school.code() + ".akshara.test";
        api.createUser(admin, "First", email, List.of("TEACHER"));
        api.post("/api/users", admin.accessToken(), newUser(email.toUpperCase(), "TEACHER"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors.email").exists());
        api.post("/api/users", admin.accessToken(), newUser("new@" + school.code() + ".akshara.test", "WIZARD"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.roles").value("Unknown role: WIZARD."));
        api.post("/api/users", admin.accessToken(), """
                {"name":"","email":"bad","password":"short","roles":[]}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name").exists())
                .andExpect(jsonPath("$.errors.email").exists())
                .andExpect(jsonPath("$.errors.password").exists())
                .andExpect(jsonPath("$.errors.roles").exists());
    }

    @Test
    void auditLimitIsBounded() throws Exception {
        api.get("/api/audit-events?limit=500", admin.accessToken()).andExpect(status().isBadRequest());
        api.get("/api/audit-events?limit=0", admin.accessToken()).andExpect(status().isBadRequest());
        api.get("/api/audit-events?limit=abc", admin.accessToken()).andExpect(status().isBadRequest());
        api.get("/api/audit-events?limit=5", admin.accessToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].action").exists());
    }

    @Test
    void unknownPathsAreNotFoundOrDenied() throws Exception {
        api.get("/api/nothing-here", admin.accessToken()).andExpect(status().isNotFound());
        api.get("/not-api", null).andExpect(status().isUnauthorized());
    }

    private static String newUser(String email, String role) {
        return """
                {"name":"New Person","email":"%s","password":"%s","roles":["%s"]}
                """.formatted(email, TestApi.PASSWORD, role);
    }
}
