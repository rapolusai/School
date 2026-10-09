package com.akshara.fees;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface FeeReminderRepository extends JpaRepository<FeeReminder, UUID> {

    /** When each student's last reminder was requested. */
    @Query("select r.studentId, max(r.createdAt) from FeeReminder r where r.studentId in ?1 group by r.studentId")
    List<Object[]> lastRequested(Collection<UUID> studentIds);
}
