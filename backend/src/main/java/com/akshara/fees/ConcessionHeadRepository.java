package com.akshara.fees;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface ConcessionHeadRepository extends JpaRepository<ConcessionHead, UUID> {

    List<ConcessionHead> findByConcessionIdIn(Collection<UUID> concessionIds);

    boolean existsByHeadId(UUID headId);
}
