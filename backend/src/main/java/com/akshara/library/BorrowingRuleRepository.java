package com.akshara.library;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface BorrowingRuleRepository extends JpaRepository<BorrowingRule, UUID> {

    Optional<BorrowingRule> findByMemberType(MemberType memberType);
}
