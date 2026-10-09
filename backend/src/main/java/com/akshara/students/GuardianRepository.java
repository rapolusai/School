package com.akshara.students;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface GuardianRepository extends JpaRepository<Guardian, UUID> {

    List<Guardian> findByPhoneIn(Collection<String> phones);

    Optional<Guardian> findByUserAccountId(UUID userAccountId);
}
