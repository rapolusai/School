package com.akshara.support;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.akshara.support.TestApi.Session;
import com.jayway.jsonpath.JsonPath;

/** Builds fee setup and payments through the API, as a school's accountant would. */
public class FeeFixtures {

    /** The sandbox secret of application-test.yml. Test-only. */
    public static final String SANDBOX_SECRET = "test-only-sandbox-secret-not-for-real-use";

    private final TestApi api;
    private final MockMvc mvc;

    public FeeFixtures(TestApi api, MockMvc mvc) {
        this.api = api;
        this.mvc = mvc;
    }

    public record Heads(String tuition, String admission, String annual, String exam) {
    }

    /** Today in India, as the fee module counts days. */
    public static LocalDate today() {
        return LocalDate.now(ZoneId.of("Asia/Kolkata"));
    }

    /**
     * Four quarterly due dates around today: Q1 60 days ago, Q2 20 days ago, Q3 in 40 days and Q4 in 100 days, so
     * the first two are overdue and the others upcoming whenever the test runs.
     */
    public static List<LocalDate> quarters() {
        LocalDate today = today();
        return List.of(today.minusDays(60), today.minusDays(20), today.plusDays(40), today.plusDays(100));
    }

    public Heads defaultHeads(Session session) throws Exception {
        String body = api.post("/api/fees/heads/defaults", session.accessToken(), null)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new Heads(headId(body, "TUITION"), headId(body, "ADMISSION"), headId(body, "ANNUAL"),
                headId(body, "EXAM"));
    }

    private static String headId(String body, String kind) {
        List<String> ids = JsonPath.read(body, "$[?(@.kind == '" + kind + "')].id");
        return ids.getFirst();
    }

    /** A structure body with tuition and annual charges split evenly over the given due dates. */
    public static String structureJson(String yearId, String classId, Heads heads, long tuitionPaise,
            long annualPaise, List<LocalDate> dueDates) {
        String instalments = dueDates.stream().map(d -> "{\"dueDate\":\"" + d + "\"}")
                .collect(Collectors.joining(","));
        return """
                {"academicYearId":"%s","classId":"%s",
                 "heads":[{"headId":"%s","amountPaise":%d},{"headId":"%s","amountPaise":%d}],
                 "instalments":[%s]}""".formatted(yearId, classId, heads.tuition(), tuitionPaise, heads.annual(),
                annualPaise, instalments);
    }

    /**
     * A published structure for the class: ₹40,000 tuition and ₹6,000 annual charges in four quarters
     * ({@link #quarters()}), so each quarter is ₹10,000 tuition plus ₹1,500 annual. Returns the structure id.
     */
    public String publishedStructure(Session session, String yearId, String classId, Heads heads) throws Exception {
        String id = TestApi.read(api.post("/api/fees/structures", session.accessToken(),
                structureJson(yearId, classId, heads, 40_000_00, 6_000_00, quarters()))
                .andExpect(status().isCreated()), "$.structure.id");
        api.post("/api/fees/structures/" + id + "/publish", session.accessToken(), null)
                .andExpect(status().isOk());
        return id;
    }

    /** The ids of a student's instalments, oldest first. */
    public List<String> instalmentIds(Session session, String studentId) throws Exception {
        String body = api.get("/api/fees/students/" + studentId, session.accessToken())
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.instalments[*].instalmentId");
    }

    /** Takes a cash payment and returns the receipt id. */
    public String cash(Session session, String studentId, long amountPaise) throws Exception {
        return TestApi.read(api.post("/api/fees/students/" + studentId + "/payments", session.accessToken(), """
                {"amountPaise":%d,"mode":"CASH"}""".formatted(amountPaise)).andExpect(status().isCreated()), "$.id");
    }

    /** HMAC-SHA256 with the sandbox secret, as the sandbox gateway signs. */
    public static String sign(String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SANDBOX_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }

    /** A webhook body in Razorpay's shape. */
    public static String webhookJson(String eventId, String event, String orderId, String paymentId, long amount) {
        return """
                {"id":"%s","entity":"event","event":"%s","contains":["payment"],
                 "payload":{"payment":{"entity":{"id":"%s","entity":"payment","amount":%d,"currency":"INR",
                 "status":"captured","order_id":"%s"}}}}""".formatted(eventId, event, paymentId, amount, orderId);
    }

    /** Posts a webhook to the public endpoint with the given signature header (null for none). */
    public ResultActions webhook(String gateway, String payload, String signature) throws Exception {
        var request = MockMvcRequestBuilders.post("/api/public/payments/webhook/" + gateway)
                .contentType(MediaType.APPLICATION_JSON).content(payload);
        if (signature != null) {
            request.header("X-Sandbox-Signature", signature);
        }
        return mvc.perform(request);
    }
}
