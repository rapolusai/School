package com.akshara.exams;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface MarkRepository extends JpaRepository<Mark, UUID> {

    List<Mark> findBySheetId(UUID sheetId);

    List<Mark> findBySheetIdIn(Collection<UUID> sheetIds);

    /** Students with something entered (marks or a code) per sheet. */
    @Query("select m.sheetId, count(m) from Mark m where m.sheetId in ?1 and (m.theory is not null "
            + "or m.practical is not null or m.internal is not null or m.code is not null) group by m.sheetId")
    List<Object[]> countEnteredPerSheet(Collection<UUID> sheetIds);

    /** The largest mark entered per component on the paper's sheets, to stop a maximum going below it. */
    @Query("select max(m.theory), max(m.practical), max(m.internal) from Mark m join MarkSheet s on s.id = m.sheetId "
            + "where s.paperId = ?1")
    List<Object[]> highestOnPaper(UUID paperId);

    long countBySheetIdIn(Collection<UUID> sheetIds);
}
