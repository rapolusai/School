package com.akshara.platform;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PlatformAdminRepository extends JpaRepository<PlatformAdmin, UUID> {

    @Query("select a from PlatformAdmin a where lower(a.email) = lower(?1)")
    Optional<PlatformAdmin> findByEmail(String email);
}
