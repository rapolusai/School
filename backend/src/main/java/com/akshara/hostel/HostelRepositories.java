package com.akshara.hostel;

/*
 * The hostel module's Spring Data repositories, one interface per table. Kept in one file because each is a few
 * queries; all are package-private and run as the current school.
 */

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface HostelRepository extends JpaRepository<Hostel, UUID> {

    List<Hostel> findAllByOrderByNameAsc();

    List<Hostel> findByWardenUserIdOrderByNameAsc(UUID wardenUserId);

    boolean existsByNameIgnoreCase(String name);
}

interface BlockRepository extends JpaRepository<Block, UUID> {

    List<Block> findByHostelIdOrderByNameAsc(UUID hostelId);

    boolean existsByHostelIdAndNameIgnoreCase(UUID hostelId, String name);

    long countByHostelId(UUID hostelId);
}

interface RoomRepository extends JpaRepository<Room, UUID> {

    List<Room> findByHostelIdOrderByFloorAscNumberAsc(UUID hostelId);

    List<Room> findByBlockIdOrderByFloorAscNumberAsc(UUID blockId);

    boolean existsByBlockIdAndNumberIgnoreCase(UUID blockId, String number);

    long countByBlockId(UUID blockId);

    List<Room> findByIdIn(Collection<UUID> ids);
}

interface BedRepository extends JpaRepository<Bed, UUID> {

    List<Bed> findByRoomIdOrderByLabelAsc(UUID roomId);

    List<Bed> findByHostelIdOrderByLabelAsc(UUID hostelId);

    boolean existsByRoomIdAndLabelIgnoreCase(UUID roomId, String label);

    long countByRoomId(UUID roomId);

    long countByHostelId(UUID hostelId);
}

interface AllotmentRepository extends JpaRepository<Allotment, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Allotment a where a.id = ?1")
    Optional<Allotment> lock(UUID id);

    @Query("select a from Allotment a where a.studentId = ?1 and a.status = 'ACTIVE'")
    Optional<Allotment> activeOfStudent(UUID studentId);

    @Query("select a from Allotment a where a.studentId in ?1 and a.status = 'ACTIVE'")
    List<Allotment> activeOfStudents(Collection<UUID> studentIds);

    @Query("select a from Allotment a where a.bedId = ?1 and a.status = 'ACTIVE'")
    Optional<Allotment> activeOfBed(UUID bedId);

    @Query("select a from Allotment a where a.hostelId = ?1 and a.status = 'ACTIVE'")
    List<Allotment> activeInHostel(UUID hostelId);

    @Query("select a from Allotment a where a.status = 'ACTIVE'")
    List<Allotment> active();

    @Query("select a from Allotment a where a.studentId = ?1 order by a.fromDate desc, a.createdAt desc")
    List<Allotment> historyOfStudent(UUID studentId);

    @Query("select count(a) from Allotment a where a.roomId = ?1 and a.status = 'ACTIVE'")
    long countActiveInRoom(UUID roomId);

    @Query("select count(a) from Allotment a where a.hostelId = ?1 and a.status = 'ACTIVE'")
    long countActiveInHostel(UUID hostelId);

    boolean existsByBedId(UUID bedId);

    boolean existsByRoomId(UUID roomId);
}

interface MenuItemRepository extends JpaRepository<MenuItem, UUID> {

    List<MenuItem> findByHostelIdOrderByDayOfWeekAscMealAsc(UUID hostelId);
}

interface MessAttendanceRepository extends JpaRepository<MessAttendance, UUID> {

    List<MessAttendance> findByHostelIdAndDayOrderByMealAsc(UUID hostelId, LocalDate day);

    @Query("select m from MessAttendance m where m.hostelId = ?1 and m.day >= ?2 and m.day <= ?3 order by m.day, m.meal")
    List<MessAttendance> between(UUID hostelId, LocalDate from, LocalDate to);
}

interface HostelPassRepository extends JpaRepository<HostelPass, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from HostelPass p where p.id = ?1")
    Optional<HostelPass> lock(UUID id);

    @Query("select p from HostelPass p where p.hostelId in ?1 and p.status in ('REQUESTED', 'APPROVED', 'OUT') "
            + "order by p.fromDate, p.createdAt")
    List<HostelPass> openIn(Collection<UUID> hostelIds);

    @Query("select p from HostelPass p where p.status in ('REQUESTED', 'APPROVED', 'OUT') order by p.fromDate, p.createdAt")
    List<HostelPass> open();

    @Query("select p from HostelPass p where p.hostelId in ?1 and p.fromDate >= ?2 and p.fromDate <= ?3 "
            + "order by p.fromDate desc, p.createdAt desc")
    List<HostelPass> betweenIn(Collection<UUID> hostelIds, LocalDate from, LocalDate to);

    @Query("select p from HostelPass p where p.fromDate >= ?1 and p.fromDate <= ?2 order by p.fromDate desc, p.createdAt desc")
    List<HostelPass> between(LocalDate from, LocalDate to);

    @Query("select p from HostelPass p where p.studentId in ?1 order by p.createdAt desc")
    List<HostelPass> ofStudents(Collection<UUID> studentIds);

    @Query("select p from HostelPass p where p.studentId = ?1 and p.status in ('REQUESTED', 'APPROVED', 'OUT')")
    List<HostelPass> openOfStudent(UUID studentId);

    /** Open passes of the hostel's boarders that cover the night (approved or out). */
    @Query("select p from HostelPass p where p.hostelId = ?1 and p.status in ('APPROVED', 'OUT') "
            + "and p.fromDate <= ?2 and p.toDate >= ?2")
    List<HostelPass> awayOn(UUID hostelId, LocalDate night);
}

interface RollCallRepository extends JpaRepository<RollCall, UUID> {

    Optional<RollCall> findByHostelIdAndNight(UUID hostelId, LocalDate night);

    @Query("select r from RollCall r where r.hostelId = ?1 and r.night >= ?2 and r.night <= ?3 order by r.night desc")
    List<RollCall> between(UUID hostelId, LocalDate from, LocalDate to);

    @Query("select r from RollCall r where r.night >= ?1 and r.night <= ?2 order by r.hostelId, r.night")
    List<RollCall> allBetween(LocalDate from, LocalDate to);
}

interface RollCallEntryRepository extends JpaRepository<RollCallEntry, UUID> {

    List<RollCallEntry> findByRollCallId(UUID rollCallId);

    List<RollCallEntry> findByRollCallIdIn(Collection<UUID> rollCallIds);

    @Query("select e from RollCallEntry e where e.studentId = ?1 and e.rollCallId in ?2")
    List<RollCallEntry> ofStudentIn(UUID studentId, Collection<UUID> rollCallIds);
}

interface HostelAlertRepository extends JpaRepository<HostelAlert, UUID> {

    @Query("select a from HostelAlert a where a.hostelId in ?1 and a.resolved = false order by a.day desc, a.createdAt desc")
    List<HostelAlert> openIn(Collection<UUID> hostelIds);

    @Query("select a from HostelAlert a where a.resolved = false order by a.day desc, a.createdAt desc")
    List<HostelAlert> open();

    @Query("select a from HostelAlert a where a.hostelId = ?1 and a.studentId = ?2 and a.kind = ?3 and a.day = ?4")
    Optional<HostelAlert> find(UUID hostelId, UUID studentId, AlertKind kind, LocalDate day);
}

interface ComplaintRepository extends JpaRepository<Complaint, UUID> {

    @Query("select c from Complaint c where c.hostelId in ?1 order by c.createdAt desc")
    List<Complaint> ofHostels(Collection<UUID> hostelIds);

    @Query("select c from Complaint c order by c.createdAt desc")
    List<Complaint> all();

    long countByRoomIdAndStatusNot(UUID roomId, ComplaintStatus status);
}
