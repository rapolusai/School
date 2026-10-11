package com.akshara.exams;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.academics.AcademicsDirectory.SectionInfo;
import com.akshara.academics.AcademicsDirectory.YearInfo;
import com.akshara.academics.AcademicsService.ClassView;
import com.akshara.academics.AcademicsService.SubjectView;
import com.akshara.audit.AuditService;
import com.akshara.exams.ExamsViews.Analysis;
import com.akshara.exams.ExamsViews.Band;
import com.akshara.exams.ExamsViews.BelowPass;
import com.akshara.exams.ExamsViews.GradeCount;
import com.akshara.exams.ExamsViews.SectionStat;
import com.akshara.exams.ExamsViews.SubjectStat;
import com.akshara.exams.ExamsViews.WeakSubject;
import com.akshara.shared.CurrentUser;
import com.akshara.shared.TenantContext;
import com.akshara.students.StudentRoster.RosterStudent;

/**
 * Result analysis of an exam for a class: subject averages, highest and lowest marks, grade distribution, pass
 * percentages per subject and per section, the change since the previous exam, and the students below the pass mark.
 * Worked out from the marks entered so far. The download is a CSV file (UTF-8 with a byte order mark so Excel opens
 * it with Indian scripts intact): the reports module's .xlsx writer is internal to that module.
 */
@Service
@Transactional(readOnly = true)
public class AnalysisService {

    /** A file to download. */
    public record Download(String fileName, byte[] bytes) {
    }

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd MMM yyyy, h:mm a", Locale.ENGLISH);

    private final ExamClassRepository examClasses;
    private final ExamRepository exams;
    private final ExamSupport support;
    private final GradingService grading;
    private final MarksService marks;
    private final ResultsService results;
    private final Scorer scorer;
    private final AuditService audit;

    AnalysisService(ExamClassRepository examClasses, ExamRepository exams, ExamSupport support,
            GradingService grading, MarksService marks, ResultsService results, Scorer scorer, AuditService audit) {
        this.examClasses = examClasses;
        this.exams = exams;
        this.support = support;
        this.grading = grading;
        this.marks = marks;
        this.results = results;
        this.scorer = scorer;
        this.audit = audit;
    }

    private record Pupil(RosterStudent student, SectionInfo section) {
    }

    public Analysis analysis(UUID examId, UUID classId) {
        TenantContext.require();
        Exam exam = support.exam(examId);
        ExamClass ec = support.examClass(examId, classId);
        ClassView schoolClass = support.classView(classId);
        YearInfo year = support.year(exam.getAcademicYearId());
        GradingService.Graded graded = grading.graded(classId, schoolClass.name(), year.id());
        Map<UUID, SubjectView> subjects = support.subjects();

        List<SectionInfo> sections = support.sectionsOfClass(classId);
        Map<UUID, Pupil> pupils = new LinkedHashMap<>();
        for (SectionInfo section : sections) {
            for (RosterStudent s : results.studentsOf(year, section, List.of(ec))) {
                pupils.putIfAbsent(s.id(), new Pupil(s, section));
            }
        }
        Scorer.Scored scored = scorer.score(ec, pupils.keySet(), graded.scale(), subjects);

        Exam previous = previousExam(exam, year, classId);
        Scorer.Scored before = null;
        if (previous != null) {
            ExamClass previousEc = examClasses.findByExamIdAndClassId(previous.getId(), classId).orElseThrow();
            before = scorer.score(previousEc, pupils.keySet(), graded.scale(), subjects);
        }

        List<Band> bands = graded.ref() == null ? List.of() : graded.ref().bands();
        List<SubjectStat> subjectStats = new ArrayList<>();
        for (Paper p : scored.papers()) {
            UUID subjectId = p.getSubjectId();
            List<GradeMath.SubjectScore> all = scored.byStudent().values().stream()
                    .map(s -> s.bySubject().get(subjectId)).toList();
            List<GradeMath.SubjectScore> wrote = all.stream().filter(AnalysisService::wrote).toList();
            int absent = (int) all.stream().filter(s -> s != null && s.code() == MarkCode.AB).count();
            int passed = (int) wrote.stream().filter(s -> Boolean.TRUE.equals(s.passed())).count();
            BigDecimal average = average(wrote.stream().map(GradeMath.SubjectScore::percent).toList());
            BigDecimal previousAverage = before == null ? null
                    : average(before.byStudent().values().stream().map(s -> s.bySubject().get(subjectId))
                            .filter(AnalysisService::wrote).map(GradeMath.SubjectScore::percent).toList());
            List<SectionStat> perSection = new ArrayList<>();
            for (SectionInfo section : sections) {
                List<GradeMath.SubjectScore> inSection = pupils.values().stream()
                        .filter(x -> x.section().id().equals(section.id()))
                        .map(x -> scored.byStudent().get(x.student().id()).bySubject().get(subjectId)).toList();
                List<GradeMath.SubjectScore> sectionWrote = inSection.stream().filter(AnalysisService::wrote)
                        .toList();
                int sectionPassed = (int) sectionWrote.stream().filter(s -> Boolean.TRUE.equals(s.passed()))
                        .count();
                perSection.add(new SectionStat(section.id(), section.label(), inSection.size(), sectionWrote.size(),
                        average(sectionWrote.stream().map(GradeMath.SubjectScore::percent).toList()), sectionPassed,
                        share(sectionPassed, sectionWrote.size())));
            }
            subjectStats.add(new SubjectStat(subjectId, ExamSupport.subjectName(subjects, subjectId),
                    p.max().total(), wrote.size(), absent,
                    average(wrote.stream().map(GradeMath.SubjectScore::obtained).toList()),
                    wrote.stream().map(GradeMath.SubjectScore::obtained).max(Comparator.naturalOrder()).orElse(null),
                    wrote.stream().map(GradeMath.SubjectScore::obtained).min(Comparator.naturalOrder()).orElse(null),
                    average, passed, share(passed, wrote.size()),
                    grades(bands, wrote.stream().map(s -> s.band() == null ? null : s.band().grade()).toList()),
                    previousAverage, difference(average, previousAverage), perSection));
        }

        Predicate<Scorer.ExamScore> complete = s -> s.overall().result() != OverallResult.INCOMPLETE;
        List<Scorer.ExamScore> completed = scored.byStudent().values().stream().filter(complete).toList();
        int passedOverall = (int) completed.stream().filter(s -> s.overall().result() == OverallResult.PASS).count();
        BigDecimal overallAverage = average(completed.stream().map(s -> s.overall().percent()).toList());
        BigDecimal previousOverall = before == null ? null
                : average(before.byStudent().values().stream().filter(complete).map(s -> s.overall().percent())
                        .toList());
        List<SectionStat> sectionStats = new ArrayList<>();
        for (SectionInfo section : sections) {
            List<Scorer.ExamScore> inSection = pupils.values().stream()
                    .filter(x -> x.section().id().equals(section.id()))
                    .map(x -> scored.byStudent().get(x.student().id())).toList();
            List<Scorer.ExamScore> sectionDone = inSection.stream().filter(complete).toList();
            int sectionPassed = (int) sectionDone.stream().filter(s -> s.overall().result() == OverallResult.PASS)
                    .count();
            sectionStats.add(new SectionStat(section.id(), section.label(), inSection.size(), sectionDone.size(),
                    average(sectionDone.stream().map(s -> s.overall().percent()).toList()), sectionPassed,
                    share(sectionPassed, sectionDone.size())));
        }

        List<BelowPass> below = new ArrayList<>();
        for (Pupil x : pupils.values()) {
            Scorer.ExamScore score = scored.byStudent().get(x.student().id());
            List<WeakSubject> weak = scored.papers().stream()
                    .filter(p -> Boolean.FALSE.equals(score.bySubject().get(p.getSubjectId()).passed()))
                    .map(p -> {
                        GradeMath.SubjectScore s = score.bySubject().get(p.getSubjectId());
                        return new WeakSubject(ExamSupport.subjectName(subjects, p.getSubjectId()), s.percent(),
                                s.code());
                    })
                    .toList();
            if (!weak.isEmpty() || score.overall().result() == OverallResult.NEEDS_IMPROVEMENT) {
                below.add(new BelowPass(x.student().id(), x.student().fullName(), x.student().admissionNo(),
                        x.student().rollNo(), x.section().label(), score.overall().percent(), weak));
            }
        }

        return new Analysis(exam.getId(), exam.getName(), support.term(exam.getTermId()).getName(), year.id(),
                classId, schoolClass.name(), previous == null ? null : previous.getId(),
                previous == null ? null : previous.getName(), graded.ref(), pupils.size(), completed.size(),
                overallAverage, passedOverall, share(passedOverall, completed.size()), previousOverall,
                grades(bands, completed.stream().map(s -> s.overall().band() == null ? null
                        : s.overall().band().grade()).toList()),
                subjectStats, sectionStats, below, ec.getResultsPublishedAt(),
                marks.pendingFor(ec, year).size());
    }

    /** The class's exam held before this one in the same year, if any. */
    private Exam previousExam(Exam exam, YearInfo year, UUID classId) {
        List<UUID> examIds = examClasses.findByYearAndClass(year.id(), classId).stream().map(ExamClass::getExamId)
                .toList();
        List<Exam> ordered = support.chronological(exams.findAllById(examIds));
        Exam previous = null;
        for (Exam e : ordered) {
            if (e.getId().equals(exam.getId())) {
                return previous;
            }
            previous = e;
        }
        return null;
    }

    /** Marks were entered (not absent, not left out, not missing). */
    private static boolean wrote(GradeMath.SubjectScore s) {
        return s != null && !s.missing() && s.code() == null && s.percent() != null;
    }

    static BigDecimal average(List<BigDecimal> values) {
        List<BigDecimal> present = values.stream().filter(Objects::nonNull).toList();
        if (present.isEmpty()) {
            return null;
        }
        return present.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(present.size()), GradeMath.SCALE, RoundingMode.HALF_UP);
    }

    static BigDecimal share(int count, int of) {
        return of == 0 ? null : GradeMath.percent(BigDecimal.valueOf(count), BigDecimal.valueOf(of));
    }

    private static BigDecimal difference(BigDecimal now, BigDecimal before) {
        return now == null || before == null ? null : now.subtract(before);
    }

    /** How many got each grade of the scheme, highest grade first (grades nobody got count 0). */
    private static List<GradeCount> grades(List<Band> bands, List<String> got) {
        Map<String, Long> counts = got.stream().filter(Objects::nonNull)
                .collect(Collectors.groupingBy(g -> g, Collectors.counting()));
        return bands.stream().map(b -> new GradeCount(b.grade(), counts.getOrDefault(b.grade(), 0L).intValue()))
                .toList();
    }

    // ------------------------------------------------------------------ download

    /** The analysis as a CSV file for Excel; audited as report.exported. */
    @Transactional
    public Download csv(UUID examId, UUID classId) {
        Analysis a = analysis(examId, classId);
        StringBuilder csv = new StringBuilder("﻿");
        line(csv, List.of(support.school().name()));
        line(csv, List.of("Result analysis: " + a.examName() + " (" + a.termName() + "), " + a.className()));
        line(csv, List.of("Generated " + TIME.format(ZonedDateTime.now(ExamSupport.INDIA)) + " IST"
                + CurrentUser.name().map(n -> " by " + n).orElse("")
                + (a.sheetsPending() > 0 ? "; " + a.sheetsPending() + " mark sheets not locked yet" : "")));
        line(csv, List.of());
        line(csv, List.of("Students", "Appeared", "Average %", "Passed", "Pass %", "Previous exam",
                "Previous average %"));
        line(csv, List.of(String.valueOf(a.students()), String.valueOf(a.appeared()), text(a.average()),
                String.valueOf(a.passed()), text(a.passPercent()), text(a.previousExamName()),
                text(a.previousAverage())));
        line(csv, List.of());

        List<String> head = new ArrayList<>(List.of("Subject", "Max marks", "Appeared", "Absent", "Average marks",
                "Average %", "Highest", "Lowest", "Passed", "Pass %"));
        a.grades().forEach(g -> head.add("Grade " + g.grade()));
        head.add("Previous average %");
        head.add("Change");
        line(csv, head);
        for (SubjectStat s : a.subjects()) {
            List<String> row = new ArrayList<>(List.of(s.subjectName(), text(s.max()), String.valueOf(s.appeared()),
                    String.valueOf(s.absent()), text(s.averageMarks()), text(s.average()), text(s.highest()),
                    text(s.lowest()), String.valueOf(s.passed()), text(s.passPercent())));
            s.grades().forEach(g -> row.add(String.valueOf(g.count())));
            row.add(text(s.previousAverage()));
            row.add(text(s.change()));
            line(csv, row);
        }
        line(csv, List.of());

        List<String> sectionHead = new ArrayList<>(List.of("Section", "Students", "Appeared", "Average %", "Passed",
                "Pass %"));
        a.subjects().forEach(s -> sectionHead.add(s.subjectName() + " pass %"));
        line(csv, sectionHead);
        for (int i = 0; i < a.sections().size(); i++) {
            SectionStat st = a.sections().get(i);
            List<String> row = new ArrayList<>(List.of(st.label(), String.valueOf(st.students()),
                    String.valueOf(st.appeared()), text(st.average()), String.valueOf(st.passed()),
                    text(st.passPercent())));
            for (SubjectStat s : a.subjects()) {
                row.add(text(s.sections().get(i).passPercent()));
            }
            line(csv, row);
        }
        line(csv, List.of());

        line(csv, List.of("Students below the pass mark"));
        line(csv, List.of("Section", "Roll no", "Admission no", "Name", "Overall %", "Subjects"));
        for (BelowPass b : a.belowPass()) {
            line(csv, List.of(b.sectionLabel(), b.rollNo() == null ? "" : b.rollNo().toString(),
                    text(b.admissionNo()), b.fullName(), text(b.percent()), b.subjects().stream()
                            .map(w -> w.subjectName() + " " + (w.code() != null ? w.code().name()
                                    : text(w.percent()) + "%"))
                            .collect(Collectors.joining("; "))));
        }

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("report", "exam-analysis");
        details.put("format", "csv");
        details.put("exam", a.examName());
        details.put("class", a.className());
        details.put("rows", a.subjects().size() + a.sections().size() + a.belowPass().size());
        audit.record("report.exported", "report", "exam-analysis", details);
        String fileName = ("result-analysis-" + a.examName() + "-" + a.className()).replaceAll("[^A-Za-z0-9]+", "-")
                .replaceAll("(^-|-$)", "").toLowerCase(Locale.ROOT) + ".csv";
        return new Download(fileName, csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String text(Object value) {
        if (value == null) {
            return "";
        }
        return value instanceof BigDecimal d ? d.stripTrailingZeros().toPlainString() : value.toString();
    }

    private static void line(StringBuilder csv, List<String> cells) {
        csv.append(cells.stream().map(AnalysisService::cell).collect(Collectors.joining(","))).append("\r\n");
    }

    /** RFC 4180 quoting, and a leading apostrophe so a spreadsheet never runs a cell as a formula. */
    static String cell(String value) {
        String v = value == null ? "" : value;
        if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0 && !v.matches("-\\d+(\\.\\d+)?")) {
            v = "'" + v;
        }
        boolean quote = v.chars().anyMatch(ch -> ch == '"' || ch == ',' || ch == '\n' || ch == '\r');
        return quote ? "\"" + v.replace("\"", "\"\"") + "\"" : v;
    }
}
