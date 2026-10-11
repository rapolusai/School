package com.akshara.transport;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface FeeZoneRepository extends JpaRepository<FeeZone, UUID> {

    @Query("select z from FeeZone z order by z.minKm, lower(z.name)")
    List<FeeZone> ordered();

    @Query("select z from FeeZone z where lower(z.name) = lower(?1)")
    List<FeeZone> withName(String name);
}
