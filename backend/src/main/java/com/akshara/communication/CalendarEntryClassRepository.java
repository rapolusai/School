package com.akshara.communication;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface CalendarEntryClassRepository extends JpaRepository<CalendarEntryClass, UUID> {

    List<CalendarEntryClass> findByEntryIdIn(Collection<UUID> entryIds);

    @Modifying(flushAutomatically = true)
    @Query("delete from CalendarEntryClass c where c.entryId = ?1")
    int deleteByEntry(UUID entryId);
}
