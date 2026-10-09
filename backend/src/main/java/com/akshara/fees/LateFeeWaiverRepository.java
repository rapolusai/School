package com.akshara.fees;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface LateFeeWaiverRepository extends JpaRepository<LateFeeWaiver, UUID> {

    List<LateFeeWaiver> findByStudentId(UUID studentId);

    List<LateFeeWaiver> findByStudentIdIn(Collection<UUID> studentIds);

    boolean existsByStudentIdAndInstalmentId(UUID studentId, UUID instalmentId);

    boolean existsByInstalmentIdIn(Collection<UUID> instalmentIds);
}
