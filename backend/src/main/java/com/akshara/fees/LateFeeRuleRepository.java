package com.akshara.fees;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface LateFeeRuleRepository extends JpaRepository<LateFeeRuleEntity, UUID> {

    /** The school's rule; row-level security leaves at most one row. */
    Optional<LateFeeRuleEntity> findFirstBy();
}
