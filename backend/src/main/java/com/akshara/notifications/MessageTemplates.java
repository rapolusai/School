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

    /** Sent to a student's primary contact when a fee receipt is issued, at the counter or online. */
    public static final String FEE_RECEIPT = "fees.receipt";

    /** Sent to a student's primary contact when staff ask for an overdue-fee reminder. */
    public static final String FEE_REMINDER = "fees.reminder";
    /** A circular (announcement) by SMS, WhatsApp or email: its title and the start of its text. */
    public static final String CIRCULAR = "communication.circular";

    /** A reminder of a school calendar entry (a holiday, exam, PTM or event) a few days before it. */
    public static final String CALENDAR_REMINDER = "calendar.reminder";

    /** Thanks a family for an enquiry sent through the school's public admissions form. */
    public static final String ENQUIRY_ACKNOWLEDGEMENT = "admissions.enquiry_ack";

    private static final Map<String, Map<String, String>> TEMPLATES = Map.of(
            ABSENCE_ALERT, Map.of(
                    ENGLISH, "Dear {guardian}, {student} ({class}) was marked absent at {school} on {date}. "
                            + "Please contact the school if this is unexpected.",
                    HINDI, "प्रिय {guardian}, {student} ({class}) को {date} को {school} में अनुपस्थित दर्ज किया गया है। "
                            + "यदि यह अपेक्षित नहीं है, तो कृपया विद्यालय से संपर्क करें।"),
            FEE_RECEIPT, Map.of(
                    ENGLISH, "Dear {guardian}, {school} received {amount} towards the fees of {student} on {date}. "
                            + "Receipt no. {receiptNo}. Thank you.",
                    HINDI, "प्रिय {guardian}, {school} को {date} को {student} की फीस के लिए {amount} प्राप्त हुए। "
                            + "रसीद संख्या {receiptNo}। धन्यवाद।"),
            FEE_REMINDER, Map.of(
                    ENGLISH, "Dear {guardian}, fees of {amount} for {student} at {school} are overdue since {date}. "
                            + "Please pay at the school office or online. Ignore this if you have already paid.",
                    HINDI, "प्रिय {guardian}, {school} में {student} की {amount} फीस {date} से बकाया है। "
                            + "कृपया विद्यालय कार्यालय में या ऑनलाइन भुगतान करें। यदि आप भुगतान कर चुके हैं, तो इसे अनदेखा करें।"),
            CIRCULAR, Map.of(
                    ENGLISH, "Circular from {school}: {title}. {summary}",
                    HINDI, "{school} का परिपत्र: {title}। {summary}"),
            CALENDAR_REMINDER, Map.of(
                    ENGLISH, "Reminder from {school}: {title} on {date}.",
                    HINDI, "{school} की ओर से अनुस्मारक: {date} को {title}।"),
            ENQUIRY_ACKNOWLEDGEMENT, Map.of(
                    ENGLISH, "Thank you for your enquiry at {school} for {child} ({class}). "
                            + "Our admissions team will contact you soon.",
                    HINDI, "{school} में {child} ({class}) के प्रवेश के बारे में पूछताछ के लिए धन्यवाद। "
                            + "हमारी प्रवेश टीम जल्द ही आपसे संपर्क करेगी।"));

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
