package com.akshara.notifications;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The texts of the messages the product sends, in English and Hindi. Placeholders look like {@code {student}}. A
 * parameter named {@code date} holds an ISO date ("2026-10-09") and is written out for the reader ("09 Oct 2026").
 * Real SMS providers in India also need each template registered on the DLT portal; the sender maps a template key and
 * language to that registration (see docs/api/phase-1-attendance.md).
 */
public final class MessageTemplates {

    public static final String ENGLISH = "en";
    public static final String HINDI = "hi";
    public static final List<String> LANGUAGES = List.of(ENGLISH, HINDI);

    /** Sent to a student's primary contact when the student is first marked absent on a day. */
    public static final String ABSENCE_ALERT = "attendance.absence";

    /** Sent to a student's primary contact when homework is set for the student's section (if the school opts in). */
    public static final String HOMEWORK_ASSIGNED = "homework.assigned";

    /** Sent the evening before homework is due, to the contacts of students who have not submitted it. */
    public static final String HOMEWORK_DUE = "homework.due";

    private static final Map<String, Map<String, String>> TEMPLATES = Map.of(
            ABSENCE_ALERT, Map.of(
                    ENGLISH, "Dear {guardian}, {student} ({class}) was marked absent at {school} on {date}. "
                            + "Please contact the school if this is unexpected.",
                    HINDI, "प्रिय {guardian}, {student} ({class}) को {date} को {school} में अनुपस्थित दर्ज किया गया है। "
                            + "यदि यह अपेक्षित नहीं है, तो कृपया विद्यालय से संपर्क करें।"),
            HOMEWORK_ASSIGNED, Map.of(
                    ENGLISH, "Dear {guardian}, new {subject} homework for {student} ({class}) at {school}: {title}. "
                            + "Due on {date}.",
                    HINDI, "प्रिय {guardian}, {school} में {student} ({class}) के लिए {subject} का नया गृहकार्य: "
                            + "{title}। जमा करने की अंतिम तिथि {date} है।"),
            HOMEWORK_DUE, Map.of(
                    ENGLISH, "Dear {guardian}, {student} ({class}) has not yet submitted the {subject} homework "
                            + "\"{title}\" due on {date} at {school}.",
                    HINDI, "प्रिय {guardian}, {student} ({class}) ने {school} का {subject} गृहकार्य \"{title}\" अभी "
                            + "तक जमा नहीं किया है। अंतिम तिथि {date} है।"));

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)}");
    private static final DateTimeFormatter ENGLISH_DATE = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter HINDI_DATE = DateTimeFormatter.ofPattern("dd MMMM yyyy",
            Locale.forLanguageTag("hi-IN"));

    private MessageTemplates() {
    }

    public static boolean exists(String templateKey) {
        return TEMPLATES.containsKey(templateKey);
    }

    /** English unless the language is one the template has. */
    public static String language(String language) {
        return language != null && LANGUAGES.contains(language) ? language : ENGLISH;
    }

    /**
     * Fills in the template. Fails for an unknown template or a missing parameter, which are programming errors.
     * Line breaks and other control characters in parameters become spaces, so a value cannot reshape the message.
     */
    public static String render(String templateKey, String language, Map<String, String> params) {
        Map<String, String> texts = TEMPLATES.get(templateKey);
        if (texts == null) {
            throw new IllegalArgumentException("Unknown message template " + templateKey);
        }
        String lang = language(language);
        String template = texts.getOrDefault(lang, texts.get(ENGLISH));
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            String value = params.get(name);
            if (value == null) {
                throw new IllegalArgumentException("Template " + templateKey + " needs the parameter " + name);
            }
            String shown = "date".equals(name) ? formatDate(value, lang) : clean(value);
            matcher.appendReplacement(out, Matcher.quoteReplacement(shown));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    static String formatDate(String isoDate, String language) {
        try {
            LocalDate date = LocalDate.parse(isoDate);
            return (HINDI.equals(language) ? HINDI_DATE : ENGLISH_DATE).format(date);
        } catch (DateTimeParseException e) {
            return clean(isoDate);
        }
    }

    private static String clean(String value) {
        return value.replaceAll("\\p{Cntrl}+", " ").trim();
    }
}
