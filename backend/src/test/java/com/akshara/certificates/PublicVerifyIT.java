package com.akshara.certificates;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.akshara.support.IntegrationTest;
import com.akshara.support.TestApi;

/** The public verification page and the family app's side of certificates. */
class PublicVerifyIT extends IntegrationTest {

    @Test
    void anyoneWithTheTokenSeesOnlyTheLimitedFields() throws Exception {
        CertificatesSchool s = new CertificatesSchool(api);
        api.put("/api/certificates/settings", s.office.accessToken(), """
                {"headerName":"Akshara Vidyalaya","signatoryName":"Prem","signatoryDesignation":"Principal",
                 "tcDuesCheck":true,"cardValidMonths":12,"lostCardFeePaise":0}""").andExpect(status().isOk());
        String id = s.prepare("BONAFIDE", s.asha, "Passport");
        s.sign(id);
        String token = TestApi.read(api.get("/api/certificates/" + id, s.office.accessToken()), "$.verifyToken");
        String body = verify(token, randomIp())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schoolName").value("Akshara Vidyalaya"))
                .andExpect(jsonPath("$.type").value("BONAFIDE"))
                .andExpect(jsonPath("$.serialNo").value("BC/2026-27/0001"))
                .andExpect(jsonPath("$.firstName").value("Asha"))
                .andExpect(jsonPath("$.issuedOn").isString())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.status").value("ISSUED"))
                .andReturn().getResponse().getContentAsString();
        // Nothing more about the student: no surname, admission number, class, parents or body.
        assertThat(body).doesNotContain("Rao").doesNotContain("CRT-1").doesNotContain("Class 5")
                .doesNotContain("Lata").doesNotContain("body").doesNotContain("fields");
        // Cancelled (here by a correction) shows as not valid; the token stays known.
        api.post("/api/certificates/" + id + "/correct", s.office.accessToken(), """
                {"reason":"Typo"}""").andExpect(status().isCreated());
        verify(token, randomIp())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        // Unknown, malformed and unsigned certificates' tokens are all 404.
        verify("AAAAAAAAAAAAAAAAAAAAAAAA", randomIp()).andExpect(status().isNotFound());
        verify("not-a-token", randomIp()).andExpect(status().isNotFound());
        verify(token.toLowerCase() + "x", randomIp()).andExpect(status().isNotFound());
    }

    @Test
    void verificationIsRateLimitedPerIp() throws Exception {
        CertificatesSchool s = new CertificatesSchool(api);
        String token = s.issued(s.asha);
        String ip = randomIp();
        for (int i = 0; i < 60; i++) {
            verify(token, ip).andExpect(status().isOk());
        }
        verify(token, ip).andExpect(status().isTooManyRequests());
        verify(token, randomIp()).andExpect(status().isOk());
    }

    @Test
    void parentsRequestCertificatesForTheirOwnChildrenAndOpenIssuedOnes() throws Exception {
        CertificatesSchool s = new CertificatesSchool(api);
        api.get("/api/me/certificates", s.parent.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.children", hasSize(1)))
                .andExpect(jsonPath("$.children[0].name").value("Asha Rao"))
                .andExpect(jsonPath("$.children[0].certificates", hasSize(0)))
                .andExpect(jsonPath("$.children[0].idCard").doesNotExist())
                .andExpect(jsonPath("$.requestableTypes[0]").value("BONAFIDE"))
                .andExpect(jsonPath("$.requestableTypes[1]").value("FEE"));
        // Only a bonafide or fee certificate, only for their own child, with a purpose.
        api.post("/api/me/certificates/requests", s.parent.accessToken(), """
                {"studentId":"%s","type":"TRANSFER","purpose":"Leaving"}""".formatted(s.asha))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.type").exists());
        api.post("/api/me/certificates/requests", s.parent.accessToken(), """
                {"studentId":"%s","type":"BONAFIDE","purpose":"Passport"}""".formatted(s.bala))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.studentId").exists());
        api.post("/api/me/certificates/requests", s.parent.accessToken(), """
                {"studentId":"%s","type":"BONAFIDE","purpose":""}""".formatted(s.asha))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.purpose").exists());
        String id = TestApi.read(api.post("/api/me/certificates/requests", s.parent.accessToken(), """
                {"studentId":"%s","type":"BONAFIDE","purpose":"Passport application"}""".formatted(s.asha))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REQUESTED"))
                .andExpect(jsonPath("$.requestedVia").value("FAMILY"))
                .andExpect(jsonPath("$.fields.student").value("Asha Rao")), "$.id");
        api.get("/api/me/certificates", s.parent.accessToken())
                .andExpect(jsonPath("$.children[0].certificates[0].status").value("REQUESTED"));
        // The office sees it among the pending ones, cannot sign a bare request, prepares it, the principal signs.
        api.get("/api/certificates?status=REQUESTED", s.office.accessToken()).andExpect(jsonPath("$.total").value(1));
        api.post("/api/certificates/" + id + "/sign", s.principal.accessToken(), "").andExpect(status().isConflict());
        api.put("/api/certificates/" + id, s.office.accessToken(), """
                {"title":"Bonafide Certificate","purpose":"Passport application","fields":{"student":"Asha Rao",
                 "class":"Class 5 A","admissionNo":"CRT-1"}}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PREPARED"));
        // Before it is signed the parent sees it, but without a serial number or token.
        api.get("/api/me/certificates/" + id, s.parent.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serialNo").doesNotExist());
        s.sign(id);
        api.get("/api/me/certificates/" + id, s.parent.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ISSUED"))
                .andExpect(jsonPath("$.serialNo").value("BC/2026-27/0001"))
                .andExpect(jsonPath("$.body").isString())
                .andExpect(jsonPath("$.verifyToken").isString());
        // Another child's certificate, and the staff screens, are not the parent's.
        String balas = s.prepare("BONAFIDE", s.bala, "Bus");
        api.get("/api/me/certificates/" + balas, s.parent.accessToken()).andExpect(status().isNotFound());
        api.get("/api/certificates/" + id, s.parent.accessToken()).andExpect(status().isForbidden());
        api.get("/api/certificates", s.parent.accessToken()).andExpect(status().isForbidden());
        // A teacher without children has an empty family view.
        api.get("/api/me/certificates", s.teacher.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.children", hasSize(0)));
    }

    private ResultActions verify(String token, String from) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get("/api/public/verify/" + token).with(r -> {
            r.setRemoteAddr(from);
            return r;
        }));
    }

    private static String randomIp() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        return "10." + r.nextInt(256) + "." + r.nextInt(256) + "." + (1 + r.nextInt(254));
    }
}
