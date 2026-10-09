package com.akshara.timetable;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.shared.CurrentUser;
import com.akshara.shared.Permissions;
import com.akshara.timetable.BellScheduleService.PeriodInput;
import com.akshara.timetable.TimetableService.CellInput;
import com.akshara.timetable.TimetableViews.AssignmentSaved;
import com.akshara.timetable.TimetableViews.AssignmentView;
import com.akshara.timetable.TimetableViews.BellSchedule;
import com.akshara.timetable.TimetableViews.ClashReport;
import com.akshara.timetable.TimetableViews.FreeTeachers;
import com.akshara.timetable.TimetableViews.SectionTimetable;
import com.akshara.timetable.TimetableViews.SectionsOverview;
import com.akshara.timetable.TimetableViews.SubstitutionDay;
import com.akshara.timetable.TimetableViews.TeacherDay;
import com.akshara.timetable.TimetableViews.TeacherSummary;
import com.akshara.timetable.TimetableViews.TeacherTimetable;

/**
 * The bell schedule, teacher assignments, section and teacher timetables, and substitutions. timetable.read (all
 * staff) to look; timetable.manage to change anything.
 */
@RestController
@RequestMapping("/api/timetable")
public class TimetableController {

    static final String READ = "hasAuthority('timetable.read')";
    static final String MANAGE = "hasAuthority('timetable.manage')";

    private final BellScheduleService bells;
    private final TimetableService timetable;
    private final SubstitutionService substitutions;

    TimetableController(BellScheduleService bells, TimetableService timetable, SubstitutionService substitutions) {
        this.bells = bells;
        this.timetable = timetable;
        this.substitutions = substitutions;
    }

    public record PeriodRequest(String label, String startsAt, String endsAt, Boolean breakTime) {
    }

    public record BellScheduleRequest(@NotNull @Size(max = 7) List<DayOfWeek> workingDays, Boolean saturdaySchedule,
            @NotNull @Size(max = BellScheduleService.MAX_ROWS) List<PeriodRequest> weekday,
            @Size(max = BellScheduleService.MAX_ROWS) List<PeriodRequest> saturday) {
    }

    public record AssignmentRequest(@NotNull UUID sectionId, @NotNull UUID subjectId, @NotNull UUID teacherId,
            @NotNull @Min(0) @Max(60) Integer periodsPerWeek) {
    }

    public record AssignmentUpdate(@NotNull UUID teacherId, @NotNull @Min(0) @Max(60) Integer periodsPerWeek) {
    }

    public record CellRequest(@NotNull DayOfWeek day, @NotNull @Min(1) @Max(16) Integer period,
            @NotNull UUID subjectId, UUID teacherId, @Size(max = 40) String room) {
    }

    public record SectionRequest(
            @NotNull @Size(max = TimetableService.MAX_CELLS) List<@NotNull @Valid CellRequest> slots) {
    }

    public record AbsenceRequest(@NotNull LocalDate date, @NotNull UUID teacherId, @Size(max = 200) String reason) {
    }

    public record SubstitutionRequest(@NotNull LocalDate date, @NotNull UUID sectionId,
            @NotNull @Min(1) @Max(16) Integer period, @NotNull UUID teacherId) {
    }

    // ------------------------------------------------------------------ bell schedule

    @GetMapping("/bell-schedule")
    @PreAuthorize(READ)
    public BellSchedule bellSchedule() {
        return bells.view();
    }

    /** Replaces the working days and the periods of the day (and of Saturday, when it has its own schedule). */
    @PutMapping("/bell-schedule")
    @PreAuthorize(MANAGE)
    public BellSchedule saveBellSchedule(@Valid @RequestBody BellScheduleRequest request) {
        return bells.save(request.workingDays(), Boolean.TRUE.equals(request.saturdaySchedule()),
                periods(request.weekday()), periods(request.saturday()));
    }

    private static List<PeriodInput> periods(List<PeriodRequest> rows) {
        return rows == null ? null : rows.stream()
                .map(r -> r == null ? null
                        : new PeriodInput(r.label(), r.startsAt(), r.endsAt(), Boolean.TRUE.equals(r.breakTime())))
                .toList();
    }

    // ------------------------------------------------------------------ teacher assignments

    @GetMapping("/assignments")
    @PreAuthorize(READ)
    public List<AssignmentView> assignments(@RequestParam(required = false) UUID sectionId,
            @RequestParam(required = false) UUID teacherId) {
        return timetable.assignments(sectionId, teacherId);
    }

    @PostMapping("/assignments")
    @PreAuthorize(MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public AssignmentView createAssignment(@Valid @RequestBody AssignmentRequest request) {
        return timetable.createAssignment(request.sectionId(), request.subjectId(), request.teacherId(),
                request.periodsPerWeek());
    }

    @PutMapping("/assignments/{id}")
    @PreAuthorize(MANAGE)
    public AssignmentSaved updateAssignment(@PathVariable UUID id, @Valid @RequestBody AssignmentUpdate request) {
        return timetable.updateAssignment(id, request.teacherId(), request.periodsPerWeek());
    }

    @DeleteMapping("/assignments/{id}")
    @PreAuthorize(MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAssignment(@PathVariable UUID id) {
        timetable.deleteAssignment(id);
    }

    // ------------------------------------------------------------------ section timetables

    @GetMapping("/sections")
    @PreAuthorize(READ)
    public SectionsOverview sections() {
        return timetable.overview(canManage());
    }

    @GetMapping("/sections/{sectionId}")
    @PreAuthorize(READ)
    public SectionTimetable section(@PathVariable UUID sectionId) {
        return timetable.section(sectionId, canManage());
    }

    /** Replaces the section's weekly timetable. 409 when a teacher would be in two sections at once. */
    @PutMapping("/sections/{sectionId}")
    @PreAuthorize(MANAGE)
    public SectionTimetable saveSection(@PathVariable UUID sectionId, @Valid @RequestBody SectionRequest request) {
        return timetable.saveSection(sectionId, request.slots().stream()
                .map(c -> new CellInput(c.day(), c.period(), c.subjectId(), c.teacherId(), c.room()))
                .toList());
    }

    /** Checks the whole school's timetables for clashes and subjects over their weekly allowance. */
    @GetMapping("/clashes")
    @PreAuthorize(MANAGE)
    public ClashReport clashes() {
        return timetable.clashReport();
    }

    // ------------------------------------------------------------------ teachers

    @GetMapping("/teachers")
    @PreAuthorize(READ)
    public List<TeacherSummary> teachers() {
        return timetable.teachers();
    }

    @GetMapping("/teachers/{teacherId}")
    @PreAuthorize(READ)
    public TeacherTimetable teacher(@PathVariable UUID teacherId) {
        return timetable.teacher(teacherId);
    }

    @GetMapping("/teachers/{teacherId}/day")
    @PreAuthorize(READ)
    public TeacherDay teacherDay(@PathVariable UUID teacherId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return substitutions.teacherDay(teacherId, date != null ? date : TimetableSupport.today());
    }

    /** The signed-in teacher's weekly timetable. */
    @GetMapping("/me")
    @PreAuthorize(READ)
    public TeacherTimetable mine() {
        return timetable.teacher(CurrentUser.requireId());
    }

    /** The signed-in teacher's day (today by default), with substitutions they cover or that cover them. */
    @GetMapping("/me/today")
    @PreAuthorize(READ)
    public TeacherDay myDay(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return substitutions.teacherDay(CurrentUser.requireId(), date != null ? date : TimetableSupport.today());
    }

    /** Teachers free in a period: on a date (absences and substitutions counted) or on a day of the week. */
    @GetMapping("/free-teachers")
    @PreAuthorize(READ)
    public FreeTeachers freeTeachers(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) DayOfWeek day, @RequestParam int period,
            @RequestParam(required = false) UUID subjectId) {
        return substitutions.freeTeachers(date, day, period, subjectId);
    }

    // ------------------------------------------------------------------ substitutions

    /** The substitution sheet of a day (today by default). */
    @GetMapping("/substitutions")
    @PreAuthorize(READ)
    public SubstitutionDay substitutions(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return substitutions.day(date != null ? date : TimetableSupport.today());
    }

    @PostMapping("/substitutions/absences")
    @PreAuthorize(MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public SubstitutionDay recordAbsence(@Valid @RequestBody AbsenceRequest request) {
        return substitutions.recordAbsence(request.date(), request.teacherId(), request.reason());
    }

    @DeleteMapping("/substitutions/absences/{id}")
    @PreAuthorize(MANAGE)
    public SubstitutionDay removeAbsence(@PathVariable UUID id) {
        return substitutions.removeAbsence(id);
    }

    @PutMapping("/substitutions")
    @PreAuthorize(MANAGE)
    public SubstitutionDay assign(@Valid @RequestBody SubstitutionRequest request) {
        return substitutions.assign(request.date(), request.sectionId(), request.period(), request.teacherId());
    }

    @DeleteMapping("/substitutions/{id}")
    @PreAuthorize(MANAGE)
    public SubstitutionDay removeSubstitution(@PathVariable UUID id) {
        return substitutions.removeSubstitution(id);
    }

    static boolean canManage() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> Permissions.TIMETABLE_MANAGE.equals(a.getAuthority()));
    }
}
