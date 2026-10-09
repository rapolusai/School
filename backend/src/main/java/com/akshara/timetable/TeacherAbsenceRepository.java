package com.akshara.timetable;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface TeacherAbsenceRepository extends JpaRepository<TeacherAbsence, UUID> {

    List<TeacherAbsence> findByAbsenceDate(LocalDate date);

    Optional<TeacherAbsence> findByAbsenceDateAndTeacherId(LocalDate date, UUID teacherId);
}
