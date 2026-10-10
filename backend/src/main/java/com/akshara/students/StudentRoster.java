package com.akshara.students;

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

    /** A parent or guardian linked to a student, with the sign-in they use (if any). */
    public record FamilyGuardian(UUID guardianId, String name, String phone, String email, UUID userId,
            boolean primary) {
    }

    /** An active student of a section, with their own sign-in (if any) and every linked parent or guardian. */
    public record Family(UUID studentId, UUID sectionId, UUID studentUserId, List<FamilyGuardian> guardians) {
    }

    /**
     * The active students enrolled in the year, in the given sections (every section when {@code sectionIds} is
     * null), with their parents and guardians: who a circular or reminder to those classes reaches.
     */
    public List<Family> families(UUID academicYearId, Collection<UUID> sectionIds) {
        TenantContext.require();
        if (sectionIds != null && sectionIds.isEmpty()) {
            return List.of();
        }
        String jpql = "select s, e from Enrollment e join Student s on s.id = e.studentId "
                + "where e.academicYearId = :year and s.status = com.akshara.students.StudentStatus.ACTIVE"
                + (sectionIds == null ? "" : " and e.sectionId in :sections")
                + " order by e.sectionId, e.rollNo asc nulls last, s.id";
        var query = entityManager.createQuery(jpql, Object[].class).setParameter("year", academicYearId);
        if (sectionIds != null) {
            query.setParameter("sections", Set.copyOf(sectionIds));
        }
        List<Object[]> rows = query.getResultList();
        if (rows.isEmpty()) {
            return List.of();
        }
        Set<UUID> studentIds = rows.stream().map(r -> ((Student) r[0]).getId())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<UUID, List<StudentGuardian>> linksByStudent = new HashMap<>();
        for (StudentGuardian link : entityManager.createQuery("select sg from StudentGuardian sg "
                + "where sg.studentId in :ids", StudentGuardian.class).setParameter("ids", studentIds).getResultList()) {
            linksByStudent.computeIfAbsent(link.getStudentId(), k -> new ArrayList<>()).add(link);
        }
        Map<UUID, Guardian> guardianById = guardians.findAllById(linksByStudent.values().stream()
                .flatMap(List::stream).map(StudentGuardian::getGuardianId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(Guardian::getId, g -> g));
        return rows.stream().map(r -> {
            Student s = (Student) r[0];
            Enrollment e = (Enrollment) r[1];
            List<FamilyGuardian> family = linksByStudent.getOrDefault(s.getId(), List.of()).stream()
                    .filter(link -> guardianById.containsKey(link.getGuardianId()))
                    .sorted(Comparator.comparing((StudentGuardian link) -> !link.isPrimary())
                            .thenComparing(StudentGuardian::getGuardianId))
                    .map(link -> {
                        Guardian g = guardianById.get(link.getGuardianId());
                        return new FamilyGuardian(g.getId(), g.getName(), g.getPhone(), g.getEmail(),
                                g.getUserAccountId(), link.isPrimary());
                    })
                    .toList();
            return new Family(s.getId(), e.getSectionId(), s.getUserAccountId(), family);
        }).toList();
    }

    /**
     * The sections, in the year, of the students a sign-in belongs to: a parent's children, or a student's own
     * record. Empty for staff and for anyone whose students are not enrolled that year.
     */
    public Set<UUID> sectionIdsOf(UUID userId, UUID academicYearId) {
        TenantContext.require();
        Set<UUID> studentIds = new LinkedHashSet<>(childIdsOf(userId));
        entityManager.createQuery("select s.id from Student s where s.userAccountId = :user", UUID.class)
                .setParameter("user", userId)
                .getResultList()
                .forEach(studentIds::add);
        if (studentIds.isEmpty() || academicYearId == null) {
            return Set.of();
        }
        return new LinkedHashSet<>(entityManager.createQuery("select e.sectionId from Enrollment e "
                + "join Student s on s.id = e.studentId where e.academicYearId = :year and e.studentId in :ids "
                + "and s.status = com.akshara.students.StudentStatus.ACTIVE", UUID.class)
                .setParameter("year", academicYearId)
                .setParameter("ids", studentIds)
                .getResultList());
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
