package com.akshara.certificates;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Serial numbers, verification tokens and the little masking the module needs, free of the database so they can be
 * tested on their own.
 */
public final class Serials {

    /** School days are days in India. */
    public static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    /** Tokens use letters and digits only, so the QR code stays small and the address needs no escaping. */
    static final String TOKEN_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    static final int TOKEN_LENGTH = 24;
    /** The key the ID card counter uses in the serial counter table. */
    static final String ID_CARD_COUNTER = "ID_CARD";
    static final String ID_CARD_PREFIX = "ID";

    private static final SecureRandom RANDOM = new SecureRandom();

    private Serials() {
    }

    /** Today in India. */
    public static LocalDate today() {
        return LocalDate.now(INDIA);
    }

    /** A serial number: the type's prefix, the academic year and a four-digit running number ("TC/2026-27/0001"). */
    public static String serialNo(String prefix, String academicYear, int seq) {
        if (seq < 1) {
            throw new IllegalArgumentException("Serial numbers start at 1");
        }
        return prefix + "/" + academicYear + "/" + String.format("%04d", seq);
    }

    /** The Indian academic year (April to March) of a day, for schools without a current year set: "2026-27". */
    public static String academicYear(LocalDate date) {
        int start = date.getMonthValue() >= 4 ? date.getYear() : date.getYear() - 1;
        return start + "-" + String.format("%02d", (start + 1) % 100);
    }

    /**
     * A year's name as the counter keys it: "2026-27" as given; "2026-2027" shortened; anything else (a name like
     * "Session 2026") falls back to the academic year of the day.
     */
    public static String yearKey(String yearName, LocalDate day) {
        if (yearName != null) {
            String name = yearName.trim();
            if (name.matches("^\\d{4}-\\d{2}$")) {
                return name;
            }
            if (name.matches("^\\d{4}-\\d{4}$")) {
                return name.substring(0, 5) + name.substring(7);
            }
        }
        return academicYear(day);
    }

    /** A random verification token of {@value #TOKEN_LENGTH} letters and digits. */
    public static String newToken() {
        StringBuilder sb = new StringBuilder(TOKEN_LENGTH);
        for (int i = 0; i < TOKEN_LENGTH; i++) {
            sb.append(TOKEN_ALPHABET.charAt(RANDOM.nextInt(TOKEN_ALPHABET.length())));
        }
        return sb.toString();
    }

    /** True when the text could be a token this module made (letters and digits, the right length). */
    public static boolean looksLikeToken(String token) {
        return token != null && token.length() == TOKEN_LENGTH && token.chars().allMatch(
                c -> TOKEN_ALPHABET.indexOf(c) >= 0);
    }

    /** "9876543210" → "XXXXXX3210": a phone number as screens show it. */
    public static String maskPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return null;
        }
        String digits = phone.replaceAll("[^0-9]", "");
        if (digits.length() <= 4) {
            return "X".repeat(digits.length());
        }
        return "X".repeat(digits.length() - 4) + digits.substring(digits.length() - 4);
    }

    /** "Asha Rao Menon" → "Asha". */
    public static String firstName(String name) {
        if (name == null) {
            return null;
        }
        String trimmed = name.trim();
        int space = trimmed.indexOf(' ');
        return space < 0 ? trimmed : trimmed.substring(0, space);
    }
}
