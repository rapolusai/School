package com.akshara.exams;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface PaperRepository extends JpaRepository<Paper, UUID> {

    List<Paper> findByExamClassId(UUID examClassId);

    List<Paper> findByExamClassIdIn(Collection<UUID> examClassIds);
}
