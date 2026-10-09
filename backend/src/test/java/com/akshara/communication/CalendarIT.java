package com.akshara.communication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.akshara.support.IntegrationTest;
import com.akshara.support.SchoolFixtures;
import com.akshara.support.TestApi;
import com.akshara.support.TestApi.School;
import com.akshara.support.TestApi.Session;

/** The school calendar: editing, who sees what, the holiday starter list, the iCalendar feed and reminders. */
class CalendarIT extends IntegrationTest {

    static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    static final DateTimeFormatter ICS_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    @Autowired
    CommunicationScheduler scheduler;

    private final LocalDate today = LocalDate.now(INDIA);
    private CommunicationSchool s;
    private Session principal;

    @BeforeEach
    void school() throws Exception {
        s = new CommunicationSchool(api, today);
        principal = s.login("principal");
    }

    @Test
    void entriesAreManagedByAdminsAndShownToTheRightPeople() throws Exception {
        LocalDate d = today.plusDays(10);
        String holiday = create(principal, entry("HOLIDAY", "Founders Day", d, null, "SCHOOL", "[]"));
        String classSix = create(principal, entry("EVENT", "Class 6 trip", d.plusDays(1), null, "CLASSES",
                "[\"" + s.class6 + "\"]"));
        String staffOnly = create(principal, entry("OTHER", "Staff meeting", d.plusDays(2), null, "STAFF", "[]"));

        // Checks mirror the form.
        api.post("/api/calendar/entries", principal.accessToken(), entry("EVENT", "Back to front", d, d.minusDays(1),
                "SCHOOL", "[]")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.endsOn").exists());
        api.post("/api/calendar/entries", principal.accessToken(), entry("EVENT", "Too long", d, d.plusDays(121),
                "SCHOOL", "[]")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.endsOn").exists());
        api.post("/api/calendar/entries", principal.accessToken(), entry("EVENT", "No class", d, null, "CLASSES",
                "[]")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.classIds").exists());
        api.post("/api/calendar/entries", principal.accessToken(), entry("EVENT", "Unknown class", d, null,
                "CLASSES", "[\"" + UUID.randomUUID() + "\"]"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.classIds").exists());
        api.post("/api/calendar/entries", principal.accessToken(), """
                {"kind":"EVENT","title":"Ends only","startsOn":"%s","endTime":"10:00","audience":"SCHOOL"}"""
                .formatted(d)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.startTime").exists());
        api.post("/api/calendar/entries", principal.accessToken(), """
                {"kind":"EVENT","title":"Backwards","startsOn":"%s","startTime":"10:00","endTime":"09:00",
                 "audience":"SCHOOL"}""".formatted(d))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.endTime").exists());
        // Only calendar.manage edits.
        for (String who : new String[] {"ravi", "rekha", "accounts"}) {
            api.post("/api/calendar/entries", s.login(who).accessToken(), entry("EVENT", "Mine", d, null, "SCHOOL",
                    "[]")).andExpect(status().isForbidden());
            api.delete("/api/calendar/entries/" + holiday, s.login(who).accessToken())
                    .andExpect(status().isForbidden());
        }

        String range = "/api/calendar/entries?from=" + d + "&to=" + d.plusDays(5);
        for (String staff : new String[] {"principal", "ravi", "accounts"}) {
            api.get(range, s.login(staff).accessToken())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.entries[*].id", contains(holiday, classSix, staffOnly)));
        }
        api.get(range, s.login("rekha").accessToken())
                .andExpect(jsonPath("$.entries[*].id", contains(holiday)))
                .andExpect(jsonPath("$.canManage").value(false));
        api.get(range, s.login("uma").accessToken())
                .andExpect(jsonPath("$.entries[*].id", contains(holiday, classSix)))
                .andExpect(jsonPath("$.entries[1].classes[0].name").value("Class 6"));
        api.get(range, s.login("asha").accessToken()).andExpect(jsonPath("$.entries[*].id", contains(holiday)));
        api.get("/api/calendar/entries/" + staffOnly, s.login("rekha").accessToken())
                .andExpect(status().isNotFound());
        api.get("/api/calendar/upcoming?limit=5", s.login("rekha").accessToken())
                .andExpect(jsonPath("$[0].id").value(holiday));
        // Without dates: the current academic year.
        api.get("/api/calendar/entries", principal.accessToken())
                .andExpect(jsonPath("$.entries.length()").value(3))
                .andExpect(jsonPath("$.canManage").value(true))
                .andExpect(jsonPath("$.classes[*].name", contains("Class 5", "Class 6")));

        api.put("/api/calendar/entries/" + holiday, principal.accessToken(), entry("HOLIDAY", "Founders' Day", d,
                d.plusDays(1), "SCHOOL", "[]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Founders' Day"))
                .andExpect(jsonPath("$.endsOn").value(d.plusDays(1).toString()))
                .andExpect(jsonPath("$.updatedByName").value("Lakshmi Iyer"));
        api.delete("/api/calendar/entries/" + classSix, principal.accessToken()).andExpect(status().isNoContent());
        api.get("/api/calendar/entries/" + classSix, principal.accessToken()).andExpect(status().isNotFound());
        api.get("/api/audit-events?limit=50", s.admin.accessToken())
                .andExpect(jsonPath("$[*].action", hasItems("calendar_entry.created", "calendar_entry.updated",
                        "calendar_entry.deleted")));

        // Another school sees and changes nothing.
        School other = api.signup();
        Session otherAdmin = api.login(other);
        api.get("/api/calendar/entries/" + holiday, otherAdmin.accessToken()).andExpect(status().isNotFound());
        api.put("/api/calendar/entries/" + holiday, otherAdmin.accessToken(), entry("HOLIDAY", "Mine", d, null,
                "SCHOOL", "[]")).andExpect(status().isNotFound());
        api.delete("/api/calendar/entries/" + holiday, otherAdmin.accessToken()).andExpect(status().isNotFound());
        api.get(range, otherAdmin.accessToken()).andExpect(jsonPath("$.entries.length()").value(0));
    }

    @Test
    void theHolidayStarterListIsOnlyASuggestion() throws Exception {
        School school = api.signup();
        Session admin = api.login(school);
        new SchoolFixtures(api).currentYear(admin);
        api.get("/api/calendar/holiday-suggestions", admin.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.academicYearName").value("2026-27"))
                .andExpect(jsonPath("$.items[?(@.title == 'Independence Day')].date", contains("2026-08-15")))
                .andExpect(jsonPath("$.items[?(@.title == 'Republic Day')].date", contains("2027-01-26")))
                .andExpect(jsonPath("$.items[?(@.title == 'Gandhi Jayanti')].needsConfirmation", contains(false)))
                .andExpect(jsonPath("$.items[?(@.title == 'Diwali')].needsConfirmation", contains(true)))
                .andExpect(jsonPath("$.items[?(@.title == 'Diwali')].group", contains("FESTIVAL")))
                // Good Friday 2026 falls before this school's year starts in June.
                .andExpect(jsonPath("$.items[?(@.title == 'Good Friday')]").isEmpty())
                .andExpect(jsonPath("$.items[*].alreadyAdded", org.hamcrest.Matchers.everyItem(
                        org.hamcrest.Matchers.is(false))));
        // Nothing was added by looking.
        api.get("/api/calendar/entries", admin.accessToken()).andExpect(jsonPath("$.entries.length()").value(0));

        api.post("/api/calendar/entries/bulk", admin.accessToken(), "{\"entries\":[%s,%s]}".formatted(
                entry("HOLIDAY", "Independence Day", LocalDate.of(2026, 8, 15), null, "SCHOOL", "[]"),
                entry("HOLIDAY", "Diwali", LocalDate.of(2026, 11, 8), null, "SCHOOL", "[]")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.length()").value(2));
        api.get("/api/calendar/holiday-suggestions", admin.accessToken())
                .andExpect(jsonPath("$.items[?(@.title == 'Diwali')].alreadyAdded", contains(true)))
                .andExpect(jsonPath("$.items[?(@.title == 'Holi')].alreadyAdded", contains(false)));
        // All or nothing: one bad entry adds none.
        api.post("/api/calendar/entries/bulk", admin.accessToken(), "{\"entries\":[%s,%s]}".formatted(
                entry("HOLIDAY", "Holi", LocalDate.of(2027, 3, 22), null, "SCHOOL", "[]"),
                entry("HOLIDAY", "Bad", LocalDate.of(2027, 3, 22), LocalDate.of(2027, 3, 1), "SCHOOL", "[]")))
                .andExpect(status().isBadRequest());
        api.get("/api/calendar/entries", admin.accessToken()).andExpect(jsonPath("$.entries.length()").value(2));
        api.get("/api/calendar/holiday-suggestions", s.login("ravi").accessToken()).andExpect(status().isForbidden());
    }

    @Test
    void theICalendarFeedHasTheCallersEntries() throws Exception {
        LocalDate d = today.plusDays(20);
        create(principal, """
                {"kind":"EVENT","title":"Sports, Games; Day","description":"Bring water.\\nWear white.",
                 "startsOn":"%s","startTime":"08:30","endTime":"13:00","audience":"SCHOOL"}""".formatted(d));
        create(principal, entry("HOLIDAY", "Autumn break", d.plusDays(5), d.plusDays(8), "SCHOOL", "[]"));
        create(principal, entry("OTHER", "Staff meeting", d.plusDays(1), null, "STAFF", "[]"));

        String start = d.atTime(8, 30).atZone(INDIA).toInstant().toString().replace("-", "").replace(":", "")
                .replace(".000", "");
        String ics = api.get("/api/calendar.ics", principal.accessToken())
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", startsWith("text/calendar")))
                .andExpect(header().string("Content-Disposition", startsWith("attachment")))
                .andReturn().getResponse().getContentAsString();
        assertThat(ics).startsWith("BEGIN:VCALENDAR\r\nVERSION:2.0\r\n").endsWith("END:VCALENDAR\r\n")
                .contains("SUMMARY:Sports\\, Games\\; Day\r\n")
                .contains("DESCRIPTION:Bring water.\\nWear white.\r\n")
                .contains("DTSTART:" + start + "\r\n")
                .contains("DTSTART;VALUE=DATE:" + ICS_DATE.format(d.plusDays(5)) + "\r\n")
                .contains("DTEND;VALUE=DATE:" + ICS_DATE.format(d.plusDays(9)) + "\r\n")
                .contains("SUMMARY:Staff meeting");
        assertThat(ics.split("\r\n")).allMatch(line -> line.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                <= 75);
        String parents = api.get("/api/calendar.ics", s.login("rekha").accessToken())
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(parents).contains("SUMMARY:Autumn break").doesNotContain("Staff meeting");
    }

    @Test
    void remindersGoOutOnceWhenTheirLeadTimeStarts() throws Exception {
        String ptm = create(principal, """
                {"kind":"PTM","title":"Parent-teacher meeting","startsOn":"%s","startTime":"09:00",
                 "audience":"SCHOOL","reminderDays":3,"reminderChannels":["SMS"]}""".formatted(today.plusDays(3)));
        String exam = create(principal, """
                {"kind":"EXAM","title":"Class 6 test","startsOn":"%s","audience":"CLASSES",
                 "classIds":["%s"],"reminderDays":2}""".formatted(today.plusDays(5), s.class6));
        Instant now = Instant.now();
        assertThat(scheduler.sendDueReminders(s.school.tenantId(), today, now)).isEqualTo(1);
        assertThat(scheduler.sendDueReminders(s.school.tenantId(), today, now)).isZero();
        api.get("/api/calendar/entries/" + ptm, principal.accessToken())
                .andExpect(jsonPath("$.reminderSentAt").isNotEmpty());

        Session rekha = s.login("rekha");
        String reminder = TestApi.read(api.get("/api/notices/board", rekha.accessToken())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].title").value("Reminder: Parent-teacher meeting"))
                .andExpect(jsonPath("$.items[0].category").value("EVENT"))
                .andExpect(jsonPath("$.items[0].calendarReminder").value(true)), "$.items[0].id");
        // One SMS per parent's number, with the reminder template.
        api.get("/api/messages?relatedId=" + reminder, s.admin.accessToken())
                .andExpect(jsonPath("$.total").value(4))
                .andExpect(jsonPath("$.items[*].templateKey", org.hamcrest.Matchers.everyItem(
                        org.hamcrest.Matchers.is("calendar.reminder"))));
        api.get("/api/notices?status=SENT", principal.accessToken())
                .andExpect(jsonPath("$.items[0].source").value("CALENDAR"));

        // The class reminder starts two days ahead, and reaches only that class's families.
        assertThat(scheduler.sendDueReminders(s.school.tenantId(), today.plusDays(3), now)).isEqualTo(1);
        api.get("/api/notices/board", s.login("uma").accessToken())
                .andExpect(jsonPath("$.items[*].title", containsInAnyOrder("Reminder: Parent-teacher meeting",
                        "Reminder: Class 6 test")));
        api.get("/api/notices/board", rekha.accessToken()).andExpect(jsonPath("$.total").value(1));

        // Moving the meeting sends its reminder again when the new lead time starts.
        api.put("/api/calendar/entries/" + ptm, principal.accessToken(), """
                {"kind":"PTM","title":"Parent-teacher meeting","startsOn":"%s","startTime":"09:00",
                 "audience":"SCHOOL","reminderDays":3,"reminderChannels":["SMS"]}""".formatted(today.plusDays(10)))
                .andExpect(jsonPath("$.reminderSentAt").isEmpty());
        assertThat(exam).isNotBlank();
    }

    // ------------------------------------------------------------------ helpers

    private String create(Session who, String json) throws Exception {
        return TestApi.read(api.post("/api/calendar/entries", who.accessToken(), json)
                .andExpect(status().isCreated()), "$.id");
    }

    static String entry(String kind, String title, LocalDate startsOn, LocalDate endsOn, String audience,
            String classIds) {
        return """
                {"kind":"%s","title":"%s","startsOn":"%s","endsOn":%s,"audience":"%s","classIds":%s}"""
                .formatted(kind, title, startsOn, endsOn == null ? "null" : "\"" + endsOn + "\"", audience, classIds);
    }
}
