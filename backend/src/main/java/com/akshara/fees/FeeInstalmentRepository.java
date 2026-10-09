package com.akshara.fees;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface FeeInstalmentRepository extends JpaRepository<FeeInstalment, UUID> {

    List<FeeInstalment> findByStructureIdOrderBySeq(UUID structureId);

    List<FeeInstalment> findByStructureIdIn(Collection<UUID> structureIds);
}
