package com.akshara.library;

import java.util.Locale;
import java.util.Optional;

/**
 * International Standard Book Numbers. An ISBN-10 has nine digits and a check character (0-9 or X, weights 10 down to 1,
 * the sum divisible by 11); an ISBN-13 has twelve digits and a check digit (weights 1 and 3 alternating, the sum
 * divisible by 10) and starts with 978 or 979. Every ISBN-10 is also the ISBN-13 "978" + its first nine digits + a new
 * check digit; only a 978 ISBN-13 has an ISBN-10. Spaces and hyphens are ignored, as printed on books.
 */
public final class Isbn {

    /** A valid ISBN as digits only: always the ISBN-13, and the ISBN-10 when there is one (978 prefix). */
    public record Parsed(String isbn13, String isbn10) {
    }

    private Isbn() {
    }

    /** The ISBN without spaces and hyphens, with a lower-case x made upper case. Null stays null. */
    public static String clean(String raw) {
        if (raw == null) {
            return null;
        }
        return raw.replaceAll("[\\s\\-‐‑‒–]", "").toUpperCase(Locale.ROOT);
    }

    /** The ISBN-13 and ISBN-10 of a valid ISBN, or empty when it is not one. */
    public static Optional<Parsed> parse(String raw) {
        String isbn = clean(raw);
        if (isbn == null) {
            return Optional.empty();
        }
        if (isValid10(isbn)) {
            return Optional.of(new Parsed(to13(isbn), isbn));
        }
        if (isValid13(isbn)) {
            return Optional.of(new Parsed(isbn, to10(isbn).orElse(null)));
        }
        return Optional.empty();
    }

    /** What is wrong with an ISBN, in words for the person typing it, or null when it is valid. */
    public static String problem(String raw) {
        String isbn = clean(raw);
        if (isbn == null || isbn.isEmpty()) {
            return "Enter the ISBN.";
        }
        if (isbn.length() == 10 && isbn.substring(0, 9).chars().allMatch(Character::isDigit)
                && (Character.isDigit(isbn.charAt(9)) || isbn.charAt(9) == 'X')) {
            return isValid10(isbn) ? null : "This ISBN-10 does not add up: check the digits (the last one is a check).";
        }
        if (isbn.length() == 13 && isbn.chars().allMatch(Character::isDigit)) {
            if (!isbn.startsWith("978") && !isbn.startsWith("979")) {
                return "An ISBN-13 starts with 978 or 979.";
            }
            return isValid13(isbn) ? null : "This ISBN-13 does not add up: check the digits (the last one is a check).";
        }
        return "An ISBN has 10 or 13 digits (an ISBN-10 may end in X).";
    }

    public static boolean isValid10(String isbn) {
        if (isbn == null || isbn.length() != 10) {
            return false;
        }
        for (int i = 0; i < 9; i++) {
            if (!Character.isDigit(isbn.charAt(i))) {
                return false;
            }
        }
        char last = isbn.charAt(9);
        if (!Character.isDigit(last) && last != 'X') {
            return false;
        }
        return check10(isbn.substring(0, 9)) == last;
    }

    public static boolean isValid13(String isbn) {
        if (isbn == null || isbn.length() != 13 || !isbn.chars().allMatch(Character::isDigit)) {
            return false;
        }
        if (!isbn.startsWith("978") && !isbn.startsWith("979")) {
            return false;
        }
        return check13(isbn.substring(0, 12)) == isbn.charAt(12);
    }

    /** The ISBN-13 of a valid ISBN-10. */
    public static String to13(String isbn10) {
        if (!isValid10(isbn10)) {
            throw new IllegalArgumentException("Not a valid ISBN-10");
        }
        String first12 = "978" + isbn10.substring(0, 9);
        return first12 + check13(first12);
    }

    /** The ISBN-10 of a valid ISBN-13 that starts with 978; empty for 979 numbers, which have none. */
    public static Optional<String> to10(String isbn13) {
        if (!isValid13(isbn13)) {
            throw new IllegalArgumentException("Not a valid ISBN-13");
        }
        if (!isbn13.startsWith("978")) {
            return Optional.empty();
        }
        String first9 = isbn13.substring(3, 12);
        return Optional.of(first9 + check10(first9));
    }

    /** The ISBN-10 check character of nine digits: 0-9 or X (for 10). */
    static char check10(String first9) {
        int sum = 0;
        for (int i = 0; i < 9; i++) {
            sum += (10 - i) * (first9.charAt(i) - '0');
        }
        int check = (11 - sum % 11) % 11;
        return check == 10 ? 'X' : (char) ('0' + check);
    }

    /** The ISBN-13 check digit of twelve digits. */
    static char check13(String first12) {
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            sum += (i % 2 == 0 ? 1 : 3) * (first12.charAt(i) - '0');
        }
        return (char) ('0' + (10 - sum % 10) % 10);
    }
}
