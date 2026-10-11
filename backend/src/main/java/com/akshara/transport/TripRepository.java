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

interface TripRepository extends JpaRepository<Trip, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Trip t where t.id = ?1")
    Optional<Trip> lockById(UUID id);

    Optional<Trip> findByRouteIdAndTripDateAndKind(UUID routeId, LocalDate tripDate, TripKind kind);

    List<Trip> findByTripDate(LocalDate tripDate);

    @Query("select t from Trip t where t.tripDate = ?1 and t.routeId in ?2")
    List<Trip> onDayForRoutes(LocalDate tripDate, Collection<UUID> routeIds);

    /** The running trip of a vehicle on a day (the newest, should there be two). */
    @Query("select t.id from Trip t where t.vehicleId = ?1 and t.tripDate = ?2 and t.status = 'RUNNING' "
            + "order by t.startedAt desc")
    List<UUID> runningOfVehicle(UUID vehicleId, LocalDate tripDate);
}
