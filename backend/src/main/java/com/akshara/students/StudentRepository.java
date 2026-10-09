package com.akshara.students;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface StudentRepository extends JpaRepository<Student, UUID> {

    @Query("select count(s) > 0 from Student s where lower(s.admissionNo) = lower(?1) and s.id <> ?2")
    boolean existsByAdmissionNoExcept(String admissionNo, UUID exceptId);

    @Query("select lower(s.admissionNo) from Student s where lower(s.admissionNo) in ?1")
    List<String> findExistingAdmissionNos(Collection<String> lowerCaseAdmissionNos);

    Optional<Student> findByUserAccountId(UUID userAccountId);
}
