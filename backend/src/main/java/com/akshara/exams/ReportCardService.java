package com.akshara.exams;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.academics.AcademicsDirectory.SectionInfo;
import com.akshara.academics.AcademicsDirectory.YearInfo;
import com.akshara.academics.AcademicsService.ClassView;
import com.akshara.academics.AcademicsService.SubjectView;
import com.akshara.attendance.AttendanceCounts;
import com.akshara.attendance.AttendanceInsights;
import com.akshara.exams.ExamsViews.CardAttendance;
import com.akshara.exams.ExamsViews.CardCell;
import com.akshara.exams.ExamsViews.CardColumn;
import com.akshara.exams.ExamsViews.CardSubject;
import com.akshara.exams.ExamsViews.CoScholastic;
import com.akshara.exams.ExamsViews.FamilyExam;
import com.akshara.exams.ExamsViews.FamilyResults;
import com.akshara.exams.ExamsViews.FamilyTerm;
import com.akshara.exams.ExamsViews.Hpc;
import com.akshara.exams.ExamsViews.PaperView;
import com.akshara.exams.ExamsViews.ReportCard;
import com.akshara.exams.ExamsViews.ReportCards;
import com.akshara.exams.ExamsViews.UpcomingExam;
import com.akshara.exams.ExamsViews.YearRef;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;
import com.akshara.students.StudentRoster;
import com.akshara.students.StudentRoster.RosterStudent;
import com.akshara.students.StudentService;
import com.akshara.students.StudentService.ChildView;

/**
 * Report cards: per student for a term (the term's exams as columns, each subject's weighted percentage, grade and
 * pass) or for the year (the terms as columns, weighted by term weight), with attendance over the term or year from
 * the attendance module, co-scholastic grades, the class teacher's remark and, for foundational and preparatory
 * classes, the holistic progress card. Staff see every exam; parents and students only published results.
 */
@Service
@Transactional(readOnly = true)
public class ReportCardService {

    static final String TERM = "TERM";
    static final String YEAR = "YEAR";

    private final TermRepository terms;
    private final ExamRepository exams;
    private final ExamClassRepository examClasses;
    private final PaperRepository papers;
    private final TermRecordRepository records;
    private final HpcEntryRepository hpcEntries;
    private final ExamSupport support;
    private final GradingService grading;
    private final Scorer scorer;
    private final ResultsService results;
    private final AttendanceInsights attendance;
    private final StudentRoster roster;
    private final StudentService students;

    ReportCardService(TermRepository terms, ExamRepository exams, ExamClassRepository examClasses,
            PaperRepository papers, TermRecordRepository records, HpcEntryRepository hpcEntries, ExamSupport support,
            GradingService grading, Scorer scorer, ResultsService results, AttendanceInsights attendance,
            StudentRoster roster, StudentService students) {
        this.terms = terms;
        this.exams = exams;
        this.examClasses = examClasses;
        this.papers = papers;
        this.records = records;
        this.hpcEntries = hpcEntries;
        this.support = support;
        this.grading = grading;
        this.scorer = scorer;
        this.results = results;
        this.attendance = attendance;
        this.roster = roster;
        this.students = students;
    }

    // ------------------------------------------------------------------ staff

    /** Every student's card in a section for a term ({@code termId}) or a year ({@code yearId}); exactly one. */
    public ReportCards section(UUID sectionId, UUID termId, UUID yearId) {
        TenantContext.require();
        SectionInfo section = support.section(sectionId);
        Period period = period(termId, yearId);
        return build(section, period, false, null);
    }

    /** One student's card, for a term or the year. 404 when the student is not in a class that year. */
    public ReportCards student(UUID studentId, UUID termId, UUID yearId) {
        TenantContext.require();
        Period period = period(termId, yearId);
        roster.student(studentId, period.year().id()).orElseThrow(() -> ApiException.notFound("Student"));
        UUID sectionId = roster.sectionOf(studentId, period.year().id())
                .orElseThrow(() -> ApiException.notFound("Student"));
        return build(support.section(sectionId), period, false, studentId);
    }

    /** A term or a year; exactly one of the ids. */
    record Period(YearInfo year, Term term) {
    }

    private Period period(UUID termId, UUID yearId) {
        if ((termId == null) == (yearId == null)) {
            throw ApiException.badRequest("Choose a term or a year.", "termId");
        }
        if (termId != null) {
            Term term = support.term(termId);
            return new Period(support.year(term.getAcademicYearId()), term);
        }
        return new Period(support.year(yearId), null);
    }

    // ------------------------------------------------------------------ families

    /** A parent's child's published results and upcoming exams. Any other student is 404. */
    public FamilyResults forChild(UUID userId, UUID studentId, UUID yearId) {
        TenantContext.require();
        ChildView child = child(userId, studentId);
        return family(child, yearId);
    }

    /** The signed-in student's own published results. 404 when the sign-in is not a student's. */
    public FamilyResults mine(UUID userId, UUID yearId) {
        TenantContext.require();
        ChildView me = students.studentOf(userId).orElseThrow(() -> ApiException.notFound("Student record"));
        return family(me, yearId);
    }

    public ReportCards childCard(UUID userId, UUID studentId, UUID termId, UUID yearId) {
        TenantContext.require();
        ChildView child = child(userId, studentId);
        return familyCard(child.id(), termId, yearId);
    }

    public ReportCards myCard(UUID userId, UUID termId, UUID yearId) {
        TenantContext.require();
        ChildView me = students.studentOf(userId).orElseThrow(() -> ApiException.notFound("Student record"));
        return familyCard(me.id(), termId, yearId);
    }

    private ChildView child(UUID userId, UUID studentId) {
        if (!roster.childIdsOf(userId).contains(studentId)) {
            throw ApiException.notFound("Student");
        }
        return students.childrenOf(userId).stream().filter(c -> c.id().equals(studentId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Student"));
    }

    private ReportCards familyCard(UUID studentId, UUID termId, UUID yearId) {
        Period period = period(termId, yearId);
        UUID sectionId = roster.sectionOf(studentId, period.year().id())
                .orElseThrow(() -> ApiException.notFound("Report card"));
        return build(support.section(sectionId), period, true, studentId);
    }

    private FamilyResults family(ChildView child, UUID yearId) {
        Optional<YearInfo> year = support.yearOrCurrent(yearId);
        if (year.isEmpty()) {
            return new FamilyResults(child.id(), child.fullName(), null, null, List.of(), List.of(), List.of());
        }
        Optional<UUID> sectionId = roster.sectionOf(child.id(), year.get().id());
        if (sectionId.isEmpty()) {
            return new FamilyResults(child.id(), child.fullName(), null, YearRef.of(year.get()), List.of(), List.of(),
                    List.of());
        }
        SectionInfo section = support.section(sectionId.get());
        List<ExamClass> ecs = examClasses.findByYearAndClass(year.get().id(), section.classId());
        Map<UUID, Exam> examById = exams.findAllById(ecs.stream().map(ExamClass::getExamId).toList()).stream()
                .collect(Collectors.toMap(Exam::getId, Function.identity()));
        Map<UUID, ExamClass> ecByExam = ecs.stream().collect(Collectors.toMap(ExamClass::getExamId,
                Function.identity()));
        List<Exam> ordered = support.chronological(examById.values());
        List<Term> termList = terms.findByYear(year.get().id());
        Map<UUID, String> termNames = termList.stream().collect(Collectors.toMap(Term::getId, Term::getName));
        GradingService.Graded graded = grading.graded(section.classId(), section.className(), year.get().id());
        Map<UUID, SubjectView> subjects = support.subjects();
        List<FamilyExam> published = new ArrayList<>();
        Set<UUID> termsWithResults = new LinkedHashSet<>();
        for (Exam e : ordered) {
            ExamClass ec = ecByExam.get(e.getId());
            if (!ec.resultsPublished()) {
                continue;
            }
            termsWithResults.add(e.getTermId());
            GradeMath.Overall o = scorer.score(ec, List.of(child.id()), graded.scale(), subjects).byStudent()
                    .get(child.id()).overall();
            published.add(new FamilyExam(e.getId(), e.getName(), e.getTermId(), termNames.get(e.getTermId()),
                    ec.getResultsPublishedAt(), o.percent(), o.band() == null ? null : o.band().grade(),
                    o.result()));
        }
        LocalDate today = ExamSupport.today();
        List<UpcomingExam> upcoming = new ArrayList<>();
        for (Exam e : ordered) {
            ExamClass ec = ecByExam.get(e.getId());
            if (!ec.dateSheetPublished()) {
                continue;
            }
            List<PaperView> list = support.orderedPapers(papers.findByExamClassId(ec.getId()), subjects).stream()
                    .filter(p -> p.getPaperDate() != null && !p.getPaperDate().isBefore(today))
                    .map(p -> support.paperView(p, subjects, false)).toList();
            if (!list.isEmpty()) {
                upcoming.add(new UpcomingExam(e.getId(), e.getName(), termNames.get(e.getTermId()), list));
            }
        }
        List<FamilyTerm> termViews = termList.stream()
                .map(t -> new FamilyTerm(t.getId(), t.getName(), termsWithResults.contains(t.getId()))).toList();
        return new FamilyResults(child.id(), child.fullName(), section.label(), YearRef.of(year.get()), termViews,
                published, upcoming);
    }

    // ------------------------------------------------------------------ building cards

    /** One exam's part in a card: the exam, its class part and every student's scores. */
    private record Included(Exam exam, ExamClass examClass, Scorer.Scored scored) {
    }

    /** A student's figures for a term: per subject the weighted percentage, and overall. */
    private record TermFigures(Map<UUID, BigDecimal> subjectPercents, BigDecimal overall, boolean incomplete) {
    }

    ReportCards build(SectionInfo section, Period period, boolean publishedOnly, UUID onlyStudent) {
        YearInfo year = period.year();
        ClassView schoolClass = support.classView(section.classId());
        GradingService.Graded graded = grading.graded(section.classId(), section.className(), year.id());
        GradeMath.Scale scale = graded.scale();
        Map<UUID, SubjectView> subjects = support.subjects();
        List<Term> termList = period.term() != null ? List.of(period.term()) : terms.findByYear(year.id());
        Set<UUID> termIds = termList.stream().map(Term::getId).collect(Collectors.toSet());
        List<ExamClass> ecs = examClasses.findByYearAndClass(year.id(), section.classId()).stream()
                .filter(ec -> !publishedOnly || ec.resultsPublished()).toList();
        Map<UUID, ExamClass> ecByExam = ecs.stream().collect(Collectors.toMap(ExamClass::getExamId,
                Function.identity()));
        List<Exam> examList = support.chronological(exams.findAllById(ecByExam.keySet())).stream()
                .filter(e -> termIds.contains(e.getTermId())).toList();
        List<ExamClass> includedEcs = examList.stream().map(e -> ecByExam.get(e.getId())).toList();
        List<RosterStudent> studentList = results.studentsOf(year, section, includedEcs).stream()
                .filter(s -> onlyStudent == null || s.id().equals(onlyStudent)).toList();
        List<RosterStudent> rankPool = onlyStudent == null ? studentList : results.studentsOf(year, section,
                includedEcs);
        List<UUID> poolIds = rankPool.stream().map(RosterStudent::id).toList();
        List<Included> included = examList.stream()
                .map(e -> new Included(e, ecByExam.get(e.getId()),
                        scorer.score(ecByExam.get(e.getId()), poolIds, scale, subjects)))
                .toList();
        List<UUID> subjectOrder = subjectOrder(schoolClass, included, subjects);

        // Per student and term: weighted subject percentages and overall.
        Map<UUID, Map<UUID, TermFigures>> figures = new HashMap<>();
        for (UUID studentId : poolIds) {
            Map<UUID, TermFigures> byTerm = new LinkedHashMap<>();
            for (Term t : termList) {
                List<Included> termExams = included.stream().filter(i -> i.exam().getTermId().equals(t.getId()))
                        .toList();
                if (!termExams.isEmpty()) {
                    byTerm.put(t.getId(), termFigures(studentId, termExams, subjectOrder));
                }
            }
            figures.put(studentId, byTerm);
        }
        boolean yearCard = period.term() == null;
        List<Term> termsWithExams = termList.stream()
                .filter(t -> included.stream().anyMatch(i -> i.exam().getTermId().equals(t.getId()))).toList();
        List<CardColumn> columns = yearCard
                ? termsWithExams.stream().map(t -> new CardColumn(t.getId(), t.getName(), t.getWeight())).toList()
                : included.stream().map(i -> new CardColumn(i.exam().getId(), i.exam().getName(),
                        i.exam().getWeight())).toList();

        // Totals per student (term or year), for ranks and the cards.
        Map<UUID, BigDecimal> overallOf = new HashMap<>();
        Map<UUID, Boolean> incompleteOf = new HashMap<>();
        for (UUID studentId : poolIds) {
            Map<UUID, TermFigures> byTerm = figures.get(studentId);
            if (yearCard) {
                overallOf.put(studentId, GradeMath.weighted(termsWithExams.stream()
                        .map(t -> new GradeMath.Weighted(byTerm.get(t.getId()).overall(), t.getWeight())).toList()));
                incompleteOf.put(studentId, byTerm.values().stream().anyMatch(TermFigures::incomplete));
            } else {
                TermFigures f = byTerm.get(period.term().getId());
                overallOf.put(studentId, f == null ? null : f.overall());
                incompleteOf.put(studentId, f == null || f.incomplete());
            }
        }
        boolean ranksEnabled = grading.ranksEnabled();
        Map<UUID, Integer> ranks = ranksEnabled ? GradeMath.ranks(overallOf.entrySet().stream()
                .filter(e -> !incompleteOf.get(e.getKey()))
                .collect(HashMap::new, (m, e) -> m.put(e.getKey(), e.getValue()), Map::putAll)) : Map.of();

        // Attendance over the term or the year, up to today.
        LocalDate from = yearCard ? year.startsOn() : period.term().getStartsOn();
        LocalDate end = yearCard ? year.endsOn() : period.term().getEndsOn();
        LocalDate today = ExamSupport.today();
        LocalDate to = end.isAfter(today) ? today : end;
        List<UUID> ids = studentList.stream().map(RosterStudent::id).toList();
        Map<UUID, AttendanceCounts> counts = attendance.countsPerStudent(ids, from, to);

        // Co-scholastic grades, remark and progress card: the term's, or the latest term's for a year card.
        Map<UUID, TermRecord> recordOf = new HashMap<>();
        Map<UUID, HpcEntry> hpcOf = new HashMap<>();
        List<Term> recordTerms = (yearCard ? termList : List.of(period.term())).stream()
                .filter(t -> !publishedOnly || termsWithExams.contains(t))
                .sorted(Comparator.comparing(Term::getStartsOn)).toList();
        for (Term t : recordTerms) {
            if (ids.isEmpty()) {
                break;
            }
            records.findByTermIdAndStudentIdIn(t.getId(), ids).forEach(r -> recordOf.put(r.getStudentId(), r));
            hpcEntries.findByTermIdAndStudentIdIn(t.getId(), ids).forEach(h -> hpcOf.put(h.getStudentId(), h));
        }
        boolean hpcEnabled = grading.hpcFor(section.className());

        List<ReportCard> cards = new ArrayList<>();
        for (RosterStudent s : studentList) {
            Map<UUID, TermFigures> byTerm = figures.get(s.id());
            List<CardSubject> subjectRows = new ArrayList<>();
            for (UUID subjectId : subjectOrder) {
                List<CardCell> cells = new ArrayList<>();
                BigDecimal percent;
                if (yearCard) {
                    for (Term t : termsWithExams) {
                        BigDecimal p = byTerm.get(t.getId()).subjectPercents().get(subjectId);
                        GradeMath.Band b = p == null ? null : scale.bandFor(p);
                        cells.add(new CardCell(t.getId(), null, null, p, b == null ? null : b.grade(), null));
                    }
                    percent = GradeMath.weighted(termsWithExams.stream().map(t -> new GradeMath.Weighted(
                            byTerm.get(t.getId()).subjectPercents().get(subjectId), t.getWeight())).toList());
                } else {
                    for (Included i : included) {
                        GradeMath.SubjectScore sc = i.scored().byStudent().get(s.id()).bySubject().get(subjectId);
                        cells.add(sc == null ? new CardCell(i.exam().getId(), null, null, null, null, null)
                                : new CardCell(i.exam().getId(), sc.obtained(), sc.max(), sc.percent(),
                                        sc.band() == null ? null : sc.band().grade(), sc.code()));
                    }
                    TermFigures f = byTerm.get(period.term().getId());
                    percent = f == null ? null : f.subjectPercents().get(subjectId);
                }
                GradeMath.Band band = percent == null ? null : scale.bandFor(percent);
                subjectRows.add(new CardSubject(subjectId, ExamSupport.subjectName(subjects, subjectId), cells,
                        percent, band == null ? null : band.grade(), band == null ? null : band.gradePoint(),
                        percent == null ? null : scale.passes(percent)));
            }
            BigDecimal overall = overallOf.get(s.id());
            GradeMath.Band band = overall == null ? null : scale.bandFor(overall);
            OverallResult result = Boolean.TRUE.equals(incompleteOf.get(s.id())) || columns.isEmpty()
                    ? OverallResult.INCOMPLETE
                    : GradeMath.result(subjectRows.stream().map(CardSubject::percent).toList(), overall, scale);
            AttendanceCounts c = counts.getOrDefault(s.id(), AttendanceCounts.NONE);
            CardAttendance att = new CardAttendance(from, to, c.total(), c.present(), c.absent(), c.late(),
                    c.halfDay(), c.leave(), c.presentPercent());
            TermRecord r = recordOf.get(s.id());
            HpcEntry h = hpcOf.get(s.id());
            cards.add(new ReportCard(s.id(), s.fullName(), s.admissionNo(), s.rollNo(), section.label(), subjectRows,
                    overall, band == null ? null : band.grade(), gpa(subjectRows, scale), result, ranks.get(s.id()),
                    att, r == null ? new CoScholastic(null, null, null, null)
                            : new CoScholastic(r.getWorkEducation(), r.getArtEducation(), r.getHealthPhysical(),
                                    r.getDiscipline()),
                    r == null ? null : r.getRemark(), hpcEnabled ? (h == null ? Hpc.EMPTY : h.view()) : null));
        }
        return new ReportCards(support.school(), yearCard ? YEAR : TERM, year.id(), year.name(),
                period.term() == null ? null : period.term().getId(),
                period.term() == null ? null : period.term().getName(), section.classId(), section.className(),
                section.id(), section.label(), section.classTeacherName(), graded.ref(), ranksEnabled, hpcEnabled,
                publishedOnly, columns, cards);
    }

    /** The class's subjects in their setup order, then any other subject that has a paper. */
    private static List<UUID> subjectOrder(ClassView schoolClass, List<Included> included,
            Map<UUID, SubjectView> subjects) {
        Set<UUID> withPapers = included.stream().flatMap(i -> i.scored().papers().stream())
                .map(Paper::getSubjectId).collect(Collectors.toCollection(LinkedHashSet::new));
        List<UUID> order = new ArrayList<>();
        schoolClass.subjects().stream().map(s -> s.id()).filter(withPapers::contains).forEach(order::add);
        withPapers.stream().filter(id -> !order.contains(id))
                .sorted(Comparator.comparing(id -> ExamSupport.subjectName(subjects, id))).forEach(order::add);
        return order;
    }

    /**
     * A student's term: each subject's percentage weighted by exam weight over the exams that have one, and the
     * overall percentage weighted the same way over the exams' overall percentages. Incomplete when any exam's marks
     * are not all in.
     */
    private static TermFigures termFigures(UUID studentId, List<Included> termExams, List<UUID> subjectOrder) {
        Map<UUID, BigDecimal> subjectPercents = new LinkedHashMap<>();
        for (UUID subjectId : subjectOrder) {
            List<GradeMath.Weighted> items = new ArrayList<>();
            for (Included i : termExams) {
                GradeMath.SubjectScore sc = i.scored().byStudent().get(studentId).bySubject().get(subjectId);
                items.add(new GradeMath.Weighted(sc == null ? null : sc.percent(), i.exam().getWeight()));
            }
            BigDecimal p = GradeMath.weighted(items);
            if (p != null) {
                subjectPercents.put(subjectId, p);
            }
        }
        BigDecimal overall = GradeMath.weighted(termExams.stream()
                .map(i -> new GradeMath.Weighted(i.scored().byStudent().get(studentId).overall().percent(),
                        i.exam().getWeight()))
                .toList());
        boolean incomplete = termExams.stream()
                .anyMatch(i -> i.scored().byStudent().get(studentId).overall().result() == OverallResult.INCOMPLETE);
        return new TermFigures(subjectPercents, overall, incomplete);
    }

    /** The average grade point of the subjects with a grade, one decimal; null for schemes without grade points. */
    private static BigDecimal gpa(List<CardSubject> rows, GradeMath.Scale scale) {
        if (!scale.usesGradePoints()) {
            return null;
        }
        List<CardSubject> graded = rows.stream().filter(r -> r.percent() != null).toList();
        if (graded.isEmpty()) {
            return null;
        }
        BigDecimal points = graded.stream().map(r -> r.gradePoint() == null ? BigDecimal.ZERO : r.gradePoint())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return points.divide(BigDecimal.valueOf(graded.size()), 1, RoundingMode.HALF_UP);
    }
}
