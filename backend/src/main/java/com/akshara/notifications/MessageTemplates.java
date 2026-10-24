package com.akshara.notifications;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The texts of the messages the product sends, in English, Hindi and Telugu. Placeholders look like {@code {student}}. A
 * parameter named {@code date} holds an ISO date ("2026-10-09") and is written out for the reader ("09 Oct 2026").
 * Real SMS providers in India also need each template registered on the DLT portal; the sender maps a template key and
 * language to that registration (see docs/api/phase-1-attendance.md).
 */
public final class MessageTemplates {

    public static final String ENGLISH = "en";
    public static final String HINDI = "hi";
    public static final String TELUGU = "te";
    public static final List<String> LANGUAGES = List.of(ENGLISH, HINDI, TELUGU);

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
    /** A boarder's outing or home-leave pass was approved or refused by the warden. */
    public static final String HOSTEL_PASS_DECIDED = "hostel.pass_decided";
    /** A boarder was marked absent at the night roll call. */
    public static final String HOSTEL_ROLL_CALL_ABSENT = "hostel.roll_call_absent";

    /** Sent to a student's primary contact when the infirmary sends the student home. */
    public static final String INFIRMARY_SENT_HOME = "health.sent_home";

    /** Sent to a student's primary contact when the infirmary sends the student to hospital. */
    public static final String INFIRMARY_HOSPITAL = "health.hospital";

    /** The one-time code of "Forgot password", sent to the person's own email. */
    public static final String PASSWORD_RESET = "identity.password_reset";

    /**
     * Templates whose text is a secret for its recipient alone (a sign-in code): the message log never shows their
     * text, and it is wiped once a provider has delivered it.
     */
    private static final Set<String> SECRET = Set.of(PASSWORD_RESET);

    /** Templates the person is waiting for right now, sent even during the school's quiet hours. */
    private static final Set<String> IMMEDIATE = Set.of(PASSWORD_RESET);

    private static final Map<String, Map<String, String>> TEMPLATES = Map.ofEntries(
            Map.entry(ABSENCE_ALERT, Map.of(
                    ENGLISH, "Dear {guardian}, {student} ({class}) was marked absent at {school} on {date}. "
                            + "Please contact the school if this is unexpected.",
                    HINDI, "प्रिय {guardian}, {student} ({class}) को {date} को {school} में अनुपस्थित दर्ज किया गया है। "
                            + "यदि यह अपेक्षित नहीं है, तो कृपया विद्यालय से संपर्क करें।",
                    TELUGU, "ప్రియమైన {guardian}, {student} ({class}) {date}న {school}లో గైర్హాజరుగా నమోదయ్యారు. ఇది అనుకోనిది అయితే దయచేసి పాఠశాలను సంప్రదించండి.")),
            Map.entry(FEE_RECEIPT, Map.of(
                    ENGLISH, "Dear {guardian}, {school} received {amount} towards the fees of {student} on {date}. "
                            + "Receipt no. {receiptNo}. Thank you.",
                    HINDI, "प्रिय {guardian}, {school} को {date} को {student} की फीस के लिए {amount} प्राप्त हुए। "
                            + "रसीद संख्या {receiptNo}। धन्यवाद।",
                    TELUGU, "ప్రియమైన {guardian}, {date}న {student} ఫీజు కోసం {school}కు {amount} అందింది. రసీదు నం. {receiptNo}. ధన్యవాదాలు.")),
            Map.entry(FEE_REMINDER, Map.of(
                    ENGLISH, "Dear {guardian}, fees of {amount} for {student} at {school} are overdue since {date}. "
                            + "Please pay at the school office or online. Ignore this if you have already paid.",
                    HINDI, "प्रिय {guardian}, {school} में {student} की {amount} फीस {date} से बकाया है। "
                            + "कृपया विद्यालय कार्यालय में या ऑनलाइन भुगतान करें। यदि आप भुगतान कर चुके हैं, तो इसे अनदेखा करें।",
                    TELUGU, "ప్రియమైన {guardian}, {school}లో {student} ఫీజు {amount} {date} నుండి బకాయి ఉంది. దయచేసి పాఠశాల కార్యాలయంలో లేదా ఆన్‌లైన్‌లో చెల్లించండి. ఇప్పటికే చెల్లించి ఉంటే దీన్ని పట్టించుకోకండి.")),
            Map.entry(CIRCULAR, Map.of(
                    ENGLISH, "Circular from {school}: {title}. {summary}",
                    HINDI, "{school} का परिपत्र: {title}। {summary}",
                    TELUGU, "{school} సర్క్యులర్: {title}. {summary}")),
            Map.entry(CALENDAR_REMINDER, Map.of(
                    ENGLISH, "Reminder from {school}: {title} on {date}.",
                    HINDI, "{school} की ओर से अनुस्मारक: {date} को {title}।",
                    TELUGU, "{school} నుండి అనుస్మారకం: {date}న {title}.")),
            Map.entry(ENQUIRY_ACKNOWLEDGEMENT, Map.of(
                    ENGLISH, "Thank you for your enquiry at {school} for {child} ({class}). "
                            + "Our admissions team will contact you soon.",
                    HINDI, "{school} में {child} ({class}) के प्रवेश के बारे में पूछताछ के लिए धन्यवाद। "
                            + "हमारी प्रवेश टीम जल्द ही आपसे संपर्क करेगी।",
                    TELUGU, "{school}లో {child} ({class}) అడ్మిషన్ గురించి విచారించినందుకు ధన్యవాదాలు. మా అడ్మిషన్ బృందం త్వరలో మిమ్మల్ని సంప్రదిస్తుంది.")),
            Map.entry(HOMEWORK_ASSIGNED, Map.of(
                    ENGLISH, "Dear {guardian}, new {subject} homework for {student} ({class}) at {school}: {title}. "
                            + "Due on {date}.",
                    HINDI, "प्रिय {guardian}, {school} में {student} ({class}) के लिए {subject} का नया गृहकार्य: "
                            + "{title}। जमा करने की अंतिम तिथि {date} है।",
                    TELUGU, "ప్రియమైన {guardian}, {school}లో {student} ({class}) కోసం కొత్త {subject} హోంవర్క్: {title}. సమర్పించాల్సిన తేదీ {date}.")),
            Map.entry(HOMEWORK_DUE, Map.of(
                    ENGLISH, "Dear {guardian}, {student} ({class}) has not yet submitted the {subject} homework "
                            + "\"{title}\" due on {date} at {school}.",
                    HINDI, "प्रिय {guardian}, {student} ({class}) ने {school} का {subject} गृहकार्य \"{title}\" अभी "
                            + "तक जमा नहीं किया है। अंतिम तिथि {date} है।",
                    TELUGU, "ప్రియమైన {guardian}, {student} ({class}) {school} {subject} హోంవర్క్ \"{title}\" ఇంకా సమర్పించలేదు. గడువు తేదీ {date}.")),
            Map.entry(LIBRARY_OVERDUE, Map.of(
                    ENGLISH, "Dear {guardian}, {student} has not yet returned the library book \"{title}\" to "
                            + "{school}. It was due on {date}. Please return it soon; a fine applies for late days.",
                    HINDI, "प्रिय {guardian}, {student} ने {school} के पुस्तकालय की पुस्तक \"{title}\" अभी तक नहीं "
                            + "लौटाई है। इसे {date} तक लौटाना था। कृपया इसे जल्द लौटाएँ; देरी के दिनों का जुर्माना लगता है।",
                    TELUGU, "ప్రియమైన {guardian}, {student} {school} లైబ్రరీ పుస్తకం \"{title}\" ఇంకా తిరిగి ఇవ్వలేదు. దీన్ని {date} నాటికి ఇవ్వాలి. దయచేసి త్వరగా తిరిగి ఇవ్వండి; ఆలస్యమైన రోజులకు జరిమానా ఉంటుంది.")),
            Map.entry(LIBRARY_HOLD_READY, Map.of(
                    ENGLISH, "Dear {guardian}, the library book \"{title}\" that {student} reserved at {school} is "
                            + "ready to collect until {date}.",
                    HINDI, "प्रिय {guardian}, {school} के पुस्तकालय में {student} द्वारा आरक्षित पुस्तक \"{title}\" "
                            + "{date} तक लेने के लिए तैयार है।",
                    TELUGU, "ప్రియమైన {guardian}, {school} లైబ్రరీలో {student} రిజర్వ్ చేసిన పుస్తకం \"{title}\" {date} వరకు తీసుకోవడానికి సిద్ధంగా ఉంది.")),
            Map.entry(RESULTS_PUBLISHED, Map.of(
                    ENGLISH, "Dear {guardian}, the {exam} results of {student} ({class}) at {school} are now "
                            + "available. Open the school app to see the report card.",
                    HINDI, "प्रिय {guardian}, {school} में {student} ({class}) के {exam} के परिणाम अब उपलब्ध हैं। "
                            + "रिपोर्ट कार्ड देखने के लिए विद्यालय ऐप खोलें।",
                    TELUGU, "ప్రియమైన {guardian}, {school}లో {student} ({class}) {exam} ఫలితాలు ఇప్పుడు అందుబాటులో ఉన్నాయి. రిపోర్ట్ కార్డు చూడడానికి పాఠశాల యాప్ తెరవండి.")),
            Map.entry(CHAT_TO_STAFF, Map.of(
                    ENGLISH, "Dear {teacher}, {parent}, a parent of {student}, sent you a message in the {school} "
                            + "app. Open the app to read and reply.",
                    HINDI, "प्रिय {teacher}, {student} के अभिभावक {parent} ने आपको {school} ऐप में संदेश भेजा है। "
                            + "पढ़ने और उत्तर देने के लिए ऐप खोलें।",
                    TELUGU, "ప్రియమైన {teacher}, {student} తల్లి/తండ్రి {parent} మీకు {school} యాప్‌లో సందేశం పంపారు. చదివి జవాబివ్వడానికి యాప్ తెరవండి.")),
            Map.entry(GATE_PASS_OTP, Map.of(
                    ENGLISH, "Dear {guardian}, the early pickup of {student} from {school} is approved; {collector} "
                            + "may collect. OTP {otp}, valid till {time}. Share it only with the person collecting.",
                    HINDI, "प्रिय {guardian}, {school} से {student} को जल्दी ले जाने की अनुमति दी गई है; {collector} "
                            + "ले जा सकते हैं। OTP {otp}, {time} तक मान्य। इसे केवल ले जाने वाले व्यक्ति को बताएँ।",
                    TELUGU, "ప్రియమైన {guardian}, {school} నుండి {student}ను ముందుగా తీసుకెళ్ళడానికి అనుమతి ఇవ్వబడింది; {collector} తీసుకెళ్ళవచ్చు. OTP {otp}, {time} వరకు చెల్లుతుంది. దీన్ని తీసుకెళ్ళే వ్యక్తికి మాత్రమే చెప్పండి.")),
            Map.entry(CHAT_TO_PARENT, Map.of(
                    ENGLISH, "Dear {guardian}, {teacher} sent you a message about {student} in the {school} app. "
                            + "Open the app to read and reply.",
                    HINDI, "प्रिय {guardian}, {teacher} ने {school} ऐप में {student} के बारे में आपको संदेश भेजा है। "
                            + "पढ़ने और उत्तर देने के लिए ऐप खोलें।",
                    TELUGU, "ప్రియమైన {guardian}, {teacher} {school} యాప్‌లో {student} గురించి మీకు సందేశం పంపారు. చదివి జవాబివ్వడానికి యాప్ తెరవండి.")),
            Map.entry(PTM_REMINDER, Map.of(
                    ENGLISH, "Dear {guardian}, reminder from {school}: your meeting with {teacher} about {student} "
                            + "is on {date} at {time}, {place}.",
                    HINDI, "प्रिय {guardian}, {school} की ओर से अनुस्मारक: {student} के बारे में {teacher} के साथ "
                            + "आपकी बैठक {date} को {time} बजे, {place} में है।",
                    TELUGU, "ప్రియమైన {guardian}, {school} నుండి అనుస్మారకం: {student} గురించి {teacher}తో మీ సమావేశం {date}న {time}కు, {place}లో ఉంది.")),
            Map.entry(PTM_REMINDER_VIDEO, Map.of(
                    ENGLISH, "Dear {guardian}, reminder from {school}: your video meeting with {teacher} about "
                            + "{student} is on {date} at {time}. The link is in the school app.",
                    HINDI, "प्रिय {guardian}, {school} की ओर से अनुस्मारक: {student} के बारे में {teacher} के साथ "
                            + "आपकी वीडियो बैठक {date} को {time} बजे है। लिंक विद्यालय ऐप में है।",
                    TELUGU, "ప్రియమైన {guardian}, {school} నుండి అనుస్మారకం: {student} గురించి {teacher}తో మీ వీడియో సమావేశం {date}న {time}కు ఉంది. లింక్ పాఠశాల యాప్‌లో ఉంది.")),
            Map.entry(ONLINE_TEST, Map.of(
                    ENGLISH, "Dear {guardian}, a new {subject} online test for {student} ({class}) at {school}: "
                            + "{title}. It closes on {date} at {time}.",
                    HINDI, "प्रिय {guardian}, {school} में {student} ({class}) के लिए {subject} की नई ऑनलाइन परीक्षा: "
                            + "{title}। यह {date} को {time} बजे बंद होगी।",
                    TELUGU, "ప్రియమైన {guardian}, {school}లో {student} ({class}) కోసం కొత్త {subject} ఆన్‌లైన్ పరీక్ష: {title}. ఇది {date}న {time}కు మూసివేయబడుతుంది.")),
            Map.entry(ONLINE_TEST_STUDENT, Map.of(
                    ENGLISH, "Dear {student}, a new {subject} online test is ready for you at {school}: {title}. "
                            + "Take it in the school app before {date} at {time}.",
                    HINDI, "प्रिय {student}, {school} में आपके लिए {subject} की नई ऑनलाइन परीक्षा तैयार है: {title}। "
                            + "इसे {date} को {time} बजे से पहले विद्यालय ऐप में दें।",
                    TELUGU, "ప్రియమైన {student}, {school}లో మీ కోసం కొత్త {subject} ఆన్‌లైన్ పరీక్ష సిద్ధంగా ఉంది: {title}. {date}న {time}కు ముందు పాఠశాల యాప్‌లో రాయండి.")),
            Map.entry(TRANSPORT_BOARDED, Map.of(
                    ENGLISH, "Dear {guardian}, {student} boarded the {school} bus ({route}) at {stop} at {time}.",
                    HINDI, "प्रिय {guardian}, {student} {time} बजे {stop} पर {school} की बस ({route}) में सवार हुए।",
                    TELUGU, "ప్రియమైన {guardian}, {student} {time}కు {stop} వద్ద {school} బస్సు ({route}) ఎక్కారు.")),
            Map.entry(TRANSPORT_DROPPED, Map.of(
                    ENGLISH, "Dear {guardian}, {student} was dropped at {stop} by the {school} bus ({route}) at "
                            + "{time}.",
                    HINDI, "प्रिय {guardian}, {school} की बस ({route}) ने {student} को {time} बजे {stop} पर उतारा।",
                    TELUGU, "ప్రియమైన {guardian}, {school} బస్సు ({route}) {student}ను {time}కు {stop} వద్ద దింపింది.")),
            Map.entry(TRANSPORT_APPROACHING, Map.of(
                    ENGLISH, "Dear {guardian}, the {school} bus ({route}) is approaching {stop}. Please be ready "
                            + "for {student}.",
                    HINDI, "प्रिय {guardian}, {school} की बस ({route}) {stop} के पास पहुँच रही है। कृपया {student} के "
                            + "लिए तैयार रहें।",
                    TELUGU, "ప్రియమైన {guardian}, {school} బస్సు ({route}) {stop}కు సమీపిస్తోంది. దయచేసి {student} కోసం సిద్ధంగా ఉండండి.")),
            Map.entry(TRANSPORT_DELAYED, Map.of(
                    ENGLISH, "Dear {guardian}, the {school} bus ({route}) for {student} is running about {minutes} "
                            + "minutes late: {reason}.",
                    HINDI, "प्रिय {guardian}, {student} की {school} बस ({route}) लगभग {minutes} मिनट देर से चल रही है: "
                            + "{reason}।",
                    TELUGU, "ప్రియమైన {guardian}, {student} {school} బస్సు ({route}) సుమారు {minutes} నిమిషాలు ఆలస్యంగా నడుస్తోంది: {reason}.")),
            Map.entry(INFIRMARY_SENT_HOME, Map.of(
                    ENGLISH, "Dear {guardian}, {student} ({class}) visited the {school} infirmary at {time} "
                            + "({complaint}) and is being sent home. Please collect {student} or call the school.",
                    HINDI, "प्रिय {guardian}, {student} ({class}) {time} बजे {school} के चिकित्सा कक्ष में आए "
                            + "({complaint}) और उन्हें घर भेजा जा रहा है। कृपया {student} को लेने आएँ या विद्यालय को फ़ोन करें।",
                    TELUGU, "ప్రియమైన {guardian}, {student} ({class}) {time}కు {school} వైద్య గదికి వచ్చారు ({complaint}) మరియు ఇంటికి పంపబడుతున్నారు. దయచేసి {student}ను తీసుకెళ్లండి లేదా పాఠశాలకు ఫోన్ చేయండి.")),
            Map.entry(INFIRMARY_HOSPITAL, Map.of(
                    ENGLISH, "Dear {guardian}, {student} ({class}) was taken from the {school} infirmary to hospital "
                            + "at {time} ({complaint}). Please call the school at once.",
                    HINDI, "प्रिय {guardian}, {student} ({class}) को {time} बजे {school} के चिकित्सा कक्ष से अस्पताल "
                            + "ले जाया गया ({complaint})। कृपया तुरंत विद्यालय को फ़ोन करें।",
                    TELUGU, "ప్రియమైన {guardian}, {student} ({class}) ను {time}కు {school} వైద్య గది నుండి ఆసుపత్రికి తీసుకెళ్లారు ({complaint}). దయచేసి వెంటనే పాఠశాలకు ఫోన్ చేయండి.")),
            Map.entry(HOSTEL_PASS_DECIDED, Map.of(
                    ENGLISH, "Dear {guardian}, the {kind} of {student} from {hostel} ({school}) for {dates} is "
                            + "{decision}. {note}",
                    HINDI, "प्रिय {guardian}, {hostel} ({school}) से {student} की {dates} की {kind} {decision}। {note}",
                    TELUGU, "ప్రియమైన {guardian}, {hostel} ({school}) నుండి {student} యొక్క {dates} {kind} {decision}. {note}")),
            Map.entry(HOSTEL_ROLL_CALL_ABSENT, Map.of(
                    ENGLISH, "Dear {guardian}, {student} was not found at {hostel} ({school}) during the night roll "
                            + "call on {date}. Please contact the warden at once.",
                    HINDI, "प्रिय {guardian}, {date} की रात की हाज़िरी में {student} {hostel} ({school}) में नहीं मिले। "
                            + "कृपया तुरंत वार्डन से संपर्क करें।",
                    TELUGU, "ప్రియమైన {guardian}, {date} రాత్రి హాజరులో {student} {hostel} ({school}) లో కనిపించలేదు. దయచేసి వెంటనే వార్డెన్‌ను సంప్రదించండి.")),
            Map.entry(PASSWORD_RESET, Map.of(
                    ENGLISH, "{school}: your Akshara password reset code is {code}. It works for 15 minutes. "
                            + "Do not share it with anyone, not even school staff. If you did not ask for it, "
                            + "ignore this message.",
                    HINDI, "{school}: आपका Akshara पासवर्ड रीसेट कोड {code} है। यह 15 मिनट तक मान्य है। इसे किसी को "
                            + "न बताएँ, विद्यालय के कर्मचारियों को भी नहीं। यदि आपने यह नहीं माँगा है, तो इस संदेश को अनदेखा करें।",
                    TELUGU, "{school}: మీ Akshara పాస్‌వర్డ్ రీసెట్ కోడ్ {code}. ఇది 15 నిమిషాలు చెల్లుతుంది. దీన్ని ఎవరికీ, పాఠశాల సిబ్బందికి కూడా చెప్పవద్దు. మీరు దీన్ని అడగకపోతే ఈ సందేశాన్ని పట్టించుకోకండి.")));

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)}");
    private static final DateTimeFormatter ENGLISH_DATE = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter HINDI_DATE = DateTimeFormatter.ofPattern("dd MMMM yyyy",
            Locale.forLanguageTag("hi-IN"));
    private static final DateTimeFormatter TELUGU_DATE = DateTimeFormatter.ofPattern("dd MMMM yyyy",
            Locale.forLanguageTag("te-IN"));

    private MessageTemplates() {
    }

    /** Every template's texts by language, for tests that check each language is complete. */
    static Map<String, Map<String, String>> all() {
        return TEMPLATES;
    }

    public static boolean exists(String templateKey) {
        return TEMPLATES.containsKey(templateKey);
    }

    /** True for a template whose text only its recipient may see (see {@link #SECRET}). */
    public static boolean isSecret(String templateKey) {
        return SECRET.contains(templateKey);
    }

    /** True for a template that is sent even during quiet hours (see {@link #IMMEDIATE}). */
    public static boolean ignoresQuietHours(String templateKey) {
        return IMMEDIATE.contains(templateKey);
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
            return (HINDI.equals(language) ? HINDI_DATE : TELUGU.equals(language) ? TELUGU_DATE : ENGLISH_DATE)
                    .format(date);
        } catch (DateTimeParseException e) {
            return clean(isoDate);
        }
    }

    private static String clean(String value) {
        return value.replaceAll("\\p{Cntrl}+", " ").trim();
    }
}
