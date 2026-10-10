package com.akshara.privacy;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface RequestEventRepository extends JpaRepository<RequestEvent, UUID> {

    /** Oldest first, like a conversation. */
    @Query("select e from RequestEvent e where e.requestId = ?1 order by e.at, e.id")
    List<RequestEvent> timeline(UUID requestId);
}
