package com.akshara.students;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface StudentGuardianRepository extends JpaRepository<StudentGuardian, UUID> {

    List<StudentGuardian> findByStudentId(UUID studentId);

    List<StudentGuardian> findByGuardianId(UUID guardianId);

    List<StudentGuardian> findByGuardianIdIn(Collection<UUID> guardianIds);

    Optional<StudentGuardian> findByStudentIdAndGuardianId(UUID studentId, UUID guardianId);

    long countByGuardianId(UUID guardianId);

    @Query("select sg.studentId, g from StudentGuardian sg join Guardian g on g.id = sg.guardianId "
            + "where sg.studentId in ?1 and sg.primary = true")
    List<Object[]> findPrimaryGuardians(Collection<UUID> studentIds);
}
