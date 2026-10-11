package com.akshara.library;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface CopyRepository extends JpaRepository<Copy, UUID> {

    Optional<Copy> findByAccessionNo(String accessionNo);

    /** Reads and locks the copy, so two desks never lend or take back the same book at the same moment. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Copy c where c.accessionNo = ?1")
    Optional<Copy> lockByAccessionNo(String accessionNo);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Copy c where c.id = ?1")
    Optional<Copy> lockById(UUID id);

    @Query("select c from Copy c where c.titleId = ?1 order by c.accessionNo")
    List<Copy> ofTitle(UUID titleId);

    @Query("select c from Copy c where c.titleId = ?1 and c.status = ?2 order by c.accessionNo")
    List<Copy> ofTitleWithStatus(UUID titleId, CopyStatus status);

    @Query("select c.accessionNo from Copy c where c.accessionNo in ?1")
    List<String> existingAccessionNos(Collection<String> accessionNos);

    /** Copies per title and status: {titleId, status, count}. */
    @Query("select c.titleId, c.status, count(c) from Copy c where c.titleId in ?1 group by c.titleId, c.status")
    List<Object[]> countsByTitle(Collection<UUID> titleIds);

    @Query("select c.status, count(c) from Copy c group by c.status")
    List<Object[]> countByStatus();

    @Query("select c from Copy c where c.status in ?1 order by c.accessionNo")
    List<Copy> withStatus(Collection<CopyStatus> statuses);

    @Query("select c from Copy c where c.id in ?1")
    List<Copy> byIds(Collection<UUID> ids);

    long countByTitleId(UUID titleId);
}
