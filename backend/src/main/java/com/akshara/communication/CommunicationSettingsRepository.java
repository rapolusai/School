package com.akshara.communication;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface CommunicationSettingsRepository extends JpaRepository<CommunicationSettings, UUID> {

    /** The current school's settings row; row-level security leaves at most one. */
    @Query("select s from CommunicationSettings s")
    Optional<CommunicationSettings> findCurrent();
}
