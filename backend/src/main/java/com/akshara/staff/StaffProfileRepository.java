package com.akshara.staff;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface StaffProfileRepository extends JpaRepository<StaffProfile, UUID> {

    Optional<StaffProfile> findByUserId(UUID userId);

    @Query("select count(p) > 0 from StaffProfile p where lower(p.employeeCode) = lower(?1) and p.userId <> ?2")
    boolean employeeCodeTaken(String employeeCode, UUID exceptUserId);

    long countByDepartmentId(UUID departmentId);

    /** Rows of [departmentId, count] for profiles that have a department. */
    @Query("select p.departmentId, count(p) from StaffProfile p where p.departmentId is not null "
            + "and p.dateOfLeaving is null group by p.departmentId")
    List<Object[]> countPerDepartment();

    @Query("select distinct p.designation from StaffProfile p order by p.designation")
    List<String> designations();
}
