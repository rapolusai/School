package com.akshara.homework;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface HomeworkRepository extends JpaRepository<Homework, UUID> {

    List<Homework> findByAcademicYearId(UUID academicYearId);

    /** Homework of the year set for any of the sections. */
    @Query("select h from Homework h where h.academicYearId = ?1 and h.id in "
            + "(select hs.homeworkId from HomeworkSection hs where hs.sectionId in ?2)")
    List<Homework> findForSections(UUID academicYearId, Collection<UUID> sectionIds);

    /** Homework with online submission due on the day whose evening-before reminder has not run. */
    @Query("select h from Homework h where h.onlineSubmission = true and h.dueOn = ?1 and h.dueReminderAt is null")
    List<Homework> findDueWithoutReminder(LocalDate dueOn);

    /** Schools with reminders switched on and homework due on the day. Works without a school selected (see V9). */
    @Query(value = "select tenant_id from homework.tenants_with_due_reminders(?1)", nativeQuery = true)
    List<UUID> tenantsWithDueReminders(LocalDate dueOn);

    long countByAcademicYearId(UUID academicYearId);
}
