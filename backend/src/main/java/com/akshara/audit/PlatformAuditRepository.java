package com.akshara.audit;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

interface PlatformAuditRepository extends JpaRepository<PlatformAuditEvent, UUID>,
        JpaSpecificationExecutor<PlatformAuditEvent> {

    @Query("select distinct e.action from PlatformAuditEvent e order by e.action")
    List<String> distinctActions();
}
