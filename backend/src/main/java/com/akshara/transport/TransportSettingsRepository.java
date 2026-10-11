package com.akshara.transport;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface TransportSettingsRepository extends JpaRepository<TransportSettings, UUID> {

    /** The current school's row (row-level security leaves only that one). */
    Optional<TransportSettings> findFirstBy();
}
