package com.akshara.academics;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface AcademicYearRepository extends JpaRepository<AcademicYear, UUID> {

    List<AcademicYear> findAllByOrderByStartsOnDesc();

    Optional<AcademicYear> findByCurrentTrue();

    @Query("select count(y) > 0 from AcademicYear y where lower(y.name) = lower(?1) and y.id <> ?2")
    boolean existsByNameExcept(String name, UUID exceptId);
}
