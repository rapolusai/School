package com.akshara.fees;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface GatewayEventRepository extends JpaRepository<GatewayEvent, UUID> {

    boolean existsByGatewayAndEventId(String gateway, String eventId);
}
