package com.akshara.onboarding;

import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.akshara.academics.AcademicsDirectory;
import com.akshara.academics.AcademicsDirectory.SectionInfo;
import com.akshara.academics.AcademicsDirectory.YearInfo;
import com.akshara.academics.AcademicsService;
import com.akshara.academics.AcademicsService.SubjectView;
import com.akshara.audit.AuditService.Actor;
import com.akshara.files.FileUploads;
import com.akshara.files.IncomingFile;
import com.akshara.homework.HomeworkScope;
import com.akshara.homework.HomeworkService;
import com.akshara.homework.HomeworkService.HomeworkInput;
import com.akshara.homework.HomeworkViews.HomeworkDetail;
import com.akshara.homework.HomeworkViews.StudentHomeworkDetail;
import com.akshara.homework.StudentHomeworkService;
import com.akshara.homework.SubmissionStatus;
import com.akshara.identity.UserService;
import com.akshara.shared.TenantContext;
import com.akshara.timetable.BellScheduleService;
import com.akshara.timetable.BellScheduleService.PeriodInput;
import com.akshara.timetable.SubstitutionService;
import com.akshara.timetable.TimetableService;
import com.akshara.timetable.TimetableService.CellInput;
import com.akshara.timetable.TimetableViews.AbsenceView;
import com.akshara.timetable.TimetableViews.AffectedPeriod;
import com.akshara.timetable.TimetableViews.FreeTeacher;
import com.akshara.timetable.TimetableViews.SubstitutionDay;

/**
 * The demo school's timetable and homework: an 8-period bell schedule with a lunch break (5 periods on Saturdays),
 * subject teachers, complete clash-free timetables for Class 5 A and Class 2 A, a partial one for Class 5 B, a
 * teacher away today with one period covered by Ravi Kumar, and ten homework items for Class 5 A with a few
 * submissions by Arjun Sharma. Sample PDFs are generated here, so no binary files live in the repository.
 */
@Component
class DemoTimetableHomeworkData {

    record Result(int teachers, int timetables, int homework, int submissions) {
    }

    record DemoTeacher(String name, String mailbox, List<String> subjects) {
    }

    /** Ravi Kumar (the demo teacher) teaches Mathematics; the others are added here. */
    static final List<DemoTeacher> TEACHERS = List.of(
            new DemoTeacher("Kavitha Menon", "kavitha.menon", List.of("ENG")),
            new DemoTeacher("Sunita Verma", "sunita.verma", List.of("HIN")),
            new DemoTeacher("Srinivas Rao", "srinivas.rao", List.of("TEL")),
            new DemoTeacher("Farah Khan", "farah.khan", List.of("EVS")),
            new DemoTeacher("Arun Joshi", "arun.joshi", List.of("CS", "GK")),
            new DemoTeacher("Deepa Nair", "deepa.nair", List.of("ART")),
            new DemoTeacher("Harish Patil", "harish.patil", List.of("PE")));

    static final Map<String, Integer> WEEKLY_PERIODS = Map.of("MAT", 8, "ENG", 7, "HIN", 6, "EVS", 6, "TEL", 5,
            "ART", 4, "PE", 4, "CS", 3, "GK", 2);

    /** Monday to Saturday, period 1 onwards, by subject code. Class 2 A never has a subject when 5 A has it. */
    static final List<String> CLASS_5A = List.of(
            "MAT ENG MAT EVS ENG HIN EVS HIN",
            "MAT TEL MAT ENG PE HIN ART ENG",
            "TEL MAT EVS TEL ENG MAT EVS PE",
            "CS HIN ART PE EVS TEL ENG ART",
            "MAT HIN GK CS MAT PE EVS TEL",
            "CS HIN ENG ART GK");
    static final List<String> CLASS_2A = List.of(
            "ENG MAT EVS MAT HIN ENG HIN EVS",
            "ENG MAT TEL MAT EVS ENG HIN TEL",
            "MAT EVS PE ART CS TEL ART ENG",
            "HIN MAT PE TEL MAT ENG ART GK",
            "CS EVS PE HIN CS EVS GK HIN",
            "MAT ENG ART PE TEL");
    /** Class 5 B so far has only its Mathematics periods (day index, period). */
    static final int[][] CLASS_5B_MATHS = {{0, 5}, {1, 5}, {2, 3}, {3, 1}, {4, 2}, {5, 2}};

    static final String TEACHER_AWAY = "Farah Khan";

    private final UserService users;
    private final AcademicsDirectory academics;
    private final AcademicsService academicsService;
    private final BellScheduleService bells;
    private final TimetableService timetable;
    private final SubstitutionService substitutions;
    private final HomeworkService homework;
    private final StudentHomeworkService learners;
    private final TransactionTemplate tx;

    DemoTimetableHomeworkData(UserService users, AcademicsDirectory academics, AcademicsService academicsService,
            BellScheduleService bells, TimetableService timetable, SubstitutionService substitutions,
            HomeworkService homework, StudentHomeworkService learners, PlatformTransactionManager transactionManager) {
        this.users = users;
        this.academics = academics;
        this.academicsService = academicsService;
        this.bells = bells;
        this.timetable = timetable;
        this.substitutions = substitutions;
        this.homework = homework;
        this.learners = learners;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** {@code userIds} maps the demo people's emails to their ids; {@code passwordHash} is for the new teachers. */
    Result seed(UUID tenantId, Map<String, UUID> userIds, String passwordHash) {
        Actor seeder = new Actor(null, "Demo data");
        Map<String, Actor> teacherOf = new HashMap<>();
        teacherOf.put("MAT", new Actor(userIds.get("teacher" + DemoDataSeeder.DEMO_DOMAIN), "Ravi Kumar"));
        TenantContext.runAs(tenantId, () -> tx.execute(status -> {
            for (DemoTeacher t : TEACHERS) {
                UUID id = users.createUser(t.name(), t.mailbox() + DemoDataSeeder.DEMO_DOMAIN, passwordHash,
                        List.of("TEACHER"), seeder).getId();
                t.subjects().forEach(code -> teacherOf.put(code, new Actor(id, t.name())));
            }
            return null;
        }));
        YearInfo year = TenantContext.runAs(tenantId, () -> academics.currentYear().orElse(null));
        if (year == null) {
            return new Result(TEACHERS.size(), 0, 0, 0);
        }
        Integer timetables = TenantContext.runAs(tenantId, () -> tx.execute(status -> timetables(teacherOf)));
        TenantContext.runAs(tenantId, () -> tx.execute(status -> substitutionToday(year, teacherOf)));
        int[] homeworkCounts = TenantContext.runAs(tenantId,
                () -> tx.execute(status -> homework(year, teacherOf, userIds)));
        return new Result(TEACHERS.size(), timetables == null ? 0 : timetables,
                homeworkCounts == null ? 0 : homeworkCounts[0], homeworkCounts == null ? 0 : homeworkCounts[1]);
    }

    // ------------------------------------------------------------------ timetable

    private int timetables(Map<String, Actor> teacherOf) {
        bells.save(List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY,
                DayOfWeek.FRIDAY, DayOfWeek.SATURDAY), true, List.of(
                        new PeriodInput("Period 1", "08:30", "09:10", false),
                        new PeriodInput("Period 2", "09:10", "09:50", false),
                        new PeriodInput("Period 3", "09:50", "10:30", false),
                        new PeriodInput("Short break", "10:30", "10:45", true),
                        new PeriodInput("Period 4", "10:45", "11:25", false),
                        new PeriodInput("Period 5", "11:25", "12:05", false),
                        new PeriodInput("Lunch", "12:05", "12:45", true),
                        new PeriodInput("Period 6", "12:45", "13:25", false),
                        new PeriodInput("Period 7", "13:25", "14:05", false),
                        new PeriodInput("Period 8", "14:05", "14:45", false)),
                List.of(
                        new PeriodInput("Period 1", "08:30", "09:10", false),
                        new PeriodInput("Period 2", "09:10", "09:50", false),
                        new PeriodInput("Period 3", "09:50", "10:30", false),
                        new PeriodInput("Short break", "10:30", "10:45", true),
                        new PeriodInput("Period 4", "10:45", "11:25", false),
                        new PeriodInput("Period 5", "11:25", "12:05", false)));
        Map<String, UUID> subjects = subjectsByCode();
        Map<String, SectionInfo> sections = new HashMap<>();
        academics.sections().forEach(s -> sections.put(s.className() + "|" + s.name(), s));
        int saved = 0;
        for (String key : List.of("Class 5|A", "Class 2|A")) {
            SectionInfo section = sections.get(key);
            if (section == null) {
                continue;
            }
            for (Map.Entry<String, Integer> e : WEEKLY_PERIODS.entrySet()) {
                timetable.createAssignment(section.id(), subjects.get(e.getKey()), teacherOf.get(e.getKey()).id(),
                        e.getValue());
            }
            List<String> grid = key.startsWith("Class 5") ? CLASS_5A : CLASS_2A;
            boolean fifth = key.startsWith("Class 5");
            List<CellInput> cells = new ArrayList<>();
            for (int d = 0; d < grid.size(); d++) {
                String[] codes = grid.get(d).split(" ");
                for (int p = 0; p < codes.length; p++) {
                    String code = codes[p];
                    cells.add(new CellInput(DayOfWeek.of(d + 1), p + 1, subjects.get(code),
                            teacherOf.get(code).id(), room(code, fifth)));
                }
            }
            timetable.saveSection(section.id(), cells);
            saved++;
        }
        SectionInfo fiveB = sections.get("Class 5|B");
        if (fiveB != null) {
            timetable.createAssignment(fiveB.id(), subjects.get("MAT"), teacherOf.get("MAT").id(), 8);
            timetable.createAssignment(fiveB.id(), subjects.get("ENG"), teacherOf.get("ENG").id(), 7);
            List<CellInput> cells = new ArrayList<>();
            for (int[] cell : CLASS_5B_MATHS) {
                cells.add(new CellInput(DayOfWeek.of(cell[0] + 1), cell[1], subjects.get("MAT"), null, null));
            }
            timetable.saveSection(fiveB.id(), cells);
            saved++;
        }
        return saved;
    }

    private static String room(String code, boolean fifth) {
        return switch (code) {
            case "CS" -> "Computer lab";
            case "PE" -> "Playground";
            case "ART" -> "Art room";
            default -> fifth ? "Room 12" : "Room 4";
        };
    }

    /** Farah Khan is away today; Ravi Kumar (or the best free teacher) covers her first class. */
    private Void substitutionToday(YearInfo year, Map<String, Actor> teacherOf) {
        LocalDate today = HomeworkService.today();
        if (today.isBefore(year.startsOn()) || today.isAfter(year.endsOn())
                || today.getDayOfWeek() == DayOfWeek.SUNDAY) {
            return null;
        }
        SubstitutionDay day = substitutions.recordAbsence(today, teacherOf.get("EVS").id(), "Medical leave");
        UUID ravi = teacherOf.get("MAT").id();
        for (AbsenceView absence : day.absences()) {
            for (AffectedPeriod period : absence.periods()) {
                Optional<FreeTeacher> pick = period.suggestions().stream().filter(t -> t.id().equals(ravi))
                        .findFirst().or(() -> period.suggestions().stream().findFirst());
                if (pick.isPresent()) {
                    substitutions.assign(today, period.sectionId(), period.period(), pick.get().id());
                    return null;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ homework

    /** subject code, title, instructions, days before "today" it was set, days after that it is due, online, PDF. */
    record DemoHomework(String code, String title, String instructions, int setDaysAgo, int dueInDays,
            boolean online, boolean pdf) {
    }

    static final List<DemoHomework> HOMEWORK = List.of(
            new DemoHomework("MAT", "Fractions worksheet", "Solve all 20 questions on the worksheet. Show your "
                    + "working for questions 15 to 20.", 12, 3, true, true),
            new DemoHomework("ENG", "Write a letter to a friend", "Write a letter of about 150 words to a friend "
                    + "describing your summer holidays.", 10, 3, true, false),
            new DemoHomework("EVS", "Water cycle diagram", "Draw and label the water cycle. Use the notes in the "
                    + "attached sheet.", 8, 3, true, true),
            new DemoHomework("HIN", "Poem recitation", "Learn the poem on page 34 by heart. You will recite it in "
                    + "class.", 7, 3, false, false),
            new DemoHomework("TEL", "Telugu letters practice", "Write each vowel five times in your notebook and "
                    + "upload a photo.", 6, 3, true, false),
            new DemoHomework("CS", "Parts of a computer", "Name the parts of a computer and write one line about "
                    + "what each part does.", 4, 5, true, false),
            new DemoHomework("GK", "Capitals of Indian states", "Learn the capitals of the states in the attached "
                    + "list. There will be a short quiz.", 3, 5, true, true),
            new DemoHomework("MAT", "Multiplication tables 12 to 15", "Write out the tables from 12 to 15 and learn "
                    + "them.", 2, 5, true, false),
            new DemoHomework("ART", "Make a paper boat", "Make a paper boat at home and bring it to the art class.",
                    1, 5, false, false),
            new DemoHomework("ENG", "Chapter 5 questions", "Read chapter 5 of the English reader and answer the "
                    + "questions in the attached sheet.", 0, 5, true, true));

    private int[] homework(YearInfo year, Map<String, Actor> teacherOf, Map<String, UUID> userIds) {
        SectionInfo section = academics.sections().stream()
                .filter(s -> (s.className() + "|" + s.name()).equals(DemoSchoolData.CLASS_TEACHER_SECTION))
                .findFirst().orElse(null);
        UUID arjun = userIds.get("student" + DemoDataSeeder.DEMO_DOMAIN);
        if (section == null || arjun == null) {
            return new int[] {0, 0};
        }
        LocalDate today = HomeworkService.today();
        LocalDate base = today.isAfter(year.endsOn().minusDays(6)) ? year.endsOn().minusDays(6) : today;
        if (base.isBefore(year.startsOn().plusDays(13))) {
            base = year.startsOn().plusDays(13);
        }
        Map<String, UUID> subjects = subjectsByCode();
        Actor student = new Actor(arjun, "Arjun Sharma");
        List<UUID> ids = new ArrayList<>();
        for (DemoHomework h : HOMEWORK) {
            LocalDate setOn = base.minusDays(h.setDaysAgo());
            Actor teacher = teacherOf.get(h.code());
            HomeworkDetail created = homework.create(HomeworkScope.WHOLE_SCHOOL, new HomeworkInput(
                    List.of(section.id()), subjects.get(h.code()), h.title(), h.instructions(), setOn,
                    setOn.plusDays(h.dueInDays()), h.online()), teacher, at(setOn, LocalTime.of(14, 50)), setOn);
            if (h.pdf()) {
                IncomingFile pdf = FileUploads.check(fileName(h.title()) + ".pdf",
                        samplePdf(h.title(), List.of(h.instructions(), "Class 5 A - Akshara Demo School")), "file");
                homework.addAttachment(HomeworkScope.WHOLE_SCHOOL, created.id(), pdf, teacher, setOn);
            }
            ids.add(created.id());
        }
        int submitted = 0;
        // Fractions: on time, reviewed with a grade.
        StudentHomeworkDetail fractions = submit(arjun, ids.get(0), base.minusDays(10), "All 20 questions done. "
                + "Working for 15 to 20 is in the attached file.", List.of(text("fractions-working.txt",
                        "15) 3/4 + 1/8 = 7/8\n16) 2/3 - 1/6 = 1/2\n17) 5/6 x 3 = 5/2\n")), student);
        review(ids.get(0), fractions, SubmissionStatus.REVIEWED, "A", "Neat work. Well done!",
                teacherOf.get("MAT"), base.minusDays(8));
        submitted++;
        // Letter: one day late, reviewed.
        StudentHomeworkDetail letter = submit(arjun, ids.get(1), base.minusDays(6), "Dear Rohan,\nThis summer I "
                + "visited my grandparents in Warangal. We went to the fort and the lake...\nYour friend,\nArjun",
                List.of(), student);
        review(ids.get(1), letter, SubmissionStatus.REVIEWED, "B+", "Good letter. Remember to add the date.",
                teacherOf.get("ENG"), base.minusDays(5));
        submitted++;
        // Water cycle: sent back to be done again.
        StudentHomeworkDetail water = submit(arjun, ids.get(2), base.minusDays(6), "Diagram attached.",
                List.of(text("water-cycle.txt", "Evaporation -> Condensation -> Precipitation\n")), student);
        review(ids.get(2), water, SubmissionStatus.NEEDS_REDO, null, "Please label the collection step too.",
                teacherOf.get("EVS"), base.minusDays(4));
        submitted++;
        // Parts of a computer: submitted, waiting for review.
        submit(arjun, ids.get(5), base, "Monitor - shows the output. Keyboard - to type. Mouse - to point and "
                + "click. CPU - the brain of the computer.", List.of(), student);
        submitted++;
        return new int[] {ids.size(), submitted};
    }

    private StudentHomeworkDetail submit(UUID studentUser, UUID homeworkId, LocalDate day, String text,
            List<IncomingFile> files, Actor student) {
        return learners.submit(studentUser, homeworkId, text, List.of(), files, student, at(day, LocalTime.of(18, 30)));
    }

    private void review(UUID homeworkId, StudentHomeworkDetail detail, SubmissionStatus status, String grade,
            String remark, Actor teacher, LocalDate day) {
        homework.review(HomeworkScope.WHOLE_SCHOOL, homeworkId, detail.submission().id(), status, grade, remark,
                teacher, at(day, LocalTime.of(16, 0)));
    }

    /** The time on the day in India, but never in the future. */
    private static Instant at(LocalDate day, LocalTime time) {
        Instant at = day.atTime(time).atZone(HomeworkService.INDIA).toInstant();
        Instant now = Instant.now();
        return at.isAfter(now) ? now : at;
    }

    private Map<String, UUID> subjectsByCode() {
        Map<String, UUID> byCode = new LinkedHashMap<>();
        for (SubjectView s : academicsService.subjects()) {
            if (s.code() != null) {
                byCode.put(s.code(), s.id());
            }
        }
        return byCode;
    }

    private static IncomingFile text(String name, String content) {
        return FileUploads.check(name, content.getBytes(StandardCharsets.UTF_8), "files");
    }

    private static String fileName(String title) {
        return title.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }

    /** A small one-page PDF with a title and lines of plain ASCII text. */
    static byte[] samplePdf(String title, List<String> lines) {
        StringBuilder content = new StringBuilder("BT /F1 18 Tf 72 770 Td (").append(pdfText(title))
                .append(") Tj ET\n");
        int y = 740;
        for (String line : lines) {
            for (String part : wrap(line, 80)) {
                content.append("BT /F1 11 Tf 72 ").append(y).append(" Td (").append(pdfText(part)).append(") Tj ET\n");
                y -= 16;
            }
        }
        String stream = content.toString();
        List<String> objects = List.of(
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents 4 0 R "
                        + "/Resources << /Font << /F1 5 0 R >> >> >>",
                "<< /Length " + stream.getBytes(StandardCharsets.US_ASCII).length + " >>\nstream\n" + stream
                        + "endstream",
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>");
        StringBuilder pdf = new StringBuilder("%PDF-1.4\n");
        List<Integer> offsets = new ArrayList<>();
        for (int i = 0; i < objects.size(); i++) {
            offsets.add(pdf.toString().getBytes(StandardCharsets.US_ASCII).length);
            pdf.append(i + 1).append(" 0 obj\n").append(objects.get(i)).append("\nendobj\n");
        }
        int xref = pdf.toString().getBytes(StandardCharsets.US_ASCII).length;
        pdf.append("xref\n0 ").append(objects.size() + 1).append("\n0000000000 65535 f \n");
        offsets.forEach(o -> pdf.append(String.format("%010d 00000 n \n", o)));
        pdf.append("trailer\n<< /Size ").append(objects.size() + 1).append(" /Root 1 0 R >>\nstartxref\n")
                .append(xref).append("\n%%EOF\n");
        return pdf.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private static String pdfText(String value) {
        return value.replaceAll("[^\\x20-\\x7E]", "?").replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)");
    }

    private static List<String> wrap(String text, int width) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            if (line.length() + word.length() + 1 > width && !line.isEmpty()) {
                lines.add(line.toString());
                line.setLength(0);
            }
            if (!line.isEmpty()) {
                line.append(' ');
            }
            line.append(word);
        }
        if (!line.isEmpty()) {
            lines.add(line.toString());
        }
        return lines;
    }
}
