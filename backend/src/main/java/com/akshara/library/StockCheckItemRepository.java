package com.akshara.library;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface StockCheckItemRepository extends JpaRepository<StockCheckItem, UUID> {

    boolean existsByStockCheckIdAndCopyId(UUID stockCheckId, UUID copyId);

    long countByStockCheckId(UUID stockCheckId);

    @Query("select i.copyId from StockCheckItem i where i.stockCheckId = ?1")
    List<UUID> copyIdsOf(UUID stockCheckId);

    @Query("select i from StockCheckItem i where i.stockCheckId = ?1 order by i.seenAt desc, i.id desc")
    List<StockCheckItem> latest(UUID stockCheckId, Limit limit);

    /** Copies seen per stock check: {stockCheckId, count}. */
    @Query("select i.stockCheckId, count(i) from StockCheckItem i group by i.stockCheckId")
    List<Object[]> countPerCheck();
}
