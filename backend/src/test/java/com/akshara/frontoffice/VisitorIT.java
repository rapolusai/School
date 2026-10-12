package com.akshara.frontoffice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.akshara.support.IntegrationTest;
import com.akshara.support.TestApi;

/** The visitor log: check-in and out, badges, photos, expected visitors, the blocklist, the register and reports. */
class VisitorIT extends IntegrationTest {

    @Test
    void theGateChecksVisitorsInAndOutWithBadgesAndPhotos() throws Exception {
        FrontOfficeSchool s = new FrontOfficeSchool(api);
        String token = s.security.accessToken();
        String visitor = TestApi.read(api.post("/api/frontoffice/visitors", token, """
                {"name":"Prakash Nair","phone":"98765 11001","purpose":"MEETING","purposeNote":"About admission",
                 "toMeetUserId":"%s","peopleCount":2,"idType":"DRIVING_LICENCE"}""".formatted(s.teacherId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("IN"))
                .andExpect(jsonPath("$.badgeNo").value("V-01"))
                .andExpect(jsonPath("$.toMeetName").value("Tara Teacher"))
                .andExpect(jsonPath("$.phoneMasked").value("98765•••01"))
                .andExpect(jsonPath("$.phone").value("9876511001"))
                .andExpect(jsonPath("$.checkedInAt").exists())
                .andExpect(jsonPath("$.checkedInByName").value("Sam Security")), "$.id");
        // Lists mask the phone; the detail (for calling back) carries it.
        String day = api.get("/api/frontoffice/visitors", token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inside").value(1))
                .andExpect(jsonPath("$.visitors[0].id").value(visitor))
                .andExpect(jsonPath("$.visitors[0].phoneMasked").value("98765•••01"))
                .andReturn().getResponse().getContentAsString();
        assertThat(day).doesNotContain("9876511001");
        api.get("/api/frontoffice/visitors/" + visitor, token).andExpect(jsonPath("$.phone").value("9876511001"));
        // Validation mirrors the form.
        api.post("/api/frontoffice/visitors", token, """
                {"name":"","purpose":"MEETING"}""").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name").exists());
        api.post("/api/frontoffice/visitors", token, """
                {"name":"X","phone":"12","purpose":"MEETING"}""").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.phone").exists());
        api.post("/api/frontoffice/visitors", token, """
                {"name":"X","purpose":"MEETING","toMeetUserId":"%s"}""".formatted(UUID.randomUUID()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.toMeetUserId").exists());
        api.post("/api/frontoffice/visitors", token, """
                {"name":"X","purpose":"MEETING","peopleCount":0}""").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.peopleCount").exists());
        // A photo from the phone camera, replaced on a second upload; staff with front office rights see it.
        String photo = TestApi.read(mvc.perform(MockMvcRequestBuilders
                .multipart("/api/frontoffice/visitors/" + visitor + "/photo")
                .file(new MockMultipartFile("file", "visitor.png", "image/png", GatePassIT.PNG))
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()), "$.photoFileId");
        mvc.perform(MockMvcRequestBuilders.multipart("/api/frontoffice/visitors/" + visitor + "/photo")
                .file(new MockMultipartFile("file", "notes.txt", "text/plain", "hello".getBytes()))
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.file").exists());
        api.get("/api/files/" + photo, s.office.accessToken()).andExpect(status().isOk());
        api.get("/api/files/" + photo, s.teacher.accessToken()).andExpect(status().isNotFound());
        api.get("/api/files/" + photo, s.parent.accessToken()).andExpect(status().isNotFound());
        // Second visitor gets the next badge; check-out closes the visit once.
        String second = s.visitor(s.office, "Sunita Deshpande", "9876511002", "VENDOR");
        api.get("/api/frontoffice/visitors/" + second, token).andExpect(jsonPath("$.badgeNo").value("V-02"));
        api.post("/api/frontoffice/visitors/" + visitor + "/check-in", token, null).andExpect(status().isConflict());
        api.post("/api/frontoffice/visitors/" + visitor + "/check-out", token, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OUT"))
                .andExpect(jsonPath("$.checkedOutAt").exists())
                .andExpect(jsonPath("$.checkedOutByName").value("Sam Security"));
        api.post("/api/frontoffice/visitors/" + visitor + "/check-out", token, null).andExpect(status().isConflict());
        api.get("/api/frontoffice/visitors", token).andExpect(jsonPath("$.inside").value(1));
        api.get("/api/dashboard", s.office.accessToken())
                .andExpect(jsonPath("$.frontOffice.visitorsInside").value(1))
                .andExpect(jsonPath("$.frontOffice.visitorsToday").value(2));
        // The office edits details (the gate does not), and the daily register prints everyone.
        api.put("/api/frontoffice/visitors/" + second, s.office.accessToken(), """
                {"name":"Sunita Deshpande","phone":"9876511002","purpose":"DELIVERY","peopleCount":1,
                 "idType":"NONE","toMeetName":"Accounts"}""")
                .andExpect(status().isOk()).andExpect(jsonPath("$.purpose").value("DELIVERY"))
                .andExpect(jsonPath("$.toMeetName").value("Accounts"));
        api.put("/api/frontoffice/visitors/" + second, token, """
                {"name":"S","purpose":"DELIVERY"}""").andExpect(status().isForbidden());
        api.get("/api/frontoffice/visitors/register?date=" + FrontOfficeText.today(), s.office.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", contains("Prakash Nair", "Sunita Deshpande")));
        api.get("/api/frontoffice/visitors/register?date=" + FrontOfficeText.today(), token)
                .andExpect(status().isForbidden());
        api.get("/api/frontoffice/staff", token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == 'Tara Teacher')]").exists());
        api.get("/api/audit-events?limit=50", s.admin.accessToken())
                .andExpect(jsonPath("$[*].action", hasItems("visitor.checked_in", "visitor.photo_added",
                        "visitor.checked_out", "visitor.updated")));
    }

    @Test
    void expectedVisitorsAndTheBlocklist() throws Exception {
        FrontOfficeSchool s = new FrontOfficeSchool(api);
        String office = s.office.accessToken();
        LocalDate today = FrontOfficeText.today();
        api.post("/api/frontoffice/visitors", office, """
                {"name":"Dr Meera Joshi","purpose":"OFFICIAL","expectedOn":"%s"}""".formatted(today.minusDays(1)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.expectedOn").exists());
        String expected = TestApi.read(api.post("/api/frontoffice/visitors", office, """
                {"name":"Dr Meera Joshi","phone":"9876511005","purpose":"OFFICIAL","peopleCount":3,
                 "expectedOn":"%s"}""".formatted(today))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("EXPECTED"))
                .andExpect(jsonPath("$.badgeNo").doesNotExist()), "$.id");
        api.get("/api/frontoffice/visitors", s.security.accessToken())
                .andExpect(jsonPath("$.expected[0].id").value(expected))
                .andExpect(jsonPath("$.visitors.length()").value(0));
        api.get("/api/frontoffice/gate", s.security.accessToken())
                .andExpect(jsonPath("$.expected[0].name").value("Dr Meera Joshi"));
        api.post("/api/frontoffice/visitors/" + expected + "/check-in", s.security.accessToken(), """
                {"badgeNo":"GUEST-1"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN"))
                .andExpect(jsonPath("$.badgeNo").value("GUEST-1"));
        // The blocklist refuses a match by phone, or by name when there is no phone; the office may override.
        String block = TestApi.read(api.post("/api/frontoffice/blocklist", office, """
                {"name":"Unknown Salesman","phone":"9800000099","reason":"Kept selling insurance to staff."}""")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.phoneMasked").value("98000•••99")), "$.id");
        api.post("/api/frontoffice/blocklist", office, """
                {"name":"No Reason","reason":""}""").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.reason").exists());
        api.post("/api/frontoffice/blocklist", office, """
                {"name":"Rowdy Person","reason":"Shouted at the clerk."}""").andExpect(status().isCreated());
        api.post("/api/frontoffice/visitors", s.security.accessToken(), """
                {"name":"Someone Else","phone":"9800000099","purpose":"VENDOR"}""")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(VisitorService.BLOCKED_TYPE));
        api.post("/api/frontoffice/visitors", s.security.accessToken(), """
                {"name":"rowdy  person","purpose":"VENDOR"}""")
                .andExpect(status().isConflict());
        // The guard cannot override; the office can, and it is audited.
        api.post("/api/frontoffice/visitors", s.security.accessToken(), """
                {"name":"Someone Else","phone":"9800000099","purpose":"VENDOR","override":true}""")
                .andExpect(status().isConflict());
        String overridden = TestApi.read(api.post("/api/frontoffice/visitors", office, """
                {"name":"Someone Else","phone":"9800000099","purpose":"VENDOR","override":true}""")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.blocked.reason").value("Kept selling insurance to staff.")), "$.id");
        assertThat(overridden).isNotBlank();
        api.get("/api/frontoffice/blocklist", s.security.accessToken())
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        api.post("/api/frontoffice/blocklist", s.security.accessToken(), """
                {"name":"X","reason":"y"}""").andExpect(status().isForbidden());
        api.put("/api/frontoffice/blocklist/" + block, office, """
                {"name":"Unknown Salesman","phone":"9800000099","reason":"Updated reason."}""")
                .andExpect(status().isOk()).andExpect(jsonPath("$.reason").value("Updated reason."));
        api.delete("/api/frontoffice/blocklist/" + block, office).andExpect(status().isNoContent());
        api.delete("/api/frontoffice/blocklist/" + block, office).andExpect(status().isNotFound());
        api.post("/api/frontoffice/visitors", s.security.accessToken(), """
                {"name":"Someone Else","phone":"9800000099","purpose":"VENDOR"}""").andExpect(status().isCreated());
        api.get("/api/audit-events?limit=50", s.admin.accessToken())
                .andExpect(jsonPath("$[*].action", hasItems("visitor.expected", "visitor_block.added",
                        "visitor_block.updated", "visitor_block.removed")));
        // The visitors report counts by day and purpose; its CSV starts with a BOM and masks phones.
        api.get("/api/frontoffice/reports/visitors?from=" + today + "&to=" + today, s.admin.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.byDay[0].count").value(3))
                .andExpect(jsonPath("$.byPurpose[?(@.name == 'VENDOR')].count").value(contains(2)));
        byte[] csv = api.get("/api/frontoffice/reports/visitors.csv?from=" + today + "&to=" + today,
                s.admin.accessToken())
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString(".csv")))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(csv[0] & 0xFF).isEqualTo(0xEF);
        String text = new String(csv, StandardCharsets.UTF_8);
        assertThat(text).contains("Dr Meera Joshi").contains("GUEST-1").doesNotContain("9800000099");
        api.get("/api/frontoffice/reports/visitors?from=2020-01-01&to=2026-12-31", s.admin.accessToken())
                .andExpect(status().isBadRequest());
        api.get("/api/frontoffice/reports/visitors", s.teacher.accessToken()).andExpect(status().isForbidden());
        api.get("/api/frontoffice/reports/visitors", s.security.accessToken()).andExpect(status().isForbidden());
    }

    @Test
    void anotherSchoolSeesNoVisitorsAndTheRolesAreKept() throws Exception {
        FrontOfficeSchool s = new FrontOfficeSchool(api);
        String visitor = s.visitor(s.security, "Prakash Nair", "9876511001", "MEETING");
        String teacher = s.teacher.accessToken();
        api.get("/api/frontoffice/visitors", teacher).andExpect(status().isForbidden());
        api.get("/api/frontoffice/visitors/" + visitor, teacher).andExpect(status().isForbidden());
        api.get("/api/frontoffice/staff", teacher).andExpect(status().isForbidden());
        api.get("/api/frontoffice/blocklist", teacher).andExpect(status().isForbidden());
        api.post("/api/frontoffice/visitors", teacher, "{\"name\":\"X\",\"purpose\":\"MEETING\"}")
                .andExpect(status().isForbidden());
        api.post("/api/frontoffice/visitors/" + visitor + "/check-out", teacher, null)
                .andExpect(status().isForbidden());
        api.get("/api/frontoffice/visitors", s.parent.accessToken()).andExpect(status().isForbidden());
        mvc.perform(MockMvcRequestBuilders.get("/api/frontoffice/visitors")).andExpect(status().isUnauthorized());
        FrontOfficeSchool other = new FrontOfficeSchool(api);
        api.get("/api/frontoffice/visitors/" + visitor, other.admin.accessToken()).andExpect(status().isNotFound());
        api.post("/api/frontoffice/visitors/" + visitor + "/check-out", other.security.accessToken(), null)
                .andExpect(status().isNotFound());
        api.put("/api/frontoffice/visitors/" + visitor, other.office.accessToken(), """
                {"name":"Hacked","purpose":"MEETING"}""").andExpect(status().isNotFound());
        api.get("/api/frontoffice/visitors", other.security.accessToken())
                .andExpect(jsonPath("$.visitors.length()").value(0));
        api.get("/api/frontoffice/reports/visitors", other.admin.accessToken()).andExpect(jsonPath("$.total").value(0));
        // The security role exists in every school with only the gate permission among the front office ones.
        api.get("/api/frontoffice/visitors/" + visitor, s.security.accessToken()).andExpect(status().isOk());
        api.get("/api/frontoffice/calls", s.security.accessToken()).andExpect(status().isForbidden());
        api.get("/api/frontoffice/courier", s.security.accessToken()).andExpect(status().isForbidden());
    }
}
