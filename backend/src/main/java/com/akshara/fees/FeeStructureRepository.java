package com.akshara.fees;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface FeeStructureRepository extends JpaRepository<FeeStructure, UUID> {

    Optional<FeeStructure> findByAcademicYearIdAndClassId(UUID academicYearId, UUID classId);

    List<FeeStructure> findByAcademicYearId(UUID academicYearId);

    @Query("select s from FeeStructure s where s.academicYearId = ?1 and s.status = 'PUBLISHED'")
    List<FeeStructure> findPublishedInYear(UUID academicYearId);

    /** Serialises edits and publishing of one structure. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from FeeStructure s where s.id = ?1")
    Optional<FeeStructure> lock(UUID id);
}
