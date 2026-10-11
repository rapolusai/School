package com.akshara.lessons;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Marks objective answers of an online test. Pure: no database, so the rules are easy to test.
 * <ul>
 * <li>MCQ (one correct): full marks for exactly the correct option, otherwise 0.</li>
 * <li>MCQ (several correct): partial marks, marks x (right picks - wrong picks) / correct options, never below 0;
 * picking every option therefore scores 0 unless every option is correct.</li>
 * <li>True/false: full marks when the answer matches.</li>
 * <li>Fill in the blank: full marks when the answer matches one of the accepted answers, ignoring case, spaces at the
 * ends and repeated spaces inside.</li>
 * <li>Short answer: marked by the teacher (null); a blank short answer is 0.</li>
 * </ul>
 * Marks are rounded to two decimals, half up.
 */
final class AutoMarker {

    private AutoMarker() {
    }

    /** The marks for an answer, or null when a teacher has to mark it. */
    static BigDecimal mark(QuestionType type, BigDecimal marks, Collection<UUID> correctOptionIds, String answerText,
            Collection<UUID> selected, String response) {
        return switch (type) {
            case MCQ_SINGLE -> selected.size() == 1 && correctOptionIds.containsAll(selected)
                    && correctOptionIds.size() == 1 ? scale(marks) : zero();
            case MCQ_MULTI -> multi(marks, correctOptionIds, selected);
            case TRUE_FALSE -> trueFalse(answerText) != null && trueFalse(answerText).equals(trueFalse(response))
                    ? scale(marks) : zero();
            case FILL_BLANK -> accepted(answerText).contains(normalise(response)) && !normalise(response).isEmpty()
                    ? scale(marks) : zero();
            case SHORT_ANSWER -> response == null || response.isBlank() ? zero() : null;
        };
    }

    private static BigDecimal multi(BigDecimal marks, Collection<UUID> correct, Collection<UUID> selected) {
        Set<UUID> right = new HashSet<>(correct);
        if (right.isEmpty() || selected.isEmpty()) {
            return zero();
        }
        Set<UUID> picked = new HashSet<>(selected);
        long rightPicks = picked.stream().filter(right::contains).count();
        long wrongPicks = picked.size() - rightPicks;
        long net = Math.max(0, rightPicks - wrongPicks);
        return marks.multiply(BigDecimal.valueOf(net)).divide(BigDecimal.valueOf(right.size()), 2,
                RoundingMode.HALF_UP);
    }

    /** "TRUE" or "FALSE" for a true/false answer (also accepts T, F, yes and no), else null. */
    static String trueFalse(String value) {
        if (value == null) {
            return null;
        }
        return switch (value.strip().toUpperCase(Locale.ROOT)) {
            case "TRUE", "T", "YES" -> "TRUE";
            case "FALSE", "F", "NO" -> "FALSE";
            default -> null;
        };
    }

    /** The accepted answers of a fill in the blank question (one per line, or separated by |), normalised. */
    static Set<String> accepted(String answerText) {
        Set<String> result = new HashSet<>();
        if (answerText == null) {
            return result;
        }
        for (String part : answerText.split("[\\n|]")) {
            String n = normalise(part);
            if (!n.isEmpty()) {
                result.add(n);
            }
        }
        return result;
    }

    /** Lower case, no spaces at the ends, single spaces inside. */
    static String normalise(String value) {
        return value == null ? "" : value.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    static List<String> acceptedList(String answerText) {
        return accepted(answerText).stream().sorted().toList();
    }

    private static BigDecimal scale(BigDecimal marks) {
        return marks.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(2);
    }
}
