package com.akshara.exams;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface MarkSheetRepository extends JpaRepository<MarkSheet, UUID> {

    Optional<MarkSheet> findByPaperIdAndSectionId(UUID paperId, UUID sectionId);

    /** The sheet, locked for this transaction: saves to one sheet are serialised. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from MarkSheet s where s.paperId = ?1 and s.sectionId = ?2")
    Optional<MarkSheet> lockByPaperAndSection(UUID paperId, UUID sectionId);

    List<MarkSheet> findByPaperIdIn(Collection<UUID> paperIds);

    long countByPaperIdIn(Collection<UUID> paperIds);

    long countBySectionId(UUID sectionId);
}
