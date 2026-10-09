package com.akshara.fees;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface FeeHeadRepository extends JpaRepository<FeeHead, UUID> {

    @Query("select h from FeeHead h order by h.displayOrder, lower(h.name)")
    List<FeeHead> findAllOrdered();

    @Query("select count(h) > 0 from FeeHead h where lower(h.name) = lower(?1) and h.id <> ?2")
    boolean existsByNameExcept(String name, UUID exceptId);

    @Query("select coalesce(max(h.displayOrder), 0) from FeeHead h")
    int maxDisplayOrder();

    List<FeeHead> findByKindAndActiveTrue(FeeHeadKind kind);
}
