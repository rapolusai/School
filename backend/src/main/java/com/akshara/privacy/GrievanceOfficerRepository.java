package com.akshara.privacy;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface GrievanceOfficerRepository extends JpaRepository<GrievanceOfficer, UUID> {

    /** The school's officer: at most one row per school, and row-level security shows only this school's. */
    @Query("select g from GrievanceOfficer g")
    Optional<GrievanceOfficer> findCurrent();
}
