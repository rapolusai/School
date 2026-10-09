package com.akshara.students;

/**
 * Indian mobile numbers: accepted with or without +91, 0, spaces or hyphens; stored as ten digits. Public so that
 * other modules that collect parents' numbers (admissions) apply exactly the same rule.
 */
public final class Phones {

    /** What the API accepts before normalising. */
    public static final String INPUT_PATTERN = "^(\\+91|91|0)?[\\s-]*[6-9](?:[\\s-]*[0-9]){9}$";
    public static final String MESSAGE = "Enter a 10-digit Indian mobile number.";

    private Phones() {
    }

    /** Returns the ten-digit number, or null when the input is not an Indian mobile number. */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (!trimmed.matches(INPUT_PATTERN)) {
            return null;
        }
        String digits = trimmed.replaceAll("[^0-9]", "");
        if (digits.length() == 12 && digits.startsWith("91")) {
            digits = digits.substring(2);
        } else if (digits.length() == 11 && digits.startsWith("0")) {
            digits = digits.substring(1);
        }
        return digits.matches("^[6-9][0-9]{9}$") ? digits : null;
    }
}
