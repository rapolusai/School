package com.akshara.timetable;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface SubstitutionRepository extends JpaRepository<Substitution, UUID> {

    List<Substitution> findBySubDate(LocalDate date);

    List<Substitution> findBySubDateAndSubstituteTeacherId(LocalDate date, UUID teacherId);

    Optional<Substitution> findBySubDateAndSectionIdAndPeriodNo(LocalDate date, UUID sectionId, int periodNo);

    List<Substitution> findBySubDateAndSectionIdIn(LocalDate date, Collection<UUID> sectionIds);
}
