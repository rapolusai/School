package com.akshara.fees;

import java.math.BigInteger;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Fee arithmetic. Every amount is a whole number of paise (a {@code long}); nothing here uses floating point. Pure
 * functions without database access, so the rules are easy to test and read.
 */
public final class FeeMath {

    /** A due whose date is this close (or closer) is shown as DUE rather than UPCOMING. */
    public static final int DUE_SOON_DAYS = 30;

    /** 100% in basis points. */
    public static final int FULL_PERCENT_BP = 10_000;

    private FeeMath() {
    }

    // ------------------------------------------------------------------ splitting

    /**
     * Splits {@code total} into {@code parts} equal shares rounded down; the last share absorbs the remainder, so the
     * shares always add up to the total exactly.
     */
    public static long[] splitEvenly(long total, int parts) {
        if (parts < 1) {
            throw new IllegalArgumentException("parts must be at least 1");
        }
        if (total < 0) {
            throw new IllegalArgumentException("total must not be negative");
        }
        long[] shares = new long[parts];
        long base = total / parts;
        for (int i = 0; i < parts - 1; i++) {
            shares[i] = base;
        }
        shares[parts - 1] = total - base * (parts - 1);
        return shares;
    }

    /**
     * Splits {@code total} in proportion to {@code weights}, rounding each share down; the last positive weight
     * absorbs the remainder. Zero weights get nothing. With no positive weight everything is zero.
     */
    public static long[] splitProportionally(long total, long[] weights) {
        long[] shares = new long[weights.length];
        long sum = 0;
        int last = -1;
        for (int i = 0; i < weights.length; i++) {
            if (weights[i] < 0) {
                throw new IllegalArgumentException("weights must not be negative");
            }
            if (weights[i] > 0) {
                sum += weights[i];
                last = i;
            }
        }
        if (last < 0 || total == 0) {
            return shares;
        }
        long given = 0;
        for (int i = 0; i < weights.length; i++) {
            if (weights[i] == 0 || i == last) {
                continue;
            }
            // total × weight can exceed a long for large amounts, so the product is taken exactly.
            shares[i] = BigInteger.valueOf(total).multiply(BigInteger.valueOf(weights[i]))
                    .divide(BigInteger.valueOf(sum)).longValueExact();
            given += shares[i];
        }
        shares[last] = total - given;
        return shares;
    }

    /** {@code basisPoints} (1% = 100) of an amount, rounded half up to whole paise. */
    public static long percentOf(long amount, int basisPoints) {
        if (basisPoints < 0 || basisPoints > FULL_PERCENT_BP) {
            throw new IllegalArgumentException("basis points must be between 0 and 10000");
        }
        return (amount * basisPoints + FULL_PERCENT_BP / 2) / FULL_PERCENT_BP;
    }

    // ------------------------------------------------------------------ concessions

    /** One amount owed: a head of one instalment. */
    public record Cell(UUID headId, long grossPaise) {
    }

    /**
     * A concession as the arithmetic sees it: a percentage (basis points) or a fixed amount, on the given heads.
     */
    public record ConcessionRule(ConcessionMode mode, int percentBp, long fixedPaise, Set<UUID> headIds) {
    }

    /**
     * The concession on each cell. A percentage applies to every cell of the chosen heads (rounded half up per cell);
     * a fixed amount is spread over those cells in proportion to their amounts, the last one absorbing rounding, and
     * never exceeds their total. Several concessions add up, but a cell's concession never exceeds its amount, so a
     * due never drops below zero.
     */
    public static long[] concessions(List<Cell> cells, List<ConcessionRule> rules) {
        long[] result = new long[cells.size()];
        for (ConcessionRule rule : rules) {
            long[] weights = new long[cells.size()];
            for (int i = 0; i < cells.size(); i++) {
                Cell cell = cells.get(i);
                weights[i] = rule.headIds().contains(cell.headId()) ? cell.grossPaise() : 0;
            }
            if (rule.mode() == ConcessionMode.PERCENT) {
                for (int i = 0; i < cells.size(); i++) {
                    result[i] += percentOf(weights[i], rule.percentBp());
                }
            } else {
                long eligible = 0;
                for (long w : weights) {
                    eligible += w;
                }
                long[] spread = splitProportionally(Math.min(rule.fixedPaise(), eligible), weights);
                for (int i = 0; i < cells.size(); i++) {
                    result[i] += spread[i];
                }
            }
        }
        for (int i = 0; i < cells.size(); i++) {
            result[i] = Math.min(result[i], cells.get(i).grossPaise());
        }
        return result;
    }

    // ------------------------------------------------------------------ late fees

    /** A school's late-fee rule. Amounts in paise. */
    public record LateFeeRule(LateFeeMode mode, int graceDays, long flatPaise, long perDayPaise, long capPaise) {

        public static final LateFeeRule NONE = new LateFeeRule(LateFeeMode.NONE, 0, 0, 0, 0);
    }

    /**
     * Days an instalment is late on {@code asOf}, counting only the days after the grace period; 0 when it is not late.
     */
    public static long daysLate(LateFeeRule rule, LocalDate dueDate, LocalDate asOf) {
        long days = ChronoUnit.DAYS.between(dueDate.plusDays(rule.graceDays()), asOf);
        return Math.max(0, days);
    }

    /**
     * The late fee for one instalment that is still unpaid on {@code asOf}. FLAT charges the flat amount once the
     * grace days have passed; PER_DAY charges per day after the grace days, up to the cap (a cap of 0 means no cap).
     */
    public static long lateFee(LateFeeRule rule, LocalDate dueDate, LocalDate asOf) {
        long days = daysLate(rule, dueDate, asOf);
        if (days == 0) {
            return 0;
        }
        return switch (rule.mode()) {
            case NONE -> 0;
            case FLAT -> rule.flatPaise();
            case PER_DAY -> {
                long amount = Math.multiplyExact(rule.perDayPaise(), days);
                yield rule.capPaise() > 0 ? Math.min(amount, rule.capPaise()) : amount;
            }
        };
    }

    // ------------------------------------------------------------------ allocation

    /**
     * Allocates a payment to outstanding amounts in the order given (oldest due first): each one is filled before the
     * next gets anything, so a short payment leaves only the newest items part paid. Returns what each item gets; any
     * amount above the total outstanding is left over for the caller to handle.
     */
    public static long[] allocate(long amount, long[] outstanding) {
        if (amount < 0) {
            throw new IllegalArgumentException("amount must not be negative");
        }
        long[] allocated = new long[outstanding.length];
        long left = amount;
        for (int i = 0; i < outstanding.length && left > 0; i++) {
            long take = Math.min(left, Math.max(0, outstanding[i]));
            allocated[i] = take;
            left -= take;
        }
        return allocated;
    }

    // ------------------------------------------------------------------ status

    /** Status of an amount owed (net of concessions) with what is paid, due on {@code dueDate}, as of {@code today}. */
    public static DueStatus status(long netPaise, long paidPaise, LocalDate dueDate, LocalDate today) {
        long balance = netPaise - paidPaise;
        if (balance <= 0) {
            return DueStatus.PAID;
        }
        if (today.isAfter(dueDate)) {
            return DueStatus.OVERDUE;
        }
        if (paidPaise > 0) {
            return DueStatus.PARTIAL;
        }
        return ChronoUnit.DAYS.between(today, dueDate) <= DUE_SOON_DAYS ? DueStatus.DUE : DueStatus.UPCOMING;
    }

    // ------------------------------------------------------------------ receipts

    /** The Indian financial year (April to March) a date falls in, e.g. 2026-10-09 is "2026-27". */
    public static String financialYear(LocalDate date) {
        int start = date.getMonthValue() >= 4 ? date.getYear() : date.getYear() - 1;
        return start + "-" + String.format("%02d", (start + 1) % 100);
    }

    /** "RCPT/2026-27/000123". */
    public static String receiptNo(String financialYear, int seq) {
        return "RCPT/" + financialYear + "/" + String.format("%06d", seq);
    }
}
