package com.akshara.billing;

import java.time.LocalDate;

/**
 * GST on Akshara's subscription invoices. Software as a service is taxed at 18%: within the seller's own state as
 * CGST 9% + SGST 9%, to any other state as IGST 18% (the place of supply is the school's state). Each tax is worked out
 * on the taxable value and rounded half up to whole paise, so CGST and SGST are always equal. Pure functions; amounts
 * are paise and nothing uses floating point.
 */
public final class GstMath {

    /** 18% in basis points. */
    public static final int GST_RATE_BP = 1_800;

    /** 9% each for CGST and SGST. */
    public static final int HALF_RATE_BP = GST_RATE_BP / 2;

    private static final int FULL_BP = 10_000;

    /** The two ways 18% GST is split. */
    public enum Split {
        /** Same state: central and state tax, 9% each. */
        CGST_SGST,
        /** Another state: integrated tax, 18%. */
        IGST
    }

    /** The taxes on a taxable value. {@code total} is the taxable value plus every tax. */
    public record Tax(Split split, long taxablePaise, long cgstPaise, long sgstPaise, long igstPaise, long totalPaise) {
    }

    private GstMath() {
    }

    /** CGST + SGST when both states are the same, otherwise IGST. State codes are the two-digit GST codes. */
    public static Split split(String sellerStateCode, String buyerStateCode) {
        return sellerStateCode.equals(buyerStateCode) ? Split.CGST_SGST : Split.IGST;
    }

    public static Tax tax(long taxablePaise, String sellerStateCode, String buyerStateCode) {
        if (taxablePaise < 0) {
            throw new IllegalArgumentException("taxable value must not be negative");
        }
        Split split = split(sellerStateCode, buyerStateCode);
        if (split == Split.CGST_SGST) {
            long half = percentOf(taxablePaise, HALF_RATE_BP);
            return new Tax(split, taxablePaise, half, half, 0, taxablePaise + 2 * half);
        }
        long igst = percentOf(taxablePaise, GST_RATE_BP);
        return new Tax(split, taxablePaise, 0, 0, igst, taxablePaise + igst);
    }

    /** {@code basisPoints} (1% = 100) of an amount, rounded half up to whole paise. */
    public static long percentOf(long amountPaise, int basisPoints) {
        return Math.addExact(Math.multiplyExact(amountPaise, basisPoints), FULL_BP / 2) / FULL_BP;
    }

    /** The Indian financial year (April to March) a date falls in, e.g. 2026-10-09 is "2026-27". */
    public static String financialYear(LocalDate date) {
        int start = date.getMonthValue() >= 4 ? date.getYear() : date.getYear() - 1;
        return start + "-" + String.format("%02d", (start + 1) % 100);
    }

    /**
     * "AKS/26-27/000123". GST invoice numbers may have at most 16 characters (letters, digits, "-" and "/") and must be
     * unique within a financial year, so the prefix has 1 to 3 letters and the year is written short.
     */
    public static String invoiceNo(String prefix, String financialYear, int seq) {
        if (seq < 1 || seq > 999_999) {
            throw new IllegalArgumentException("invoice sequence out of range");
        }
        return prefix + "/" + financialYear.substring(2) + "/" + String.format("%06d", seq);
    }
}
