package com.akshara.library;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface LoanRepository extends JpaRepository<Loan, UUID> {

    /** The open loan of a copy, if it is out. */
    @Query("select l from Loan l where l.copyId = ?1 and l.status = com.akshara.library.LoanStatus.ISSUED")
    Optional<Loan> openOfCopy(UUID copyId);

    @Query("select l from Loan l where l.copyId in ?1 and l.status = com.akshara.library.LoanStatus.ISSUED")
    List<Loan> openOfCopies(Collection<UUID> copyIds);

    @Query("select l from Loan l where l.studentId = ?1 and l.status = com.akshara.library.LoanStatus.ISSUED "
            + "order by l.dueOn, l.id")
    List<Loan> openOfStudent(UUID studentId);

    @Query("select l from Loan l where l.staffUserId = ?1 and l.status = com.akshara.library.LoanStatus.ISSUED "
            + "order by l.dueOn, l.id")
    List<Loan> openOfStaff(UUID userId);

    @Query("select l from Loan l where l.studentId in ?1 and l.status = com.akshara.library.LoanStatus.ISSUED "
            + "order by l.dueOn, l.id")
    List<Loan> openOfStudents(Collection<UUID> studentIds);

    @Query("select l from Loan l where l.studentId = ?1 order by l.issuedOn desc, l.createdAt desc")
    List<Loan> historyOfStudent(UUID studentId);

    @Query("select l from Loan l where l.staffUserId = ?1 order by l.issuedOn desc, l.createdAt desc")
    List<Loan> historyOfStaff(UUID userId);

    long countByTitleId(UUID titleId);

    boolean existsByTitleId(UUID titleId);

    @Query("select l from Loan l where l.status = com.akshara.library.LoanStatus.ISSUED and l.dueOn < ?1 "
            + "order by l.dueOn, l.issuedOn, l.id")
    List<Loan> overdue(LocalDate today);

    @Query("select l from Loan l where l.status = com.akshara.library.LoanStatus.ISSUED and l.dueOn < ?1 "
            + "and l.memberType = com.akshara.library.MemberType.STUDENT order by l.dueOn, l.id")
    List<Loan> overdueOfStudents(LocalDate today);

    @Query("select l from Loan l where l.issuedOn = ?1 order by l.createdAt, l.id")
    List<Loan> issuedOn(LocalDate day);

    /** Loans per title issued between the two days (both included), most first: {titleId, count}. */
    @Query("select l.titleId, count(l) from Loan l where l.issuedOn between ?1 and ?2 group by l.titleId "
            + "order by count(l) desc, l.titleId")
    List<Object[]> mostBorrowed(LocalDate from, LocalDate to, Limit limit);

    @Query("select count(l) from Loan l where l.status = com.akshara.library.LoanStatus.ISSUED")
    long countOpen();

    @Query("select count(l) from Loan l where l.status = com.akshara.library.LoanStatus.ISSUED and l.dueOn < ?1")
    long countOverdue(LocalDate today);

    @Query("select count(l) from Loan l where l.status = com.akshara.library.LoanStatus.ISSUED and l.dueOn = ?1")
    long countDueOn(LocalDate day);

    @Query("select l from Loan l where l.id in ?1")
    List<Loan> byIds(Collection<UUID> ids);

    /** Schools with library messages on and a student's book past its due date (works with no school selected). */
    @Query(value = "select tenant_id from library.tenants_with_overdue_reminders(?1)", nativeQuery = true)
    List<UUID> tenantsWithOverdueReminders(LocalDate today);
}
