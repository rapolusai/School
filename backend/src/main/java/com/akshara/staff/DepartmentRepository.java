package com.akshara.staff;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface DepartmentRepository extends JpaRepository<Department, UUID> {

    @Query("select d from Department d order by lower(d.name)")
    List<Department> findAllOrdered();

    @Query("select count(d) > 0 from Department d where lower(d.name) = lower(?1) and d.id <> ?2")
    boolean nameTaken(String name, UUID exceptId);

    List<Department> findByHeadUserId(UUID headUserId);
}
