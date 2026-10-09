package com.akshara.timetable;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.akshara.academics.AcademicsDirectory;
import com.akshara.academics.AcademicsDirectory.SectionInfo;
import com.akshara.academics.AcademicsDirectory.YearInfo;
import com.akshara.academics.AcademicsService;
import com.akshara.academics.AcademicsService.SubjectView;
import com.akshara.academics.AcademicsService.TeacherRef;
import com.akshara.identity.UserAccount;
import com.akshara.identity.UserRepository;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;
import com.akshara.timetable.ClashDetector.Cell;
import com.akshara.timetable.ClashDetector.Conflict;
import com.akshara.timetable.TimetableViews.Clash;
import com.akshara.timetable.TimetableViews.ClashEntry;
import com.akshara.timetable.TimetableViews.SlotView;

/** What the timetable services share: the school's year, bell schedule, names for display and the write lock. */
@Component
class TimetableSupport {

    static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    /** First half of the advisory lock key, so timetable locks never collide with another feature's. */
    private static final int LOCK_AREA = 0x54540001;

    private final TimetableSettingsRepository settings;
    private final PeriodRepository periods;
    private final SlotRepository slots;
    private final AcademicsDirectory academics;
    private final AcademicsService academicsService;
    private final UserRepository users;

    TimetableSupport(TimetableSettingsRepository settings, PeriodRepository periods, SlotRepository slots,
            AcademicsDirectory academics, AcademicsService academicsService, UserRepository users) {
        this.settings = settings;
        this.periods = periods;
        this.slots = slots;
        this.academics = academics;
        this.academicsService = academicsService;
        this.users = users;
    }

    static LocalDate today() {
        return LocalDate.now(INDIA);
    }

    /** Holds this school's timetable until the transaction ends. */
    void lock() {
        slots.lockSchool(LOCK_AREA, TenantContext.require().hashCode());
    }

    Bells bells() {
        TenantContext.require();
        Optional<TimetableSettings> s = settings.findFirstBy();
        return new Bells(s.map(TimetableSettings::getWorkingDays).orElse(List.copyOf(TimetableSettings.DEFAULT_DAYS)),
                s.map(TimetableSettings::isSaturdaySchedule).orElse(false), periods.findAll());
    }

    Optional<YearInfo> currentYearIfAny() {
        return academics.currentYear();
    }

    YearInfo currentYear() {
        return academics.currentYear().orElseThrow(() -> ApiException.badRequest(
                "Set up the current academic year in School setup first.", "academicYear"));
    }

    /** A section in the path: another school's (or a missing) section is 404. */
    SectionInfo section(UUID id) {
        return academics.section(id).orElseThrow(() -> ApiException.notFound("Section"));
    }

    /** Every section of the school in class order. */
    Map<UUID, SectionInfo> sections() {
        return academics.sections().stream()
                .collect(Collectors.toMap(SectionInfo::id, s -> s, (a, b) -> a, LinkedHashMap::new));
    }

    Map<UUID, String> subjectNames() {
        return academicsService.subjects().stream()
                .collect(Collectors.toMap(SubjectView::id, SubjectView::name, (a, b) -> a, LinkedHashMap::new));
    }

    /** The subjects a class studies, as set up in School setup. */
    Set<UUID> classSubjects(UUID classId) {
        return academicsService.classSubjects(classId).stream().map(AcademicsService.SubjectRef::id)
                .collect(Collectors.toSet());
    }

    /** Active people with the Teacher role, by name. */
    List<TeacherRef> activeTeachers() {
        return academicsService.teachers();
    }

    /** A teacher given in a request body must be an active teacher of this school. */
    void checkTeacher(UUID teacherId, String field) {
        boolean ok = teacherId != null && activeTeachers().stream().anyMatch(t -> t.id().equals(teacherId));
        if (!ok) {
            throw ApiException.badRequest("Pick a teacher from this school.", field);
        }
    }

    Map<UUID, String> userNames(Collection<UUID> ids) {
        Set<UUID> wanted = new HashSet<>(ids);
        wanted.remove(null);
        if (wanted.isEmpty()) {
            return Map.of();
        }
        return users.findAllById(wanted).stream()
                .collect(Collectors.toMap(UserAccount::getId, UserAccount::getName));
    }

    /** Names for display, loaded once per request. */
    Names names(Collection<UUID> teacherIds) {
        return new Names(sections(), subjectNames(), userNames(teacherIds));
    }

    record Names(Map<UUID, SectionInfo> sections, Map<UUID, String> subjects, Map<UUID, String> teachers) {

        String section(UUID id) {
            SectionInfo s = sections.get(id);
            return s == null ? null : s.label();
        }

        String subject(UUID id) {
            return id == null ? null : subjects.get(id);
        }

        String teacher(UUID id) {
            return id == null ? null : teachers.get(id);
        }

        SlotView slot(Slot s) {
            return new SlotView(s.getDay(), s.getPeriodNo(), s.getSubjectId(), subject(s.getSubjectId()),
                    s.getTeacherId(), teacher(s.getTeacherId()), s.getRoom());
        }

        Clash clash(Conflict c) {
            List<ClashEntry> entries = c.cells().stream()
                    .map(cell -> new ClashEntry(cell.sectionId(), section(cell.sectionId()), cell.subjectId(),
                            subject(cell.subjectId()), cell.teacherId(), teacher(cell.teacherId())))
                    .toList();
            return new Clash(c.kind().name(), c.day(), c.period(), c.teacherId(), teacher(c.teacherId()),
                    c.sectionId(), section(c.sectionId()), entries);
        }
    }

    static Cell cell(Slot s) {
        return new Cell(s.getSectionId(), s.getDay(), s.getPeriodNo(), s.getSubjectId(), s.getTeacherId());
    }
}
