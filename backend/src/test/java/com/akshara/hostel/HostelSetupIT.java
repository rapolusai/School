package com.akshara.hostel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.akshara.support.IntegrationTest;
import com.akshara.support.TestApi;
import com.akshara.support.TestApi.School;
import com.akshara.support.TestApi.Session;

/** Hostels, blocks, rooms and beds; who may set them up and who sees which hostel. */
class HostelSetupIT extends IntegrationTest {

    @Test
    void adminSetsUpAHostelAndEveryoneSeesWhatTheyMay() throws Exception {
        HostelSchool s = new HostelSchool(api);
        // The admin and principal see both hostels; each warden only their own; a teacher nothing.
        api.get("/api/hostel/hostels", s.admin.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("Boys Hostel"))
                .andExpect(jsonPath("$[0].wardenName").value("Wasim Warden"))
                .andExpect(jsonPath("$[0].blocks").value(1))
                .andExpect(jsonPath("$[0].rooms").value(1))
                .andExpect(jsonPath("$[0].beds").value(2))
                .andExpect(jsonPath("$[0].occupied").value(0))
                .andExpect(jsonPath("$[0].canManage").value(true))
                .andExpect(jsonPath("$[0].canWarden").value(true));
        api.get("/api/hostel/hostels", s.principal.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].canManage").value(false))
                .andExpect(jsonPath("$[0].canWarden").value(false));
        api.get("/api/hostel/hostels", s.warden.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(s.boys))
                .andExpect(jsonPath("$[0].canManage").value(false))
                .andExpect(jsonPath("$[0].canWarden").value(true));
        api.get("/api/hostel/hostels", s.teacher.accessToken()).andExpect(status().isForbidden());
        api.get("/api/hostel/hostels/" + s.girls, s.warden.accessToken()).andExpect(status().isNotFound());
        api.get("/api/hostel/hostels/" + s.boys, s.warden.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hostel.name").value("Boys Hostel"))
                .andExpect(jsonPath("$.blocks[0].name").value("Block A"))
                .andExpect(jsonPath("$.rooms[0].number").value("101"))
                .andExpect(jsonPath("$.rooms[0].blockName").value("Block A"))
                .andExpect(jsonPath("$.rooms[0].beds").value(2))
                .andExpect(jsonPath("$.rooms[0].monthlyFeePaise").value(250000));
        // Only hostel.manage writes.
        api.post("/api/hostel/hostels", s.warden.accessToken(), """
                {"name":"Staff Quarters","type":"STAFF"}""").andExpect(status().isForbidden());
        api.post("/api/hostel/hostels", s.principal.accessToken(), """
                {"name":"Staff Quarters","type":"STAFF"}""").andExpect(status().isForbidden());
        // Duplicates and bad references.
        api.post("/api/hostel/hostels", s.admin.accessToken(), """
                {"name":"boys hostel","type":"BOYS"}""").andExpect(status().isConflict());
        api.post("/api/hostel/hostels", s.admin.accessToken(), """
                {"name":"Annex","type":"BOYS","wardenUserId":"%s"}""".formatted(UUID.randomUUID()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.wardenUserId").exists());
        api.post("/api/hostel/hostels", s.admin.accessToken(), """
                {"name":"","type":"BOYS"}""").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name").exists());
        api.post("/api/hostel/hostels/" + s.boys + "/blocks", s.admin.accessToken(), """
                {"name":"block a"}""").andExpect(status().isConflict());
        api.post("/api/hostel/hostels/" + s.boys + "/blocks/" + s.blockA + "/rooms", s.admin.accessToken(), """
                {"number":"101","capacity":2,"type":"DOUBLE"}""").andExpect(status().isConflict());
        api.post("/api/hostel/hostels/" + s.boys + "/blocks/" + s.blockA + "/rooms", s.admin.accessToken(), """
                {"floor":5,"number":"501","capacity":2,"type":"DOUBLE"}""").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.floor").exists());
        // Beds: no more than the capacity, labels unique in the room.
        api.post("/api/hostel/hostels/" + s.boys + "/rooms/" + s.room101 + "/beds", s.admin.accessToken(), """
                {"labels":["C"]}""").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.labels").exists());
        String room102 = s.room(s.boys, s.blockA, "102", 3, 200000);
        api.post("/api/hostel/hostels/" + s.boys + "/rooms/" + room102 + "/beds", s.admin.accessToken(), """
                {"labels":["A","a"]}""").andExpect(status().isBadRequest());
        api.post("/api/hostel/hostels/" + s.boys + "/rooms/" + room102 + "/beds", s.admin.accessToken(), """
                {"labels":["A"]}""").andExpect(status().isCreated());
        api.post("/api/hostel/hostels/" + s.boys + "/rooms/" + room102 + "/beds", s.admin.accessToken(), """
                {"labels":["a"]}""").andExpect(status().isConflict());
        // Room and block ids belong to the hostel in the path.
        api.put("/api/hostel/hostels/" + s.girls + "/rooms/" + room102, s.admin.accessToken(), """
                {"number":"102","capacity":3,"type":"TRIPLE"}""").andExpect(status().isNotFound());
        api.put("/api/hostel/hostels/" + s.boys + "/rooms/" + room102, s.admin.accessToken(), """
                {"floor":1,"number":"102","capacity":4,"type":"DORMITORY","monthlyFeePaise":180000}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capacity").value(4))
                .andExpect(jsonPath("$.type").value("DORMITORY"));
        api.put("/api/hostel/hostels/" + s.boys, s.admin.accessToken(), """
                {"name":"Boys Hostel","type":"BOYS","wardenUserId":"%s","address":"Gate 2","active":true}"""
                .formatted(s.warden2Id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.wardenName").value("Wendy Warden"));
        // Wasim is no longer the warden: the hostel left his list.
        api.get("/api/hostel/hostels", s.warden.accessToken()).andExpect(jsonPath("$.length()").value(0));
        api.get("/api/hostel/hostels/" + s.boys, s.warden.accessToken()).andExpect(status().isNotFound());
        // The board shows rooms as cards with their free beds.
        api.get("/api/hostel/hostels/" + s.boys + "/occupancy", s.warden2.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.beds").value(3))
                .andExpect(jsonPath("$.free").value(3))
                .andExpect(jsonPath("$.occupancyPercent").value(0))
                .andExpect(jsonPath("$.rooms.length()").value(2))
                .andExpect(jsonPath("$.rooms[0].number").value("101"))
                .andExpect(jsonPath("$.rooms[0].freeBeds").value(2))
                .andExpect(jsonPath("$.rooms[0].beds[0].label").value("A"))
                .andExpect(jsonPath("$.rooms[0].beds[0].studentName").doesNotExist());
        // Removing: an empty room goes, a block with rooms stays.
        api.delete("/api/hostel/hostels/" + s.boys + "/blocks/" + s.blockA, s.admin.accessToken())
                .andExpect(status().isConflict());
        api.delete("/api/hostel/hostels/" + s.boys + "/rooms/" + room102, s.admin.accessToken())
                .andExpect(status().isNoContent());
        api.get("/api/hostel/hostels/" + s.boys, s.admin.accessToken())
                .andExpect(jsonPath("$.rooms.length()").value(1));
        api.get("/api/audit-events?limit=50", s.admin.accessToken())
                .andExpect(jsonPath("$[*].action", hasItems("hostel.created", "hostel.updated",
                        "hostel_block.created", "hostel_room.created", "hostel_room.updated", "hostel_room.removed",
                        "hostel_bed.added")));
        api.get("/api/hostel/staff", s.admin.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == 'Wasim Warden')]").exists());
        api.get("/api/hostel/staff", s.warden.accessToken()).andExpect(status().isForbidden());
    }

    @Test
    void anotherSchoolSeesNothing() throws Exception {
        HostelSchool s = new HostelSchool(api);
        School other = api.signup();
        Session otherAdmin = api.login(other);
        api.get("/api/hostel/hostels", otherAdmin.accessToken()).andExpect(jsonPath("$.length()").value(0));
        api.get("/api/hostel/hostels/" + s.boys, otherAdmin.accessToken()).andExpect(status().isNotFound());
        api.get("/api/hostel/hostels/" + s.boys + "/occupancy", otherAdmin.accessToken())
                .andExpect(status().isNotFound());
        api.post("/api/hostel/hostels/" + s.boys + "/blocks", otherAdmin.accessToken(), """
                {"name":"Block Z"}""").andExpect(status().isNotFound());
        api.post("/api/hostel/allotments", otherAdmin.accessToken(), """
                {"studentId":"%s","bedId":"%s","fromDate":"%s"}""".formatted(s.asha, s.bedA, HostelSchool.TODAY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.studentId").exists());
        assertThat(api.get("/api/hostel/reports/occupancy", otherAdmin.accessToken())
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .doesNotContain("Boys Hostel");
        // The WARDEN role exists in the new school too, with the warden's permission.
        api.get("/api/roles", otherAdmin.accessToken())
                .andExpect(jsonPath("$[?(@.code == 'WARDEN')].permissions[*]", hasItems("hostel.warden")));
    }
}
