package com.akshara.staff;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface LeaveTypeRepository extends JpaRepository<LeaveType, UUID> {

    @Query("select t from LeaveType t order by t.lossOfPay, lower(t.name)")
    List<LeaveType> findAllOrdered();

    @Query("select count(t) > 0 from LeaveType t where lower(t.name) = lower(?1) and t.id <> ?2")
    boolean nameTaken(String name, UUID exceptId);

    @Query("select count(t) > 0 from LeaveType t where lower(t.code) = lower(?1) and t.id <> ?2")
    boolean codeTaken(String code, UUID exceptId);
}
