package com.akshara.library;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface ReservationRepository extends JpaRepository<Reservation, UUID> {

    /** The title's queue: holds being kept and holds waiting, in the order members asked. */
    @Query("select r from Reservation r where r.titleId = ?1 and r.status in "
            + "(com.akshara.library.ReservationStatus.WAITING, com.akshara.library.ReservationStatus.READY) "
            + "order by r.createdAt, r.id")
    List<Reservation> queueOf(UUID titleId);

    @Query("select r from Reservation r where r.titleId = ?1 and r.status = "
            + "com.akshara.library.ReservationStatus.WAITING order by r.createdAt, r.id")
    List<Reservation> waitingFor(UUID titleId);

    @Query("select r from Reservation r where r.copyId = ?1 and r.status = "
            + "com.akshara.library.ReservationStatus.READY")
    Optional<Reservation> readyWithCopy(UUID copyId);

    @Query("select r from Reservation r where r.status = com.akshara.library.ReservationStatus.READY "
            + "and r.holdUntil < ?1 order by r.holdUntil, r.id")
    List<Reservation> readyExpiredBefore(LocalDate today);

    @Query("select r from Reservation r where r.status in "
            + "(com.akshara.library.ReservationStatus.WAITING, com.akshara.library.ReservationStatus.READY) "
            + "order by r.titleId, r.createdAt, r.id")
    List<Reservation> allActive();

    @Query("select r from Reservation r where r.status in "
            + "(com.akshara.library.ReservationStatus.WAITING, com.akshara.library.ReservationStatus.READY) "
            + "and r.titleId in ?1 order by r.titleId, r.createdAt, r.id")
    List<Reservation> activeOfTitles(Collection<UUID> titleIds);

    @Query("select r from Reservation r where r.studentId = ?1 and r.status in "
            + "(com.akshara.library.ReservationStatus.WAITING, com.akshara.library.ReservationStatus.READY) "
            + "order by r.createdAt, r.id")
    List<Reservation> activeOfStudent(UUID studentId);

    @Query("select r from Reservation r where r.staffUserId = ?1 and r.status in "
            + "(com.akshara.library.ReservationStatus.WAITING, com.akshara.library.ReservationStatus.READY) "
            + "order by r.createdAt, r.id")
    List<Reservation> activeOfStaff(UUID userId);

    @Query("select r from Reservation r where r.studentId in ?1 and r.status in "
            + "(com.akshara.library.ReservationStatus.WAITING, com.akshara.library.ReservationStatus.READY) "
            + "order by r.createdAt, r.id")
    List<Reservation> activeOfStudents(Collection<UUID> studentIds);

    boolean existsByTitleId(UUID titleId);
}
