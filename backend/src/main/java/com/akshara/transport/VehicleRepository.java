package com.akshara.transport;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface VehicleRepository extends JpaRepository<Vehicle, UUID> {

    @Query("select v from Vehicle v order by v.registrationNo")
    List<Vehicle> ordered();

    Optional<Vehicle> findByRegistrationNo(String registrationNo);

    /**
     * Keeps the vehicle's latest position (unless a newer one is already kept), without touching its version, so it
     * never conflicts with someone editing the vehicle.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "update transport.vehicle set last_latitude = round(cast(?2 as numeric), 6), "
            + "last_longitude = round(cast(?3 as numeric), 6), last_position_at = ?4 "
            + "where id = ?1 and (last_position_at is null or last_position_at <= ?4)", nativeQuery = true)
    int recordPosition(UUID vehicleId, double latitude, double longitude, Instant at);

    /** The school a vehicle belongs to when the device token's hash matches (works without a school selected). */
    @Query(value = "select transport.device_tenant(?1, ?2)", nativeQuery = true)
    Optional<UUID> deviceTenant(UUID vehicleId, String tokenHash);
}
