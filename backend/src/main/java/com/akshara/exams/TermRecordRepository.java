package com.akshara.exams;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface TermRecordRepository extends JpaRepository<TermRecord, UUID> {

    List<TermRecord> findByTermIdAndStudentIdIn(UUID termId, Collection<UUID> studentIds);

    List<TermRecord> findByStudentId(UUID studentId);

    long countByTermId(UUID termId);
}
