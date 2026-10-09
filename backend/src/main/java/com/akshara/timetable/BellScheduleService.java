package com.akshara.timetable;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.academics.AcademicsDirectory.YearInfo;
import com.akshara.audit.AuditService;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;
import com.akshara.timetable.TimetableViews.BellSchedule;

/**
 * The bell schedule: working days and the periods and breaks of a school day, with an optional separate Saturday
 * schedule. Saving replaces the whole schedule, and is refused while a timetable still uses a day or period that
 * would disappear.
 */
@Service
@Transactional
public class BellScheduleService {

    static final int MAX_ROWS = 30;
    static final int MAX_PERIODS = 16;

    /** One row of the schedule as the school types it. Times are HH:mm. */
    public record PeriodInput(String label, String startsAt, String endsAt, boolean breakTime) {
    }

    private final TimetableSettingsRepository settings;
    private final PeriodRepository periods;
    private final SlotRepository slots;
    private final TimetableSupport support;
    private final AuditService audit;

    BellScheduleService(TimetableSettingsRepository settings, PeriodRepository periods, SlotRepository slots,
            TimetableSupport support, AuditService audit) {
        this.settings = settings;
        this.periods = periods;
        this.slots = slots;
        this.support = support;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public BellSchedule view() {
        return support.bells().view();
    }

    public BellSchedule save(List<DayOfWeek> workingDays, boolean saturdaySchedule, List<PeriodInput> weekday,
            List<PeriodInput> saturday) {
        TenantContext.require();
        support.lock();
        Set<DayOfWeek> days = workingDays == null ? Set.of() : EnumSet.noneOf(DayOfWeek.class);
        if (workingDays != null) {
            for (DayOfWeek d : workingDays) {
                if (d == null || !days.add(d)) {
                    throw ApiException.badRequest("Pick each working day once.", "workingDays");
                }
            }
        }
        if (days.isEmpty()) {
            throw ApiException.badRequest("Pick at least one working day.", "workingDays");
        }
        if (saturdaySchedule && !days.contains(DayOfWeek.SATURDAY)) {
            throw ApiException.badRequest("Saturday must be a working day to have its own schedule.",
                    "saturdaySchedule");
        }
        List<Period> weekdayRows = rows(Period.Schedule.WEEKDAY, "weekday", weekday);
        List<Period> saturdayRows = saturdaySchedule ? rows(Period.Schedule.SATURDAY, "saturday", saturday)
                : List.of();
        Bells next = new Bells(List.copyOf(new TreeSet<>(days)), saturdaySchedule, concat(weekdayRows, saturdayRows));
        checkTimetablesFit(next);

        Bells before = support.bells();
        TimetableSettings row = settings.findFirstBy().orElse(null);
        if (row == null) {
            row = settings.save(new TimetableSettings(days, saturdaySchedule));
        } else {
            row.update(days, saturdaySchedule);
        }
        periods.deleteAllInBatch();
        periods.flush();
        periods.saveAll(concat(weekdayRows, saturdayRows));
        periods.flush();
        settings.flush();

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("workingDays", TimetableSettings.encode(days));
        details.put("weekdayPeriods", next.view().weekdayPeriods());
        details.put("saturdayPeriods", next.view().saturdayPeriods());
        details.put("previousWeekdayPeriods", before.view().weekdayPeriods());
        audit.record("bell_schedule.updated", "bell_schedule", row.getId(), details);
        return support.bells().view();
    }

    private static List<Period> concat(List<Period> a, List<Period> b) {
        List<Period> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }

    /** Checks one schedule's rows: labels, HH:mm times, time order without overlaps, and 1–16 teaching periods. */
    private static List<Period> rows(Period.Schedule schedule, String field, List<PeriodInput> input) {
        if (input == null || input.isEmpty()) {
            throw ApiException.badRequest("Add the periods of the day.", field);
        }
        if (input.size() > MAX_ROWS) {
            throw ApiException.badRequest("A day can have at most " + MAX_ROWS + " periods and breaks.", field);
        }
        List<Period> result = new ArrayList<>();
        LocalTime previousEnd = null;
        String previousLabel = null;
        int number = 0;
        for (int i = 0; i < input.size(); i++) {
            PeriodInput row = input.get(i);
            String key = field + "[" + i + "]";
            if (row == null) {
                throw ApiException.badRequest("Fill in every row.", key);
            }
            String label = row.label() == null ? "" : row.label().strip().replaceAll("\\s+", " ");
            if (label.isEmpty()) {
                throw ApiException.badRequest("Give this row a name, for example Period 1 or Lunch.", key + ".label");
            }
            if (label.length() > 40) {
                throw ApiException.badRequest("Keep the name to 40 characters.", key + ".label");
            }
            LocalTime start = time(row.startsAt(), key + ".startsAt");
            LocalTime end = time(row.endsAt(), key + ".endsAt");
            if (!end.isAfter(start)) {
                throw ApiException.badRequest(label + " must end after it starts.", key + ".endsAt");
            }
            if (previousEnd != null && start.isBefore(previousEnd)) {
                throw ApiException.badRequest(label + " starts at " + Bells.time(start) + ", before " + previousLabel
                        + " ends at " + Bells.time(previousEnd) + ". Periods must be in time order and must not "
                        + "overlap.", key + ".startsAt");
            }
            Integer n = null;
            if (!row.breakTime()) {
                n = ++number;
                if (n > MAX_PERIODS) {
                    throw ApiException.badRequest("A day can have at most " + MAX_PERIODS + " teaching periods.",
                            key);
                }
            }
            result.add(new Period(schedule, i + 1, n, label, start, end, row.breakTime()));
            previousEnd = end;
            previousLabel = label;
        }
        if (number == 0) {
            throw ApiException.badRequest("Add at least one teaching period (not a break).", field);
        }
        return result;
    }

    private static LocalTime time(String value, String field) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest("Give the time as HH:mm, for example 08:30.", field);
        }
        try {
            String v = value.strip();
            if (!v.matches("\\d{1,2}:\\d{2}")) {
                throw new DateTimeParseException("format", v, 0);
            }
            String[] parts = v.split(":");
            return LocalTime.of(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
        } catch (DateTimeException e) {
            throw ApiException.badRequest("Give the time as HH:mm, for example 08:30.", field);
        }
    }

    /** Refuses a schedule that would drop a day or period that a section's timetable still uses this year. */
    private void checkTimetablesFit(Bells next) {
        Optional<YearInfo> year = support.currentYearIfAny();
        if (year.isEmpty()) {
            return;
        }
        List<Slot> all = slots.findByAcademicYearId(year.get().id());
        TimetableSupport.Names names = null;
        for (Slot s : all) {
            DayOfWeek day = s.getDay();
            if (!next.isWorking(day) || s.getPeriodNo() > next.teachingPeriods(day)) {
                if (names == null) {
                    names = support.names(List.of());
                }
                String dayName = day.name().charAt(0) + day.name().substring(1).toLowerCase(Locale.ROOT);
                boolean dayGone = !next.isWorking(day);
                String field = dayGone ? "workingDays"
                        : (day == DayOfWeek.SATURDAY && next.view().saturdaySchedule() ? "saturday" : "weekday");
                throw new ApiException(HttpStatus.CONFLICT, "Timetable in use",
                        names.section(s.getSectionId()) + " has " + names.subject(s.getSubjectId()) + " in period "
                                + s.getPeriodNo() + " on " + dayName + (dayGone ? ", which would no longer be a "
                                        + "working day." : ", which this schedule no longer has.")
                                + " Clear it from the timetable first.",
                        Map.of(field, "A timetable still uses this."));
            }
        }
    }
}
