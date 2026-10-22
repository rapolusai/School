package com.akshara.certificates;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fills a template's placeholders. {@code {key}} is replaced by the value of {@code key}; a value that is a plain
 * date ("2026-10-11") is written as "11 Oct 2026"; a missing or blank value is written as a short dash, so a signed
 * certificate never shows a raw placeholder. Free of the database so it can be tested on its own.
 */
public final class Placeholders {

    static final String BLANK = "—";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z][A-Za-z0-9]*)}");
    private static final Pattern ISO_DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final DateTimeFormatter DISPLAY = DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH);

    private Placeholders() {
    }

    /** The template with every placeholder filled in. */
    public static String render(String template, Map<String, String> values) {
        if (template == null) {
            return "";
        }
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = values == null ? null : values.get(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(display(value)));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** A value as printed: dates in words, blanks as a dash. */
    public static String display(String value) {
        if (value == null || value.isBlank()) {
            return BLANK;
        }
        String v = value.trim();
        if (ISO_DATE.matcher(v).matches()) {
            try {
                return LocalDate.parse(v).format(DISPLAY);
            } catch (RuntimeException e) {
                return v;
            }
        }
        return v;
    }

    /** "11 Oct 2026", or an empty string for null. */
    public static String date(LocalDate date) {
        return date == null ? "" : date.format(DISPLAY);
    }

    /** "₹1,84,300" or "₹1,84,300.50": Indian digit grouping, for amounts on certificates. */
    public static String rupees(long paise) {
        boolean negative = paise < 0;
        long abs = Math.abs(paise);
        String whole = Long.toString(abs / 100);
        StringBuilder grouped = new StringBuilder();
        if (whole.length() <= 3) {
            grouped.append(whole);
        } else {
            String head = whole.substring(0, whole.length() - 3);
            String tail = whole.substring(whole.length() - 3);
            StringBuilder h = new StringBuilder();
            for (int i = head.length(); i > 0; i -= 2) {
                h.insert(0, head.substring(Math.max(0, i - 2), i));
                if (i - 2 > 0) {
                    h.insert(0, ',');
                }
            }
            grouped.append(h).append(',').append(tail);
        }
        long rest = abs % 100;
        return (negative ? "-" : "") + "₹" + grouped + (rest == 0 ? "" : String.format(".%02d", rest));
    }

    /** The placeholder names used in a template, in order of first use (for the editor's checks). */
    public static java.util.List<String> namesIn(String template) {
        java.util.List<String> names = new java.util.ArrayList<>();
        if (template == null) {
            return names;
        }
        Matcher m = PLACEHOLDER.matcher(template);
        while (m.find()) {
            if (!names.contains(m.group(1))) {
                names.add(m.group(1));
            }
        }
        return names;
    }
}
