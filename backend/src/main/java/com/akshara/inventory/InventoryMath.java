package com.akshara.inventory;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Collection;

/** Stock, GST and depreciation arithmetic, in whole paise and whole units; no floating point. */
public final class InventoryMath {

    private InventoryMath() {
    }

    /** The stock on hand: the sum of the movements' quantities, each with its direction. */
    static long stock(Collection<StockMovement> movements) {
        long total = 0;
        for (StockMovement m : movements) {
            total += (long) m.getDirection() * m.getQuantity();
        }
        return total;
    }

    /** +1 for a movement that adds to stock, -1 for one that takes from it; an adjustment says which itself. */
    public static int direction(MovementKind kind, boolean adds) {
        return switch (kind) {
            case RECEIPT, RETURN -> 1;
            case ISSUE, WRITE_OFF -> -1;
            case ADJUSTMENT -> adds ? 1 : -1;
        };
    }

    /** GST on an amount at a rate in basis points, rounded half up to the paisa. */
    public static long gst(long amountPaise, int rateBp) {
        return Math.floorDiv(amountPaise * rateBp + 5_000, 10_000);
    }

    /**
     * Straight-line depreciation to a date: cost × rate × whole days held / 365, capped at the cost. Nothing before
     * the purchase date, nothing at a zero rate.
     */
    public static long depreciation(long costPaise, int rateBp, LocalDate purchaseDate, LocalDate asOf) {
        if (rateBp <= 0 || costPaise <= 0 || asOf == null || !asOf.isAfter(purchaseDate)) {
            return 0;
        }
        long days = ChronoUnit.DAYS.between(purchaseDate, asOf);
        long perYear = Math.floorDiv(costPaise * rateBp, 10_000);
        long total = Math.floorDiv(perYear * days, 365);
        return Math.min(total, costPaise);
    }

    /** The cost less its depreciation to the date. */
    public static long writtenDown(long costPaise, int rateBp, LocalDate purchaseDate, LocalDate asOf) {
        return costPaise - depreciation(costPaise, rateBp, purchaseDate, asOf);
    }
}
