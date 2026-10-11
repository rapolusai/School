package com.akshara.transport;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface PositionRepository extends JpaRepository<Position, UUID> {

    long countByTripId(UUID tripId);
}
