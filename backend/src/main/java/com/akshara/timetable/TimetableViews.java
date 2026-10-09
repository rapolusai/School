package com.akshara.timetable;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.akshara.academics.AcademicsDirectory.YearInfo;

/** What the timetable API returns. Days are MONDAY…SUNDAY, times are HH:mm in India time. */
public final class TimetableViews {

    private TimetableViews() {
    }

    public record YearRef(UUID id, String name, LocalDate startsOn, LocalDate endsOn) {

        static YearRef of(YearInfo y) {
            return y == null ? null : new YearRef(y.id(), y.name(), y.startsOn(), y.endsOn());
        }
    }

    // ------------------------------------------------------------------ bell schedule

    /** A teaching period (numbered) or a break (no number). */
    public record PeriodView(Integer number, String label, String startsAt, String endsAt, boolean breakTime) {
    }

    /**
     * The school's bell schedule. {@code saturday} is used on Saturdays when {@code saturdaySchedule} is on;
     * otherwise every working day follows {@code weekday}.
     */
    public record BellSchedule(List<DayOfWeek> workingDays, boolean saturdaySchedule, List<PeriodView> weekday,
            List<PeriodView> saturday, int weekdayPeriods, int saturdayPeriods) {
    }

    // ------------------------------------------------------------------ teacher assignments

    public record AssignmentView(UUID id, UUID sectionId, String sectionLabel, UUID classId, UUID subjectId,
            String subjectName, UUID teacherId, String teacherName, int periodsPerWeek, int scheduled) {
    }

    /** The result of changing an assignment: its section's periods move to the new teacher, which may clash. */
    public record AssignmentSaved(AssignmentView assignment, int periodsMoved, List<Clash> clashes) {
    }

    // ------------------------------------------------------------------ section timetables

    public record SlotView(DayOfWeek day, int period, UUID subjectId, String subjectName, UUID teacherId,
            String teacherName, String room) {
    }

    /** A subject of the section with its teacher, weekly allowance (null when not assigned) and periods placed. */
    public record SubjectLoad(UUID subjectId, String subjectName, UUID assignmentId, UUID teacherId,
            String teacherName, Integer periodsPerWeek, int scheduled) {
    }

    /** A teacher's period in another section, so the editor can warn about a clash before saving. */
    public record Booking(UUID teacherId, DayOfWeek day, int period, UUID sectionId, String sectionLabel,
            String subjectName) {
    }

    public record ClashEntry(UUID sectionId, String sectionLabel, UUID subjectId, String subjectName,
            UUID teacherId, String teacherName) {
    }

    /**
     * TEACHER: one teacher in two sections at once ({@code teacherId} set). SECTION: two subjects in one period of
     * one section ({@code sectionId} set).
     */
    public record Clash(String kind, DayOfWeek day, int period, UUID teacherId, String teacherName, UUID sectionId,
            String sectionLabel, List<ClashEntry> entries) {
    }

    /** A subject placed more times in a week than its teacher assignment allows. A warning, not an error. */
    public record WeeklyWarning(UUID sectionId, String sectionLabel, UUID subjectId, String subjectName,
            int scheduled, int periodsPerWeek) {
    }

    public record SectionTimetable(UUID sectionId, String label, UUID classId, String className, String sectionName,
            String classTeacherName, YearRef academicYear, BellSchedule bells, List<SlotView> slots,
            List<SubjectLoad> subjects, List<Booking> busy, List<Clash> clashes, List<WeeklyWarning> warnings,
            boolean canEdit) {
    }

    /** One section on the overview: how many of its teaching periods are filled, and its clashes. */
    public record SectionSummary(UUID sectionId, String label, UUID classId, String className, String sectionName,
            String classTeacherName, int scheduled, int cells, int clashes) {
    }

    public record SectionsOverview(YearRef academicYear, BellSchedule bells, List<SectionSummary> sections,
            int clashes, boolean canEdit) {
    }

    /** The whole-school check: every clash and every subject over its weekly allowance. */
    public record ClashReport(YearRef academicYear, int sectionsChecked, int periodsChecked, List<Clash> clashes,
            List<WeeklyWarning> warnings) {
    }

    // ------------------------------------------------------------------ teachers

    public record TeacherSummary(UUID id, String name, int periodsPerWeek, int scheduled, List<String> subjects) {
    }

    public record TeacherSlot(DayOfWeek day, int period, UUID sectionId, String sectionLabel, UUID subjectId,
            String subjectName, String room) {
    }

    public record TeacherTimetable(UUID teacherId, String teacherName, YearRef academicYear, BellSchedule bells,
            List<TeacherSlot> slots, int periodsPerWeek, List<Clash> clashes) {
    }

    /**
     * What happens in one period of a teacher's or a section's day. CLASS: a regular class. SUBSTITUTION: covering
     * for {@code absentTeacherName}. COVERED: the regular teacher is away; {@code substituteName} covers it (null
     * when nobody is assigned yet).
     */
    public record DayEntry(String kind, UUID sectionId, String sectionLabel, UUID subjectId, String subjectName,
            String teacherName, String room, String substituteName, String absentTeacherName) {
    }

    public record DayPeriod(Integer number, String label, String startsAt, String endsAt, boolean breakTime,
            List<DayEntry> entries) {
    }

    /** One day of a teacher's timetable with substitutions applied. */
    public record TeacherDay(LocalDate date, DayOfWeek day, boolean workingDay, UUID teacherId, String teacherName,
            boolean absent, List<DayPeriod> periods) {
    }

    // ------------------------------------------------------------------ free teachers and substitutions

    public record FreeTeacher(UUID id, String name, int periodsThatDay, boolean teachesSubject) {
    }

    public record FreeTeachers(LocalDate date, DayOfWeek day, int period, List<FreeTeacher> teachers) {
    }

    public record SubstituteRef(UUID id, UUID teacherId, String teacherName) {
    }

    /** One period an absent teacher would have taught, with its cover (if any) and ranked suggestions. */
    public record AffectedPeriod(int period, String label, String startsAt, String endsAt, UUID sectionId,
            String sectionLabel, UUID subjectId, String subjectName, String room, SubstituteRef substitute,
            List<FreeTeacher> suggestions) {
    }

    public record AbsenceView(UUID id, UUID teacherId, String teacherName, String reason,
            List<AffectedPeriod> periods) {
    }

    public record TeacherRef(UUID id, String name) {
    }

    /** The substitution sheet of a day. */
    public record SubstitutionDay(LocalDate date, DayOfWeek day, boolean workingDay, List<AbsenceView> absences,
            int periodsToCover, int periodsCovered, List<TeacherRef> teachers) {
    }

    // ------------------------------------------------------------------ parents and students

    /** A student's section timetable, with today's day (substitutions applied) for the dashboard. */
    public record FamilyTimetable(UUID studentId, String studentName, UUID sectionId, String sectionLabel,
            YearRef academicYear, BellSchedule bells, List<SlotView> slots, LocalDate today, DayOfWeek todayDay,
            boolean workingDay, List<DayPeriod> todayPeriods) {
    }
}
