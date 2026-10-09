package com.akshara.platform;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface TenantRepository extends JpaRepository<Tenant, UUID> {

    Optional<Tenant> findByCode(String code);

    boolean existsByCode(String code);

    List<Tenant> findAllByOrderByCreatedAtDesc();

    @Query(value = "select tenant_id, user_count from platform.tenant_user_counts()", nativeQuery = true)
    List<Object[]> userCounts();
}
