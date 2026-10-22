package com.akshara.accounts;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/** GST, TDS and budget arithmetic in paise: nothing is ever a floating point number. */
public final class AccountsMath {

    /** Below this share of a budget nothing is said; from here on the head is "nearly used up". */
    public static final int NEAR_BUDGET_PERCENT = 90;

    private AccountsMath() {
    }

    /** GST on an amount at a rate ("18.00"), rounded to the nearest paisa (half up). */
    public static long gst(long amountPaise, BigDecimal ratePercent) {
        return percentOf(amountPaise, ratePercent);
    }

    /**
     * TDS to keep back from a vendor: the rate applied to the amount before GST (CBDT circular 23/2017), rounded to
     * the nearest paisa. Zero when the vendor has no section.
     */
    public static long tds(long amountBeforeGstPaise, BigDecimal ratePercent) {
        return ratePercent == null ? 0 : percentOf(amountBeforeGstPaise, ratePercent);
    }

    static long percentOf(long paise, BigDecimal ratePercent) {
        if (ratePercent == null || ratePercent.signum() <= 0 || paise <= 0) {
            return 0;
        }
        return BigDecimal.valueOf(paise).multiply(ratePercent)
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP).longValueExact();
    }

    /** Whole percent of a budget used (0 when there is no budget), capped at 999. */
    public static int percentUsed(long actualPaise, long budgetPaise) {
        if (budgetPaise <= 0) {
            return 0;
        }
        long percent = Math.round(actualPaise * 100.0 / budgetPaise);
        return (int) Math.min(999, percent);
    }

    /** OK, NEAR (90% or more used) or OVER (more than the budget); NONE without a budget. */
    public static String budgetStatus(long actualPaise, long budgetPaise) {
        if (budgetPaise <= 0) {
            return "NONE";
        }
        if (actualPaise > budgetPaise) {
            return "OVER";
        }
        return percentUsed(actualPaise, budgetPaise) >= NEAR_BUDGET_PERCENT ? "NEAR" : "OK";
    }

    /** A yearly amount split evenly over 12 months; the odd paise go to the last months. */
    public static List<Long> spreadEvenly(long annualPaise) {
        List<Long> months = new ArrayList<>(12);
        long base = annualPaise / 12;
        long rest = annualPaise - base * 12;
        for (int i = 0; i < 12; i++) {
            months.add(base + (i >= 12 - rest ? 1 : 0));
        }
        return months;
    }
}
