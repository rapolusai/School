package com.akshara.fees;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface FeeInstalmentShareRepository extends JpaRepository<FeeInstalmentShare, UUID> {

    List<FeeInstalmentShare> findByInstalmentIdIn(Collection<UUID> instalmentIds);

    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("delete from FeeInstalmentShare s where s.instalmentId in ?1")
    int deleteByInstalmentIds(Collection<UUID> instalmentIds);

    boolean existsByHeadId(UUID headId);
}
