package com.akshara.hostel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import com.akshara.support.IntegrationTest;
import com.akshara.support.TestApi;

/** Allotting, moving and vacating beds; the boarder list; the student's hostel standing. */
@RecordApplicationEvents
class AllotmentIT extends IntegrationTest {

    @Autowired
    ApplicationEvents events;

    @Test
    void aStudentWhoLeavesTheSchoolGivesUpTheirBed() throws Exception {
        HostelSchool s = new HostelSchool(api);
        s.allot(s.asha, s.bedA);
        api.post("/api/students/" + s.asha + "/leave", s.admin.accessToken(), """
                {"status":"TRANSFERRED","leftOn":"%s","reason":"Family moved"}""".formatted(HostelSchool.TODAY))
                .andExpect(status().isOk());
        api.get("/api/hostel/students/" + s.asha + "/boarding", s.admin.accessToken())
                .andExpect(jsonPath("$.boarder").value(false))
                .andExpect(jsonPath("$.history[0].status").value("VACATED"))
                .andExpect(jsonPath("$.history[0].toDate").value(HostelSchool.TODAY.toString()))
                .andExpect(jsonPath("$.history[0].vacateReason").value("Left the school (transferred)"));
        assertThat(events.stream(HostelVacated.class).filter(e -> e.studentId().toString().equals(s.asha)))
                .hasSize(1);
        // The bed is free for the next boarder.
        s.allot(s.bala, s.bedA);
    }

    @Test
    void allotMoveAndVacateKeepTheRoomHistoryAndPublishEvents() throws Exception {
        HostelSchool s = new HostelSchool(api);
        // Before: a day scholar.
        api.get("/api/hostel/students/" + s.asha + "/boarding", s.teacher.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.boarder").value(false))
                .andExpect(jsonPath("$.history.length()").value(0));
        api.get("/api/me/children/" + s.asha + "/hostel", s.parent.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.boarder").value(false));
        // Only hostel.manage allots; bad bodies answer 400 on the field.
        api.post("/api/hostel/allotments", s.warden.accessToken(), """
                {"studentId":"%s","bedId":"%s","fromDate":"%s"}""".formatted(s.asha, s.bedA, HostelSchool.TODAY))
                .andExpect(status().isForbidden());
        api.post("/api/hostel/allotments", s.admin.accessToken(), """
                {"studentId":"%s","bedId":"%s","fromDate":"%s"}""".formatted(s.asha, s.bedA,
                HostelSchool.TODAY.plusDays(200))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.fromDate").exists());
        api.post("/api/hostel/allotments", s.admin.accessToken(), """
                {"studentId":"%s","bedId":"%s","fromDate":"%s"}""".formatted(s.asha, s.asha, HostelSchool.TODAY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.bedId").exists());
        String ashaBed = s.allot(s.asha, s.bedA);
        api.post("/api/hostel/allotments", s.admin.accessToken(), """
                {"studentId":"%s","bedId":"%s","fromDate":"%s"}""".formatted(s.bala, s.bedA, HostelSchool.TODAY))
                .andExpect(status().isConflict());
        api.post("/api/hostel/allotments", s.admin.accessToken(), """
                {"studentId":"%s","bedId":"%s","fromDate":"%s"}""".formatted(s.asha, s.bedB, HostelSchool.TODAY))
                .andExpect(status().isConflict());
        s.allot(s.bala, s.bedB);
        assertThat(events.stream(HostelAllotted.class)).hasSize(2);
        assertThat(events.stream(HostelAllotted.class).filter(e -> e.monthlyFeePaise() == 250000
                && e.roomNumber().equals("101") && e.hostelName().equals("Boys Hostel"))).hasSize(2);
        // The board shows both beds taken; the boarder list has the class and the masked guardian phone.
        api.get("/api/hostel/hostels/" + s.boys + "/occupancy", s.warden.accessToken())
                .andExpect(jsonPath("$.occupied").value(2))
                .andExpect(jsonPath("$.occupancyPercent").value(100))
                .andExpect(jsonPath("$.rooms[0].freeBeds").value(0))
                .andExpect(jsonPath("$.rooms[0].beds[0].studentName").value("Asha Rao"))
                .andExpect(jsonPath("$.rooms[0].beds[0].className").value("Class 5 A"));
        String boarders = api.get("/api/hostel/boarders", s.warden.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].studentName").value("Asha Rao"))
                .andExpect(jsonPath("$[0].roomNumber").value("101"))
                .andExpect(jsonPath("$[0].bedLabel").value("A"))
                .andExpect(jsonPath("$[0].guardianName").value("Lata Rao"))
                .andExpect(jsonPath("$[0].guardianPhoneMasked").value("98765•••01"))
                .andReturn().getResponse().getContentAsString();
        assertThat(boarders).doesNotContain("9876501001");
        api.get("/api/hostel/boarders?hostelId=" + s.girls, s.warden.accessToken()).andExpect(status().isNotFound());
        api.get("/api/hostel/boarders", s.warden2.accessToken()).andExpect(jsonPath("$.length()").value(0));
        api.get("/api/hostel/boarders", s.teacher.accessToken()).andExpect(status().isForbidden());
        // A room change keeps the history; the new bed must be free.
        String room102 = s.room(s.boys, s.blockA, "102", 1, 300000);
        String bedC = TestApi.read(api.post("/api/hostel/hostels/" + s.boys + "/rooms/" + room102 + "/beds",
                s.admin.accessToken(), """
                {"labels":["A"]}""").andExpect(status().isCreated()), "$[0].id");
        api.post("/api/hostel/allotments/" + ashaBed + "/move", s.admin.accessToken(), """
                {"bedId":"%s"}""".formatted(s.bedB)).andExpect(status().isConflict());
        api.post("/api/hostel/allotments/" + ashaBed + "/move", s.admin.accessToken(), """
                {"bedId":"%s"}""".formatted(s.bedA)).andExpect(status().isBadRequest());
        String moved = TestApi.read(api.post("/api/hostel/allotments/" + ashaBed + "/move", s.admin.accessToken(),
                """
                {"bedId":"%s"}""".formatted(bedC)).andExpect(status().isOk())
                .andExpect(jsonPath("$.roomNumber").value("102"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.guardianConsent").value(true))
                .andExpect(jsonPath("$.monthlyFeePaise").value(300000)), "$.id");
        assertThat(events.stream(HostelVacated.class).filter(e -> e.reason().equals("ROOM_CHANGE"))).hasSize(1);
        assertThat(events.stream(HostelAllotted.class).filter(e -> e.previousAllotmentId() != null)).hasSize(1);
        api.get("/api/hostel/students/" + s.asha + "/boarding", s.principal.accessToken())
                .andExpect(jsonPath("$.boarder").value(true))
                .andExpect(jsonPath("$.current.id").value(moved))
                .andExpect(jsonPath("$.history.length()").value(2))
                .andExpect(jsonPath("$.history[1].status").value("VACATED"))
                .andExpect(jsonPath("$.history[1].vacateReason").value("ROOM_CHANGE"));
        api.post("/api/hostel/allotments/" + ashaBed + "/vacate", s.admin.accessToken(), """
                {"reason":"Left"}""").andExpect(status().isConflict());
        // Vacating frees the bed.
        api.post("/api/hostel/allotments/" + moved + "/vacate", s.admin.accessToken(), """
                {"reason":"","toDate":"%s"}""".formatted(HostelSchool.TODAY)).andExpect(status().isBadRequest());
        api.post("/api/hostel/allotments/" + moved + "/vacate", s.admin.accessToken(), """
                {"reason":"Family moved to Pune","toDate":"%s"}""".formatted(HostelSchool.TODAY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VACATED"))
                .andExpect(jsonPath("$.vacatedByName").exists());
        assertThat(events.stream(HostelVacated.class)).hasSize(2);
        api.get("/api/hostel/students/" + s.asha + "/boarding", s.admin.accessToken())
                .andExpect(jsonPath("$.boarder").value(false))
                .andExpect(jsonPath("$.history.length()").value(2));
        api.get("/api/hostel/hostels/" + s.boys + "/occupancy", s.admin.accessToken())
                .andExpect(jsonPath("$.occupied").value(1));
        // Beds and rooms with history cannot be removed.
        api.delete("/api/hostel/hostels/" + s.boys + "/beds/" + bedC, s.admin.accessToken())
                .andExpect(status().isConflict());
        api.delete("/api/hostel/hostels/" + s.boys + "/rooms/" + room102, s.admin.accessToken())
                .andExpect(status().isConflict());
        api.get("/api/audit-events?limit=50", s.admin.accessToken())
                .andExpect(jsonPath("$[*].action", hasItems("hostel_allotment.allotted", "hostel_allotment.moved",
                        "hostel_allotment.vacated")));
        assertThat(List.of(ashaBed, moved)).doesNotHaveDuplicates();
    }

    @Test
    void theStudentPageReadsBoardingAndStrangersGet404() throws Exception {
        HostelSchool s = new HostelSchool(api);
        s.allot(s.asha, s.bedA);
        api.get("/api/hostel/students/" + s.bala + "/boarding", s.warden.accessToken())
                .andExpect(status().isOk()).andExpect(jsonPath("$.boarder").value(false));
        api.get("/api/hostel/students/" + s.asha + "/boarding", s.warden2.accessToken())
                .andExpect(status().isOk()).andExpect(jsonPath("$.boarder").value(false));
        api.get("/api/hostel/students/" + s.asha + "/boarding", s.parent.accessToken())
                .andExpect(status().isForbidden());
        api.get("/api/hostel/students/" + s.asha + "/boarding", api.login(api.signup()).accessToken())
                .andExpect(status().isNotFound());
    }
}
