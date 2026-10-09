package com.akshara.support;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import jakarta.servlet.http.Cookie;

import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.jayway.jsonpath.JsonPath;

/** Small helpers for driving the API the way the web app does. */
public class TestApi {

    public static final String PASSWORD = "Correct-Horse-42";

    private final MockMvc mvc;

    public TestApi(MockMvc mvc) {
        this.mvc = mvc;
    }

    public record School(UUID tenantId, String code, String adminEmail) {
    }

    public record Session(String accessToken, String refreshToken, String body) {

        public String json(String path) {
            Object value = JsonPath.read(body, path);
            return value == null ? null : value.toString();
        }
    }

    public static String randomCode() {
        byte[] bytes = new byte[5];
        ThreadLocalRandom.current().nextBytes(bytes);
        return "s" + HexFormat.of().formatHex(bytes);
    }

    public School signup() throws Exception {
        String code = randomCode();
        String email = "admin@" + code + ".akshara.test";
        MockHttpServletResponse response = perform(MockMvcRequestBuilders.post("/api/public/signup"), null, """
                {"schoolName":"Test School %s","schoolCode":"%s","board":"CBSE","city":"Pune",
                 "adminName":"Asha Admin","adminEmail":"%s","password":"%s"}
                """.formatted(code, code, email, PASSWORD))
                .andExpect(status().isCreated())
                .andReturn().getResponse();
        String tenantId = JsonPath.read(response.getContentAsString(), "$.tenantId");
        return new School(UUID.fromString(tenantId), code, email);
    }

    public Session login(String schoolCode, String email, String password) throws Exception {
        MockHttpServletResponse response = perform(MockMvcRequestBuilders.post("/api/auth/login"), null, """
                {"schoolCode":"%s","email":"%s","password":"%s"}
                """.formatted(schoolCode, email, password))
                .andExpect(status().isOk())
                .andReturn().getResponse();
        return session(response);
    }

    public Session login(School school) throws Exception {
        return login(school.code(), school.adminEmail(), PASSWORD);
    }

    public Session platformLogin(String email, String password) throws Exception {
        MockHttpServletResponse response = perform(MockMvcRequestBuilders.post("/api/platform/auth/login"), null, """
                {"email":"%s","password":"%s"}
                """.formatted(email, password))
                .andExpect(status().isOk())
                .andReturn().getResponse();
        return session(response);
    }

    /** Creates a person in the admin's school and returns their id. */
    public String createUser(Session admin, String name, String email, List<String> roles) throws Exception {
        String roleJson = String.join(",", roles.stream().map(r -> "\"" + r + "\"").toList());
        MockHttpServletResponse response = perform(MockMvcRequestBuilders.post("/api/users"), admin.accessToken(), """
                {"name":"%s","email":"%s","password":"%s","roles":[%s]}
                """.formatted(name, email, PASSWORD, roleJson))
                .andExpect(status().isCreated())
                .andReturn().getResponse();
        return JsonPath.read(response.getContentAsString(), "$.id");
    }

    public ResultActions get(String path, String token) throws Exception {
        return perform(MockMvcRequestBuilders.get(path), token, null);
    }

    public ResultActions post(String path, String token, String json) throws Exception {
        return perform(MockMvcRequestBuilders.post(path), token, json);
    }

    public ResultActions put(String path, String token, String json) throws Exception {
        return perform(MockMvcRequestBuilders.put(path), token, json);
    }

    public ResultActions delete(String path, String token) throws Exception {
        return perform(MockMvcRequestBuilders.delete(path), token, null);
    }

    /** Reads one value from a response body, e.g. {@code "$.id"}. */
    public static String read(ResultActions result, String path) throws Exception {
        Object value = JsonPath.read(result.andReturn().getResponse().getContentAsString(), path);
        return value == null ? null : value.toString();
    }

    public ResultActions refresh(String refreshToken) throws Exception {
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders.post("/api/auth/refresh");
        if (refreshToken != null) {
            request.cookie(new Cookie("refresh_token", refreshToken));
        }
        return mvc.perform(request);
    }

    public ResultActions logout(String refreshToken) throws Exception {
        return mvc.perform(
                MockMvcRequestBuilders.post("/api/auth/logout").cookie(new Cookie("refresh_token", refreshToken)));
    }

    public static Session session(MockHttpServletResponse response) throws Exception {
        String body = response.getContentAsString();
        Cookie cookie = response.getCookie("refresh_token");
        return new Session(JsonPath.read(body, "$.accessToken"), cookie == null ? null : cookie.getValue(), body);
    }

    private ResultActions perform(MockHttpServletRequestBuilder request, String token, String json) throws Exception {
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mvc.perform(request);
    }
}
