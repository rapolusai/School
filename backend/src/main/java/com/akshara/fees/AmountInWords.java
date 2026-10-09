package com.akshara.fees;

/**
 * Amounts in words as Indian receipts print them, with lakhs and crores: 1250000 paise is "Rupees Twelve Thousand Five
 * Hundred Only", and 1,23,45,678.50 rupees is "Rupees One Crore Twenty Three Lakh Forty Five Thousand Six Hundred
 * Seventy Eight and Fifty Paise Only".
 */
public final class AmountInWords {

    private static final String[] ONES = {"Zero", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight",
        "Nine", "Ten", "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen", "Sixteen", "Seventeen", "Eighteen",
        "Nineteen"};
    private static final String[] TENS = {"", "", "Twenty", "Thirty", "Forty", "Fifty", "Sixty", "Seventy", "Eighty",
        "Ninety"};

    private AmountInWords() {
    }

    /** The amount, given in paise, written out in rupees (and paise when there are any). */
    public static String rupees(long paise) {
        if (paise < 0) {
            throw new IllegalArgumentException("amount must not be negative");
        }
        long rupees = paise / 100;
        int rest = (int) (paise % 100);
        StringBuilder text = new StringBuilder("Rupees ").append(words(rupees));
        if (rest > 0) {
            text.append(" and ").append(words(rest)).append(" Paise");
        }
        return text.append(" Only").toString();
    }

    /** A whole number in words with Indian grouping (thousand, lakh, crore). */
    public static String words(long n) {
        if (n < 0) {
            throw new IllegalArgumentException("number must not be negative");
        }
        if (n == 0) {
            return ONES[0];
        }
        StringBuilder out = new StringBuilder();
        long crores = n / 10_000_000;
        long rest = n % 10_000_000;
        if (crores > 0) {
            // Amounts of a hundred crore or more repeat the grouping: "One Hundred Crore".
            append(out, words(crores) + " Crore");
        }
        int lakhs = (int) (rest / 100_000);
        int thousands = (int) (rest % 100_000 / 1_000);
        int hundreds = (int) (rest % 1_000 / 100);
        int lastTwo = (int) (rest % 100);
        if (lakhs > 0) {
            append(out, belowHundred(lakhs) + " Lakh");
        }
        if (thousands > 0) {
            append(out, belowHundred(thousands) + " Thousand");
        }
        if (hundreds > 0) {
            append(out, ONES[hundreds] + " Hundred");
        }
        if (lastTwo > 0) {
            append(out, belowHundred(lastTwo));
        }
        return out.toString();
    }

    private static String belowHundred(int n) {
        if (n < 20) {
            return ONES[n];
        }
        return n % 10 == 0 ? TENS[n / 10] : TENS[n / 10] + " " + ONES[n % 10];
    }

    private static void append(StringBuilder out, String part) {
        if (!out.isEmpty()) {
            out.append(' ');
        }
        out.append(part);
    }
}
