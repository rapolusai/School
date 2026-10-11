package com.akshara.exams;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface HpcEntryRepository extends JpaRepository<HpcEntry, UUID> {

    List<HpcEntry> findByTermIdAndStudentIdIn(UUID termId, Collection<UUID> studentIds);

    List<HpcEntry> findByStudentId(UUID studentId);

    long countByTermId(UUID termId);
}
