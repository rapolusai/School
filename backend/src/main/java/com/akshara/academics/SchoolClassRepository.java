package com.akshara.academics;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface SchoolClassRepository extends JpaRepository<SchoolClass, UUID> {

    @Query("select c from SchoolClass c order by c.displayOrder, lower(c.name)")
    List<SchoolClass> findAllOrdered();

    @Query("select count(c) > 0 from SchoolClass c where lower(c.name) = lower(?1) and c.id <> ?2")
    boolean existsByNameExcept(String name, UUID exceptId);

    @Query("select coalesce(max(c.displayOrder), 0) from SchoolClass c")
    int maxDisplayOrder();
}
