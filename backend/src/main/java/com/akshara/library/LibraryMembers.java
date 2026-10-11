package com.akshara.library;

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
import com.akshara.library.LibraryViews.Member;
import com.akshara.shared.ApiException;
import com.akshara.staff.StaffService;
import com.akshara.staff.StaffService.StaffPage;
import com.akshara.staff.StaffService.StaffQuery;
import com.akshara.staff.StaffService.StaffRow;
import com.akshara.staff.StaffStatus;
import com.akshara.students.StudentRoster;
import com.akshara.students.StudentRoster.RosterStudent;
import com.akshara.students.StudentService;
import com.akshara.students.StudentService.ChildView;
import com.akshara.students.StudentService.StudentPage;
import com.akshara.students.StudentService.StudentQuery;
import com.akshara.students.StudentService.StudentRow;
import com.akshara.students.StudentStatus;

/**
 * The library's members, read through the students and staff modules' public services only: the school's students
 * (active ones may borrow) and its staff (anyone with a staff role; those who left may not borrow). Only names, the
 * admission number or employee code and the class or designation are used; nothing about members is stored here.
 */
@Component
class LibraryMembers {

    /** Up to this many ids, classes are looked up one by one; above it, the whole year's sections are read at once. */
    private static final int ONE_BY_ONE = 10;
    private static final int MAX_STAFF_PAGES = 50;

    private final StudentService students;
    private final StudentRoster roster;
    private final StaffService staff;
    private final AcademicsDirectory academics;

    LibraryMembers(StudentService students, StudentRoster roster, StaffService staff, AcademicsDirectory academics) {
        this.students = students;
        this.roster = roster;
        this.staff = staff;
        this.academics = academics;
    }

    /** Active students and staff whose name, admission number or employee code contains the text. */
    List<Member> search(String text, int limit) {
        String q = text == null ? "" : text.trim();
        if (q.isEmpty()) {
            return List.of();
        }
        int size = Math.min(Math.max(limit, 1), 50);
        List<Member> found = new ArrayList<>();
        StudentPage page = students.list(new StudentQuery(null, null, null, StudentStatus.ACTIVE, q, 0, size));
        for (StudentRow r : page.items()) {
            found.add(new Member(MemberType.STUDENT, r.id(), r.fullName(), r.admissionNo(),
                    label(r.className(), r.sectionName()), true));
        }
        StaffPage people = staff.list(new StaffQuery(null, null, null, StaffStatus.ACTIVE, null, q, 0, size));
        for (StaffRow r : people.items()) {
            found.add(staffMember(r));
        }
        return found;
    }

    /** The member, if they belong to this school (students in any status; staff active or not). */
    Optional<Member> find(MemberRef ref) {
        return Optional.ofNullable(describe(List.of(ref)).get(ref)).filter(m -> m.name() != null);
    }

    /**
     * A member who may borrow: an active student or a member of staff who has not left. An id of someone else (another
     * school, a parent) is a 400 on {@code field}, like any foreign id in a request body.
     */
    Member requireActive(MemberRef ref, String field) {
        Member member = find(ref).orElseThrow(() -> ApiException.badRequest(
                ref.type() == MemberType.STUDENT ? "Pick a student of this school." : "Pick a member of staff.",
                field));
        if (!member.active()) {
            throw ApiException.badRequest(member.name() + " has left the school and cannot borrow.", field);
        }
        return member;
    }

    /** A member for a path id: 404 when they are not a student or staff member of this school. */
    Member require(MemberRef ref) {
        return find(ref).orElseThrow(() -> ApiException.notFound("Member"));
    }

    /**
     * Names and classes or designations of many members at once. Members that cannot be found (which the database's
     * keys rule out) come back with a null name.
     */
    Map<MemberRef, Member> describe(Collection<MemberRef> refs) {
        Map<MemberRef, Member> result = new LinkedHashMap<>();
        if (refs.isEmpty()) {
            return result;
        }
        Set<UUID> studentIds = refs.stream().filter(r -> r.type() == MemberType.STUDENT).map(MemberRef::id)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<UUID> staffIds = refs.stream().filter(r -> r.type() == MemberType.STAFF).map(MemberRef::id)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!studentIds.isEmpty()) {
            UUID yearId = academics.currentYear().map(YearInfo::id).orElse(null);
            Map<UUID, RosterStudent> found = roster.students(studentIds, yearId);
            Map<UUID, String> classes = classLabels(found.keySet(), yearId);
            for (UUID id : studentIds) {
                RosterStudent s = found.get(id);
                result.put(MemberRef.student(id), s == null
                        ? new Member(MemberType.STUDENT, id, null, null, null, false)
                        : new Member(MemberType.STUDENT, id, s.fullName(), s.admissionNo(), classes.get(id),
                                s.active()));
            }
        }
        if (!staffIds.isEmpty()) {
            Map<UUID, StaffRow> directory = staffDirectory();
            for (UUID id : staffIds) {
                StaffRow r = directory.get(id);
                result.put(MemberRef.staff(id), r == null
                        ? new Member(MemberType.STAFF, id, null, null, null, false)
                        : staffMember(r));
            }
        }
        // Keep the caller's order.
        Map<MemberRef, Member> ordered = new LinkedHashMap<>();
        for (MemberRef ref : refs) {
            ordered.put(ref, result.get(ref));
        }
        return ordered;
    }

    /** The students of a class this year (any status), for report filters. */
    Set<UUID> studentIdsInClass(UUID classId) {
        Set<UUID> ids = new LinkedHashSet<>();
        for (int page = 0; page < 100; page++) {
            StudentPage found = students.list(new StudentQuery(null, classId, null, null, null, page,
                    StudentService.MAX_PAGE_SIZE));
            found.items().forEach(r -> ids.add(r.id()));
            if ((long) (page + 1) * StudentService.MAX_PAGE_SIZE >= found.total()) {
                break;
            }
        }
        return ids;
    }

    /** A parent's children (their guardian record's students). */
    List<ChildView> childrenOf(UUID userId) {
        return students.childrenOf(userId);
    }

    /** The student record of a student's own sign-in. */
    Optional<ChildView> studentOf(UUID userId) {
        return students.studentOf(userId);
    }

    /** The staff member behind a sign-in, if the sign-in has a staff role. */
    Optional<Member> staffOf(UUID userId) {
        return Optional.ofNullable(staffDirectory().get(userId)).map(LibraryMembers::staffMember);
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
        } else {
            for (StudentRoster.Family f : roster.families(yearId, null)) {
                SectionInfo s = sections.get(f.sectionId());
                if (s != null && studentIds.contains(f.studentId())) {
                    labels.put(f.studentId(), s.label());
                }
            }
            // Students who left are not in the active families; look them up one by one.
            for (UUID id : studentIds) {
                if (!labels.containsKey(id)) {
                    roster.sectionOf(id, yearId).map(sections::get).ifPresent(s -> labels.put(id, s.label()));
                }
            }
        }
        return labels;
    }

    /** Everyone on the staff, active or not, by sign-in id. */
    private Map<UUID, StaffRow> staffDirectory() {
        Map<UUID, StaffRow> all = new HashMap<>();
        for (int page = 0; page < MAX_STAFF_PAGES; page++) {
            StaffPage found = staff.list(new StaffQuery(null, null, null, null, null, null, page,
                    StaffService.MAX_PAGE_SIZE));
            found.items().forEach(r -> all.put(r.userId(), r));
            if ((long) (page + 1) * StaffService.MAX_PAGE_SIZE >= found.total()) {
                break;
            }
        }
        return all;
    }

    private static Member staffMember(StaffRow r) {
        return new Member(MemberType.STAFF, r.userId(), r.name(), r.employeeCode(), r.designation(),
                r.status() == StaffStatus.ACTIVE);
    }

    static String label(String className, String sectionName) {
        if (className == null) {
            return null;
        }
        return sectionName == null ? className : className + " " + sectionName;
    }
}
