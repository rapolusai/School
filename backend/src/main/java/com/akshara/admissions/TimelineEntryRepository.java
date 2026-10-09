package com.akshara.admissions;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface TimelineEntryRepository extends JpaRepository<TimelineEntry, UUID> {

    /** Newest first. Ids are time-ordered, which breaks ties between entries written in the same instant. */
    @Query("select t from TimelineEntry t where t.applicationId = ?1 order by t.at desc, t.id desc")
    List<TimelineEntry> findByApplicationId(UUID applicationId);
}
