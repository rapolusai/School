package com.akshara.homework;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface HomeworkSettingsRepository extends JpaRepository<HomeworkSettings, UUID> {

    Optional<HomeworkSettings> findFirstBy();
}
