package com.akshara.timetable;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface PeriodRepository extends JpaRepository<Period, UUID> {
}
