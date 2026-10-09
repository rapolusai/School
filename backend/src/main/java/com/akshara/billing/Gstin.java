package com.akshara.billing;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * GST state codes and GSTIN checks. A GSTIN is 15 characters: the two-digit state code, the holder's PAN (five
 * letters, four digits, a letter), an entity number, the letter Z and a check character (mod-36 checksum over the
 * first 14 characters).
 */
public final class Gstin {

    /**
     * Two-digit GST state and union territory codes in use (25 and 28 were retired when Daman and Diu merged into 26
     * and Andhra Pradesh moved to 37).
     */
    public static final Set<String> STATE_CODES = Set.of("01", "02", "03", "04", "05", "06", "07", "08", "09", "10",
            "11", "12", "13", "14", "15", "16", "17", "18", "19", "20", "21", "22", "23", "24", "26", "27", "29", "30",
            "31", "32", "33", "34", "35", "36", "37", "38");

    private static final Pattern FORMAT = Pattern.compile("^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$");
    private static final String CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    private Gstin() {
    }

    public static boolean isStateCode(String code) {
        return code != null && STATE_CODES.contains(code);
    }

    /** Whether the text has the GSTIN shape and a correct check character. */
    public static boolean isValid(String gstin) {
        return gstin != null && FORMAT.matcher(gstin).matches() && checkCharacter(gstin.substring(0, 14))
                == gstin.charAt(14);
    }

    /** The check character for the first 14 characters of a GSTIN. */
    public static char checkCharacter(String first14) {
        int sum = 0;
        for (int i = 0; i < 14; i++) {
            int value = CHARS.indexOf(first14.charAt(i));
            if (value < 0) {
                throw new IllegalArgumentException("not a GSTIN character");
            }
            int product = value * (i % 2 == 0 ? 1 : 2);
            sum += product / 36 + product % 36;
        }
        return CHARS.charAt((36 - sum % 36) % 36);
    }
}
