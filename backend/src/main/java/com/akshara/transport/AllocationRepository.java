package com.akshara.transport;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface AllocationRepository extends JpaRepository<Allocation, UUID> {

    @Query("select a from Allocation a where a.studentId = ?1 and a.status = 'ACTIVE'")
    Optional<Allocation> activeOf(UUID studentId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Allocation a where a.id = ?1")
    Optional<Allocation> lockById(UUID id);

    List<Allocation> findByStudentIdOrderByStartDateDesc(UUID studentId);

    @Query("select a from Allocation a where a.studentId in ?1 order by a.startDate desc")
    List<Allocation> ofStudents(Collection<UUID> studentIds);

    @Query("select a from Allocation a where a.status = 'ACTIVE' and a.studentId in ?1")
    List<Allocation> activeOfStudents(Collection<UUID> studentIds);

    /** ACTIVE allocations on the route still running on or after the day: the seats they take. */
    @Query("select count(a) from Allocation a where a.routeId = ?1 and a.status = 'ACTIVE' "
            + "and (a.endDate is null or a.endDate >= ?2)")
    long seatsTaken(UUID routeId, LocalDate from);

    @Query("select a.routeId, count(a) from Allocation a where a.status = 'ACTIVE' "
            + "and (a.endDate is null or a.endDate >= ?1) group by a.routeId")
    List<Object[]> seatsTakenPerRoute(LocalDate from);

    @Query("select a.stopId, count(a) from Allocation a where a.routeId = ?1 and a.status = 'ACTIVE' "
            + "and (a.endDate is null or a.endDate >= ?2) group by a.stopId")
    List<Object[]> perStop(UUID routeId, LocalDate from);

    /** Allocations of the route that cover the day, whatever their status (an allocation ended today still rides). */
    @Query("select a from Allocation a where a.routeId = ?1 and a.startDate <= ?2 "
            + "and (a.endDate is null or a.endDate >= ?2)")
    List<Allocation> coveringOnRoute(UUID routeId, LocalDate day);

    @Query("select a from Allocation a where a.startDate <= ?1 and (a.endDate is null or a.endDate >= ?1)")
    List<Allocation> covering(LocalDate day);

    @Query("select a from Allocation a where a.status = 'ACTIVE' and a.stopId in ?1")
    List<Allocation> activeAtStops(Collection<UUID> stopIds);

    @Query("select count(a) > 0 from Allocation a where a.status = 'ACTIVE' and a.routeId = ?1")
    boolean anyActiveOnRoute(UUID routeId);

    @Query("select a from Allocation a order by a.createdAt desc")
    List<Allocation> newestFirst();

    /** Allocations overlapping the period, for the fee report. */
    @Query("select a from Allocation a where a.startDate <= ?2 and (a.endDate is null or a.endDate >= ?1)")
    List<Allocation> overlapping(LocalDate from, LocalDate to);
}
