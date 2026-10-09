package com.akshara.admissions;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface ApplicationGuardianRepository extends JpaRepository<ApplicationGuardian, UUID> {

    @Query("select g from ApplicationGuardian g where g.applicationId = ?1 order by g.position")
    List<ApplicationGuardian> findByApplicationId(UUID applicationId);

    @Query("select g from ApplicationGuardian g where g.applicationId in ?1 and g.primary = true")
    List<ApplicationGuardian> findPrimaryIn(Collection<UUID> applicationIds);

    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("delete from ApplicationGuardian g where g.applicationId = ?1")
    void deleteByApplicationId(UUID applicationId);
}
