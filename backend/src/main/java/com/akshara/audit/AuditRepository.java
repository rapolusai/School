package com.akshara.audit;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

interface AuditRepository extends JpaRepository<AuditEvent, UUID> {

    List<AuditEvent> findAllByOrderByAtDesc(Limit limit);
}
