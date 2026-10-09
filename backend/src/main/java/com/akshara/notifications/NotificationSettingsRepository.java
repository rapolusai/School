package com.akshara.notifications;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface NotificationSettingsRepository extends JpaRepository<NotificationSettings, UUID> {

    /** The current school's settings row; row-level security leaves at most one. */
    @Query("select s from NotificationSettings s")
    Optional<NotificationSettings> findCurrent();
}
