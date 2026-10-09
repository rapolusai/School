package com.akshara.timetable;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface TimetableSettingsRepository extends JpaRepository<TimetableSettings, UUID> {

    Optional<TimetableSettings> findFirstBy();
}
