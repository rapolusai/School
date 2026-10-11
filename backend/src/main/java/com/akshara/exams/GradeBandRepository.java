package com.akshara.exams;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface GradeBandRepository extends JpaRepository<GradeBand, UUID> {

    @Query("select b from GradeBand b where b.schemeId in ?1 order by b.schemeId, b.minPercent desc")
    List<GradeBand> findBySchemeIds(Collection<UUID> schemeIds);

    @Modifying
    @Query("delete from GradeBand b where b.schemeId = ?1")
    int deleteBySchemeId(UUID schemeId);
}
