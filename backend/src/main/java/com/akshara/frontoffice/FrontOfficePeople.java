package com.akshara.frontoffice;

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
import com.akshara.frontoffice.FrontOfficeViews.StaffRef;
import com.akshara.frontoffice.FrontOfficeViews.StudentRef;
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
import com.akshara.students.StudentService.StudentQuery;
import com.akshara.students.StudentService.StudentRow;
import com.akshara.students.StudentStatus;

/**
 * The people the front office deals with, read through the students, staff and academics modules' public services
 * only: the staff a visitor may meet, students for gate passes with their class, class teacher and primary guardian,
 * and a parent's children. Nothing about them is stored here beyond ids and names.
 */
@Component
class FrontOfficePeople {

    private static final int MAX_STAFF_PAGES = 50;

    /** A student with the class they are in this year and the section's class teacher. */
    record Pupil(UUID id, String fullName, String admissionNo, boolean active, UUID sectionId, String className,
            UUID classTeacherUserId) {
    }

    private final StudentService students;
    private final StudentRoster roster;
    private final StaffService staff;
    private final AcademicsDirectory academics;

    FrontOfficePeople(StudentService students, StudentRoster roster, StaffService staff,
            AcademicsDirectory academics) {
        this.students = students;
        this.roster = roster;
        this.staff = staff;
        this.academics = academics;
    }

    /** Active staff, for "whom to meet" and "assigned to". */
    List<StaffRef> activeStaff() {
        List<StaffRef> result = new java.util.ArrayList<>();
        for (int page = 0; page < MAX_STAFF_PAGES; page++) {
            StaffPage found = staff.list(new StaffQuery(null, null, null, StaffStatus.ACTIVE, null, null, page,
                    StaffService.MAX_PAGE_SIZE));
            found.items().forEach(r -> result.add(new StaffRef(r.userId(), r.name(), r.designation())));
            if ((long) (page + 1) * StaffService.MAX_PAGE_SIZE >= found.total()) {
                break;
            }
        }
        return result;
    }

    /** A member of staff for a body field: 400 when the id is not one of this school's staff. */
    StaffRef requireStaff(UUID userId, String field) {
        return activeStaff().stream().filter(s -> s.id().equals(userId)).findFirst()
                .orElseThrow(() -> ApiException.badRequest("Pick a member of staff.", field));
    }

    /** Active students whose name or admission number contains the text. */
    List<StudentRef> searchStudents(String text, int limit) {
        String q = text == null ? "" : text.trim();
        if (q.isEmpty()) {
            return List.of();
        }
        return students.list(new StudentQuery(null, null, null, StudentStatus.ACTIVE, q, 0,
                Math.min(Math.max(limit, 1), 50))).items().stream()
                .map(r -> new StudentRef(r.id(), r.fullName(), r.admissionNo(), label(r)))
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

    /** The primary guardians of many students (those who have one). */
    Map<UUID, GuardianContact> primaryGuardians(Collection<UUID> studentIds) {
        if (studentIds.isEmpty()) {
            return Map.of();
        }
        return new HashMap<>(roster.primaryGuardians(Set.copyOf(studentIds)));
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

    Optional<StaffRef> staffMember(UUID userId) {
        return activeStaff().stream().filter(s -> s.id().equals(userId)).findFirst();
    }
}
