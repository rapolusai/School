package com.akshara.transport;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface CrewMemberRepository extends JpaRepository<CrewMember, UUID> {

    Optional<CrewMember> findByUserId(UUID userId);

    boolean existsByUserId(UUID userId);
}
