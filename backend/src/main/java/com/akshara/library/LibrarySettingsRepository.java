package com.akshara.library;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface LibrarySettingsRepository extends JpaRepository<LibrarySettings, UUID> {

    Optional<LibrarySettings> findFirstBy();
}
