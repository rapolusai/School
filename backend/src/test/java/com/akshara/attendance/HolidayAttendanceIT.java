package com.akshara.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

import com.akshara.communication.SchoolCalendar;
import com.akshara.shared.TenantContext;
import com.akshara.support.IntegrationTest;
import com.akshara.support.SchoolFixtures;
import com.akshara.support.TestApi.School;
import com.akshara.support.TestApi.Session;

/** Whole-school holidays from the calendar: attendance is not marked on them and they leave the month's totals. */
class HolidayAttendanceIT extends IntegrationTest {

    static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    @Autowired
    SchoolCalendar calendar;

    private final LocalDate today = LocalDate.now(INDIA);
    private School school;
    private Session admin;
    private String section;
    private String classId;
    private String asha;

    @BeforeEach
    void schoolWithASection() throws Exception {
        school = api.signup();
        admin = api.login(school);
        SchoolFixtures fixtures = new SchoolFixtures(api);
        fixtures.year(admin, "This year", today.minusDays(150).toString(), today.plusDays(200).toString(), true);
        classId = fixtures.schoolClass(admin, "Class 5");
        section = fixtures.section(admin, classId, "A", 40);
        asha = fixtures.student(admin, section, "A-1", "Asha", "Rao", "Rekha Rao", "9876500011");
    }

    @Test
    void holidaysCannotBeMarkedAndAreLeftOutOfTheMonthRegister() throws Exception {
        List<LocalDate> days = schoolDaysBefore(today, 3);
        LocalDate other = days.get(0);
        LocalDate marked = days.get(1);
        LocalDate holiday = days.get(2);
        for (LocalDate d : List.of(marked, holiday)) {
            mark(d).andExpect(status().isOk());
        }
        // A holiday declared afterwards on a day that was already marked, and one today.
        entry("HOLIDAY", "Local festival", holiday, "SCHOOL", "[]");
        entry("HOLIDAY", "Founders Day", today, "SCHOOL", "[]");
        // Holidays for some classes or for staff only do not close the school.
        entry("HOLIDAY", "Class trip", other, "CLASSES", "[\"" + classId + "\"]");
        entry("HOLIDAY", "Staff training", other, "STAFF", "[]");

        mark(today).andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("School holiday"))
                .andExpect(jsonPath("$.errors.date").exists());
        mark(holiday).andExpect(status().isConflict());
        mark(other).andExpect(status().isOk());
        api.get("/api/attendance/sections?date=" + today, admin.accessToken())
                .andExpect(jsonPath("$.holiday").value("Founders Day"))
                .andExpect(jsonPath("$.canMark").value(false))
                .andExpect(jsonPath("$.sections[0].canMark").value(false));
        api.get("/api/attendance/sections?date=" + marked, admin.accessToken())
                .andExpect(jsonPath("$.holiday").isEmpty())
                .andExpect(jsonPath("$.canMark").value(true));
        api.get(AttendanceIT.register(section, today), admin.accessToken())
                .andExpect(jsonPath("$.holiday").value("Founders Day"))
                .andExpect(jsonPath("$.canEdit").value(false));

        YearMonth month = YearMonth.from(holiday);
        Set<LocalDate> holidays = Set.of(holiday, today);
        long expectedMarked = List.of(marked, other).stream().filter(d -> YearMonth.from(d).equals(month)).count();
        int expectedSchoolDays = 0;
        int expectedHolidays = 0;
        for (LocalDate d = month.atDay(1); !d.isAfter(month.atEndOfMonth()); d = d.plusDays(1)) {
            if (holidays.contains(d)) {
                expectedHolidays++;
            } else if (d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                expectedSchoolDays++;
            }
        }
        api.get("/api/attendance/sections/" + section + "/month?month=" + month, admin.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days[" + (holiday.getDayOfMonth() - 1) + "].holiday").value("Local festival"))
                .andExpect(jsonPath("$.days[" + (holiday.getDayOfMonth() - 1) + "].marked").value(true))
                .andExpect(jsonPath("$.daysMarked").value((int) expectedMarked))
                .andExpect(jsonPath("$.students[0].daysMarked").value((int) expectedMarked))
                .andExpect(jsonPath("$.holidays").value(expectedHolidays))
                .andExpect(jsonPath("$.schoolDays").value(expectedSchoolDays));

        boolean isHoliday = TenantContext.runAs(school.tenantId(), () -> calendar.isHoliday(holiday));
        boolean otherIsHoliday = TenantContext.runAs(school.tenantId(), () -> calendar.isHoliday(other));
        Map<LocalDate, String> between = TenantContext.runAs(school.tenantId(),
                () -> calendar.holidaysBetween(other, today));
        assertThat(isHoliday).isTrue();
        assertThat(otherIsHoliday).isFalse();
        assertThat(between).containsEntry(holiday, "Local festival").containsEntry(today, "Founders Day")
                .hasSize(2);
        // Another school's holidays are not this school's.
        School otherSchool = api.signup();
        assertThat(TenantContext.runAs(otherSchool.tenantId(), () -> calendar.isHoliday(today))).isFalse();
    }

    private ResultActions mark(LocalDate date) throws Exception {
        return api.put(AttendanceIT.register(section, date), admin.accessToken(),
                AttendanceIT.body(Map.of(asha, "PRESENT")));
    }

    private void entry(String kind, String title, LocalDate date, String audience, String classIds)
            throws Exception {
        api.post("/api/calendar/entries", admin.accessToken(), """
                {"kind":"%s","title":"%s","startsOn":"%s","audience":"%s","classIds":%s}"""
                .formatted(kind, title, date, audience, classIds)).andExpect(status().isCreated());
    }

    /** The {@code count} Mondays to Saturdays before {@code day}, oldest first. */
    private static List<LocalDate> schoolDaysBefore(LocalDate day, int count) {
        List<LocalDate> days = new ArrayList<>();
        for (LocalDate d = day.minusDays(1); days.size() < count; d = d.minusDays(1)) {
            if (d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                days.addFirst(d);
            }
        }
        return days;
    }
}
