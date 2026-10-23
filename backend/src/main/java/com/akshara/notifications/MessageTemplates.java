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

    /** Sent to a student's primary contact when homework is set for the student's section (if the school opts in). */
    public static final String HOMEWORK_ASSIGNED = "homework.assigned";

    /** Sent the evening before homework is due, to the contacts of students who have not submitted it. */
    public static final String HOMEWORK_DUE = "homework.due";

    /** Sent to a student's primary contact when a library book is overdue (weekly, if the school opts in). */
    public static final String LIBRARY_OVERDUE = "library.overdue";

    /** Sent to a student's primary contact when a library book the student reserved is ready to collect. */
    public static final String LIBRARY_HOLD_READY = "library.hold_ready";

    /** Sent to a student's primary contact when the school publishes an exam's results for the student's class. */
    public static final String RESULTS_PUBLISHED = "exams.results_published";

    /** Tells a member of staff that a parent wrote to them in the app (never the message itself). */
    public static final String CHAT_TO_STAFF = "chat.to_staff";

    /** Tells a parent that a member of staff wrote to them in the app (never the message itself). */
    public static final String CHAT_TO_PARENT = "chat.to_parent";

    /** Reminds a parent of a parent-teacher meeting slot, the day before and an hour before (if the school opts in). */
    public static final String PTM_REMINDER = "ptm.reminder";

    /** As {@link #PTM_REMINDER}, for a meeting by video call (the link is in the app). */
    public static final String PTM_REMINDER_VIDEO = "ptm.reminder_video";

    /** Sent to a student's primary contact when a new online test is published (if the school opts in). */
    public static final String ONLINE_TEST = "lessons.online_test";

    /** Sent to a student's own sign-in email when a new online test is published (if the school opts in). */
    public static final String ONLINE_TEST_STUDENT = "lessons.online_test_student";

    /** Sent to a student's primary contact when the bus attendant marks the student boarded (if the school opts in). */
    public static final String TRANSPORT_BOARDED = "transport.boarded";

    /** Sent to a student's primary contact when the bus attendant marks the student dropped at their stop. */
    public static final String TRANSPORT_DROPPED = "transport.dropped";

    /** Sent to a student's primary contact when the bus reaches the stop before the student's stop. */
    public static final String TRANSPORT_APPROACHING = "transport.approaching";

    /** Sent to the contacts of the students still waiting for the bus when the attendant reports a delay. */
    public static final String TRANSPORT_DELAYED = "transport.delayed";
    /** The one-time password of an approved early pickup pass, sent to the student's primary contact. */
    public static final String GATE_PASS_OTP = "frontoffice.gate_pass_otp";

    /** Sent to a student's primary contact when the infirmary sends the student home. */
    public static final String INFIRMARY_SENT_HOME = "health.sent_home";

    /** Sent to a student's primary contact when the infirmary sends the student to hospital. */
    public static final String INFIRMARY_HOSPITAL = "health.hospital";

    private static final Map<String, Map<String, String>> TEMPLATES = Map.ofEntries(
            Map.entry(ABSENCE_ALERT, Map.of(
                    ENGLISH, "Dear {guardian}, {student} ({class}) was marked absent at {school} on {date}. "
                            + "Please contact the school if this is unexpected.",
                    HINDI, "प्रिय {guardian}, {student} ({class}) को {date} को {school} में अनुपस्थित दर्ज किया गया है। "
                            + "यदि यह अपेक्षित नहीं है, तो कृपया विद्यालय से संपर्क करें।")),
            Map.entry(FEE_RECEIPT, Map.of(
                    ENGLISH, "Dear {guardian}, {school} received {amount} towards the fees of {student} on {date}. "
                            + "Receipt no. {receiptNo}. Thank you.",
                    HINDI, "प्रिय {guardian}, {school} को {date} को {student} की फीस के लिए {amount} प्राप्त हुए। "
                            + "रसीद संख्या {receiptNo}। धन्यवाद।")),
            Map.entry(FEE_REMINDER, Map.of(
                    ENGLISH, "Dear {guardian}, fees of {amount} for {student} at {school} are overdue since {date}. "
                            + "Please pay at the school office or online. Ignore this if you have already paid.",
                    HINDI, "प्रिय {guardian}, {school} में {student} की {amount} फीस {date} से बकाया है। "
                            + "कृपया विद्यालय कार्यालय में या ऑनलाइन भुगतान करें। यदि आप भुगतान कर चुके हैं, तो इसे अनदेखा करें।")),
            Map.entry(CIRCULAR, Map.of(
                    ENGLISH, "Circular from {school}: {title}. {summary}",
                    HINDI, "{school} का परिपत्र: {title}। {summary}")),
            Map.entry(CALENDAR_REMINDER, Map.of(
                    ENGLISH, "Reminder from {school}: {title} on {date}.",
                    HINDI, "{school} की ओर से अनुस्मारक: {date} को {title}।")),
            Map.entry(ENQUIRY_ACKNOWLEDGEMENT, Map.of(
                    ENGLISH, "Thank you for your enquiry at {school} for {child} ({class}). "
                            + "Our admissions team will contact you soon.",
                    HINDI, "{school} में {child} ({class}) के प्रवेश के बारे में पूछताछ के लिए धन्यवाद। "
                            + "हमारी प्रवेश टीम जल्द ही आपसे संपर्क करेगी।")),
            Map.entry(HOMEWORK_ASSIGNED, Map.of(
                    ENGLISH, "Dear {guardian}, new {subject} homework for {student} ({class}) at {school}: {title}. "
                            + "Due on {date}.",
                    HINDI, "प्रिय {guardian}, {school} में {student} ({class}) के लिए {subject} का नया गृहकार्य: "
                            + "{title}। जमा करने की अंतिम तिथि {date} है।")),
            Map.entry(HOMEWORK_DUE, Map.of(
                    ENGLISH, "Dear {guardian}, {student} ({class}) has not yet submitted the {subject} homework "
                            + "\"{title}\" due on {date} at {school}.",
                    HINDI, "प्रिय {guardian}, {student} ({class}) ने {school} का {subject} गृहकार्य \"{title}\" अभी "
                            + "तक जमा नहीं किया है। अंतिम तिथि {date} है।")),
            Map.entry(LIBRARY_OVERDUE, Map.of(
                    ENGLISH, "Dear {guardian}, {student} has not yet returned the library book \"{title}\" to "
                            + "{school}. It was due on {date}. Please return it soon; a fine applies for late days.",
                    HINDI, "प्रिय {guardian}, {student} ने {school} के पुस्तकालय की पुस्तक \"{title}\" अभी तक नहीं "
                            + "लौटाई है। इसे {date} तक लौटाना था। कृपया इसे जल्द लौटाएँ; देरी के दिनों का जुर्माना लगता है।")),
            Map.entry(LIBRARY_HOLD_READY, Map.of(
                    ENGLISH, "Dear {guardian}, the library book \"{title}\" that {student} reserved at {school} is "
                            + "ready to collect until {date}.",
                    HINDI, "प्रिय {guardian}, {school} के पुस्तकालय में {student} द्वारा आरक्षित पुस्तक \"{title}\" "
                            + "{date} तक लेने के लिए तैयार है।")),
            Map.entry(RESULTS_PUBLISHED, Map.of(
                    ENGLISH, "Dear {guardian}, the {exam} results of {student} ({class}) at {school} are now "
                            + "available. Open the school app to see the report card.",
                    HINDI, "प्रिय {guardian}, {school} में {student} ({class}) के {exam} के परिणाम अब उपलब्ध हैं। "
                            + "रिपोर्ट कार्ड देखने के लिए विद्यालय ऐप खोलें।")),
            Map.entry(CHAT_TO_STAFF, Map.of(
                    ENGLISH, "Dear {teacher}, {parent}, a parent of {student}, sent you a message in the {school} "
                            + "app. Open the app to read and reply.",
                    HINDI, "प्रिय {teacher}, {student} के अभिभावक {parent} ने आपको {school} ऐप में संदेश भेजा है। "
                            + "पढ़ने और उत्तर देने के लिए ऐप खोलें।")),
            Map.entry(GATE_PASS_OTP, Map.of(
                    ENGLISH, "Dear {guardian}, the early pickup of {student} from {school} is approved; {collector} "
                            + "may collect. OTP {otp}, valid till {time}. Share it only with the person collecting.",
                    HINDI, "प्रिय {guardian}, {school} से {student} को जल्दी ले जाने की अनुमति दी गई है; {collector} "
                            + "ले जा सकते हैं। OTP {otp}, {time} तक मान्य। इसे केवल ले जाने वाले व्यक्ति को बताएँ।")),
            Map.entry(CHAT_TO_PARENT, Map.of(
                    ENGLISH, "Dear {guardian}, {teacher} sent you a message about {student} in the {school} app. "
                            + "Open the app to read and reply.",
                    HINDI, "प्रिय {guardian}, {teacher} ने {school} ऐप में {student} के बारे में आपको संदेश भेजा है। "
                            + "पढ़ने और उत्तर देने के लिए ऐप खोलें।")),
            Map.entry(PTM_REMINDER, Map.of(
                    ENGLISH, "Dear {guardian}, reminder from {school}: your meeting with {teacher} about {student} "
                            + "is on {date} at {time}, {place}.",
                    HINDI, "प्रिय {guardian}, {school} की ओर से अनुस्मारक: {student} के बारे में {teacher} के साथ "
                            + "आपकी बैठक {date} को {time} बजे, {place} में है।")),
            Map.entry(PTM_REMINDER_VIDEO, Map.of(
                    ENGLISH, "Dear {guardian}, reminder from {school}: your video meeting with {teacher} about "
                            + "{student} is on {date} at {time}. The link is in the school app.",
                    HINDI, "प्रिय {guardian}, {school} की ओर से अनुस्मारक: {student} के बारे में {teacher} के साथ "
                            + "आपकी वीडियो बैठक {date} को {time} बजे है। लिंक विद्यालय ऐप में है।")),
            Map.entry(ONLINE_TEST, Map.of(
                    ENGLISH, "Dear {guardian}, a new {subject} online test for {student} ({class}) at {school}: "
                            + "{title}. It closes on {date} at {time}.",
                    HINDI, "प्रिय {guardian}, {school} में {student} ({class}) के लिए {subject} की नई ऑनलाइन परीक्षा: "
                            + "{title}। यह {date} को {time} बजे बंद होगी।")),
            Map.entry(ONLINE_TEST_STUDENT, Map.of(
                    ENGLISH, "Dear {student}, a new {subject} online test is ready for you at {school}: {title}. "
                            + "Take it in the school app before {date} at {time}.",
                    HINDI, "प्रिय {student}, {school} में आपके लिए {subject} की नई ऑनलाइन परीक्षा तैयार है: {title}। "
                            + "इसे {date} को {time} बजे से पहले विद्यालय ऐप में दें।")),
            Map.entry(TRANSPORT_BOARDED, Map.of(
                    ENGLISH, "Dear {guardian}, {student} boarded the {school} bus ({route}) at {stop} at {time}.",
                    HINDI, "प्रिय {guardian}, {student} {time} बजे {stop} पर {school} की बस ({route}) में सवार हुए।")),
            Map.entry(TRANSPORT_DROPPED, Map.of(
                    ENGLISH, "Dear {guardian}, {student} was dropped at {stop} by the {school} bus ({route}) at "
                            + "{time}.",
                    HINDI, "प्रिय {guardian}, {school} की बस ({route}) ने {student} को {time} बजे {stop} पर उतारा।")),
            Map.entry(TRANSPORT_APPROACHING, Map.of(
                    ENGLISH, "Dear {guardian}, the {school} bus ({route}) is approaching {stop}. Please be ready "
                            + "for {student}.",
                    HINDI, "प्रिय {guardian}, {school} की बस ({route}) {stop} के पास पहुँच रही है। कृपया {student} के "
                            + "लिए तैयार रहें।")),
            Map.entry(TRANSPORT_DELAYED, Map.of(
                    ENGLISH, "Dear {guardian}, the {school} bus ({route}) for {student} is running about {minutes} "
                            + "minutes late: {reason}.",
                    HINDI, "प्रिय {guardian}, {student} की {school} बस ({route}) लगभग {minutes} मिनट देर से चल रही है: "
                            + "{reason}।")),
            Map.entry(INFIRMARY_SENT_HOME, Map.of(
                    ENGLISH, "Dear {guardian}, {student} ({class}) visited the {school} infirmary at {time} "
                            + "({complaint}) and is being sent home. Please collect {student} or call the school.",
                    HINDI, "प्रिय {guardian}, {student} ({class}) {time} बजे {school} के चिकित्सा कक्ष में आए "
                            + "({complaint}) और उन्हें घर भेजा जा रहा है। कृपया {student} को लेने आएँ या विद्यालय को फ़ोन करें।")),
            Map.entry(INFIRMARY_HOSPITAL, Map.of(
                    ENGLISH, "Dear {guardian}, {student} ({class}) was taken from the {school} infirmary to hospital "
                            + "at {time} ({complaint}). Please call the school at once.",
                    HINDI, "प्रिय {guardian}, {student} ({class}) को {time} बजे {school} के चिकित्सा कक्ष से अस्पताल "
                            + "ले जाया गया ({complaint})। कृपया तुरंत विद्यालय को फ़ोन करें।")));

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
