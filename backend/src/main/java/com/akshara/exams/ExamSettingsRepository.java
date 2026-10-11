package com.akshara.exams;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface ExamSettingsRepository extends JpaRepository<ExamSettings, UUID> {

    /** The school's settings: at most one row per school, and row-level security shows only this school's. */
    Optional<ExamSettings> findFirstBy();

    long countByCoScholasticSchemeId(UUID schemeId);
}
