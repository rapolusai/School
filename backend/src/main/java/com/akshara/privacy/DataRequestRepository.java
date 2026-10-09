package com.akshara.privacy;

import java.util.List;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface DataRequestRepository extends JpaRepository<DataRequest, UUID> {

    /** Reads and locks the request, so two staff members never erase or close it at the same time. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from DataRequest r where r.id = ?1")
    java.util.Optional<DataRequest> findForUpdate(UUID id);

    @Query("select r from DataRequest r where r.requestedById = ?1 order by r.createdAt desc, r.id desc")
    List<DataRequest> byRequester(UUID userId);

    @Query("select count(r) from DataRequest r where r.requestedById = ?1 "
            + "and r.status <> com.akshara.privacy.RequestStatus.CLOSED")
    long openByRequester(UUID userId);

    @Query("select r.status, count(r) from DataRequest r group by r.status")
    List<Object[]> countByStatus();

    @Query("select count(r) from DataRequest r where r.status <> com.akshara.privacy.RequestStatus.CLOSED "
            + "and r.dueOn < ?1")
    long countOverdue(java.time.LocalDate today);
}
