package com.akshara.fees;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.akshara.shared.ApiException;
import com.akshara.students.StudentService;
import com.akshara.students.StudentService.ChildView;
import com.akshara.students.StudentService.EnrollmentView;
import com.akshara.students.StudentService.StudentDetail;
import com.akshara.students.StudentService.StudentPage;
import com.akshara.students.StudentService.StudentQuery;
import com.akshara.students.StudentService.StudentRow;
import com.akshara.students.StudentStatus;

/**
 * The students the fees module needs to know about, read through the students module's public service only: who is
 * enrolled in a class in a year, a student's current class, and a parent's children.
 */
@Component("feeStudentRoster")
class StudentRoster {

    /** A student as fee screens and reports show them. {@code guardianPhone} is the full number; mask it in lists. */
    record Entry(UUID id, String fullName, String admissionNo, StudentStatus status, UUID classId, String className,
            UUID sectionId, String sectionName, Integer rollNo, String guardianName, String guardianPhone) {

        String classLabel() {
            if (className == null) {
                return null;
            }
            return sectionName == null ? className : className + " " + sectionName;
        }
    }

    /** Where a student is this year. */
    record Placement(UUID academicYearId, UUID classId, boolean active) {
    }

    private final StudentService students;

    StudentRoster(StudentService students) {
        this.students = students;
    }

    /** Every student enrolled in the year (any status), by id. */
    Map<UUID, Entry> year(UUID academicYearId) {
        Map<UUID, Entry> result = new HashMap<>();
        for (StudentRow row : all(new Query(academicYearId, null, null, null))) {
            result.put(row.id(), entry(row));
        }
        return result;
    }

    /** Active students enrolled in a class in a year. */
    Set<UUID> activeInClass(UUID academicYearId, UUID classId) {
        Set<UUID> ids = new HashSet<>();
        for (StudentRow row : all(new Query(academicYearId, classId, StudentStatus.ACTIVE, null))) {
            ids.add(row.id());
        }
        return ids;
    }

    /** Students of the current year matching a name or admission number. */
    List<Entry> search(String text, int limit) {
        StudentPage page = students.list(new StudentQuery(null, null, null, null, text, 0, limit));
        return page.items().stream().map(StudentRoster::entry).toList();
    }

    /** A student of the current school, with their current class; 404 when there is no such student. */
    Entry student(UUID studentId) {
        StudentDetail d = students.detail(studentId);
        EnrollmentView e = d.currentEnrollment();
        var primary = d.guardians().stream().filter(g -> g.primary()).findFirst();
        return new Entry(d.id(), d.fullName(), d.admissionNo(), d.status(), e == null ? null : e.classId(),
                e == null ? null : e.className(), e == null ? null : e.sectionId(), e == null ? null : e.sectionName(),
                e == null ? null : e.rollNo(), primary.map(g -> g.name()).orElse(null),
                primary.map(g -> g.phone()).orElse(null));
    }

    /** Like {@link #student} for an id sent in a request body: an unknown id is a 400 on that field. */
    Entry studentInBody(UUID studentId, String field) {
        try {
            return student(studentId);
        } catch (ApiException e) {
            if (e.status() == HttpStatus.NOT_FOUND) {
                throw ApiException.badRequest("Pick a student of this school.", field);
            }
            throw e;
        }
    }

    /** The student's class in the current year, if they are enrolled in it. */
    Optional<Placement> currentPlacement(UUID studentId) {
        StudentDetail d = students.detail(studentId);
        EnrollmentView e = d.currentEnrollment();
        if (e == null) {
            return Optional.empty();
        }
        return Optional.of(new Placement(e.academicYearId(), e.classId(), d.status() == StudentStatus.ACTIVE));
    }

    /** The children linked to a parent's sign-in. */
    List<ChildView> childrenOf(UUID userId) {
        return students.childrenOf(userId);
    }

    private record Query(UUID yearId, UUID classId, StudentStatus status, String search) {
    }

    private List<StudentRow> all(Query q) {
        List<StudentRow> rows = new java.util.ArrayList<>();
        int page = 0;
        while (true) {
            StudentPage result = students.list(new StudentQuery(q.yearId(), q.classId(), null, q.status(), q.search(),
                    page, StudentService.MAX_PAGE_SIZE));
            rows.addAll(result.items());
            if (result.items().size() < StudentService.MAX_PAGE_SIZE
                    || (long) (page + 1) * StudentService.MAX_PAGE_SIZE >= result.total()) {
                return rows;
            }
            page++;
        }
    }

    private static Entry entry(StudentRow r) {
        return new Entry(r.id(), r.fullName(), r.admissionNo(), r.status(), r.classId(), r.className(),
                r.sectionId(), r.sectionName(), r.rollNo(), r.guardianName(), r.guardianPhone());
    }

    /** "9876500001" → "98•••••001": enough to recognise a number in a list without exposing it. */
    static String maskPhone(String phone) {
        if (phone == null || phone.length() < 6) {
            return phone;
        }
        return phone.substring(0, 2) + "•".repeat(phone.length() - 5) + phone.substring(phone.length() - 3);
    }
}
