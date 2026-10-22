package com.akshara.hostel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.YearMonth;

import org.junit.jupiter.api.Test;

import com.akshara.support.IntegrationTest;
import com.akshara.support.TestApi;

/** The night roll call with its alerts, the mess, complaints and the reports. */
class RollCallIT extends IntegrationTest {

    @Test
    void wardenTakesTheRollCallAndAnAbsenteeRaisesAnAlertAndAMessage() throws Exception {
        HostelSchool s = new HostelSchool(api);
        s.allot(s.asha, s.bedA);
        s.allot(s.bala, s.bedB);
        // An approved home leave covering tonight suggests ON_LEAVE.
        String leave = TestApi.read(api.post("/api/hostel/passes", s.warden.accessToken(), """
                {"studentId":"%s","kind":"HOME_LEAVE","fromDate":"%s","toDate":"%s","reason":"Wedding"}"""
                .formatted(s.bala, HostelSchool.TODAY, HostelSchool.TODAY.plusDays(2)))
                .andExpect(status().isCreated()), "$.id");
        api.post("/api/hostel/passes/" + leave + "/approve", s.warden.accessToken(), null).andExpect(status().isOk());
        api.get("/api/hostel/hostels/" + s.boys + "/roll-call", s.warden.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.night").value(HostelSchool.TODAY.toString()))
                .andExpect(jsonPath("$.taken").value(false))
                .andExpect(jsonPath("$.canMark").value(true))
                .andExpect(jsonPath("$.rows.length()").value(2))
                .andExpect(jsonPath("$.rows[0].studentName").value("Asha Rao"))
                .andExpect(jsonPath("$.rows[0].roomNumber").value("101"))
                .andExpect(jsonPath("$.rows[0].suggestedOnLeave").value(false))
                .andExpect(jsonPath("$.rows[1].suggestedOnLeave").value(true))
                .andExpect(jsonPath("$.rows[1].passId").value(leave));
        api.get("/api/hostel/hostels/" + s.boys + "/roll-call", s.warden2.accessToken())
                .andExpect(status().isNotFound());
        api.get("/api/hostel/hostels/" + s.boys + "/roll-call", s.principal.accessToken())
                .andExpect(status().isOk()).andExpect(jsonPath("$.canMark").value(false));
        // Every boarder must be marked; not for a future night; only by the warden or the admin.
        String partial = """
                {"entries":[{"studentId":"%s","status":"PRESENT"}]}""".formatted(s.asha);
        api.put("/api/hostel/hostels/" + s.boys + "/roll-call/" + HostelSchool.TODAY, s.warden.accessToken(), partial)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.entries").exists());
        String full = """
                {"entries":[{"studentId":"%s","status":"ABSENT","note":"Not in room at 10 pm"},
                            {"studentId":"%s","status":"ON_LEAVE"}]}""".formatted(s.asha, s.bala);
        api.put("/api/hostel/hostels/" + s.boys + "/roll-call/" + HostelSchool.TODAY.plusDays(1),
                s.warden.accessToken(), full).andExpect(status().isBadRequest());
        api.put("/api/hostel/hostels/" + s.boys + "/roll-call/" + HostelSchool.TODAY, s.principal.accessToken(), full)
                .andExpect(status().isForbidden());
        api.put("/api/hostel/hostels/" + s.boys + "/roll-call/" + HostelSchool.TODAY, s.warden2.accessToken(), full)
                .andExpect(status().isNotFound());
        api.put("/api/hostel/hostels/" + s.boys + "/roll-call/" + HostelSchool.TODAY, s.warden.accessToken(), full)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taken").value(true))
                .andExpect(jsonPath("$.takenByName").value("Wasim Warden"))
                .andExpect(jsonPath("$.present").value(0))
                .andExpect(jsonPath("$.onLeave").value(1))
                .andExpect(jsonPath("$.absent").value(1))
                .andExpect(jsonPath("$.rows[0].status").value("ABSENT"))
                .andExpect(jsonPath("$.rows[0].note").value("Not in room at 10 pm"));
        // The absentee raised an alert and a message to her mother.
        String alert = TestApi.read(api.get("/api/hostel/alerts", s.warden.accessToken())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].kind").value("ABSENT_AT_ROLL_CALL"))
                .andExpect(jsonPath("$[0].studentName").value("Asha Rao"))
                .andExpect(jsonPath("$[0].resolved").value(false)), "$[0].id");
        api.get("/api/hostel/alerts", s.warden2.accessToken()).andExpect(jsonPath("$.length()").value(0));
        String message = TestApi.read(api.get("/api/messages?relatedId=" + s.boys, s.admin.accessToken())
                .andExpect(jsonPath("$.total").value(1)), "$.items[0].id");
        api.get("/api/messages/" + message, s.admin.accessToken())
                .andExpect(jsonPath("$.templateKey").value("hostel.roll_call_absent"));
        // The parent sees last night's mark on the hostel card.
        api.get("/api/me/children/" + s.asha + "/hostel", s.parent.accessToken())
                .andExpect(jsonPath("$.lastRollCallNight").value(HostelSchool.TODAY.toString()))
                .andExpect(jsonPath("$.lastRollCallStatus").value("ABSENT"));
        // Correcting the night to PRESENT does not raise a second alert; the first is resolved by hand.
        api.put("/api/hostel/hostels/" + s.boys + "/roll-call/" + HostelSchool.TODAY, s.warden.accessToken(), """
                {"entries":[{"studentId":"%s","status":"PRESENT","note":"Was in the library"},
                            {"studentId":"%s","status":"ON_LEAVE"}]}""".formatted(s.asha, s.bala))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.present").value(1))
                .andExpect(jsonPath("$.absent").value(0));
        api.get("/api/messages?relatedId=" + s.boys, s.admin.accessToken()).andExpect(jsonPath("$.total").value(1));
        api.post("/api/hostel/alerts/" + alert + "/resolve", s.warden2.accessToken(), null)
                .andExpect(status().isNotFound());
        api.post("/api/hostel/alerts/" + alert + "/resolve", s.warden.accessToken(), """
                {"note":"Found in the library"}""").andExpect(status().isOk())
                .andExpect(jsonPath("$.resolved").value(true))
                .andExpect(jsonPath("$.resolvedByName").value("Wasim Warden"));
        api.post("/api/hostel/alerts/" + alert + "/resolve", s.warden.accessToken(), null)
                .andExpect(status().isConflict());
        api.get("/api/hostel/alerts", s.warden.accessToken()).andExpect(jsonPath("$.length()").value(0));
        // The register and the month's summary.
        api.get("/api/hostel/hostels/" + s.boys + "/roll-call/register", s.warden.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nights.length()").value(1))
                .andExpect(jsonPath("$.nights[0].present").value(1))
                .andExpect(jsonPath("$.nights[0].absentees.length()").value(0));
        api.get("/api/hostel/hostels/" + s.boys + "/roll-call/summary?month=" + YearMonth.from(HostelSchool.TODAY),
                s.admin.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nightsTaken").value(1))
                .andExpect(jsonPath("$.boarders.length()").value(2))
                .andExpect(jsonPath("$.boarders[0].studentName").value("Asha Rao"))
                .andExpect(jsonPath("$.boarders[0].present").value(1))
                .andExpect(jsonPath("$.boarders[1].onLeave").value(1));
        api.get("/api/hostel/hostels/" + s.boys + "/roll-call/summary?month=nope", s.admin.accessToken())
                .andExpect(status().isBadRequest());
        api.get("/api/hostel/reports/roll-call", s.admin.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nights").value(1))
                .andExpect(jsonPath("$.rows[0].hostelName").value("Boys Hostel"));
        api.get("/api/hostel/reports/roll-call", s.warden.accessToken()).andExpect(status().isForbidden());
        api.get("/api/audit-events?limit=50", s.admin.accessToken())
                .andExpect(jsonPath("$[*].action", hasItems("hostel_roll_call.taken", "hostel_alert.resolved")));
    }

    @Test
    void messMenuAndAttendanceAndComplaints() throws Exception {
        HostelSchool s = new HostelSchool(api);
        s.allot(s.asha, s.bedA);
        // The menu: the warden of the hostel writes it, the office reads it.
        String menu = """
                {"items":[{"dayOfWeek":1,"meal":"BREAKFAST","dish":"Idli, sambar","veg":true},
                          {"dayOfWeek":1,"meal":"DINNER","dish":"Chicken curry, rice","veg":false},
                          {"dayOfWeek":2,"meal":"LUNCH","dish":"Rajma chawal"}]}""";
        api.put("/api/hostel/hostels/" + s.boys + "/menu", s.warden2.accessToken(), menu)
                .andExpect(status().isNotFound());
        api.put("/api/hostel/hostels/" + s.boys + "/menu", s.principal.accessToken(), menu)
                .andExpect(status().isForbidden());
        api.put("/api/hostel/hostels/" + s.boys + "/menu", s.warden.accessToken(), menu)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[1].veg").value(false))
                .andExpect(jsonPath("$.items[2].veg").value(true))
                .andExpect(jsonPath("$.canEdit").value(true));
        // A blank dish clears the slot.
        api.put("/api/hostel/hostels/" + s.boys + "/menu", s.admin.accessToken(), """
                {"items":[{"dayOfWeek":1,"meal":"DINNER","dish":" "}]}""").andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2));
        api.get("/api/hostel/hostels/" + s.boys + "/menu", s.principal.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].dish").value("Idli, sambar"))
                .andExpect(jsonPath("$.canEdit").value(false));
        // Mess attendance for today.
        api.put("/api/hostel/hostels/" + s.boys + "/mess-attendance/" + HostelSchool.TODAY, s.warden.accessToken(), """
                {"counts":[{"meal":"BREAKFAST","headCount":1},{"meal":"LUNCH","headCount":1}]}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.boarders").value(1))
                .andExpect(jsonPath("$.counts.length()").value(2))
                .andExpect(jsonPath("$.counts[0].markedByName").value("Wasim Warden"));
        api.put("/api/hostel/hostels/" + s.boys + "/mess-attendance/" + HostelSchool.TODAY, s.warden.accessToken(), """
                {"counts":[{"meal":"BREAKFAST","headCount":0}]}""").andExpect(status().isOk())
                .andExpect(jsonPath("$.counts[0].headCount").value(0))
                .andExpect(jsonPath("$.counts.length()").value(2));
        api.put("/api/hostel/hostels/" + s.boys + "/mess-attendance/" + HostelSchool.TODAY.plusDays(1),
                s.warden.accessToken(), """
                {"counts":[{"meal":"BREAKFAST","headCount":1}]}""").andExpect(status().isBadRequest());
        api.get("/api/hostel/hostels/" + s.boys + "/mess-attendance?date=" + HostelSchool.TODAY,
                s.admin.accessToken()).andExpect(jsonPath("$.counts.length()").value(2));
        // Complaints about a room.
        api.post("/api/hostel/complaints", s.warden2.accessToken(), """
                {"roomId":"%s","category":"PLUMBING","description":"Tap leaks"}""".formatted(s.room101))
                .andExpect(status().isNotFound());
        api.post("/api/hostel/complaints", s.warden.accessToken(), """
                {"roomId":"%s","category":"PLUMBING","description":"Tap leaks"}""".formatted(s.asha))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.roomId").exists());
        String complaint = TestApi.read(api.post("/api/hostel/complaints", s.warden.accessToken(), """
                {"roomId":"%s","category":"PLUMBING","description":"Tap leaks"}""".formatted(s.room101))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.roomNumber").value("101"))
                .andExpect(jsonPath("$.raisedByName").value("Wasim Warden")), "$.id");
        api.get("/api/hostel/hostels/" + s.boys + "/occupancy", s.warden.accessToken())
                .andExpect(jsonPath("$.rooms[0].openComplaints").value(1));
        api.post("/api/hostel/complaints/" + complaint + "/status", s.warden.accessToken(), """
                {"status":"OPEN"}""").andExpect(status().isConflict());
        api.post("/api/hostel/complaints/" + complaint + "/status", s.warden.accessToken(), """
                {"status":"RESOLVED"}""").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.note").exists());
        api.post("/api/hostel/complaints/" + complaint + "/status", s.warden.accessToken(), """
                {"status":"IN_PROGRESS","note":"Plumber called"}""").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
        api.post("/api/hostel/complaints/" + complaint + "/status", s.admin.accessToken(), """
                {"status":"RESOLVED","note":"Washer replaced"}""").andExpect(status().isOk())
                .andExpect(jsonPath("$.resolvedAt").exists());
        api.get("/api/hostel/complaints?status=RESOLVED", s.principal.accessToken())
                .andExpect(jsonPath("$.length()").value(1));
        api.get("/api/hostel/complaints", s.warden2.accessToken()).andExpect(jsonPath("$.length()").value(0));
        api.get("/api/audit-events?limit=50", s.admin.accessToken())
                .andExpect(jsonPath("$[*].action", hasItems("hostel_menu.updated", "hostel_mess.marked",
                        "hostel_complaint.raised", "hostel_complaint.updated")));
    }

    @Test
    void reportsComeAsJsonAndCsvWithABom() throws Exception {
        HostelSchool s = new HostelSchool(api);
        s.allot(s.asha, s.bedA);
        String outing = s.requestOuting();
        api.post("/api/hostel/passes/" + outing + "/approve", s.warden.accessToken(), null)
                .andExpect(status().isOk());
        api.get("/api/hostel/reports/occupancy", s.admin.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.beds").value(2))
                .andExpect(jsonPath("$.occupied").value(1))
                .andExpect(jsonPath("$.occupancyPercent").value(50))
                .andExpect(jsonPath("$.hostels.length()").value(2))
                .andExpect(jsonPath("$.hostels[0].wardenName").value("Wasim Warden"))
                .andExpect(jsonPath("$.rows[0].studentName").value("Asha Rao"));
        String csv = api.get("/api/hostel/reports/occupancy.csv", s.principal.accessToken())
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Content-Disposition", org.hamcrest.Matchers.containsString("hostel-occupancy.csv")))
                .andReturn().getResponse().getContentAsString();
        assertThat(csv).startsWith("﻿Hostel,Type").contains("Boys Hostel,BOYS,Wasim Warden,1,2,1,1,50")
                .contains("Asha Rao,HO-1,Class 5 A").contains("98765•••01").doesNotContain("9876501001");
        String month = YearMonth.from(HostelSchool.TODAY.plusDays(1)).toString();
        api.get("/api/hostel/reports/outings?month=" + month, s.admin.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.outings").value(1))
                .andExpect(jsonPath("$.homeLeaves").value(0))
                .andExpect(jsonPath("$.rows[0].status").value("APPROVED"));
        assertThat(api.get("/api/hostel/reports/outings.csv?month=" + month, s.admin.accessToken())
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .startsWith("﻿Student,Class,Hostel,Kind").contains("Asha Rao,Class 5 A,Boys Hostel,OUTING");
        api.get("/api/hostel/reports/outings?month=2026-13", s.admin.accessToken()).andExpect(status().isBadRequest());
        api.get("/api/hostel/reports/fee-dues", s.admin.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.boarders").value(1))
                .andExpect(jsonPath("$.rows[0].studentName").value("Asha Rao"))
                .andExpect(jsonPath("$.rows[0].balancePaise").isNumber());
        assertThat(api.get("/api/hostel/reports/fee-dues.csv", s.admin.accessToken())
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .startsWith("﻿Student,Admission no.").contains("Asha Rao,HO-1");
        assertThat(api.get("/api/hostel/reports/roll-call.csv", s.admin.accessToken())
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .startsWith("﻿Night,Hostel");
        for (String path : new String[] {"occupancy", "roll-call", "outings", "fee-dues"}) {
            api.get("/api/hostel/reports/" + path, s.warden.accessToken()).andExpect(status().isForbidden());
            api.get("/api/hostel/reports/" + path, s.teacher.accessToken()).andExpect(status().isForbidden());
        }
    }
}
