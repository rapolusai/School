package com.akshara.onboarding;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
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
import com.akshara.academics.AcademicsService.ClassView;
import com.akshara.academics.AcademicsService.SubjectRef;
import com.akshara.audit.AuditService.Actor;
import com.akshara.exams.ExamScope;
import com.akshara.exams.ExamSetupService;
import com.akshara.exams.ExamsForms.ExamForm;
import com.akshara.exams.ExamsForms.MarkEntryForm;
import com.akshara.exams.ExamsForms.MarkSheetForm;
import com.akshara.exams.ExamsForms.PaperForm;
import com.akshara.exams.ExamsForms.PapersForm;
import com.akshara.exams.ExamsForms.RecordForm;
import com.akshara.exams.ExamsForms.RecordsForm;
import com.akshara.exams.ExamsForms.TermForm;
import com.akshara.exams.ExamsForms.WeightForm;
import com.akshara.exams.ExamsForms.WeightsForm;
import com.akshara.exams.ExamsViews.ExamClassDetail;
import com.akshara.exams.ExamsViews.ExamSummary;
import com.akshara.exams.ExamsViews.MarkSheetView;
import com.akshara.exams.ExamsViews.PaperView;
import com.akshara.exams.ExamsViews.Structure;
import com.akshara.exams.ExamsViews.TermView;
import com.akshara.exams.HpcLevel;
import com.akshara.exams.MarkCode;
import com.akshara.exams.MarksService;
import com.akshara.exams.RecordsService;
import com.akshara.exams.ResultsService;
import com.akshara.shared.TenantContext;
import com.akshara.students.StudentRoster;
import com.akshara.students.StudentRoster.RosterStudent;

/**
 * The demo school's exams in 2026-27 (the built-in grading schemes come with every school; Class 5 uses CBSE
 * classes 1 to 8 by default): two terms weighted 50/50; Periodic Test 1 for Class 5 in September, with every mark
 * entered and locked, Class 5 A's co-scholastic grades, remarks and holistic progress cards, and the results published
 * (so the demo parent sees Arjun's report card); and the Half-Yearly Examination for Classes 2 and 5 in the exam week
 * of the school calendar (7 to 12 December), its date sheet published. Added after the calendar exists.
 */
@Component
class DemoExamsData {

    static final String PERIODIC_TEST = "Periodic Test 1";
    static final String HALF_YEARLY = "Half-Yearly Examination";
    static final List<String> TEST_SUBJECTS = List.of("English", "Hindi", "Telugu", "Mathematics",
            "Environmental Studies");
    static final List<String> EXAM_SUBJECTS = List.of("English", "Hindi", "Telugu", "Mathematics",
            "Environmental Studies", "Computer Science");
    static final LocalDate TEST_STARTS = LocalDate.of(2026, 9, 14);
    static final LocalDate EXAM_STARTS = LocalDate.of(2026, 12, 7);
    /** Arjun's Periodic Test 1 marks out of 40, in {@link #TEST_SUBJECTS} order. */
    static final int[] ARJUN = {37, 34, 33, 39, 36};

    private static final BigDecimal FORTY = BigDecimal.valueOf(40);
    private static final List<String> REMARKS = List.of(
            "Attentive in class and completes work on time.",
            "Participates well. Reading aloud at home will help.",
            "Neat work. Should ask questions when in doubt.",
            "A cheerful learner who helps classmates.");
    private static final List<HpcLevel> LEVELS = List.of(HpcLevel.PROFICIENT, HpcLevel.ADVANCED,
            HpcLevel.PROGRESSING, HpcLevel.PROFICIENT);

    private final ExamSetupService setup;
    private final MarksService marks;
    private final RecordsService records;
    private final ResultsService results;
    private final AcademicsDirectory academics;
    private final AcademicsService academicsService;
    private final StudentRoster roster;
    private final TransactionTemplate tx;

    DemoExamsData(ExamSetupService setup, MarksService marks, RecordsService records, ResultsService results,
            AcademicsDirectory academics, AcademicsService academicsService, StudentRoster roster,
            PlatformTransactionManager transactionManager) {
        this.setup = setup;
        this.marks = marks;
        this.records = records;
        this.results = results;
        this.academics = academics;
        this.academicsService = academicsService;
        this.roster = roster;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** Returns the number of marks entered. {@code userIds} maps the demo people's emails to their ids. */
    int seed(UUID tenantId, Map<String, UUID> userIds) {
        UUID principalId = userIds.get("principal" + DemoDataSeeder.DEMO_DOMAIN);
        UUID teacherId = userIds.get("teacher" + DemoDataSeeder.DEMO_DOMAIN);
        if (principalId == null || teacherId == null) {
            return 0;
        }
        Actor principal = new Actor(principalId, "Lakshmi Iyer");
        Actor teacher = new Actor(teacherId, "Ravi Kumar");
        Integer entered = TenantContext.runAs(tenantId, () -> tx.execute(status -> {
            Optional<YearInfo> year = academics.currentYear();
            Optional<ClassView> classFive = classNamed("Class 5");
            Optional<ClassView> classTwo = classNamed("Class 2");
            if (year.isEmpty() || classFive.isEmpty() || classTwo.isEmpty()
                    || year.get().endsOn().isBefore(EXAM_STARTS) || year.get().startsOn().isAfter(TEST_STARTS)) {
                return 0;
            }
            UUID yearId = year.get().id();
            LocalDate termTwoStarts = LocalDate.of(2027, 1, 1);
            setup.createTerm(new TermForm(yearId, "Term 1", year.get().startsOn(), termTwoStarts.minusDays(1)),
                    principal);
            Structure structure = setup.createTerm(new TermForm(yearId, "Term 2", termTwoStarts,
                    year.get().endsOn()), principal);
            UUID termOne = term(structure, "Term 1").id();
            UUID termTwo = term(structure, "Term 2").id();
            setup.setTermWeights(yearId, new WeightsForm(List.of(new WeightForm(termOne, BigDecimal.valueOf(50)),
                    new WeightForm(termTwo, BigDecimal.valueOf(50)))), principal);

            setup.createExam(new ExamForm(termOne, PERIODIC_TEST, "Bring your own stationery. Reach the classroom "
                    + "by 9:20.", List.of(classFive.get().id())), principal);
            structure = setup.createExam(new ExamForm(termOne, HALF_YEARLY, "Reach school by 8:45. Carry this hall "
                    + "ticket every day; it will be checked at the door.", List.of(classTwo.get().id(),
                    classFive.get().id())), principal);
            UUID test = exam(structure, PERIODIC_TEST).id();
            UUID halfYearly = exam(structure, HALF_YEARLY).id();
            setup.setExamWeights(termOne, new WeightsForm(List.of(new WeightForm(test, BigDecimal.valueOf(20)),
                    new WeightForm(halfYearly, BigDecimal.valueOf(80)))), principal);

            // Periodic Test 1: one paper a day from Monday 14 September, 9:30 to 10:30, 40 marks each.
            List<PaperForm> testPapers = new ArrayList<>();
            for (int i = 0; i < TEST_SUBJECTS.size(); i++) {
                testPapers.add(new PaperForm(subject(classFive.get(), TEST_SUBJECTS.get(i)), FORTY, null, null,
                        TEST_STARTS.plusDays(i), LocalTime.of(9, 30), 60, null));
            }
            ExamClassDetail testClass = setup.savePapers(test, classFive.get().id(), new PapersForm(testPapers),
                    principal);
            setup.publishDateSheet(test, classFive.get().id(), principal);

            // Half-Yearly: Monday 7 to Saturday 12 December from 9:00; theory 80 and internal assessment 20.
            for (ClassView c : List.of(classTwo.get(), classFive.get())) {
                List<PaperForm> papers = new ArrayList<>();
                for (int i = 0; i < EXAM_SUBJECTS.size(); i++) {
                    boolean computers = "Computer Science".equals(EXAM_SUBJECTS.get(i));
                    papers.add(new PaperForm(subject(c, EXAM_SUBJECTS.get(i)),
                            BigDecimal.valueOf(computers ? 50 : 80), computers ? BigDecimal.valueOf(30) : null,
                            BigDecimal.valueOf(20), EXAM_STARTS.plusDays(i), LocalTime.of(9, 0),
                            c == classFive.get() ? 150 : 120, null));
                }
                setup.savePapers(halfYearly, c.id(), new PapersForm(papers), principal);
                setup.publishDateSheet(halfYearly, c.id(), principal);
            }

            // Marks: Ravi Kumar for his Class 5 A, the principal for Class 5 B.
            int count = 0;
            for (SectionInfo section : academics.sectionsOfClass(classFive.get().id())) {
                List<RosterStudent> students = roster.activeInSection(yearId, section.id());
                boolean classTeacher = teacherId.equals(section.classTeacherId());
                Actor by = classTeacher ? teacher : principal;
                for (int j = 0; j < testClass.papers().size(); j++) {
                    PaperView paper = testClass.papers().get(j);
                    int subjectIndex = TEST_SUBJECTS.indexOf(paper.subjectName());
                    List<MarkEntryForm> entries = new ArrayList<>();
                    for (int i = 0; i < students.size(); i++) {
                        entries.add(entry(students.get(i), i, subjectIndex, students.size(), classTeacher));
                    }
                    MarkSheetView sheet = marks.save(paper.id(), section.id(), new MarkSheetForm(null, entries), by,
                            ExamScope.WHOLE_SCHOOL);
                    marks.submit(paper.id(), section.id(), sheet.version(), by, ExamScope.WHOLE_SCHOOL);
                    count += entries.size();
                }
                if (classTeacher) {
                    records.save(termOne, section.id(), new RecordsForm(recordsFor(students)), teacher,
                            ExamScope.WHOLE_SCHOOL);
                }
            }
            results.publish(test, classFive.get().id(), principal);
            return count;
        }));
        return entered == null ? 0 : entered;
    }

    /**
     * A student's Periodic Test mark out of 40: a steady level for the student with some spread across subjects.
     * In the class teacher's section the second student missed the Hindi test (AB); in the other section the last
     * student is below the pass mark in Mathematics, so the analysis has someone to follow up.
     */
    private static MarkEntryForm entry(RosterStudent s, int index, int subjectIndex, int size, boolean classTeacher) {
        if ("Arjun Sharma".equals(s.fullName())) {
            return new MarkEntryForm(s.id(), BigDecimal.valueOf(ARJUN[subjectIndex]), null, null, null);
        }
        if (classTeacher && index == 1 && "Hindi".equals(TEST_SUBJECTS.get(subjectIndex))) {
            return new MarkEntryForm(s.id(), null, null, null, MarkCode.AB);
        }
        if (!classTeacher && index == size - 1 && "Mathematics".equals(TEST_SUBJECTS.get(subjectIndex))) {
            return new MarkEntryForm(s.id(), BigDecimal.valueOf(10), null, null, null);
        }
        int base = 60 + Math.floorMod(s.fullName().hashCode(), 33);
        int spread = Math.floorMod(index * 7 + subjectIndex * 11, 21) - 10;
        int percent = Math.max(35, Math.min(100, base + spread));
        return new MarkEntryForm(s.id(), BigDecimal.valueOf(Math.round(percent * 40 / 100.0)), null, null, null);
    }

    private static List<RecordForm> recordsFor(List<RosterStudent> students) {
        List<RecordForm> list = new ArrayList<>();
        for (int i = 0; i < students.size(); i++) {
            RosterStudent s = students.get(i);
            if ("Arjun Sharma".equals(s.fullName())) {
                list.add(new RecordForm(s.id(), "A", "A", "A", "A",
                        "Arjun is curious and works hard. He should keep practising Telugu reading at home.",
                        HpcLevel.PROFICIENT, HpcLevel.ADVANCED, HpcLevel.ADVANCED, HpcLevel.PROFICIENT,
                        HpcLevel.ADVANCED, HpcLevel.PROGRESSING,
                        "Arjun solves problems in more than one way and explains his thinking clearly.",
                        "I like maths puzzles. I want to read more Telugu stories.",
                        "He enjoys science programmes at home and asks many questions."));
                continue;
            }
            list.add(new RecordForm(s.id(), "A", i % 3 == 2 ? "B" : "A", "A", i % 4 == 3 ? "B" : "A",
                    REMARKS.get(i % REMARKS.size()), LEVELS.get(i % 4), LEVELS.get((i + 1) % 4),
                    LEVELS.get((i + 2) % 4), LEVELS.get((i + 3) % 4), LEVELS.get(i % 4), LEVELS.get((i + 1) % 4),
                    null, null, null));
        }
        return list;
    }

    private Optional<ClassView> classNamed(String name) {
        return academicsService.classes().stream().filter(c -> c.name().equals(name)).findFirst();
    }

    private static UUID subject(ClassView c, String name) {
        return c.subjects().stream().filter(s -> s.name().equals(name)).map(SubjectRef::id).findFirst()
                .orElseThrow(() -> new IllegalStateException(c.name() + " has no " + name));
    }

    private static TermView term(Structure structure, String name) {
        return structure.terms().stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
    }

    private static ExamSummary exam(Structure structure, String name) {
        return structure.terms().stream().flatMap(t -> t.exams().stream()).filter(e -> e.name().equals(name))
                .findFirst().orElseThrow();
    }
}
