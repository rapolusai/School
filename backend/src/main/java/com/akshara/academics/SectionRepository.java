package com.akshara.academics;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface SectionRepository extends JpaRepository<Section, UUID> {

    @Query("select s from Section s where s.classId = ?1 order by lower(s.name)")
    List<Section> findByClassId(UUID classId);

    @Query("select count(s) > 0 from Section s where s.classId = ?1 and lower(s.name) = lower(?2) and s.id <> ?3")
    boolean existsByNameExcept(UUID classId, String name, UUID exceptId);

    long countByClassId(UUID classId);
}
