package com.akshara.academics;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.academics.AcademicsDirectory.SectionInfo;
import com.akshara.academics.AcademicsDirectory.YearInfo;
import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.identity.UserAccount;
import com.akshara.identity.UserRepository;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;

/**
 * School setup: academic years, classes, sections and subjects of the current school. Every change is written to the
 * audit trail in the same transaction. Records that other data still refers to cannot be deleted.
 */
@Service
@Transactional
public class AcademicsService {

    static final String TEACHER_ROLE = "TEACHER";

    public record TeacherRef(UUID id, String name) {
    }

    public record SubjectRef(UUID id, String name, String code) {

        static SubjectRef of(Subject s) {
            return new SubjectRef(s.getId(), s.getName(), s.getCode());
        }
    }

    public record SectionView(UUID id, UUID classId, String className, String name, Integer capacity,
            TeacherRef classTeacher, long studentCount) {
    }

    public record ClassView(UUID id, String name, int displayOrder, List<SectionView> sections,
            List<SubjectRef> subjects) {
    }

    public record SubjectView(UUID id, String name, String code, int classCount) {
    }

    private final AcademicYearRepository years;
    private final SchoolClassRepository classes;
    private final SectionRepository sections;
    private final SubjectRepository subjects;
    private final ClassSubjectRepository classSubjects;
    private final AcademicsDirectory directory;
    private final UserRepository users;
    private final AuditService audit;
    private final List<AcademicsUsage> usages;

    public AcademicsService(AcademicYearRepository years, SchoolClassRepository classes, SectionRepository sections,
            SubjectRepository subjects, ClassSubjectRepository classSubjects, AcademicsDirectory directory,
            UserRepository users, AuditService audit, List<AcademicsUsage> usages) {
        this.years = years;
        this.classes = classes;
        this.sections = sections;
        this.subjects = subjects;
        this.classSubjects = classSubjects;
        this.directory = directory;
        this.users = users;
        this.audit = audit;
        this.usages = usages;
    }

    // ------------------------------------------------------------------ academic years

    @Transactional(readOnly = true)
    public List<YearInfo> years() {
        return directory.years();
    }

    /** Adds a year. The school's first year becomes current automatically. */
    public YearInfo createYear(String name, LocalDate startsOn, LocalDate endsOn, boolean makeCurrent, Actor actor) {
        TenantContext.require();
        String cleanName = name.trim();
        checkYear(null, cleanName, startsOn, endsOn);
        AcademicYear year = new AcademicYear(cleanName, startsOn, endsOn);
        boolean first = years.findByCurrentTrue().isEmpty();
        if (makeCurrent && !first) {
            years.findByCurrentTrue().ifPresent(previous -> previous.setCurrent(false));
            years.flush();
        }
        year.setCurrent(makeCurrent || first);
        years.saveAndFlush(year);
        record(actor, "academic_year.created", "academic_year", year.getId(), Map.of("name", cleanName,
                "startsOn", startsOn.toString(), "endsOn", endsOn.toString(), "current", year.isCurrent()));
        return YearInfo.of(year);
    }

    public YearInfo updateYear(UUID id, String name, LocalDate startsOn, LocalDate endsOn) {
        TenantContext.require();
        AcademicYear year = years.findById(id).orElseThrow(() -> ApiException.notFound("Academic year"));
        String cleanName = name.trim();
        checkYear(id, cleanName, startsOn, endsOn);
        year.update(cleanName, startsOn, endsOn);
        years.saveAndFlush(year);
        record(null, "academic_year.updated", "academic_year", id, Map.of("name", cleanName,
                "startsOn", startsOn.toString(), "endsOn", endsOn.toString()));
        return YearInfo.of(year);
    }

    /** Makes the year current; the previous current year stops being current. */
    public YearInfo setCurrentYear(UUID id, Actor actor) {
        TenantContext.require();
        AcademicYear year = years.findById(id).orElseThrow(() -> ApiException.notFound("Academic year"));
        if (!year.isCurrent()) {
            years.findByCurrentTrue().ifPresent(previous -> previous.setCurrent(false));
            years.flush();
            year.setCurrent(true);
            years.saveAndFlush(year);
            record(actor, "academic_year.set_current", "academic_year", id, Map.of("name", year.getName()));
        }
        return YearInfo.of(year);
    }

    public void deleteYear(UUID id) {
        TenantContext.require();
        AcademicYear year = years.findById(id).orElseThrow(() -> ApiException.notFound("Academic year"));
        if (year.isCurrent()) {
            throw inUse("This is the current academic year. Make another year current before deleting it.");
        }
        long used = usages.stream().mapToLong(u -> u.yearUseCount(id)).sum();
        if (used > 0) {
            throw inUse("Students are enrolled in " + year.getName() + ", so it cannot be deleted.");
        }
        years.delete(year);
        years.flush();
        record(null, "academic_year.deleted", "academic_year", id, Map.of("name", year.getName()));
    }

    private void checkYear(UUID id, String name, LocalDate startsOn, LocalDate endsOn) {
        if (!endsOn.isAfter(startsOn)) {
            throw ApiException.badRequest("The end date must be after the start date.", "endsOn");
        }
        if (years.existsByNameExcept(name, id == null ? new UUID(0, 0) : id)) {
            throw ApiException.conflict("There is already an academic year called " + name + ".", "name");
        }
        years.findAll().stream()
                .filter(other -> !other.getId().equals(id) && other.overlaps(startsOn, endsOn))
                .findFirst()
                .ifPresent(other -> {
                    throw ApiException.conflict("These dates overlap " + other.getName() + " ("
                            + other.getStartsOn() + " to " + other.getEndsOn() + ").", "startsOn");
                });
    }

    // ------------------------------------------------------------------ classes

    /** Every class in display order, with its sections (and current-year headcounts) and subjects. */
    @Transactional(readOnly = true)
    public List<ClassView> classes() {
        TenantContext.require();
        List<SchoolClass> all = classes.findAllOrdered();
        Map<UUID, List<SectionView>> sectionsByClass = sectionViews(directory.sections()).stream()
                .collect(Collectors.groupingBy(SectionView::classId, LinkedHashMap::new, Collectors.toList()));
        Map<UUID, Subject> subjectById = subjects.findAll().stream()
                .collect(Collectors.toMap(Subject::getId, Function.identity()));
        Map<UUID, List<SubjectRef>> subjectsByClass = new HashMap<>();
        for (ClassSubject cs : classSubjects.findAll()) {
            Subject subject = subjectById.get(cs.getSubjectId());
            if (subject != null) {
                subjectsByClass.computeIfAbsent(cs.getClassId(), k -> new ArrayList<>()).add(SubjectRef.of(subject));
            }
        }
        subjectsByClass.values().forEach(list -> list.sort(Comparator.comparing(s -> s.name().toLowerCase())));
        return all.stream()
                .map(c -> new ClassView(c.getId(), c.getName(), c.getDisplayOrder(),
                        sectionsByClass.getOrDefault(c.getId(), List.of()),
                        subjectsByClass.getOrDefault(c.getId(), List.of())))
                .toList();
    }

    @Transactional(readOnly = true)
    public ClassView classView(UUID id) {
        TenantContext.require();
        classes.findById(id).orElseThrow(() -> ApiException.notFound("Class"));
        return classes().stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }

    public ClassView createClass(String name, Integer displayOrder, Actor actor) {
        TenantContext.require();
        String cleanName = name.trim();
        if (classes.existsByNameExcept(cleanName, new UUID(0, 0))) {
            throw ApiException.conflict("There is already a class called " + cleanName + ".", "name");
        }
        int order = displayOrder != null ? displayOrder : classes.maxDisplayOrder() + 1;
        SchoolClass created = classes.saveAndFlush(new SchoolClass(cleanName, order));
        record(actor, "class.created", "class", created.getId(), Map.of("name", cleanName, "displayOrder", order));
        return new ClassView(created.getId(), cleanName, order, List.of(), List.of());
    }

    /** Renames or reorders a class. A null display order keeps the current one. */
    public ClassView updateClass(UUID id, String name, Integer displayOrder) {
        TenantContext.require();
        SchoolClass schoolClass = classes.findById(id).orElseThrow(() -> ApiException.notFound("Class"));
        String cleanName = name.trim();
        if (classes.existsByNameExcept(cleanName, id)) {
            throw ApiException.conflict("There is already a class called " + cleanName + ".", "name");
        }
        int order = displayOrder != null ? displayOrder : schoolClass.getDisplayOrder();
        schoolClass.update(cleanName, order);
        classes.saveAndFlush(schoolClass);
        record(null, "class.updated", "class", id, Map.of("name", cleanName, "displayOrder", order));
        return classView(id);
    }

    public void deleteClass(UUID id) {
        TenantContext.require();
        SchoolClass schoolClass = classes.findById(id).orElseThrow(() -> ApiException.notFound("Class"));
        long sectionCount = sections.countByClassId(id);
        if (sectionCount > 0) {
            throw inUse(schoolClass.getName() + " still has " + sectionCount
                    + (sectionCount == 1 ? " section" : " sections") + ". Delete them first.");
        }
        classSubjects.deleteByClassId(id);
        classes.delete(schoolClass);
        classes.flush();
        record(null, "class.deleted", "class", id, Map.of("name", schoolClass.getName()));
    }

    // ------------------------------------------------------------------ sections

    @Transactional(readOnly = true)
    public List<SectionView> sections(UUID classId) {
        TenantContext.require();
        classes.findById(classId).orElseThrow(() -> ApiException.notFound("Class"));
        return sectionViews(directory.sectionsOfClass(classId));
    }

    public SectionView createSection(UUID classId, String name, Integer capacity, UUID classTeacherId, Actor actor) {
        TenantContext.require();
        SchoolClass schoolClass = classes.findById(classId).orElseThrow(() -> ApiException.notFound("Class"));
        String cleanName = name.trim();
        if (sections.existsByNameExcept(classId, cleanName, new UUID(0, 0))) {
            throw ApiException.conflict(schoolClass.getName() + " already has a section " + cleanName + ".", "name");
        }
        checkTeacher(classTeacherId);
        Section section = sections.saveAndFlush(new Section(classId, cleanName, capacity, classTeacherId));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("class", schoolClass.getName());
        details.put("name", cleanName);
        if (capacity != null) {
            details.put("capacity", capacity);
        }
        record(actor, "section.created", "section", section.getId(), details);
        return sectionView(section.getId());
    }

    public SectionView updateSection(UUID id, String name, Integer capacity, UUID classTeacherId) {
        TenantContext.require();
        Section section = sections.findById(id).orElseThrow(() -> ApiException.notFound("Section"));
        String cleanName = name.trim();
        if (sections.existsByNameExcept(section.getClassId(), cleanName, id)) {
            throw ApiException.conflict("This class already has a section " + cleanName + ".", "name");
        }
        checkTeacher(classTeacherId);
        if (capacity != null) {
            long enrolled = currentEnrolment(id);
            if (enrolled > capacity) {
                throw ApiException.badRequest(enrolled + " students are already enrolled this year. "
                        + "Set a capacity of at least " + enrolled + ".", "capacity");
            }
        }
        section.update(cleanName, capacity, classTeacherId);
        sections.saveAndFlush(section);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("name", cleanName);
        details.put("capacity", capacity);
        details.put("classTeacherId", classTeacherId);
        record(null, "section.updated", "section", id, details);
        return sectionView(id);
    }

    public void deleteSection(UUID id) {
        TenantContext.require();
        Section section = sections.findById(id).orElseThrow(() -> ApiException.notFound("Section"));
        long used = usages.stream().mapToLong(u -> u.sectionUseCount(id)).sum();
        if (used > 0) {
            throw inUse("Students are enrolled in this section, so it cannot be deleted.");
        }
        sections.delete(section);
        sections.flush();
        record(null, "section.deleted", "section", id, Map.of("name", section.getName()));
    }

    private SectionView sectionView(UUID id) {
        return sectionViews(List.of(directory.section(id).orElseThrow())).getFirst();
    }

    private List<SectionView> sectionViews(List<SectionInfo> infos) {
        Map<UUID, Long> counts = years.findByCurrentTrue()
                .map(year -> enrolledPerSection(year.getId()))
                .orElse(Map.of());
        return infos.stream()
                .map(s -> new SectionView(s.id(), s.classId(), s.className(), s.name(), s.capacity(),
                        s.classTeacherId() == null ? null : new TeacherRef(s.classTeacherId(), s.classTeacherName()),
                        counts.getOrDefault(s.id(), 0L)))
                .toList();
    }

    private Map<UUID, Long> enrolledPerSection(UUID yearId) {
        Map<UUID, Long> total = new HashMap<>();
        usages.forEach(u -> u.enrolledPerSection(yearId).forEach((k, v) -> total.merge(k, v, Long::sum)));
        return total;
    }

    private long currentEnrolment(UUID sectionId) {
        return years.findByCurrentTrue()
                .map(year -> enrolledPerSection(year.getId()).getOrDefault(sectionId, 0L))
                .orElse(0L);
    }

    /** A class teacher must be an active person in this school who holds the Teacher role. */
    private void checkTeacher(UUID teacherId) {
        if (teacherId == null) {
            return;
        }
        boolean ok = users.findByIdWithRoles(teacherId)
                .filter(UserAccount::isActive)
                .filter(u -> u.roleCodes().contains(TEACHER_ROLE))
                .isPresent();
        if (!ok) {
            throw ApiException.badRequest("Pick a teacher from this school.", "classTeacherId");
        }
    }

    /** Active people with the Teacher role, for the class teacher picker. */
    @Transactional(readOnly = true)
    public List<TeacherRef> teachers() {
        TenantContext.require();
        return users.findAllWithRoles().stream()
                .filter(UserAccount::isActive)
                .filter(u -> u.roleCodes().contains(TEACHER_ROLE))
                .map(u -> new TeacherRef(u.getId(), u.getName()))
                .toList();
    }

    // ------------------------------------------------------------------ subjects

    @Transactional(readOnly = true)
    public List<SubjectView> subjects() {
        TenantContext.require();
        Map<UUID, Long> classCounts = classSubjects.findAll().stream()
                .collect(Collectors.groupingBy(ClassSubject::getSubjectId, Collectors.counting()));
        return subjects.findAllOrdered().stream()
                .map(s -> new SubjectView(s.getId(), s.getName(), s.getCode(),
                        classCounts.getOrDefault(s.getId(), 0L).intValue()))
                .toList();
    }

    public SubjectView createSubject(String name, String code, Actor actor) {
        TenantContext.require();
        String cleanName = name.trim();
        String cleanCode = blankToNull(code);
        checkSubject(null, cleanName, cleanCode);
        Subject subject = subjects.saveAndFlush(new Subject(cleanName, cleanCode));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("name", cleanName);
        if (cleanCode != null) {
            details.put("code", cleanCode);
        }
        record(actor, "subject.created", "subject", subject.getId(), details);
        return new SubjectView(subject.getId(), cleanName, cleanCode, 0);
    }

    public SubjectView updateSubject(UUID id, String name, String code) {
        TenantContext.require();
        Subject subject = subjects.findById(id).orElseThrow(() -> ApiException.notFound("Subject"));
        String cleanName = name.trim();
        String cleanCode = blankToNull(code);
        checkSubject(id, cleanName, cleanCode);
        subject.update(cleanName, cleanCode);
        subjects.saveAndFlush(subject);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("name", cleanName);
        details.put("code", cleanCode);
        record(null, "subject.updated", "subject", id, details);
        return new SubjectView(id, cleanName, cleanCode, (int) classSubjects.countBySubjectId(id));
    }

    public void deleteSubject(UUID id) {
        TenantContext.require();
        Subject subject = subjects.findById(id).orElseThrow(() -> ApiException.notFound("Subject"));
        long mapped = classSubjects.countBySubjectId(id);
        if (mapped > 0) {
            throw inUse(subject.getName() + " is taught in " + mapped + (mapped == 1 ? " class" : " classes")
                    + ". Remove it from those classes first.");
        }
        subjects.delete(subject);
        subjects.flush();
        record(null, "subject.deleted", "subject", id, Map.of("name", subject.getName()));
    }

    private void checkSubject(UUID id, String name, String code) {
        UUID except = id == null ? new UUID(0, 0) : id;
        if (subjects.existsByNameExcept(name, except)) {
            throw ApiException.conflict("There is already a subject called " + name + ".", "name");
        }
        if (code != null && subjects.existsByCodeExcept(code, except)) {
            throw ApiException.conflict("Another subject already uses the code " + code + ".", "code");
        }
    }

    @Transactional(readOnly = true)
    public List<SubjectRef> classSubjects(UUID classId) {
        TenantContext.require();
        classes.findById(classId).orElseThrow(() -> ApiException.notFound("Class"));
        return subjectsOf(classId);
    }

    /** Replaces the subjects a class studies with exactly the given ones. */
    public List<SubjectRef> setClassSubjects(UUID classId, Collection<UUID> subjectIds, Actor actor) {
        TenantContext.require();
        SchoolClass schoolClass = classes.findById(classId).orElseThrow(() -> ApiException.notFound("Class"));
        Set<UUID> wanted = new LinkedHashSet<>(subjectIds);
        List<Subject> found = subjects.findAllById(wanted);
        if (found.size() != wanted.size()) {
            throw ApiException.badRequest("Pick subjects from this school's list.", "subjectIds");
        }
        Map<UUID, ClassSubject> existing = classSubjects.findByClassId(classId).stream()
                .collect(Collectors.toMap(ClassSubject::getSubjectId, Function.identity()));
        List<ClassSubject> removed = existing.values().stream().filter(cs -> !wanted.contains(cs.getSubjectId()))
                .toList();
        classSubjects.deleteAll(removed);
        List<ClassSubject> added = wanted.stream().filter(sid -> !existing.containsKey(sid))
                .map(sid -> new ClassSubject(classId, sid)).toList();
        classSubjects.saveAll(added);
        classSubjects.flush();
        if (!removed.isEmpty() || !added.isEmpty()) {
            record(actor, "class.subjects_updated", "class", classId, Map.of("class", schoolClass.getName(),
                    "subjects", found.stream().map(Subject::getName).sorted().toList()));
        }
        return subjectsOf(classId);
    }

    private List<SubjectRef> subjectsOf(UUID classId) {
        List<UUID> ids = classSubjects.findByClassId(classId).stream().map(ClassSubject::getSubjectId).toList();
        return subjects.findAllById(ids).stream()
                .map(SubjectRef::of)
                .sorted(Comparator.comparing(s -> s.name().toLowerCase()))
                .toList();
    }

    // ------------------------------------------------------------------ helpers

    private void record(Actor actor, String action, String entityType, UUID entityId, Map<String, ?> details) {
        if (actor == null) {
            audit.record(action, entityType, entityId, details);
        } else {
            audit.record(actor, action, entityType, entityId, details);
        }
    }

    private static ApiException inUse(String detail) {
        return new ApiException(HttpStatus.CONFLICT, "In use", detail);
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
