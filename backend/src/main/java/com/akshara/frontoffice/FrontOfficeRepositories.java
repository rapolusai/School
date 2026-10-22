package com.akshara.frontoffice;

/*
 * The front office module's Spring Data repositories, one interface per table. Kept in one file because each is a
 * few queries; all are package-private and run as the current school.
 */

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface VisitorRepository extends JpaRepository<Visitor, UUID> {

    /** Visitors checked in between two instants (a day), newest first. */
    @Query("select v from Visitor v where v.checkedInAt >= ?1 and v.checkedInAt < ?2 order by v.checkedInAt desc")
    List<Visitor> checkedInBetween(Instant from, Instant to);

    /** Visitors checked in between two instants, oldest first (the register and reports). */
    @Query("select v from Visitor v where v.checkedInAt >= ?1 and v.checkedInAt < ?2 order by v.checkedInAt")
    List<Visitor> registerBetween(Instant from, Instant to);

    @Query("select v from Visitor v where v.status = 'IN' order by v.checkedInAt desc")
    List<Visitor> inside();

    @Query("select v from Visitor v where v.status = 'EXPECTED' and v.expectedOn >= ?1 order by v.expectedOn, v.name")
    List<Visitor> expectedFrom(LocalDate day);

    @Query("select count(v) from Visitor v where v.status = 'IN'")
    long countInside();

    @Query("select coalesce(max(v.badgeNo), '') from Visitor v where v.checkedInAt >= ?1 and v.checkedInAt < ?2")
    String lastBadgeBetween(Instant from, Instant to);
}

interface BlockedVisitorRepository extends JpaRepository<BlockedVisitor, UUID> {

    List<BlockedVisitor> findAllByOrderByNameAsc();
}

interface GatePassRepository extends JpaRepository<GatePass, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from GatePass p where p.id = ?1")
    Optional<GatePass> lock(UUID id);

    /** Passes whose pickup time falls between two instants, latest first. */
    @Query("select p from GatePass p where p.pickupAt >= ?1 and p.pickupAt < ?2 order by p.pickupAt desc")
    List<GatePass> between(Instant from, Instant to);

    @Query("select p from GatePass p where p.status in ('REQUESTED', 'APPROVED') order by p.pickupAt")
    List<GatePass> open();

    @Query("select p from GatePass p where p.studentId in ?1 order by p.createdAt desc")
    List<GatePass> ofStudents(Collection<UUID> studentIds);

    @Query("select p from GatePass p where p.studentId = ?1 and p.status in ('REQUESTED', 'APPROVED')")
    List<GatePass> openOfStudent(UUID studentId);

    @Query("select count(p) from GatePass p where p.status = ?1")
    long countByStatus(PassStatus status);
}

interface PickupPersonRepository extends JpaRepository<PickupPerson, UUID> {

    List<PickupPerson> findByStudentIdOrderByNameAsc(UUID studentId);

    List<PickupPerson> findByStudentIdInOrderByNameAsc(Collection<UUID> studentIds);

    long countByStudentId(UUID studentId);
}

interface CallLogRepository extends JpaRepository<CallLog, UUID> {

    @Query("select c from CallLog c where c.calledAt >= ?1 and c.calledAt < ?2 order by c.calledAt desc")
    List<CallLog> between(Instant from, Instant to);

    @Query("select c from CallLog c where c.status = 'OPEN' and c.followUpOn is not null order by c.followUpOn, c.calledAt")
    List<CallLog> withFollowUp();

    @Query("select c from CallLog c order by c.calledAt desc")
    List<CallLog> latest(org.springframework.data.domain.Limit limit);
}

interface EnquiryRepository extends JpaRepository<Enquiry, UUID> {

    @Query("select e from Enquiry e where e.enquiredAt >= ?1 and e.enquiredAt < ?2 order by e.enquiredAt desc")
    List<Enquiry> between(Instant from, Instant to);

    @Query("select e from Enquiry e where e.status in ('OPEN', 'FOLLOW_UP') order by e.followUpOn nulls last, e.enquiredAt")
    List<Enquiry> open();

    @Query("select e from Enquiry e order by e.enquiredAt desc")
    List<Enquiry> latest(org.springframework.data.domain.Limit limit);
}

interface CourierItemRepository extends JpaRepository<CourierItem, UUID> {

    @Query("select c from CourierItem c where c.itemDate >= ?1 and c.itemDate <= ?2 order by c.itemDate desc, c.createdAt desc")
    List<CourierItem> between(LocalDate from, LocalDate to);
}
