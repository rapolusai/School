package com.akshara.exams;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a class's level from its name, for the default grading scheme and the holistic progress card: "Class 5",
 * "Grade 5", "5" and "Class V" are 5; pre-primary classes (Nursery, LKG, UKG, KG, Pre-KG, playgroup, PP1) are 0.
 * Anything else has no level (null).
 */
final class ClassLevel {

    private static final Pattern NUMBER = Pattern.compile("(?<![0-9])(1[0-2]|[1-9])(?![0-9])");
    private static final Pattern ROMAN = Pattern.compile("(?<![a-z])(xii|xi|x|ix|viii|vii|vi|v|iv|iii|ii|i)(?![a-z])");
    private static final Pattern PRE_PRIMARY = Pattern.compile(
            "(?<![a-z])(nursery|lkg|ukg|kg|pre[- ]?kg|pre[- ]?primary|playgroup|play group|pp ?[12]?|prep)(?![a-z])");
    private static final Map<String, Integer> ROMANS = Map.ofEntries(Map.entry("i", 1), Map.entry("ii", 2),
            Map.entry("iii", 3), Map.entry("iv", 4), Map.entry("v", 5), Map.entry("vi", 6), Map.entry("vii", 7),
            Map.entry("viii", 8), Map.entry("ix", 9), Map.entry("x", 10), Map.entry("xi", 11), Map.entry("xii", 12));

    private ClassLevel() {
    }

    static Integer of(String className) {
        if (className == null) {
            return null;
        }
        String name = className.toLowerCase(Locale.ROOT).trim();
        if (PRE_PRIMARY.matcher(name).find()) {
            return 0;
        }
        Matcher number = NUMBER.matcher(name);
        if (number.find()) {
            return Integer.parseInt(number.group(1));
        }
        Matcher roman = ROMAN.matcher(name.replaceFirst("^(class|grade|std\\.?|standard)\\s*", ""));
        if (roman.find() && roman.start() == 0) {
            return ROMANS.get(roman.group(1));
        }
        return null;
    }
}
