package com.akshara.shared;

import java.util.Locale;
import java.util.Set;

/**
 * Rules for a new password beyond its length (10 to 200 characters, checked on the form): it must not be one of the
 * passwords people guess first, a run of one character or a few repeated ones, or a straight sequence such as
 * "1234567890" or "qwertyuiop". Every place that sets a password calls {@link #check}, so a school admin, a staff
 * member and a parent get the same answer.
 */
public final class PasswordRules {

    /** Fewer different characters than this ("1111111111", "1212121212", "abcabcabca") is too easy to guess. */
    static final int MIN_DISTINCT = 4;

    private static final String[] SEQUENCES = {
        "01234567890123456789", "09876543210987654321", "abcdefghijklmnopqrstuvwxyz", "zyxwvutsrqponmlkjihgfedcba",
        "qwertyuiopasdfghjklzxcvbnm", "1qaz2wsx3edc4rfv5tgb", "!@#$%^&*()",
    };

    /** Common passwords of 10 or more characters, compared ignoring case. */
    private static final Set<String> COMMON = Set.of(
            "password12", "password123", "password1234", "password@1", "password@12", "password@123",
            "password@1234", "password!1", "password!12", "passw0rd123", "p@ssword123", "p@ssw0rd123",
            "iloveyou12", "iloveyou123", "welcome123", "welcome@12", "welcome@123", "welcome1234", "admin12345",
            "admin@1234", "admin@12345", "administrator", "school1234", "school@123", "school@1234",
            "principal1", "principal@1", "principal123", "teacher123", "teacher@123", "student123", "student@123",
            "india@1234", "india12345", "changeme123", "letmein123", "football12", "baseball12", "sunshine12",
            "princess12", "computer12", "superman12", "trustno1234", "abc1234567", "abcd123456", "abcde12345",
            "abc@123456", "aa12345678", "a123456789", "1234567890a", "123456789a", "qwerty1234", "qwerty@123",
            "qwerty12345", "1q2w3e4r5t", "1q2w3e4r5t6y", "monkey1234", "dragon1234", "master1234", "hello12345",
            "hello@1234", "akshara123", "akshara@123");

    private PasswordRules() {
    }

    /** Throws 400 on {@code field} when the password is too easy to guess. */
    public static void check(String password, String field) {
        String problem = problem(password);
        if (problem != null) {
            throw ApiException.badRequest(problem, field);
        }
    }

    /** Why the password is too easy to guess, or null when it is fine. Length is checked elsewhere. */
    static String problem(String password) {
        if (password == null) {
            return null;
        }
        String lower = password.toLowerCase(Locale.ROOT);
        if (lower.chars().distinct().count() < MIN_DISTINCT) {
            return "This password repeats the same few characters. Mix letters, numbers and symbols.";
        }
        if (COMMON.contains(lower)) {
            return "This password is one of the first people try. Choose something less common.";
        }
        for (String sequence : SEQUENCES) {
            if (sequence.contains(lower)) {
                return "This password is a straight run of keys. Choose something less predictable.";
            }
        }
        return null;
    }
}
