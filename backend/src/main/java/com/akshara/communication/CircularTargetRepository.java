package com.akshara.communication;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface CircularTargetRepository extends JpaRepository<CircularTarget, UUID> {

    List<CircularTarget> findByCircularId(UUID circularId);

    List<CircularTarget> findByCircularIdIn(Collection<UUID> circularIds);

    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("delete from CircularTarget t where t.circularId = ?1")
    int deleteByCircular(UUID circularId);
}
