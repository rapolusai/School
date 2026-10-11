package com.akshara.transport;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface RouteRepository extends JpaRepository<Route, UUID> {

    @Query("select r from Route r order by lower(r.name)")
    List<Route> ordered();

    @Query("select r from Route r where lower(r.name) = lower(?1)")
    List<Route> withName(String name);

    @Query("select r from Route r where r.active and (r.driverId in ?1 or r.attendantId in ?1) order by lower(r.name)")
    List<Route> activeWithCrew(Collection<UUID> crewIds);

    @Query("select r from Route r where r.active and r.vehicleId = ?1 order by lower(r.name)")
    List<Route> activeWithVehicle(UUID vehicleId);
}
