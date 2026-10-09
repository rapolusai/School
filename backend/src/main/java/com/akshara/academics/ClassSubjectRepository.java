package com.akshara.academics;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface ClassSubjectRepository extends JpaRepository<ClassSubject, UUID> {

    List<ClassSubject> findByClassId(UUID classId);

    long countBySubjectId(UUID subjectId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from ClassSubject cs where cs.classId = ?1")
    int deleteByClassId(UUID classId);
}
