package com.akshara.admissions;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.akshara.admissions.AdmissionTypes.SlotStatus;

interface AssessmentSlotRepository extends JpaRepository<AssessmentSlot, UUID> {

    @Query("select s from AssessmentSlot s where s.applicationId = ?1 order by s.scheduledAt desc")
    List<AssessmentSlot> findByApplicationId(UUID applicationId);

    Optional<AssessmentSlot> findByIdAndApplicationId(UUID id, UUID applicationId);

    /** Slots with the given status in a time range, soonest first. */
    @Query("select s from AssessmentSlot s where s.status = ?1 and s.scheduledAt >= ?2 and s.scheduledAt < ?3 "
            + "order by s.scheduledAt")
    List<AssessmentSlot> findByStatusBetween(SlotStatus status, Instant from, Instant to, Limit limit);

    @Query("select count(s) from AssessmentSlot s where s.status = ?1 and s.scheduledAt >= ?2")
    long countByStatusFrom(SlotStatus status, Instant from);

    /** The next slot time (with the given status, from a moment on) of each application that has one. */
    @Query("select s.applicationId, min(s.scheduledAt) from AssessmentSlot s "
            + "where s.applicationId in ?1 and s.status = ?2 and s.scheduledAt >= ?3 group by s.applicationId")
    List<Object[]> nextSlots(Collection<UUID> applicationIds, SlotStatus status, Instant from);
}
