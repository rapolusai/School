package com.akshara.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.akshara.support.TestApi;
import com.akshara.support.TestApi.School;
import com.akshara.support.TestApi.Session;

/** Parent-teacher meetings through the API: setting up, booking, schedules, marking, reminders and the summary. */
class PtmIT extends ChatTestBase {

    /** 09:00 to 10:00 in 15-minute slots with a break at 09:30: slots at 09:00, 09:15 and 09:45. */
    String event(World w, LocalDate date, boolean subjectTeachers) throws Exception {
        return TestApi.read(api.post("/api/ptm/events", w.principal().accessToken(), """
                {"title":"Term 1 PTM","date":"%s","startsAt":"09:00","endsAt":"10:00","slotMinutes":15,
                 "breaks":[{"from":"09:30","to":"09:45"}],"mode":"IN_PERSON","location":"Room 12",
                 "sectionIds":["%s","%s"],"includeSubjectTeachers":%s,"cutoffHours":12}"""
                .formatted(date, w.sectionA(), w.sectionB(), subjectTeachers))
                .andExpect(status().isCreated()), "$.id");
    }

    /** The id of a teacher's slot at a time, from the leadership view. */
    String slot(World w, String event, String teacherId, String time) throws Exception {
        String ids = TestApi.read(api.get("/api/ptm/events/" + event, w.principal().accessToken()),
                "$.teachers[?(@.teacherId == '" + teacherId + "')].slots[?(@.startsAt == '" + time + ":00')].id");
        return ids.replaceAll("[\\[\\]\"]", "");
    }

    String book(Session parent, String slot, String student) throws Exception {
        return TestApi.read(api.post("/api/me/ptm/bookings", parent.accessToken(), """
                {"slotId":"%s","studentId":"%s"}""".formatted(slot, student)).andExpect(status().isCreated()), "$.id");
    }

    @Test
    void thePrincipalSetsUpAMeetingAndParentsBook() throws Exception {
        World w = world();
        // Checks on the form.
        api.post("/api/ptm/events", w.principal().accessToken(), """
                {"title":"PTM","date":"%s","startsAt":"10:00","endsAt":"09:00","slotMinutes":15,"mode":"IN_PERSON",
                 "sectionIds":["%s"]}""".formatted(today().plusDays(5), w.sectionA()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.endsAt").exists());
        api.post("/api/ptm/events", w.principal().accessToken(), """
                {"title":"PTM","date":"%s","startsAt":"09:00","endsAt":"10:00","slotMinutes":15,"mode":"IN_PERSON",
                 "sectionIds":["%s"]}""".formatted(today().minusDays(1), w.sectionA()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.date").exists());
        api.post("/api/ptm/events", w.principal().accessToken(), """
                {"title":"PTM","date":"%s","startsAt":"09:00","endsAt":"10:00","slotMinutes":15,"mode":"VIDEO",
                 "videoLink":"http://meet.example.test/x","sectionIds":["%s"]}""".formatted(today().plusDays(5),
                w.sectionA()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.videoLink").exists());
        api.post("/api/ptm/events", w.principal().accessToken(), """
                {"title":"PTM","date":"%s","startsAt":"09:00","endsAt":"10:00","slotMinutes":15,"mode":"IN_PERSON",
                 "breaks":[{"from":"08:00","to":"08:30"}],"sectionIds":["%s"]}""".formatted(today().plusDays(5),
                w.sectionA()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.breaks").exists());
        api.post("/api/ptm/events", w.principal().accessToken(), """
                {"title":"PTM","date":"%s","startsAt":"09:00","endsAt":"10:00","slotMinutes":15,"mode":"IN_PERSON",
                 "sectionIds":["%s"]}""".formatted(today().plusDays(5), UUID.randomUUID()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.sectionIds").exists());
        api.post("/api/ptm/events", w.ravi().accessToken(), """
                {"title":"PTM","date":"%s","startsAt":"09:00","endsAt":"10:00","slotMinutes":15,"mode":"IN_PERSON",
                 "sectionIds":["%s"]}""".formatted(today().plusDays(5), w.sectionA()))
                .andExpect(status().isForbidden());

        String event = event(w, today().plusDays(5), false);
        api.get("/api/ptm/events/" + event, w.principal().accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sections.length()").value(2))
                .andExpect(jsonPath("$.sections[0].classTeacherName").value("Ravi Kumar"))
                .andExpect(jsonPath("$.sections[0].students").value(2))
                .andExpect(jsonPath("$.teachers.length()").value(2))
                .andExpect(jsonPath("$.teachers[*].kind", org.hamcrest.Matchers.everyItem(
                        org.hamcrest.Matchers.is("CLASS_TEACHER"))))
                .andExpect(jsonPath("$.teachers[0].slots[*].startsAt", org.hamcrest.Matchers.contains("09:00:00",
                        "09:15:00", "09:45:00")))
                .andExpect(jsonPath("$.breaks[0].from").value("09:30:00"));
        api.get("/api/ptm/events", w.principal().accessToken())
                .andExpect(jsonPath("$[0].id").value(event))
                .andExpect(jsonPath("$[0].slots").value(6))
                .andExpect(jsonPath("$[0].booked").value(0));

        // Lata sees Asha's class teacher only (subject teachers are not part of this meeting).
        api.get("/api/me/ptm", w.lata().accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.children[0].name").value("Asha Rao"))
                .andExpect(jsonPath("$.children[0].events[0].title").value("Term 1 PTM"))
                .andExpect(jsonPath("$.children[0].events[0].location").value("Room 12"))
                .andExpect(jsonPath("$.children[0].events[0].teachers.length()").value(1))
                .andExpect(jsonPath("$.children[0].events[0].teachers[0].name").value("Ravi Kumar"))
                .andExpect(jsonPath("$.children[0].events[0].teachers[0].detail").value("Class teacher"))
                .andExpect(jsonPath("$.children[0].events[0].teachers[0].slots.length()").value(3))
                .andExpect(jsonPath("$.children[0].events[0].teachers[0].slots[0].available").value(true));
        String ravi9 = slot(w, event, w.raviId(), "09:00");
        String ravi915 = slot(w, event, w.raviId(), "09:15");
        String ravi945 = slot(w, event, w.raviId(), "09:45");
        String meera9 = slot(w, event, w.meeraId(), "09:00");

        String booking = book(w.lata(), ravi9, w.asha());
        // The slot is taken: double booking is a conflict, and parents only see that it is taken.
        api.post("/api/me/ptm/bookings", w.priya().accessToken(), """
                {"slotId":"%s","studentId":"%s"}""".formatted(ravi9, w.chitra()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errors.slotId").exists());
        api.get("/api/me/ptm", w.priya().accessToken())
                .andExpect(jsonPath("$.children[0].events[0].teachers[0].slots[0].available").value(false))
                .andExpect(content().string(not(containsString("Asha"))));
        // One slot per child per teacher.
        api.post("/api/me/ptm/bookings", w.lata().accessToken(), """
                {"slotId":"%s","studentId":"%s"}""".formatted(ravi915, w.asha()))
                .andExpect(status().isConflict());
        // Only her own child, and only the child's teachers; ids that are not slots are field errors.
        api.post("/api/me/ptm/bookings", w.lata().accessToken(), """
                {"slotId":"%s","studentId":"%s"}""".formatted(meera9, w.bala()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.studentId").exists());
        api.post("/api/me/ptm/bookings", w.lata().accessToken(), """
                {"slotId":"%s","studentId":"%s"}""".formatted(meera9, w.asha()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.slotId").exists());
        api.post("/api/me/ptm/bookings", w.lata().accessToken(), """
                {"slotId":"%s","studentId":"%s"}""".formatted(UUID.randomUUID(), w.asha()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.slotId").exists());
        book(w.kamala(), meera9, w.bala());

        // Moving and cancelling.
        String moved = TestApi.read(api.put("/api/me/ptm/bookings/" + booking, w.lata().accessToken(), """
                {"slotId":"%s"}""".formatted(ravi945))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.startsAt").value("09:45:00"))
                .andExpect(jsonPath("$.canChange").value(true)), "$.id");
        api.put("/api/me/ptm/bookings/" + moved, w.lata().accessToken(), """
                {"slotId":"%s"}""".formatted(meera9)).andExpect(status().isBadRequest());
        api.put("/api/me/ptm/bookings/" + moved, w.kamala().accessToken(), """
                {"slotId":"%s"}""".formatted(ravi9)).andExpect(status().isNotFound());
        book(w.priya(), ravi9, w.chitra());
        api.delete("/api/me/ptm/bookings/" + moved, w.kamala().accessToken()).andExpect(status().isNotFound());
        api.delete("/api/me/ptm/bookings/" + moved, w.lata().accessToken()).andExpect(status().isNoContent());
        api.delete("/api/me/ptm/bookings/" + moved, w.lata().accessToken()).andExpect(status().isConflict());
        book(w.lata(), ravi945, w.asha());

        // Ravi's schedule: parents' names and the children, never a phone number.
        api.get("/api/ptm/my", w.ravi().accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].event.id").value(event))
                .andExpect(jsonPath("$[0].schedule.booked").value(2))
                .andExpect(jsonPath("$[0].schedule.slots[0].booking.student.name").value("Chitra Nair"))
                .andExpect(jsonPath("$[0].schedule.slots[0].booking.parentName").value("Priya Nair"))
                .andExpect(jsonPath("$[0].schedule.slots[2].booking.student.name").value("Asha Rao"))
                .andExpect(content().string(not(containsString(LATA_PHONE))))
                .andExpect(content().string(not(containsString("@"))));
        api.get("/api/ptm/my/events/" + event, w.meera().accessToken())
                .andExpect(jsonPath("$.schedule.booked").value(1))
                .andExpect(jsonPath("$.schedule.slots[0].booking.student.name").value("Bala Devi"));
        api.get("/api/ptm/my/events/" + event, w.sunil().accessToken()).andExpect(status().isNotFound());
        api.get("/api/ptm/my", w.lata().accessToken()).andExpect(status().isForbidden());
        api.get("/api/ptm/events", w.ravi().accessToken()).andExpect(status().isForbidden());

        // The office books for a family; a booked slot cannot be blocked; another teacher's slot is not his.
        api.put("/api/ptm/my/slots/" + ravi9 + "/block", w.ravi().accessToken(), """
                {"blocked":true}""").andExpect(status().isConflict());
        api.put("/api/ptm/my/slots/" + meera9 + "/block", w.ravi().accessToken(), """
                {"blocked":true}""").andExpect(status().isNotFound());
        api.put("/api/ptm/my/slots/" + ravi915 + "/block", w.ravi().accessToken(), """
                {"blocked":true}""").andExpect(status().isOk()).andExpect(jsonPath("$.blocked").value(true));
        api.post("/api/me/ptm/bookings", w.priya().accessToken(), """
                {"slotId":"%s","studentId":"%s"}""".formatted(ravi915, w.chitra()))
                .andExpect(status().isConflict());
        api.put("/api/ptm/slots/" + ravi915 + "/block", w.principal().accessToken(), """
                {"blocked":false}""").andExpect(status().isOk()).andExpect(jsonPath("$.blocked").value(false));
        String meera915 = slot(w, event, w.meeraId(), "09:15");
        api.post("/api/ptm/events/" + event + "/bookings", w.principal().accessToken(), """
                {"slotId":"%s","studentId":"%s"}""".formatted(meera915, w.bala()))
                .andExpect(status().isConflict());
        api.post("/api/ptm/events/" + event + "/bookings", w.principal().accessToken(), """
                {"slotId":"%s","studentId":"%s"}""".formatted(meera915, UUID.randomUUID()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.studentId").exists());

        // Hours: inside the meeting's, and not dropping a booked slot.
        api.put("/api/ptm/my/events/" + event + "/hours", w.ravi().accessToken(), """
                {"availableFrom":"08:00","availableTo":"10:00"}""").andExpect(status().isBadRequest());
        api.put("/api/ptm/my/events/" + event + "/hours", w.ravi().accessToken(), """
                {"availableFrom":"09:15","availableTo":"10:00"}""").andExpect(status().isConflict());
        api.put("/api/ptm/my/events/" + event + "/hours", w.meera().accessToken(), """
                {"availableFrom":"09:00","availableTo":"09:30"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slots[*].startsAt", org.hamcrest.Matchers.contains("09:00:00", "09:15:00")));

        // Marking: notes any time, attendance from the meeting day.
        String chitraBooking = TestApi.read(api.get("/api/ptm/my", w.ravi().accessToken()),
                "$[0].schedule.slots[0].booking.id");
        api.put("/api/ptm/my/bookings/" + chitraBooking, w.ravi().accessToken(), """
                {"attendance":"ATTENDED"}""").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.attendance").exists());
        api.put("/api/ptm/my/bookings/" + chitraBooking, w.ravi().accessToken(), """
                {"privateNote":"Ask about reading","parentNote":"Please read with Chitra daily."}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.privateNote").value("Ask about reading"));
        api.put("/api/ptm/my/bookings/" + chitraBooking, w.meera().accessToken(), """
                {"privateNote":"x"}""").andExpect(status().isNotFound());

        // Changing the meeting: the day cannot change once booked, and booked slots must still fit.
        api.put("/api/ptm/events/" + event, w.principal().accessToken(), """
                {"title":"Term 1 PTM","date":"%s","startsAt":"09:00","endsAt":"10:00","slotMinutes":15,
                 "breaks":[{"from":"09:30","to":"09:45"}],"mode":"IN_PERSON","location":"Room 12",
                 "sectionIds":["%s","%s"]}""".formatted(today().plusDays(6), w.sectionA(), w.sectionB()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errors.date").exists());
        api.put("/api/ptm/events/" + event, w.principal().accessToken(), """
                {"title":"Term 1 PTM","date":"%s","startsAt":"09:00","endsAt":"10:00","slotMinutes":20,
                 "mode":"IN_PERSON","sectionIds":["%s","%s"]}""".formatted(today().plusDays(5), w.sectionA(),
                w.sectionB()))
                .andExpect(status().isConflict());
        api.put("/api/ptm/events/" + event, w.principal().accessToken(), """
                {"title":"Term 1 parent-teacher meeting","date":"%s","startsAt":"09:00","endsAt":"10:00",
                 "slotMinutes":15,"breaks":[{"from":"09:30","to":"09:45"}],"mode":"IN_PERSON",
                 "location":"Hall","sectionIds":["%s","%s"]}""".formatted(today().plusDays(5), w.sectionA(),
                w.sectionB()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Term 1 parent-teacher meeting"))
                .andExpect(jsonPath("$.location").value("Hall"));

        // Cancelling cancels every booking.
        api.post("/api/ptm/events/" + event + "/cancel", w.principal().accessToken(), """
                {"reason":"Heavy rain expected"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.teachers[0].booked").value(0));
        api.get("/api/me/ptm", w.lata().accessToken())
                .andExpect(jsonPath("$.children[0].events[0].status").value("CANCELLED"))
                .andExpect(jsonPath("$.children[0].events[0].cancelReason").value("Heavy rain expected"))
                .andExpect(jsonPath("$.children[0].events[0].teachers[0].booking").doesNotExist());
        api.post("/api/me/ptm/bookings", w.priya().accessToken(), """
                {"slotId":"%s","studentId":"%s"}""".formatted(ravi915, w.chitra()))
                .andExpect(status().isConflict());
        api.post("/api/ptm/events/" + event + "/cancel", w.principal().accessToken(), """
                {"reason":"Again"}""").andExpect(status().isConflict());
    }

    @Test
    void subjectTeachersMeetParentsWhenTheMeetingIncludesThem() throws Exception {
        World w = world();
        String event = event(w, today().plusDays(5), true);
        api.get("/api/ptm/events/" + event, w.principal().accessToken())
                .andExpect(jsonPath("$.teachers.length()").value(2))
                .andExpect(jsonPath("$.teachers[?(@.name == 'Meera Das')].subjects",
                        org.hamcrest.Matchers.contains("Science")));
        api.get("/api/me/ptm", w.lata().accessToken())
                .andExpect(jsonPath("$.children[0].events[0].teachers[*].name",
                        org.hamcrest.Matchers.contains("Ravi Kumar", "Meera Das")))
                .andExpect(jsonPath("$.children[0].events[0].teachers[1].detail").value("Science"));
        book(w.lata(), slot(w, event, w.meeraId(), "09:00"), w.asha());
        // Asha cannot be in two meetings at once.
        api.post("/api/me/ptm/bookings", w.lata().accessToken(), """
                {"slotId":"%s","studentId":"%s"}""".formatted(slot(w, event, w.raviId(), "09:00"), w.asha()))
                .andExpect(status().isConflict());
        book(w.lata(), slot(w, event, w.raviId(), "09:15"), w.asha());
    }

    @Test
    void bookingClosesAtTheCutoff() throws Exception {
        World w = world();
        // Today at 23:00 with a 72-hour cut-off: already closed for parents.
        String event = TestApi.read(api.post("/api/ptm/events", w.principal().accessToken(), """
                {"title":"Evening PTM","date":"%s","startsAt":"23:00","endsAt":"23:30","slotMinutes":15,
                 "mode":"VIDEO","videoLink":"https://meet.example.test/ptm","sectionIds":["%s"],"cutoffHours":72}"""
                .formatted(today(), w.sectionA())).andExpect(status().isCreated()), "$.id");
        String slot = slot(w, event, w.raviId(), "23:00");
        api.get("/api/me/ptm", w.lata().accessToken())
                .andExpect(jsonPath("$.children[0].events[0].videoLink").value("https://meet.example.test/ptm"))
                .andExpect(jsonPath("$.children[0].events[0].teachers[0].slots[0].available").value(false));
        api.post("/api/me/ptm/bookings", w.lata().accessToken(), """
                {"slotId":"%s","studentId":"%s"}""".formatted(slot, w.asha()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errors.slotId").exists());
        // The office can still book for the family.
        String booking = TestApi.read(api.post("/api/ptm/events/" + event + "/bookings",
                w.principal().accessToken(), """
                {"slotId":"%s","studentId":"%s"}""".formatted(slot, w.asha()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.bookedBy").value("SCHOOL"))
                .andExpect(jsonPath("$.parentName").doesNotExist()), "$.id");
        api.get("/api/me/ptm", w.lata().accessToken())
                .andExpect(jsonPath("$.children[0].events[0].teachers[0].booking.canChange").value(false));
        api.delete("/api/me/ptm/bookings/" + booking, w.lata().accessToken()).andExpect(status().isConflict());
        api.delete("/api/ptm/bookings/" + booking, w.principal().accessToken()).andExpect(status().isNoContent());
        api.delete("/api/ptm/bookings/" + booking, w.principal().accessToken()).andExpect(status().isConflict());
    }

    @Test
    void remindersGoTheDayBeforeAndAnHourBeforeWhenSwitchedOn() throws Exception {
        World w = world();
        String event = event(w, today().plusDays(5), false);
        String first = book(w.lata(), slot(w, event, w.raviId(), "09:00"), w.asha());
        api.get("/api/messages?relatedId=" + first, w.admin().accessToken()).andExpect(jsonPath("$.total").value(0));
        api.get("/api/ptm/settings", w.principal().accessToken())
                .andExpect(jsonPath("$.remindersEnabled").value(false));
        api.put("/api/ptm/settings", w.ravi().accessToken(), """
                {"remindersEnabled":true}""").andExpect(status().isForbidden());
        // Switching them on queues reminders for bookings already made.
        api.put("/api/ptm/settings", w.principal().accessToken(), """
                {"remindersEnabled":true}""").andExpect(status().isOk());
        api.get("/api/messages?relatedId=" + first, w.admin().accessToken())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[*].templateKey", org.hamcrest.Matchers.everyItem(
                        org.hamcrest.Matchers.is("ptm.reminder"))))
                .andExpect(jsonPath("$.items[*].status", org.hamcrest.Matchers.everyItem(
                        org.hamcrest.Matchers.is("QUEUED"))));
        String message = TestApi.read(api.get("/api/messages?relatedId=" + first, w.admin().accessToken()),
                "$.items[0].id");
        api.get("/api/messages/" + message, w.admin().accessToken())
                .andExpect(jsonPath("$.body").value(containsString("Ravi Kumar")))
                .andExpect(jsonPath("$.body").value(containsString("09:00")))
                .andExpect(jsonPath("$.body").value(containsString("Room 12")));
        // A new booking gets them too; moving drops the old ones.
        String second = book(w.kamala(), slot(w, event, w.meeraId(), "09:00"), w.bala());
        api.get("/api/messages?relatedId=" + second, w.admin().accessToken()).andExpect(jsonPath("$.total").value(2));
        String moved = TestApi.read(api.put("/api/me/ptm/bookings/" + first, w.lata().accessToken(), """
                {"slotId":"%s"}""".formatted(slot(w, event, w.raviId(), "09:15"))).andExpect(status().isOk()), "$.id");
        api.get("/api/messages?relatedId=" + first + "&status=SKIPPED", w.admin().accessToken())
                .andExpect(jsonPath("$.total").value(2));
        api.get("/api/messages?relatedId=" + moved + "&status=QUEUED", w.admin().accessToken())
                .andExpect(jsonPath("$.total").value(2));
        // Switching them off drops what is waiting.
        api.put("/api/ptm/settings", w.principal().accessToken(), """
                {"remindersEnabled":false}""").andExpect(status().isOk());
        api.get("/api/messages?relatedId=" + second + "&status=QUEUED", w.admin().accessToken())
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void theAttendanceSummaryCountsFamiliesAndDownloadsAsCsv() throws Exception {
        World w = world();
        String event = event(w, today().plusDays(5), false);
        String asha = book(w.lata(), slot(w, event, w.raviId(), "09:00"), w.asha());
        String chitra = book(w.priya(), slot(w, event, w.raviId(), "09:15"), w.chitra());
        book(w.kamala(), slot(w, event, w.meeraId(), "09:00"), w.bala());
        // The meeting day comes.
        try (Connection owner = ownerConnection();
                PreparedStatement s = owner.prepareStatement(
                        "update chat.ptm_event set meeting_date = ? where id = ?")) {
            s.setObject(1, today());
            s.setObject(2, UUID.fromString(event));
            s.executeUpdate();
        }
        api.put("/api/ptm/my/bookings/" + asha, w.ravi().accessToken(), """
                {"attendance":"ATTENDED","parentNote":"Asha is doing well."}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attendance").value("ATTENDED"));
        api.put("/api/ptm/bookings/" + chitra, w.principal().accessToken(), """
                {"attendance":"NO_SHOW"}""").andExpect(status().isOk());
        api.get("/api/me/ptm", w.lata().accessToken())
                .andExpect(jsonPath("$.children[0].events[0].teachers[0].booking.attendance").value("ATTENDED"))
                .andExpect(jsonPath("$.children[0].events[0].teachers[0].booking.parentNote")
                        .value("Asha is doing well."))
                .andExpect(content().string(not(containsString("privateNote"))));

        api.get("/api/ptm/reports/attendance?eventId=" + event, w.principal().accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sections[0].label").value("Class 5 A"))
                .andExpect(jsonPath("$.sections[0].students").value(2))
                .andExpect(jsonPath("$.sections[0].booked").value(2))
                .andExpect(jsonPath("$.sections[0].attended").value(1))
                .andExpect(jsonPath("$.sections[0].noShow").value(1))
                .andExpect(jsonPath("$.sections[1].notMarked").value(1))
                .andExpect(jsonPath("$.total.booked").value(3))
                .andExpect(jsonPath("$.teachers[?(@.name == 'Ravi Kumar')].attended",
                        org.hamcrest.Matchers.contains(1)));
        byte[] csv = api.get("/api/ptm/reports/attendance.csv?eventId=" + event, w.principal().accessToken())
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("text/csv")))
                .andExpect(header().string("Content-Disposition", containsString("ptm-attendance.csv")))
                .andReturn().getResponse().getContentAsByteArray();
        String text = new String(csv, StandardCharsets.UTF_8);
        assertThat(text).startsWith("﻿Meeting,Date,Type,Name");
        assertThat(text).contains("Class,Class 5 A,2,2,1,1,0\r\n").contains("Teacher,Ravi Kumar,3,2,1,1,0");
        api.get("/api/ptm/reports/attendance?eventId=" + event, w.ravi().accessToken())
                .andExpect(status().isForbidden());
        api.get("/api/ptm/reports/attendance?eventId=" + UUID.randomUUID(), w.principal().accessToken())
                .andExpect(status().isNotFound());
        api.get("/api/audit-events?limit=200", w.admin().accessToken())
                .andExpect(jsonPath("$[?(@.action == 'ptm_booking.marked')]", hasSize(2)))
                .andExpect(jsonPath("$[?(@.action == 'ptm_event.created')]", hasSize(1)));
    }

    @Test
    void anotherSchoolSeesNoMeeting() throws Exception {
        World w = world();
        String event = event(w, today().plusDays(5), false);
        String slot = slot(w, event, w.raviId(), "09:00");
        String booking = book(w.lata(), slot, w.asha());
        School other = api.signup();
        Session outsider = api.login(other);
        api.get("/api/ptm/events/" + event, outsider.accessToken()).andExpect(status().isNotFound());
        api.get("/api/ptm/events", outsider.accessToken()).andExpect(jsonPath("$").isEmpty());
        api.post("/api/ptm/events/" + event + "/cancel", outsider.accessToken(), """
                {"reason":"x"}""").andExpect(status().isNotFound());
        api.delete("/api/ptm/bookings/" + booking, outsider.accessToken()).andExpect(status().isNotFound());
        api.put("/api/ptm/slots/" + slot + "/block", outsider.accessToken(), """
                {"blocked":true}""").andExpect(status().isNotFound());
        new com.akshara.support.SchoolFixtures(api).currentYear(outsider);
        api.post("/api/ptm/events", outsider.accessToken(), """
                {"title":"PTM","date":"%s","startsAt":"09:00","endsAt":"10:00","slotMinutes":15,"mode":"IN_PERSON",
                 "sectionIds":["%s"]}""".formatted(today().plusDays(5), w.sectionA()))
                .andExpect(status().isBadRequest());
    }
}
