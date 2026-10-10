package com.akshara.fees;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.shared.TenantContext;

/** Read-only fee figures for the dashboard that the fee reports do not already give: collections day by day. */
@Service
@Transactional(readOnly = true)
public class FeeInsights {

    /** The longest range {@link #collectedPerDay} accepts. */
    public static final int MAX_DAYS = 400;

    /** Receipts issued on one day that are not cancelled. */
    public record DayTotal(LocalDate date, long amountPaise, long receiptCount) {
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
}
