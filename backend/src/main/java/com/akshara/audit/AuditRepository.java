package com.akshara.audit;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

interface AuditRepository extends JpaRepository<AuditEvent, UUID>, JpaSpecificationExecutor<AuditEvent> {

    List<AuditEvent> findAllByOrderByAtDesc(Limit limit);

    /** The actions in the school's trail, for the audit page's filter. */
    @Query("select distinct e.action from AuditEvent e order by e.action")
    List<String> distinctActions();

    /** Everyone named in the school's trail (id and latest name), for the audit page's filter. */
    @Query("select e.actorId, max(e.actorName) from AuditEvent e where e.actorId is not null group by e.actorId "
            + "order by max(e.actorName)")
    List<Object[]> distinctActors();
}
