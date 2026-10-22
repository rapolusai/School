package com.akshara.health;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
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
import com.akshara.health.HealthViews.PersonRef;
import com.akshara.shared.ApiException;
import com.akshara.staff.StaffService;
import com.akshara.staff.StaffService.StaffPage;
import com.akshara.staff.StaffService.StaffQuery;
import com.akshara.staff.StaffService.StaffRow;
import com.akshara.staff.StaffStatus;
import com.akshara.students.StudentRoster;
import com.akshara.students.StudentRoster.GuardianContact;
import com.akshara.students.StudentRoster.RosterStudent;
import com.akshara.students.StudentService;
import com.akshara.students.StudentService.StudentDetail;
import com.akshara.students.StudentService.StudentQuery;
import com.akshara.students.StudentService.StudentRow;
import com.akshara.students.StudentStatus;

/**
 * The people the health module deals with, read through the students, staff and academics modules' public services
 * only: students with their class, class teacher and primary guardian (the emergency contact), a parent's children,
 * and the staff (for infirmary visits, verifications, trainings and the committee). Nothing about them is stored here
 * beyond ids and names.
 */
@Component
class HealthPeople {

    private static final int MAX_STAFF_PAGES = 50;

    /** A student with the class they are in this year and the section's class teacher. */
    record Pupil(UUID id, String fullName, String admissionNo, boolean active, UUID sectionId, String className,
            UUID classTeacherUserId) {
    }

    /** A member of staff, active or left. */
    record Staff(UUID userId, String name, String designation, List<String> roles, boolean active,
            LocalDate dateOfJoining) {
    }

    private final StudentService students;
    private final StudentRoster roster;
    private final StaffService staff;
    private final AcademicsDirectory academics;

    HealthPeople(StudentService students, StudentRoster roster, StaffService staff, AcademicsDirectory academics) {
        this.students = students;
        this.roster = roster;
        this.staff = staff;
        this.academics = academics;
    }

    /** Everyone on the staff, active first, by name. */
    List<Staff> allStaff() {
        List<Staff> result = new ArrayList<>();
        for (int page = 0; page < MAX_STAFF_PAGES; page++) {
            StaffPage found = staff.list(new StaffQuery(null, null, null, null, null, null, page,
                    StaffService.MAX_PAGE_SIZE));
            found.items().forEach(r -> result.add(staffOf(r)));
            if ((long) (page + 1) * StaffService.MAX_PAGE_SIZE >= found.total()) {
                break;
            }
        }
        result.sort((a, b) -> a.active() == b.active() ? a.name().compareToIgnoreCase(b.name())
                : (a.active() ? -1 : 1));
        return result;
    }

    List<Staff> activeStaff() {
        return allStaff().stream().filter(Staff::active).toList();
    }

    private static Staff staffOf(StaffRow r) {
        return new Staff(r.userId(), r.name(), r.designation(), r.roles(), r.status() == StaffStatus.ACTIVE,
                r.dateOfJoining());
    }

    /** A member of staff for a body field: 400 when the id is not one of this school's staff. */
    Staff requireStaff(UUID userId, String field) {
        return allStaff().stream().filter(s -> s.userId().equals(userId)).findFirst()
                .orElseThrow(() -> ApiException.badRequest("Pick a member of staff.", field));
    }

    /** A member of staff for a path id: 404 when the id is not one of this school's staff. */
    Staff requireStaff(UUID userId) {
        return allStaff().stream().filter(s -> s.userId().equals(userId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Staff member"));
    }

    /** Active students whose name or admission number contains the text. */
    List<PersonRef> searchStudents(String text, int limit) {
        String q = text == null ? "" : text.trim();
        if (q.isEmpty()) {
            return List.of();
        }
        return students.list(new StudentQuery(null, null, null, StudentStatus.ACTIVE, q, 0,
                Math.min(Math.max(limit, 1), 50))).items().stream()
                .map(r -> new PersonRef(r.id(), PersonKind.STUDENT, r.fullName(), r.admissionNo(), label(r)))
                .toList();
    }

    /** Active staff whose name contains the text. */
    List<PersonRef> searchStaff(String text, int limit) {
        String q = text == null ? "" : text.trim().toLowerCase();
        if (q.isEmpty()) {
            return List.of();
        }
        return activeStaff().stream().filter(s -> s.name().toLowerCase().contains(q)).limit(limit)
                .map(s -> new PersonRef(s.userId(), PersonKind.STAFF, s.name(), null, s.designation()))
                .toList();
    }

    private static String label(StudentRow r) {
        if (r.className() == null) {
            return null;
        }
        return r.sectionName() == null ? r.className() : r.className() + " " + r.sectionName();
    }

    /** The student behind a body field: 400 when they are not an active student of this school. */
    Pupil requireActivePupil(UUID studentId, String field) {
        Pupil p = pupils(List.of(studentId)).get(studentId);
        if (p == null) {
            throw ApiException.badRequest("Pick a student of this school.", field);
        }
        if (!p.active()) {
            throw ApiException.badRequest(p.fullName() + " is no longer an active student.", field);
        }
        return p;
    }

    /** The student behind a path id: 404 when they are not a student of this school. */
    Pupil requirePupil(UUID studentId) {
        Pupil p = pupils(List.of(studentId)).get(studentId);
        if (p == null) {
            throw ApiException.notFound("Student");
        }
        return p;
    }

    /** The student's date of birth (the growth chart's age axis). */
    LocalDate dateOfBirth(UUID studentId) {
        StudentDetail detail = students.detail(studentId);
        return detail.dateOfBirth();
    }

    /** Students by id with their class this year. Ids that are not this school's students are missing. */
    Map<UUID, Pupil> pupils(Collection<UUID> studentIds) {
        Map<UUID, Pupil> result = new LinkedHashMap<>();
        if (studentIds.isEmpty()) {
            return result;
        }
        UUID yearId = academics.currentYear().map(YearInfo::id).orElse(null);
        Set<UUID> ids = Set.copyOf(studentIds);
        Map<UUID, RosterStudent> found = roster.students(ids, yearId);
        Map<UUID, SectionInfo> sections = academics.sections().stream()
                .collect(Collectors.toMap(SectionInfo::id, s -> s));
        for (UUID id : studentIds) {
            RosterStudent s = found.get(id);
            if (s == null || result.containsKey(id)) {
                continue;
            }
            SectionInfo section = yearId == null ? null
                    : roster.sectionOf(id, yearId).map(sections::get).orElse(null);
            result.put(id, new Pupil(id, s.fullName(), s.admissionNo(), s.active(),
                    section == null ? null : section.id(), section == null ? null : section.label(),
                    section == null ? null : section.classTeacherId()));
        }
        return result;
    }

    /** Every active student of the current year with their section: the allergies-by-class report. */
    Map<UUID, SectionInfo> sectionsOfActiveStudents() {
        Optional<YearInfo> year = academics.currentYear();
        if (year.isEmpty()) {
            return Map.of();
        }
        Map<UUID, SectionInfo> result = new HashMap<>();
        for (SectionInfo section : academics.sections()) {
            roster.activeInSection(year.get().id(), section.id()).forEach(s -> result.put(s.id(), section));
        }
        return result;
    }

    List<SectionInfo> sections() {
        return academics.sections();
    }

    /** The primary guardian of a student (the emergency contact), if any. */
    Optional<GuardianContact> primaryGuardian(UUID studentId) {
        return Optional.ofNullable(roster.primaryGuardians(List.of(studentId)).get(studentId));
    }

    /** The children of a parent's sign-in (their guardian record's students). */
    Set<UUID> childrenOf(UUID parentUserId) {
        return roster.childIdsOf(parentUserId);
    }

    /** The child in a path, for a parent: 404 unless the student is one of their children. */
    Pupil requireChild(UUID parentUserId, UUID studentId) {
        if (!childrenOf(parentUserId).contains(studentId)) {
            throw ApiException.notFound("Student");
        }
        return requirePupil(studentId);
    }

    /** The sections the user is class teacher of this year. */
    Set<UUID> sectionsLedBy(UUID userId) {
        return academics.sections().stream().filter(s -> userId.equals(s.classTeacherId())).map(SectionInfo::id)
                .collect(Collectors.toSet());
    }
}
