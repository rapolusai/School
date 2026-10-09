package com.akshara.academics;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface SubjectRepository extends JpaRepository<Subject, UUID> {

    @Query("select s from Subject s order by lower(s.name)")
    List<Subject> findAllOrdered();

    @Query("select count(s) > 0 from Subject s where lower(s.name) = lower(?1) and s.id <> ?2")
    boolean existsByNameExcept(String name, UUID exceptId);

    @Query("select count(s) > 0 from Subject s where lower(s.code) = lower(?1) and s.id <> ?2")
    boolean existsByCodeExcept(String code, UUID exceptId);
}
