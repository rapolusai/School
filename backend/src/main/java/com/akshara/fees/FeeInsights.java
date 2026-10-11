package com.akshara.fees;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.shared.TenantContext;

/**
 * Read-only fee figures that the fee reports do not already give: collections day by day (dashboard) and dues of one
 * kind of head (the transport fee report).
 */
@Service
@Transactional(readOnly = true)
public class FeeInsights {

    /** The longest range {@link #collectedPerDay} accepts. */
    public static final int MAX_DAYS = 400;

    /** Receipts issued on one day that are not cancelled. */
    public record DayTotal(LocalDate date, long amountPaise, long receiptCount) {
    }

    /**
     * One due of a head of a given kind: what the student owes for it in one instalment. {@code balancePaise} is net of
     * concessions and payments.
     */
    public record KindDue(UUID studentId, UUID instalmentId, LocalDate dueDate, long grossPaise, long concessionPaise,
            long paidPaise) {

        public long netPaise() {
            return grossPaise - concessionPaise;
        }

        public long balancePaise() {
            return grossPaise - concessionPaise - paidPaise;
        }
    }

    private final EntityManager entityManager;

    FeeInsights(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /** Today in India, the day fee receipts are dated with. */
    public static LocalDate today() {
        return SchoolDay.today();
    }

    /**
     * Every day between the two dates (both included) with what was collected, zero on days without receipts. Counts
     * exactly what the collection report counts: issued receipts by their receipt date.
     */
    public List<DayTotal> collectedPerDay(LocalDate from, LocalDate to) {
        TenantContext.require();
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) >= MAX_DAYS) {
            throw new IllegalArgumentException("Give a range of at most " + MAX_DAYS + " days");
        }
        Map<LocalDate, long[]> byDay = new HashMap<>();
        entityManager.createQuery("select r.receivedOn, count(r), sum(r.amountPaise) from Receipt r "
                + "where r.status = 'ISSUED' and r.receivedOn between :from and :to group by r.receivedOn",
                Object[].class)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList()
                .forEach(r -> byDay.put((LocalDate) r[0], new long[] {((Number) r[1]).longValue(),
                        ((Number) r[2]).longValue()}));
        List<DayTotal> days = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            long[] t = byDay.getOrDefault(d, new long[2]);
            days.add(new DayTotal(d, t[1], t[0]));
        }
        return days;
    }

    /**
     * Every due of the academic year under heads of the given kind (for example TRANSPORT), whatever structure or
     * per-student charge it came from, ordered by student and due date. Dues with nothing owed and nothing paid are
     * left out.
     */
    public List<KindDue> duesOfKind(UUID academicYearId, FeeHeadKind kind) {
        TenantContext.require();
        return entityManager.createQuery("select d.studentId, d.instalmentId, d.dueDate, d.grossPaise, "
                + "d.concessionPaise, d.paidPaise from StudentDue d, FeeHead h where h.id = d.headId "
                + "and h.kind = :kind and d.academicYearId = :year and (d.grossPaise > 0 or d.paidPaise > 0) "
                + "order by d.studentId, d.dueDate, d.instalmentId", Object[].class)
                .setParameter("kind", kind)
                .setParameter("year", academicYearId)
                .getResultList()
                .stream()
                .map(r -> new KindDue((UUID) r[0], (UUID) r[1], (LocalDate) r[2], ((Number) r[3]).longValue(),
                        ((Number) r[4]).longValue(), ((Number) r[5]).longValue()))
                .toList();
    }
}
