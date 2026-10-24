package com.akshara.notifications;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface WhatsAppOptOutRepository extends JpaRepository<WhatsAppOptOut, UUID> {

    boolean existsByAddress(String address);

    List<WhatsAppOptOut> findByStudentId(UUID studentId);

    @Modifying(flushAutomatically = true)
    @Query("delete from WhatsAppOptOut o where o.studentId = ?1")
    int deleteByStudent(UUID studentId);
}
