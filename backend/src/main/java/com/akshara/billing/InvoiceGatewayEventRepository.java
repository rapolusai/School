package com.akshara.billing;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface InvoiceGatewayEventRepository extends JpaRepository<InvoiceGatewayEvent, UUID> {

    boolean existsByGatewayAndEventId(String gateway, String eventId);
}
