package com.akshara.students;

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

import jakarta.persistence.EntityManager;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.shared.TenantContext;

/**
 * Read-only view of class lists and parents' contacts for modules that work with students day to day (attendance
 * today; homework and fees later). Returns only what those modules need: names, admission and roll numbers, and the
 * primary contact's name and mobile number.
 */
@Service
@Transactional(readOnly = true)
public class StudentRoster {

    /** A student as a class list shows them. {@code rollNo} is the roll number in the year asked for, if any. */
    public record RosterStudent(UUID id, String fullName, String admissionNo, Integer rollNo, boolean active) {
    }

    /** The parent or guardian the school contacts first. */
    public record GuardianContact(UUID studentId, UUID guardianId, String name, String phone, String email) {
    }

    private final EntityManager entityManager;
    private final StudentGuardianRepository links;
    private final GuardianRepository guardians;

    StudentRoster(EntityManager entityManager, StudentGuardianRepository links, GuardianRepository guardians) {
        this.entityManager = entityManager;
        this.links = links;
        this.guardians = guardians;
    }

    /** Active students enrolled in the section for the year, by roll number and then name. */
    public List<RosterStudent> activeInSection(UUID academicYearId, UUID sectionId) {
        TenantContext.require();
        return entityManager.createQuery("select s, e from Enrollment e join Student s on s.id = e.studentId "
                + "where e.academicYearId = :year and e.sectionId = :section "
                + "and s.status = com.akshara.students.StudentStatus.ACTIVE "
                + "order by e.rollNo asc nulls last, lower(s.firstName), lower(s.lastName), s.admissionNo",
                Object[].class)
                .setParameter("year", academicYearId)
                .setParameter("section", sectionId)
                .getResultList().stream()
                .map(r -> view((Student) r[0], (Enrollment) r[1]))
                .toList();
    }

    /** Active students per section in the year, for headcounts. */
    public Map<UUID, Long> activePerSection(UUID academicYearId) {
        TenantContext.require();
        Map<UUID, Long> counts = new HashMap<>();
        entityManager.createQuery("select e.sectionId, count(e) from Enrollment e join Student s on s.id = e.studentId "
                + "where e.academicYearId = :year and s.status = com.akshara.students.StudentStatus.ACTIVE "
                + "group by e.sectionId", Object[].class)
                .setParameter("year", academicYearId)
                .getResultList()
                .forEach(r -> counts.put((UUID) r[0], ((Number) r[1]).longValue()));
        return counts;
    }

    /**
     * Students by id, in no particular order, with their roll number in the given year (null when they were not
     * enrolled then). Ids of another school are simply missing.
     */
    public Map<UUID, RosterStudent> students(Collection<UUID> ids, UUID academicYearId) {
        TenantContext.require();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Set<UUID> wanted = new LinkedHashSet<>(ids);
        Map<UUID, Integer> rollNos = new HashMap<>();
        if (academicYearId != null) {
            entityManager.createQuery("select e from Enrollment e where e.academicYearId = :year "
                    + "and e.studentId in :ids", Enrollment.class)
                    .setParameter("year", academicYearId)
                    .setParameter("ids", wanted)
                    .getResultList()
                    .forEach(e -> {
                        if (e.getRollNo() != null) {
                            rollNos.put(e.getStudentId(), e.getRollNo());
                        }
                    });
        }
        Map<UUID, RosterStudent> result = new LinkedHashMap<>();
        entityManager.createQuery("select s from Student s where s.id in :ids", Student.class)
                .setParameter("ids", wanted)
                .getResultList()
                .forEach(s -> result.put(s.getId(), new RosterStudent(s.getId(), s.fullName(), s.getAdmissionNo(),
                        rollNos.get(s.getId()), s.isActive())));
        return result;
    }

    public Optional<RosterStudent> student(UUID id, UUID academicYearId) {
        return Optional.ofNullable(students(List.of(id), academicYearId).get(id));
    }

    /** The section the student is enrolled in for the year, if any. */
    public Optional<UUID> sectionOf(UUID studentId, UUID academicYearId) {
        TenantContext.require();
        return entityManager.createQuery("select e.sectionId from Enrollment e where e.studentId = :student "
                + "and e.academicYearId = :year", UUID.class)
                .setParameter("student", studentId)
                .setParameter("year", academicYearId)
                .getResultStream()
                .findFirst();
    }

    /** The primary parent or guardian of each student that has one. */
    public Map<UUID, GuardianContact> primaryGuardians(Collection<UUID> studentIds) {
        TenantContext.require();
        if (studentIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, GuardianContact> result = new HashMap<>();
        for (Object[] row : links.findPrimaryGuardians(Set.copyOf(studentIds))) {
            Guardian g = (Guardian) row[1];
            result.put((UUID) row[0], new GuardianContact((UUID) row[0], g.getId(), g.getName(), g.getPhone(),
                    g.getEmail()));
        }
        return result;
    }

    /** The students linked to the guardian record of a parent's sign-in. Empty for anyone else. */
    public Set<UUID> childIdsOf(UUID userId) {
        TenantContext.require();
        return guardians.findByUserAccountId(userId)
                .<Set<UUID>>map(g -> links.findByGuardianId(g.getId()).stream()
                        .map(StudentGuardian::getStudentId)
                        .collect(Collectors.toCollection(LinkedHashSet::new)))
                .orElse(Set.of());
    }

    /** How many students the school has on roll (status ACTIVE): its plan's student count and billing use this. */
    public long activeCount() {
        TenantContext.require();
        return entityManager.createQuery("select count(s) from Student s "
                + "where s.status = com.akshara.students.StudentStatus.ACTIVE", Long.class)
                .getSingleResult();
    }

    private static RosterStudent view(Student s, Enrollment e) {
        return new RosterStudent(s.getId(), s.fullName(), s.getAdmissionNo(), e.getRollNo(), s.isActive());
    }
}
