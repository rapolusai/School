package com.akshara.chat;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
import com.akshara.identity.UserAccount;
import com.akshara.identity.UserRepository;
import com.akshara.shared.Permissions;
import com.akshara.students.StudentRoster;
import com.akshara.students.StudentRoster.Family;
import com.akshara.students.StudentRoster.FamilyGuardian;
import com.akshara.students.StudentRoster.RosterStudent;
import com.akshara.timetable.TimetableDirectory;
import com.akshara.timetable.TimetableDirectory.SectionTeacher;
import com.akshara.timetable.TimetableDirectory.Teaching;

/**
 * Who is who for messages and meetings, read through other modules' public services only: students with their class
 * this year, parents' sign-ins linked to them, the class teacher and subject teachers of a section (school setup and
 * timetable), and people's names and whether they may still use messages. Phone numbers and email addresses that these
 * services return never leave this class except as the address of a notification.
 */
@Component
class ChatPeople {

    /** A student with their section this year, if any. */
    record StudentInfo(UUID id, String name, String classLabel, UUID sectionId, boolean active) {
    }

    /** A sign-in: name, whether it is active, and whether it holds chat.use. */
    record Person(UUID id, String name, String email, boolean active, boolean canChat) {
    }

    /** A teacher of a student: their class teacher, and/or a subject teacher with the subjects they teach. */
    record ChildTeacher(UUID teacherId, String name, boolean classTeacher, List<String> subjects) {

        /** "Class teacher · Mathematics, Science" */
        String detail() {
            List<String> parts = new ArrayList<>();
            if (classTeacher) {
                parts.add("Class teacher");
            }
            if (!subjects.isEmpty()) {
                parts.add(String.join(", ", subjects));
            }
            return String.join(" · ", parts);
        }
    }

    /** A parent with a sign-in, linked to a student. */
    record ParentOf(UUID studentId, UUID sectionId, UUID parentUserId, String parentName) {
    }

    /** Up to this many ids, sections are looked up one by one; above it, the whole year's families are read at once. */
    private static final int ONE_BY_ONE = 10;

    private final StudentRoster roster;
    private final AcademicsDirectory academics;
    private final AcademicsService academicsService;
    private final TimetableDirectory timetable;
    private final UserRepository users;

    ChatPeople(StudentRoster roster, AcademicsDirectory academics, AcademicsService academicsService,
            TimetableDirectory timetable, UserRepository users) {
        this.roster = roster;
        this.academics = academics;
        this.academicsService = academicsService;
        this.timetable = timetable;
        this.users = users;
    }

    Optional<YearInfo> currentYear() {
        return academics.currentYear();
    }

    /** Students by id with their class this year. Ids of another school are missing. */
    Map<UUID, StudentInfo> students(Collection<UUID> ids) {
        Map<UUID, StudentInfo> result = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return result;
        }
        UUID yearId = currentYear().map(YearInfo::id).orElse(null);
        Map<UUID, RosterStudent> found = roster.students(Set.copyOf(ids), yearId);
        Map<UUID, UUID> sectionOf = sectionsOfStudents(found.keySet(), yearId);
        Map<UUID, SectionInfo> sections = sectionsById();
        for (UUID id : ids) {
            RosterStudent s = found.get(id);
            if (s == null) {
                continue;
            }
            UUID sectionId = sectionOf.get(id);
            SectionInfo section = sectionId == null ? null : sections.get(sectionId);
            result.put(id, new StudentInfo(id, s.fullName(), section == null ? null : section.label(), sectionId,
                    s.active()));
        }
        return result;
    }

    /** Students' names by id (any status). Ids of another school are missing. */
    Map<UUID, String> studentNames(Collection<UUID> ids) {
        Map<UUID, String> result = new HashMap<>();
        if (!ids.isEmpty()) {
            roster.students(Set.copyOf(ids), null).forEach((id, s) -> result.put(id, s.fullName()));
        }
        return result;
    }

    Optional<StudentInfo> student(UUID id) {
        return Optional.ofNullable(students(List.of(id)).get(id));
    }

    /** Sign-ins by id. Ids of another school are missing. */
    Map<UUID, Person> people(Collection<UUID> ids) {
        Map<UUID, Person> result = new HashMap<>();
        if (ids.isEmpty()) {
            return result;
        }
        for (UserAccount u : users.findAllById(Set.copyOf(ids))) {
            result.put(u.getId(), new Person(u.getId(), u.getName(), u.getEmail(), u.isActive(),
                    u.isActive() && u.permissions().contains(Permissions.CHAT_USE)));
        }
        return result;
    }

    Optional<Person> person(UUID id) {
        return Optional.ofNullable(people(List.of(id)).get(id));
    }

    /** The students linked to a parent's sign-in (their guardian record). */
    Set<UUID> childrenOf(UUID parentUserId) {
        return roster.childIdsOf(parentUserId);
    }

    boolean isParentOf(UUID parentUserId, UUID studentId) {
        return childrenOf(parentUserId).contains(studentId);
    }

    /**
     * The teachers of a student this year who may receive messages (active, with chat.use): the class teacher of their
     * section and the subject teachers from the timetable. Class teacher first, then by name.
     */
    List<ChildTeacher> teachersOf(UUID studentId) {
        Optional<YearInfo> year = currentYear();
        if (year.isEmpty()) {
            return List.of();
        }
        Optional<UUID> sectionId = roster.sectionOf(studentId, year.get().id());
        if (sectionId.isEmpty()) {
            return List.of();
        }
        return teachersOfSection(sectionId.get(), year.get().id());
    }

    List<ChildTeacher> teachersOfSection(UUID sectionId, UUID yearId) {
        SectionInfo section = academics.section(sectionId).orElse(null);
        if (section == null) {
            return List.of();
        }
        Map<UUID, Set<UUID>> subjectsByTeacher = new LinkedHashMap<>();
        if (section.classTeacherId() != null) {
            subjectsByTeacher.put(section.classTeacherId(), new LinkedHashSet<>());
        }
        for (SectionTeacher t : timetable.teachersOf(sectionId, yearId)) {
            Set<UUID> subjects = subjectsByTeacher.computeIfAbsent(t.teacherId(), k -> new LinkedHashSet<>());
            if (t.subjectId() != null) {
                subjects.add(t.subjectId());
            }
        }
        Map<UUID, Person> people = people(subjectsByTeacher.keySet());
        Map<UUID, String> subjectNames = subjectNames();
        List<ChildTeacher> result = new ArrayList<>();
        subjectsByTeacher.forEach((teacherId, subjectIds) -> {
            Person p = people.get(teacherId);
            if (p == null || !p.canChat()) {
                return;
            }
            List<String> names = subjectIds.stream().map(subjectNames::get).filter(n -> n != null).sorted().toList();
            result.add(new ChildTeacher(teacherId, p.name(), teacherId.equals(section.classTeacherId()), names));
        });
        result.sort(Comparator.comparing((ChildTeacher t) -> !t.classTeacher()).thenComparing(ChildTeacher::name,
                String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    /**
     * Every teacher of a section this year with what they teach there, whether or not they use messages: the class
     * teacher first (with no subjects unless the timetable gives some), then the timetable's teachers.
     */
    Map<UUID, Set<UUID>> teachingInSection(UUID sectionId, UUID yearId) {
        Map<UUID, Set<UUID>> result = new LinkedHashMap<>();
        academics.section(sectionId).map(SectionInfo::classTeacherId)
                .ifPresent(id -> result.put(id, new LinkedHashSet<>()));
        for (SectionTeacher t : timetable.teachersOf(sectionId, yearId)) {
            Set<UUID> subjects = result.computeIfAbsent(t.teacherId(), k -> new LinkedHashSet<>());
            if (t.subjectId() != null) {
                subjects.add(t.subjectId());
            }
        }
        return result;
    }

    /**
     * The teachers a family of the section meets at a PTM: the class teacher, and the subject teachers when the
     * meeting includes them.
     */
    Set<UUID> ptmTeachersOf(UUID sectionId, UUID yearId, boolean includeSubjectTeachers) {
        UUID classTeacher = academics.section(sectionId).map(SectionInfo::classTeacherId).orElse(null);
        Set<UUID> result = new LinkedHashSet<>();
        for (UUID id : teachingInSection(sectionId, yearId).keySet()) {
            if (id.equals(classTeacher) || includeSubjectTeachers) {
                result.add(id);
            }
        }
        return result;
    }

    /** The name and mobile number of a student's primary parent or guardian, for a reminder. */
    record GuardianPhone(String name, String phone) {
    }

    Optional<GuardianPhone> primaryPhone(UUID studentId) {
        return Optional.ofNullable(roster.primaryGuardians(List.of(studentId)).get(studentId))
                .filter(g -> g.phone() != null && !g.phone().isBlank())
                .map(g -> new GuardianPhone(g.name(), g.phone()));
    }

    Optional<ChildTeacher> teacherOf(UUID studentId, UUID teacherId) {
        return teachersOf(studentId).stream().filter(t -> t.teacherId().equals(teacherId)).findFirst();
    }

    /** The sections a member of staff leads (class teacher) or teaches in the current year. */
    Set<UUID> sectionsOf(UUID staffUserId) {
        Set<UUID> result = new LinkedHashSet<>();
        academics.sections().stream().filter(s -> staffUserId.equals(s.classTeacherId()))
                .forEach(s -> result.add(s.id()));
        currentYear().ifPresent(y -> timetable.teachingOf(staffUserId, y.id()).stream().map(Teaching::sectionId)
                .forEach(result::add));
        return result;
    }

    /** The section a student is enrolled in this year, if any. */
    Optional<UUID> sectionOf(UUID studentId) {
        return currentYear().flatMap(y -> roster.sectionOf(studentId, y.id()));
    }

    /** Whether the member of staff teaches (or is class teacher of) the student's section this year. */
    boolean teaches(UUID staffUserId, UUID studentId) {
        Optional<UUID> section = sectionOf(studentId);
        return section.isPresent() && sectionsOf(staffUserId).contains(section.get());
    }

    /**
     * Parents with a sign-in of the active students in the sections (every section when null) this year, by student
     * and then parent name.
     */
    List<ParentOf> parents(Collection<UUID> sectionIds) {
        Optional<YearInfo> year = currentYear();
        if (year.isEmpty()) {
            return List.of();
        }
        List<ParentOf> result = new ArrayList<>();
        for (Family f : roster.families(year.get().id(), sectionIds)) {
            for (FamilyGuardian g : f.guardians()) {
                if (g.userId() != null) {
                    result.add(new ParentOf(f.studentId(), f.sectionId(), g.userId(), g.name()));
                }
            }
        }
        return result;
    }

    /** The parent's own mobile number from their guardian record, for a notification; empty when unknown. */
    Optional<String> parentPhone(UUID parentUserId, UUID studentId) {
        Optional<UUID> section = sectionOf(studentId);
        Optional<YearInfo> year = currentYear();
        if (section.isEmpty() || year.isEmpty()) {
            return Optional.empty();
        }
        return roster.families(year.get().id(), List.of(section.get())).stream()
                .filter(f -> f.studentId().equals(studentId))
                .flatMap(f -> f.guardians().stream())
                .filter(g -> parentUserId.equals(g.userId()))
                .map(FamilyGuardian::phone)
                .filter(p -> p != null && !p.isBlank())
                .findFirst();
    }

    /** Active students per section in the year. */
    Map<UUID, Long> activePerSection(UUID yearId) {
        return roster.activePerSection(yearId);
    }

    /** The active students of a section in the year, by name. */
    List<RosterStudent> activeInSection(UUID yearId, UUID sectionId) {
        return roster.activeInSection(yearId, sectionId);
    }

    Map<UUID, SectionInfo> sectionsById() {
        return academics.sections().stream().collect(Collectors.toMap(SectionInfo::id, s -> s, (a, b) -> a,
                LinkedHashMap::new));
    }

    Map<UUID, String> subjectNames() {
        return academicsService.subjects().stream().collect(Collectors.toMap(SubjectView::id, SubjectView::name));
    }

    private Map<UUID, UUID> sectionsOfStudents(Collection<UUID> studentIds, UUID yearId) {
        Map<UUID, UUID> result = new HashMap<>();
        if (yearId == null || studentIds.isEmpty()) {
            return result;
        }
        if (studentIds.size() <= ONE_BY_ONE) {
            for (UUID id : studentIds) {
                roster.sectionOf(id, yearId).ifPresent(s -> result.put(id, s));
            }
            return result;
        }
        for (Family f : roster.families(yearId, null)) {
            if (studentIds.contains(f.studentId())) {
                result.put(f.studentId(), f.sectionId());
            }
        }
        // Students who left are not in the active families; look them up one by one.
        for (UUID id : studentIds) {
            if (!result.containsKey(id)) {
                roster.sectionOf(id, yearId).ifPresent(s -> result.put(id, s));
            }
        }
        return result;
    }
}
