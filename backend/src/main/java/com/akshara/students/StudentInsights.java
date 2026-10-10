package com.akshara.students;

import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.shared.TenantContext;

/** Read-only student figures for the dashboard: students on roll and new admissions. */
@Service
@Transactional(readOnly = true)
public class StudentInsights {

    private final EntityManager entityManager;

    StudentInsights(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /** Active students enrolled in a section for the academic year. */
    public long onRoll(UUID academicYearId) {
        TenantContext.require();
        return entityManager.createQuery("select count(e) from Enrollment e join Student s on s.id = e.studentId "
                + "where e.academicYearId = :year and s.status = com.akshara.students.StudentStatus.ACTIVE", Long.class)
                .setParameter("year", academicYearId)
                .getSingleResult();
    }

    /** Students whose admission date falls between the two days (both included), whatever their status now. */
    public long admittedBetween(LocalDate from, LocalDate to) {
        TenantContext.require();
        return entityManager.createQuery("select count(s) from Student s where s.admissionDate between :from and :to",
                Long.class)
                .setParameter("from", from)
                .setParameter("to", to)
                .getSingleResult();
    }
}
