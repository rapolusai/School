package com.akshara.transport;

import java.util.ArrayList;
import java.util.Collection;
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
import com.akshara.notifications.RecipientMask;
import com.akshara.platform.TenantDirectory;
import com.akshara.shared.TenantContext;
import com.akshara.staff.StaffService;
import com.akshara.staff.StaffService.StaffPage;
import com.akshara.staff.StaffService.StaffQuery;
import com.akshara.staff.StaffService.StaffRow;
import com.akshara.staff.StaffStatus;
import com.akshara.students.StudentRoster;
import com.akshara.students.StudentRoster.GuardianContact;
import com.akshara.students.StudentRoster.RosterStudent;
import com.akshara.students.StudentService;
import com.akshara.students.StudentService.ChildView;
import com.akshara.students.StudentService.StudentPage;
import com.akshara.students.StudentService.StudentQuery;
import com.akshara.students.StudentService.StudentRow;
import com.akshara.students.StudentStatus;
import com.akshara.transport.TransportViews.StudentRef;

/**
 * The people transport needs, read through the students, staff and academics modules' public services only: students
 * with their class this year, their primary parent's contact for alerts, a family's children, and the staff who drive
 * and accompany the buses. Nothing about them is stored in the transport tables except their ids.
 */
@Component
class TransportPeople {

    /** A student as transport screens show them. */
    record StudentInfo(UUID id, String name, String admissionNo, String classLabel, boolean active) {

        StudentRef ref() {
            return new StudentRef(id, name, admissionNo, classLabel);
        }
    }

    /** Up to this many ids, classes are looked up one by one; above it, the whole year's sections are read at once. */
    private static final int ONE_BY_ONE = 10;
    private static final int MAX_PAGES = 100;

    private final StudentService students;
    private final StudentRoster roster;
    private final StaffService staff;
    private final AcademicsDirectory academics;
    private final TenantDirectory tenants;

    TransportPeople(StudentService students, StudentRoster roster, StaffService staff, AcademicsDirectory academics,
            TenantDirectory tenants) {
        this.students = students;
        this.roster = roster;
        this.staff = staff;
        this.academics = academics;
        this.tenants = tenants;
    }

    Optional<YearInfo> currentYear() {
        return academics.currentYear();
    }

    String schoolName() {
        return tenants.profile(TenantContext.require()).map(p -> p.name()).orElse("");
    }

    // ------------------------------------------------------------------ students

    /** Students by id with their class this year; ids of another school are missing. */
    Map<UUID, StudentInfo> students(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        UUID yearId = academics.currentYear().map(YearInfo::id).orElse(null);
        Map<UUID, RosterStudent> found = roster.students(ids, yearId);
        Map<UUID, String> classes = classLabels(found.keySet(), yearId);
        Map<UUID, StudentInfo> result = new LinkedHashMap<>();
        for (RosterStudent s : found.values()) {
            result.put(s.id(), new StudentInfo(s.id(), s.fullName(), s.admissionNo(), classes.get(s.id()),
                    s.active()));
        }
        return result;
    }

    Optional<StudentInfo> student(UUID id) {
        return Optional.ofNullable(students(List.of(id)).get(id));
    }

    /** Active students of a class this year (one section when given), by section and roll number. */
    List<StudentRow> classStudents(UUID classId, UUID sectionId) {
        List<StudentRow> rows = new ArrayList<>();
        for (int page = 0; page < MAX_PAGES; page++) {
            StudentPage found = students.list(new StudentQuery(null, classId, sectionId, StudentStatus.ACTIVE, null,
                    page, StudentService.MAX_PAGE_SIZE));
            rows.addAll(found.items());
            if ((long) (page + 1) * StudentService.MAX_PAGE_SIZE >= found.total()) {
                break;
            }
        }
        return rows;
    }

    /** Active students this year matching a name or admission number, optionally of one class or section. */
    List<StudentRow> search(String text, UUID classId, UUID sectionId, int limit) {
        String q = text == null || text.isBlank() ? null : text.trim();
        if (q == null && classId == null && sectionId == null) {
            return List.of();
        }
        return students.list(new StudentQuery(null, classId, sectionId, StudentStatus.ACTIVE, q, 0,
                Math.min(Math.max(limit, 1), StudentService.MAX_PAGE_SIZE))).items();
    }

    static String label(String className, String sectionName) {
        if (className == null) {
            return null;
        }
        return sectionName == null ? className : className + " " + sectionName;
    }

    /** The primary parent or guardian of each student that has one (full numbers: only for queuing alerts). */
    Map<UUID, GuardianContact> guardians(Collection<UUID> studentIds) {
        return roster.primaryGuardians(studentIds);
    }

    static String maskPhone(String phone) {
        return phone == null ? null : RecipientMask.mask(phone);
    }

    /** A parent's children and a student's own record: whose bus a family member may see. */
    List<ChildView> familyOf(UUID userId) {
        Map<UUID, ChildView> children = new LinkedHashMap<>();
        students.childrenOf(userId).forEach(c -> children.putIfAbsent(c.id(), c));
        students.studentOf(userId).ifPresent(c -> children.putIfAbsent(c.id(), c));
        return new ArrayList<>(children.values());
    }

    private Map<UUID, String> classLabels(Collection<UUID> studentIds, UUID yearId) {
        Map<UUID, String> labels = new HashMap<>();
        if (yearId == null || studentIds.isEmpty()) {
            return labels;
        }
        Map<UUID, SectionInfo> sections = academics.sections().stream()
                .collect(Collectors.toMap(SectionInfo::id, s -> s));
        if (studentIds.size() <= ONE_BY_ONE) {
            for (UUID id : studentIds) {
                roster.sectionOf(id, yearId).map(sections::get).ifPresent(s -> labels.put(id, s.label()));
            }
            return labels;
        }
        Set<UUID> wanted = new LinkedHashSet<>(studentIds);
        for (StudentRoster.Family f : roster.families(yearId, null)) {
            SectionInfo s = sections.get(f.sectionId());
            if (s != null && wanted.contains(f.studentId())) {
                labels.put(f.studentId(), s.label());
            }
        }
        // Students who left are not in the active families; look them up one by one.
        for (UUID id : wanted) {
            if (!labels.containsKey(id)) {
                roster.sectionOf(id, yearId).map(sections::get).ifPresent(s -> labels.put(id, s.label()));
            }
        }
        return labels;
    }

    // ------------------------------------------------------------------ staff

    /** Everyone on the staff, active or not, by sign-in id. */
    Map<UUID, StaffRow> staff() {
        Map<UUID, StaffRow> all = new HashMap<>();
        for (int page = 0; page < MAX_PAGES; page++) {
            StaffPage found = staff.list(new StaffQuery(null, null, null, null, null, null, page,
                    StaffService.MAX_PAGE_SIZE));
            found.items().forEach(r -> all.put(r.userId(), r));
            if ((long) (page + 1) * StaffService.MAX_PAGE_SIZE >= found.total()) {
                break;
            }
        }
        return all;
    }

    /** Active staff whose name, email or employee code contains the text (everyone when empty), up to the limit. */
    List<StaffRow> searchStaff(String text, int limit) {
        String q = text == null || text.isBlank() ? null : text.trim();
        return staff.list(new StaffQuery(null, null, null, StaffStatus.ACTIVE, null, q, 0,
                Math.min(Math.max(limit, 1), StaffService.MAX_PAGE_SIZE))).items();
    }

    static boolean active(StaffRow row) {
        return row != null && row.status() == StaffStatus.ACTIVE;
    }
}
