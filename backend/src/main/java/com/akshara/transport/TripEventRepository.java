package com.akshara.transport;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface TripEventRepository extends JpaRepository<TripEvent, UUID> {

    @Query("select e from TripEvent e where e.tripId = ?1 order by e.recordedAt, e.id")
    List<TripEvent> ofTrip(UUID tripId);

    @Query("select e from TripEvent e where e.tripId in ?1 order by e.recordedAt, e.id")
    List<TripEvent> ofTrips(Collection<UUID> tripIds);
}
