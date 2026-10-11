package com.akshara.onboarding;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.akshara.academics.AcademicsDirectory;
import com.akshara.academics.AcademicsDirectory.SectionInfo;
import com.akshara.academics.AcademicsDirectory.YearInfo;
import com.akshara.academics.AcademicsService;
import com.akshara.academics.AcademicsService.ClassView;
import com.akshara.academics.AcademicsService.SubjectRef;
import com.akshara.audit.AuditService.Actor;
import com.akshara.files.FileUploads;
import com.akshara.identity.UserRepository;
import com.akshara.lessons.BloomLevel;
import com.akshara.lessons.Difficulty;
import com.akshara.lessons.LearnerService;
import com.akshara.lessons.LessonsForms.AnswerForm;
import com.akshara.lessons.LessonsForms.MarkForm;
import com.akshara.lessons.LessonsForms.MarksForm;
import com.akshara.lessons.LessonsForms.MaterialForm;
import com.akshara.lessons.LessonsForms.OptionForm;
import com.akshara.lessons.LessonsForms.PlanForm;
import com.akshara.lessons.LessonsForms.QuestionForm;
import com.akshara.lessons.LessonsForms.SyllabusForm;
import com.akshara.lessons.LessonsForms.TestForm;
import com.akshara.lessons.LessonsForms.TestQuestionForm;
import com.akshara.lessons.LessonsForms.TestQuestionsForm;
import com.akshara.lessons.LessonsForms.TopicForm;
import com.akshara.lessons.LessonsForms.UnitForm;
import com.akshara.lessons.LessonsViews.AnswerReview;
import com.akshara.lessons.LessonsViews.LearnerAttempt;
import com.akshara.lessons.LessonsViews.LearnerOption;
import com.akshara.lessons.LessonsViews.LearnerQuestion;
import com.akshara.lessons.LessonsViews.MaterialView;
import com.akshara.lessons.LessonsViews.PlanDetail;
import com.akshara.lessons.LessonsViews.QuestionView;
import com.akshara.lessons.LessonsViews.SyllabusDetail;
import com.akshara.lessons.LessonsViews.TestDetail;
import com.akshara.lessons.LessonsViews.TopicView;
import com.akshara.lessons.MaterialService;
import com.akshara.lessons.OnlineTestService;
import com.akshara.lessons.PlanService;
import com.akshara.lessons.QuestionService;
import com.akshara.lessons.QuestionType;
import com.akshara.lessons.ShowAnswers;
import com.akshara.lessons.SyllabusService;
import com.akshara.shared.JwtService;
import com.akshara.shared.Permissions;
import com.akshara.shared.TenantContext;

/**
 * The demo school's lessons in the current year: the Class 5 syllabus for Mathematics, English and Science
 * (Environmental Studies is Class 5's science subject), with the topics due so far taught in 5 A (English is one
 * topic behind); Ravi Kumar's lesson plans (taught, approved, one waiting for the principal, a draft and one returned
 * with a comment); two pieces of study material; a question bank of 30 Mathematics questions; a closed online test
 * that Arjun took, marked and released; and an open test. Each person acts through the same services and checks as
 * the app, signed in as themselves. Added after the timetable exists.
 */
@Component
class DemoLessonsData {

    static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    static final String CLOSED_TEST = "Numbers and operations quiz";
    static final String OPEN_TEST = "Fractions practice test";

    /** A syllabus topic: its title, planned periods and target month (months after the year's first). */
    private record T(String title, int periods, int month) {
    }

    private record U(String title, List<T> topics) {
    }

    /** A bank question; {@code correct} are 1-based option numbers. */
    private record Q(String topic, QuestionType type, Difficulty difficulty, BloomLevel bloom, String text,
            List<String> options, Set<Integer> correct, String answer, String explanation, int marks) {
    }

    private static final List<U> MATHS = List.of(
            new U("Numbers and place value", List.of(new T("Large numbers up to crores", 4, 0),
                    new T("Place value and expanded form", 3, 0), new T("Comparing and ordering numbers", 3, 1))),
            new U("Operations", List.of(new T("Multiplication of large numbers", 5, 1),
                    new T("Division with remainders", 5, 2), new T("Factors and multiples", 4, 2))),
            new U("Fractions and decimals", List.of(new T("Equivalent fractions", 4, 3),
                    new T("Adding and subtracting fractions", 5, 4), new T("Decimals in money and measurement", 4, 5))),
            new U("Shapes and measurement", List.of(new T("Angles and their measure", 4, 6),
                    new T("Perimeter and area", 5, 7), new T("Volume of cuboids", 3, 8))));
    private static final List<U> ENGLISH = List.of(
            new U("Ice-cream Man and Wonderful Waste!", List.of(new T("Reading the poem Ice-cream Man", 2, 0),
                    new T("Wonderful Waste!: reading and comprehension", 3, 1),
                    new T("Naming words and describing words", 2, 1))),
            new U("Teamwork and Flying Together", List.of(new T("Teamwork (poem)", 2, 2),
                    new T("Flying Together: story reading", 3, 2), new T("Writing a short paragraph", 2, 3))),
            new U("My Shadow and Robinson Crusoe", List.of(new T("My Shadow (poem)", 2, 4),
                    new T("Robinson Crusoe Discovers a Footprint", 3, 5), new T("Using the past tense", 2, 5))));
    private static final List<U> SCIENCE = List.of(
            new U("Super senses", List.of(new T("How animals sense their world", 3, 0),
                    new T("Sense of smell and hearing", 2, 1))),
            new U("Food and water", List.of(new T("A snake charmer's story", 2, 1),
                    new T("From tasting to digesting", 3, 2), new T("Mangoes round the year: keeping food", 3, 3))),
            new U("Plants and seeds", List.of(new T("Seeds and seeds", 3, 4),
                    new T("Every drop counts: saving water", 3, 5))),
            new U("Our earth", List.of(new T("Experiments with water", 3, 6), new T("A treat for mosquitoes", 3, 7))));

    private static final List<Q> QUESTIONS = List.of(
            mcq("Large numbers up to crores", Difficulty.EASY, "How many lakhs make one crore?",
                    List.of("10", "100", "1,000", "10,000"), 2, "100 lakhs make 1 crore."),
            new Q("Large numbers up to crores", QuestionType.FILL_BLANK, Difficulty.EASY, BloomLevel.REMEMBER,
                    "4,05,302 in words is four lakh five thousand three hundred ___.", List.of(), Set.of(), "two",
                    "The ones digit is 2.", 1),
            tf("Large numbers up to crores", Difficulty.MEDIUM, "The smallest 7-digit number is 10,00,000.", true,
                    "10,00,000 (ten lakh) has 7 digits; 9,99,999 has 6."),
            mcq("Place value and expanded form", Difficulty.EASY, "What is the place value of 7 in 3,47,215?",
                    List.of("7", "700", "7,000", "70,000"), 3, "7 is in the thousands place."),
            mcq("Place value and expanded form", Difficulty.MEDIUM, "Which is the expanded form of 52,064?",
                    List.of("50,000 + 2,000 + 60 + 4", "50,000 + 2,000 + 600 + 4", "5,000 + 2,000 + 60 + 4",
                            "52,000 + 600 + 4"), 1, "There are no hundreds in 52,064."),
            new Q("Place value and expanded form", QuestionType.FILL_BLANK, Difficulty.MEDIUM,
                    BloomLevel.UNDERSTAND, "The face value of 9 in 9,46,120 is ___.", List.of(), Set.of(),
                    "9\nnine", "The face value of a digit is the digit itself.", 1),
            mcq("Comparing and ordering numbers", Difficulty.EASY, "Which number is the greatest?",
                    List.of("98,765", "1,00,001", "99,999", "89,999"), 2, "1,00,001 has the most digits."),
            new Q("Comparing and ordering numbers", QuestionType.MCQ_MULTI, Difficulty.MEDIUM, BloomLevel.APPLY,
                    "Which of these numbers are greater than 50,000?", List.of("49,999", "50,001", "5,00,000",
                            "45,678"), Set.of(2, 3), null, "50,001 and 5,00,000 are more than 50,000.", 1),
            tf("Comparing and ordering numbers", Difficulty.EASY, "45,678 is less than 45,687.", true,
                    "Compare the tens: 7 is less than 8."),
            mcq("Multiplication of large numbers", Difficulty.MEDIUM, "325 x 24 = ?",
                    List.of("7,600", "7,800", "7,750", "8,200"), 2, "325 x 24 = 6,500 + 1,300 = 7,800."),
            new Q("Multiplication of large numbers", QuestionType.FILL_BLANK, Difficulty.EASY, BloomLevel.REMEMBER,
                    "Any number multiplied by 0 is ___.", List.of(), Set.of(), "0\nzero", null, 1),
            new Q("Multiplication of large numbers", QuestionType.SHORT_ANSWER, Difficulty.HARD, BloomLevel.APPLY,
                    "A school bus makes 4 trips a day with 36 children each trip. How many children does it carry "
                            + "in 25 school days? Show your working.", List.of(), Set.of(),
                    "36 x 4 = 144 children a day; 144 x 25 = 3,600 children.", "Multiply step by step.", 2),
            mcq("Division with remainders", Difficulty.MEDIUM, "What is the remainder when 158 is divided by 12?",
                    List.of("2", "12", "13", "14"), 1, "12 x 13 = 156, and 158 - 156 = 2."),
            tf("Division with remainders", Difficulty.MEDIUM,
                    "In a division, the remainder is always smaller than the divisor.", true,
                    "Otherwise the divisor would go in once more."),
            new Q("Division with remainders", QuestionType.SHORT_ANSWER, Difficulty.MEDIUM, BloomLevel.APPLY,
                    "250 sweets are shared equally among 8 children. How many does each child get, and how many "
                            + "are left over?", List.of(), Set.of(), "250 / 8 = 31 each, with 2 left over.", null, 2),
            new Q("Factors and multiples", QuestionType.MCQ_MULTI, Difficulty.MEDIUM, BloomLevel.UNDERSTAND,
                    "Which of these are factors of 24?", List.of("3", "5", "6", "8"), Set.of(1, 3, 4), null,
                    "24 = 3 x 8 = 4 x 6; 5 does not divide 24.", 1),
            mcq("Factors and multiples", Difficulty.EASY, "Which is the smallest common multiple of 4 and 6?",
                    List.of("10", "12", "18", "24"), 2, "12 is the first number in both tables."),
            new Q("Factors and multiples", QuestionType.FILL_BLANK, Difficulty.MEDIUM, BloomLevel.REMEMBER,
                    "The only even prime number is ___.", List.of(), Set.of(), "2\ntwo", null, 1),
            new Q("Factors and multiples", QuestionType.MCQ_MULTI, Difficulty.HARD, BloomLevel.ANALYSE,
                    "Which of these numbers are prime?", List.of("21", "29", "31", "39"), Set.of(2, 3), null,
                    "21 = 3 x 7 and 39 = 3 x 13.", 1),
            mcq("Equivalent fractions", Difficulty.EASY, "Which fraction is equal to 1/2?",
                    List.of("2/3", "3/6", "2/5", "4/6"), 2, "3/6 = 1/2 (divide both by 3)."),
            tf("Equivalent fractions", Difficulty.MEDIUM, "2/3 and 6/9 are equivalent fractions.", true,
                    "Multiply 2 and 3 by 3."),
            new Q("Equivalent fractions", QuestionType.FILL_BLANK, Difficulty.MEDIUM, BloomLevel.APPLY,
                    "3/4 = ___/12", List.of(), Set.of(), "9", "Multiply both by 3.", 1),
            mcq("Adding and subtracting fractions", Difficulty.MEDIUM, "1/4 + 2/4 = ?",
                    List.of("3/8", "3/4", "2/4", "1/2"), 2, "Add the numerators when the denominators match."),
            mcq("Adding and subtracting fractions", Difficulty.HARD, "1/2 + 1/3 = ?",
                    List.of("2/5", "5/6", "1/6", "2/6"), 2, "3/6 + 2/6 = 5/6."),
            tf("Adding and subtracting fractions", Difficulty.EASY, "5/8 - 3/8 = 2/8", true,
                    "Subtract the numerators."),
            new Q("Adding and subtracting fractions", QuestionType.SHORT_ANSWER, Difficulty.HARD, BloomLevel.APPLY,
                    "Riya ate 1/3 of a cake and her brother ate 1/4 of it. What part of the cake is left? Explain.",
                    List.of(), Set.of(), "1/3 + 1/4 = 7/12 eaten, so 5/12 is left.", null, 3),
            mcq("Decimals in money and measurement", Difficulty.EASY, "How is 5 rupees and 50 paise written?",
                    List.of("Rs 5.05", "Rs 5.50", "Rs 55.0", "Rs 0.55"), 2, "50 paise is half a rupee: 0.50."),
            new Q("Decimals in money and measurement", QuestionType.FILL_BLANK, Difficulty.MEDIUM,
                    BloomLevel.REMEMBER, "1 centimetre = ___ millimetres", List.of(), Set.of(), "10\nten", null, 1),
            mcq("Angles and their measure", Difficulty.MEDIUM, "A right angle measures",
                    List.of("45 degrees", "90 degrees", "180 degrees", "360 degrees"), 2, null),
            mcq("Perimeter and area", Difficulty.MEDIUM, "What is the perimeter of a square with sides of 6 cm?",
                    List.of("12 cm", "24 cm", "36 cm", "18 cm"), 2, "4 x 6 cm = 24 cm."));

    private static final List<String> CLOSED_TEST_QUESTIONS = List.of("How many lakhs make one crore?",
            "What is the place value of 7 in 3,47,215?", "Which number is the greatest?",
            "Which of these numbers are greater than 50,000?", "325 x 24 = ?",
            "What is the remainder when 158 is divided by 12?",
            "In a division, the remainder is always smaller than the divisor.",
            QUESTIONS.get(11).text());
    /** Arjun's answers: option texts, TRUE/FALSE or typed text, by question text. */
    private static final Map<String, List<String>> ARJUN_ANSWERS = Map.of(
            "How many lakhs make one crore?", List.of("100"),
            "What is the place value of 7 in 3,47,215?", List.of("7,000"),
            "Which number is the greatest?", List.of("1,00,001"),
            "Which of these numbers are greater than 50,000?", List.of("50,001"),
            "325 x 24 = ?", List.of("7,600"),
            "What is the remainder when 158 is divided by 12?", List.of("2"),
            "In a division, the remainder is always smaller than the divisor.", List.of("TRUE"),
            QUESTIONS.get(11).text(), List.of("36 x 4 = 144 children each day. 144 x 25 = 3600 children."));
    private static final List<String> OPEN_TEST_QUESTIONS = List.of("Which fraction is equal to 1/2?",
            "2/3 and 6/9 are equivalent fractions.", "3/4 = ___/12", "1/4 + 2/4 = ?", "1/2 + 1/3 = ?",
            "5/8 - 3/8 = 2/8");

    private static final List<String> TEACHER = List.of(Permissions.LESSONS_READ, Permissions.LESSONS_MANAGE);
    private static final List<String> PRINCIPAL = List.of(Permissions.LESSONS_READ, Permissions.LESSONS_APPROVE);

    private final SyllabusService syllabi;
    private final PlanService plans;
    private final MaterialService materials;
    private final QuestionService questions;
    private final OnlineTestService tests;
    private final LearnerService learners;
    private final AcademicsDirectory academics;
    private final AcademicsService academicsService;
    private final UserRepository users;
    private final TransactionTemplate tx;

    DemoLessonsData(SyllabusService syllabi, PlanService plans, MaterialService materials, QuestionService questions,
            OnlineTestService tests, LearnerService learners, AcademicsDirectory academics,
            AcademicsService academicsService, UserRepository users, PlatformTransactionManager transactionManager) {
        this.syllabi = syllabi;
        this.plans = plans;
        this.materials = materials;
        this.questions = questions;
        this.tests = tests;
        this.learners = learners;
        this.academics = academics;
        this.academicsService = academicsService;
        this.users = users;
        this.tx = new TransactionTemplate(transactionManager);
    }

    private static Q mcq(String topic, Difficulty difficulty, String text, List<String> options, int correct,
            String explanation) {
        return new Q(topic, QuestionType.MCQ_SINGLE, difficulty, BloomLevel.UNDERSTAND, text, options,
                Set.of(correct), null, explanation, 1);
    }

    private static Q tf(String topic, Difficulty difficulty, String text, boolean answer, String explanation) {
        return new Q(topic, QuestionType.TRUE_FALSE, difficulty, BloomLevel.REMEMBER, text, List.of(), Set.of(),
                answer ? "TRUE" : "FALSE", explanation, 1);
    }

    /** Returns the number of lesson plans added. {@code userIds} maps the demo people's emails to their ids. */
    int seed(UUID tenantId, Map<String, UUID> userIds) {
        UUID principalId = userIds.get("principal" + DemoDataSeeder.DEMO_DOMAIN);
        UUID teacherId = userIds.get("teacher" + DemoDataSeeder.DEMO_DOMAIN);
        UUID studentUserId = userIds.get("student" + DemoDataSeeder.DEMO_DOMAIN);
        if (principalId == null || teacherId == null || studentUserId == null) {
            return 0;
        }
        Actor principal = new Actor(principalId, "Lakshmi Iyer");
        Actor ravi = new Actor(teacherId, "Ravi Kumar");
        LocalDate today = LocalDate.now(INDIA);
        Integer added = TenantContext.runAs(tenantId, () -> tx.execute(status -> {
            Optional<YearInfo> year = academics.currentYear();
            Optional<ClassView> five = academicsService.classes().stream().filter(c -> c.name().equals("Class 5"))
                    .findFirst();
            Optional<ClassView> two = academicsService.classes().stream().filter(c -> c.name().equals("Class 2"))
                    .findFirst();
            if (year.isEmpty() || five.isEmpty() || two.isEmpty() || today.isBefore(year.get().startsOn())
                    || today.isAfter(year.get().endsOn())) {
                return 0;
            }
            Map<String, Actor> byEmail = new HashMap<>();
            users.findAll().forEach(u -> byEmail.put(u.getEmail(), new Actor(u.getId(), u.getName())));
            Actor kavitha = byEmail.get("kavitha.menon" + DemoDataSeeder.DEMO_DOMAIN);
            Actor farah = byEmail.get("farah.khan" + DemoDataSeeder.DEMO_DOMAIN);
            YearInfo y = year.get();
            ClassView c5 = five.get();
            SectionInfo fiveA = section(c5, "A");
            SectionInfo fiveB = section(c5, "B");
            SectionInfo twoA = section(two.get(), "A");
            UUID maths = subject(c5, "Mathematics");
            UUID english = subject(c5, "English");
            UUID science = subject(c5, "Environmental Studies");

            Map<String, UUID> mathsTopics = as(principal, PRINCIPAL, () -> topics(syllabi.create(
                    syllabus(y, c5.id(), maths, MATHS), principal)));
            Map<String, UUID> englishTopics = as(principal, PRINCIPAL, () -> topics(syllabi.create(
                    syllabus(y, c5.id(), english, ENGLISH), principal)));
            Map<String, UUID> scienceTopics = as(principal, PRINCIPAL, () -> topics(syllabi.create(
                    syllabus(y, c5.id(), science, SCIENCE), principal)));

            int count = 0;
            YearMonth thisMonth = YearMonth.from(today);
            YearMonth first = YearMonth.from(y.startsOn());
            // Topics due so far, taught in 5 A: every Mathematics and Science one, and all but the last English one.
            count += taught(y, fiveA, maths, MATHS, mathsTopics, first, thisMonth, ravi, principal, today, 0);
            if (kavitha != null) {
                count += taught(y, fiveA, english, ENGLISH, englishTopics, first, thisMonth, kavitha, principal,
                        today, 1);
            }
            if (farah != null) {
                count += taught(y, fiveA, science, SCIENCE, scienceTopics, first, thisMonth, farah, principal,
                        today, 0);
            }
            // Ravi's plans around this week: approved, waiting for approval, a draft and one returned.
            LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            Instant now = Instant.now();
            if (!monday.plusWeeks(1).isAfter(y.endsOn())) {
                PlanDetail approved = as(ravi, TEACHER, () -> plans.create(plan(y, fiveA, maths,
                        mathsTopics.get("Adding and subtracting fractions"), null, monday, 5), ravi));
                as(ravi, TEACHER, () -> plans.submit(approved.id(), ravi, now.minusSeconds(86_400)));
                as(principal, PRINCIPAL, () -> plans.approve(approved.id(), "Good use of fraction strips.", principal,
                        now.minusSeconds(3_600)));
                PlanDetail waiting = as(ravi, TEACHER, () -> plans.create(plan(y, fiveA, maths,
                        mathsTopics.get("Decimals in money and measurement"), null, monday.plusWeeks(1), 4), ravi));
                as(ravi, TEACHER, () -> plans.submit(waiting.id(), ravi, now));
                as(ravi, TEACHER, () -> plans.create(plan(y, fiveB, maths, mathsTopics.get("Equivalent fractions"),
                        null, monday, 4), ravi));
                PlanDetail returned = as(ravi, TEACHER, () -> plans.create(plan(y, twoA, subject(two.get(),
                        "Mathematics"), null, "Shapes around us", monday, 2), ravi));
                as(ravi, TEACHER, () -> plans.submit(returned.id(), ravi, now.minusSeconds(7_200)));
                as(principal, PRINCIPAL, () -> plans.returnPlan(returned.id(), "Add a short activity for the first "
                        + "ten minutes, such as finding shapes in the classroom.", principal, now.minusSeconds(3_000)));
                count += 4;
            }

            // Study material: a chart for every Class 5 section and a video link for 5 A.
            LocalDate early = y.startsOn().plusDays(20).isAfter(today) ? today : y.startsOn().plusDays(20);
            MaterialView chart = as(ravi, TEACHER, () -> materials.create(new MaterialForm(y.id(), c5.id(), null,
                    maths, mathsTopics.get("Place value and expanded form"), "Place value chart",
                    "Use this chart to write numbers up to crores. Practise with the number on your house or your "
                            + "school bus.", null, early), ravi, today));
            as(ravi, TEACHER, () -> materials.addFile(chart.id(), FileUploads.check("place-value-chart.txt",
                    ("Place value chart\n\nCrores | Ten lakhs | Lakhs | Ten thousands | Thousands | Hundreds | Tens"
                            + " | Ones\n\nExample: 3,47,215 = 3 lakhs + 4 ten thousands + 7 thousands + 2 hundreds"
                            + " + 1 ten + 5 ones\n").getBytes(StandardCharsets.UTF_8), "file"), ravi, today));
            LocalDate recent = today.minusDays(3).isBefore(y.startsOn()) ? today : today.minusDays(3);
            as(ravi, TEACHER, () -> materials.create(new MaterialForm(y.id(), c5.id(), fiveA.id(), maths,
                    mathsTopics.get("Equivalent fractions"), "Fractions with pictures",
                    "Watch this before Tuesday's class and draw two pictures that show 1/2 = 2/4.",
                    "https://ncert.nic.in/textbook.php", recent), ravi, today));

            // The question bank and the two online tests.
            Map<String, UUID> bank = new LinkedHashMap<>();
            for (Q q : QUESTIONS) {
                QuestionView saved = as(ravi, TEACHER, () -> questions.create(question(c5.id(), maths, mathsTopics,
                        q), ravi));
                bank.put(q.text(), saved.id());
            }
            closedTest(y, c5, fiveA, maths, bank, ravi, studentUserId, today);
            openTest(y, c5, fiveA, maths, bank, ravi, now);
            return count;
        }));
        return added == null ? 0 : added;
    }

    /** Plans for the topics due before this month, approved and marked taught; {@code skipLast} leaves some out. */
    private int taught(YearInfo y, SectionInfo section, UUID subjectId, List<U> units, Map<String, UUID> topicIds,
            YearMonth first, YearMonth thisMonth, Actor teacher, Actor principal, LocalDate today, int skipLast) {
        List<T> due = new ArrayList<>();
        units.forEach(u -> u.topics().stream().filter(t -> first.plusMonths(t.month()).isBefore(thisMonth))
                .forEach(due::add));
        int count = 0;
        for (int i = 0; i < due.size() - skipLast; i++) {
            T t = due.get(i);
            LocalDate week = first.plusMonths(t.month()).atDay(8).with(TemporalAdjusters.previousOrSame(
                    DayOfWeek.MONDAY));
            if (week.isBefore(y.startsOn())) {
                week = y.startsOn().with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
            }
            LocalDate taughtOn = week.plusDays(1 + (i % 3));
            if (!taughtOn.isBefore(today)) {
                continue;
            }
            LocalDate start = week;
            PlanDetail p = as(teacher, TEACHER, () -> plans.create(plan(y, section, subjectId,
                    topicIds.get(t.title()), null, start, t.periods()), teacher));
            Instant submitted = start.minusDays(2).atTime(LocalTime.of(18, 0)).atZone(INDIA).toInstant();
            as(teacher, TEACHER, () -> plans.submit(p.id(), teacher, submitted));
            as(principal, PRINCIPAL, () -> plans.approve(p.id(), null, principal, submitted.plusSeconds(50_000)));
            as(teacher, TEACHER, () -> plans.taught(p.id(), taughtOn, teacher, today));
            count++;
        }
        return count;
    }

    private void closedTest(YearInfo y, ClassView c5, SectionInfo fiveA, UUID maths, Map<String, UUID> bank,
            Actor ravi, UUID studentUserId, LocalDate today) {
        LocalDate day = today.minusDays(9).isBefore(y.startsOn()) ? y.startsOn() : today.minusDays(9);
        Instant opens = day.atTime(9, 0).atZone(INDIA).toInstant();
        Instant closes = day.plusDays(2).atTime(17, 0).atZone(INDIA).toInstant();
        if (!closes.isBefore(Instant.now())) {
            return;
        }
        TestDetail test = as(ravi, TEACHER, () -> tests.create(new TestForm(y.id(), c5.id(), maths,
                List.of(fiveA.id()), CLOSED_TEST, "Answer every question. You have 20 minutes and one attempt.",
                opens, closes, 20, 1, false, false, ShowAnswers.WITH_RESULTS, null), ravi, opens.minusSeconds(86_400)));
        as(ravi, TEACHER, () -> tests.setQuestions(test.id(), new TestQuestionsForm(CLOSED_TEST_QUESTIONS.stream()
                .map(text -> new TestQuestionForm(bank.get(text), null)).toList()), ravi, opens.minusSeconds(86_000)));
        as(ravi, TEACHER, () -> tests.publish(test.id(), ravi, opens.minusSeconds(80_000)));

        // Arjun takes it two hours after it opens and submits after twelve minutes.
        Instant started = opens.plusSeconds(7_200);
        Actor arjun = new Actor(studentUserId, "Arjun Sharma");
        LearnerAttempt attempt = learners.start(studentUserId, test.id(), arjun, started);
        int i = 0;
        for (LearnerQuestion q : attempt.questions()) {
            List<String> answer = ARJUN_ANSWERS.get(q.text());
            if (answer == null) {
                continue;
            }
            List<UUID> options = q.options().stream().filter(o -> answer.contains(o.text())).map(LearnerOption::id)
                    .toList();
            AnswerForm form = q.type() == QuestionType.MCQ_SINGLE || q.type() == QuestionType.MCQ_MULTI
                    ? new AnswerForm(options, null) : new AnswerForm(null, answer.getFirst());
            learners.save(studentUserId, attempt.id(), q.answerId(), form, started.plusSeconds(60L * ++i));
        }
        learners.submit(studentUserId, attempt.id(), arjun, started.plusSeconds(720));

        // Ravi marks the short answer and releases the results.
        Instant now = Instant.now();
        AnswerReview shortAnswer = as(ravi, TEACHER, () -> tests.attempt(test.id(), attempt.id(), now)).answers()
                .stream().filter(a -> a.type() == QuestionType.SHORT_ANSWER).findFirst().orElseThrow();
        as(ravi, TEACHER, () -> tests.mark(test.id(), attempt.id(), new MarksForm(List.of(new MarkForm(
                shortAnswer.answerId(), BigDecimal.valueOf(2), "Clear working. Well done!"))), ravi, now));
        as(ravi, TEACHER, () -> tests.release(test.id(), ravi, now));
    }

    private void openTest(YearInfo y, ClassView c5, SectionInfo fiveA, UUID maths, Map<String, UUID> bank,
            Actor ravi, Instant now) {
        Instant opens = now.minusSeconds(86_400);
        Instant closes = now.plusSeconds(7 * 86_400);
        if (closes.atZone(INDIA).toLocalDate().isAfter(y.endsOn())) {
            return;
        }
        TestDetail test = as(ravi, TEACHER, () -> tests.create(new TestForm(y.id(), c5.id(), maths,
                List.of(fiveA.id()), OPEN_TEST, "Fifteen minutes. You may try twice; your better score counts.",
                opens, closes, 15, 2, false, true, ShowAnswers.WITH_RESULTS, null), ravi, now));
        as(ravi, TEACHER, () -> tests.setQuestions(test.id(), new TestQuestionsForm(OPEN_TEST_QUESTIONS.stream()
                .map(text -> new TestQuestionForm(bank.get(text), null)).toList()), ravi, now));
        as(ravi, TEACHER, () -> tests.publish(test.id(), ravi, now));
    }

    private static SyllabusForm syllabus(YearInfo y, UUID classId, UUID subjectId, List<U> units) {
        YearMonth first = YearMonth.from(y.startsOn());
        YearMonth last = YearMonth.from(y.endsOn());
        return new SyllabusForm(y.id(), classId, subjectId, null, units.stream()
                .map(u -> new UnitForm(null, u.title(), u.topics().stream()
                        .map(t -> new TopicForm(null, t.title(), t.periods(), first.plusMonths(t.month()).isAfter(last)
                                ? last : first.plusMonths(t.month())))
                        .toList()))
                .toList());
    }

    private static Map<String, UUID> topics(SyllabusDetail detail) {
        Map<String, UUID> result = new HashMap<>();
        detail.units().forEach(u -> u.topics().forEach((TopicView t) -> result.put(t.title(), t.id())));
        return result;
    }

    private static PlanForm plan(YearInfo y, SectionInfo section, UUID subjectId, UUID topicId, String title,
            LocalDate week, int periods) {
        return new PlanForm(y.id(), section.id(), subjectId, topicId, title, week, week.plusDays(1), periods,
                "Students can explain the idea in their own words and solve two textbook problems on their own.",
                "Start with a question from daily life, model one example on the board, then pair work and a quick "
                        + "check.",
                "Textbook, worksheet, chart paper", null, "Two practice questions from the textbook exercise.",
                "Exit ticket: one question in the last five minutes.", null);
    }

    private static QuestionForm question(UUID classId, UUID subjectId, Map<String, UUID> topics, Q q) {
        List<OptionForm> options = new ArrayList<>();
        for (int i = 0; i < q.options().size(); i++) {
            options.add(new OptionForm(q.options().get(i), q.correct().contains(i + 1)));
        }
        return new QuestionForm(classId, subjectId, topics.get(q.topic()), q.type(), q.difficulty(), q.bloom(),
                q.text(), q.answer(), q.explanation(), BigDecimal.valueOf(q.marks()), true,
                options.isEmpty() ? null : options);
    }

    private static SectionInfo section(ClassView c, String name) {
        return c.sections().stream().filter(s -> s.name().equals(name))
                .map(s -> new SectionInfo(s.id(), s.name(), s.capacity(), c.id(), c.name(), c.displayOrder(), null,
                        null))
                .findFirst().orElseThrow(() -> new IllegalStateException(c.name() + " has no section " + name));
    }

    private static UUID subject(ClassView c, String name) {
        return c.subjects().stream().filter(s -> s.name().equals(name)).map(SubjectRef::id).findFirst()
                .orElseThrow(() -> new IllegalStateException(c.name() + " has no " + name));
    }

    /** Runs the work signed in as the person, with the permissions they act with here. */
    private static <T> T as(Actor actor, List<String> permissions, Supplier<T> work) {
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        Instant now = Instant.now();
        Jwt jwt = Jwt.withTokenValue("demo-data").header("alg", "none").subject(actor.id().toString())
                .claim(JwtService.CLAIM_NAME, actor.name()).issuedAt(now).expiresAt(now.plusSeconds(600)).build();
        context.setAuthentication(new JwtAuthenticationToken(jwt, permissions.stream()
                .map(SimpleGrantedAuthority::new).toList()));
        SecurityContextHolder.setContext(context);
        try {
            return work.get();
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }
}
