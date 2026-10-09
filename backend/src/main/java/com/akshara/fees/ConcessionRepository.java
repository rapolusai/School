package com.akshara.fees;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface ConcessionRepository extends JpaRepository<Concession, UUID> {

    @Query("select c from Concession c where c.studentId = ?1 and c.academicYearId = ?2 and c.status = 'ACTIVE' "
            + "order by c.createdAt")
    List<Concession> findActive(UUID studentId, UUID academicYearId);

    List<Concession> findByAcademicYearIdOrderByCreatedAtDesc(UUID academicYearId);

    List<Concession> findByStudentIdOrderByCreatedAtDesc(UUID studentId);

    List<Concession> findByStudentIdIn(Collection<UUID> studentIds);
}
