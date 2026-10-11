package com.akshara.transport;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface StopRepository extends JpaRepository<Stop, UUID> {

    List<Stop> findByRouteIdOrderBySeq(UUID routeId);

    @Query("select s from Stop s order by s.routeId, s.seq")
    List<Stop> ordered();

    List<Stop> findByIdIn(Collection<UUID> ids);

    boolean existsByFeeZoneId(UUID feeZoneId);

    List<Stop> findByFeeZoneId(UUID feeZoneId);
}
