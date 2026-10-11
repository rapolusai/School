package com.akshara.exams;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface GradingSchemeRepository extends JpaRepository<GradingScheme, UUID> {

    @Query("select s from GradingScheme s order by s.kind, lower(s.name)")
    List<GradingScheme> findAllOrdered();

    Optional<GradingScheme> findByCode(String code);

    @Query("select count(s) > 0 from GradingScheme s where lower(s.name) = lower(?1) and s.id <> ?2")
    boolean nameTaken(String name, UUID except);
}
