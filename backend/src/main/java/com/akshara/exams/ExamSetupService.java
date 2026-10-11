package com.akshara.exams;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.academics.AcademicsDirectory.SectionInfo;
import com.akshara.academics.AcademicsDirectory.YearInfo;
import com.akshara.academics.AcademicsService.ClassView;
import com.akshara.academics.AcademicsService.SubjectView;
import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.communication.CalendarService;
import com.akshara.communication.CalendarService.EntryView;
import com.akshara.communication.CommunicationTypes.CalendarAudience;
import com.akshara.communication.CommunicationTypes.EntryKind;
import com.akshara.communication.NoticeCaller;
import com.akshara.communication.NoticeForms.EntryRequest;
import com.akshara.communication.SchoolCalendar;
import com.akshara.exams.ExamsForms.ExamForm;
import com.akshara.exams.ExamsForms.PaperForm;
import com.akshara.exams.ExamsForms.PapersForm;
import com.akshara.exams.ExamsForms.TermForm;
import com.akshara.exams.ExamsForms.WeightForm;
import com.akshara.exams.ExamsForms.WeightsForm;
import com.akshara.exams.ExamsViews.ClassRef;
import com.akshara.exams.ExamsViews.Clash;
import com.akshara.exams.ExamsViews.ExamClassDetail;
import com.akshara.exams.ExamsViews.ExamClassSummary;
import com.akshara.exams.ExamsViews.ExamSummary;
import com.akshara.exams.ExamsViews.HallTicketStudent;
import com.akshara.exams.ExamsViews.HallTickets;
import com.akshara.exams.ExamsViews.PaperView;
import com.akshara.exams.ExamsViews.Structure;
import com.akshara.exams.ExamsViews.SubjectRef;
import com.akshara.exams.ExamsViews.TermView;
import com.akshara.exams.ExamsViews.YearRef;
import com.akshara.shared.ApiException;
import com.akshara.shared.Permissions;
import com.akshara.shared.TenantContext;
import com.akshara.students.StudentRoster;
import com.akshara.students.StudentRoster.RosterStudent;

/**
 * The exam structure of an academic year: terms and exams with their weightage, the classes each exam covers, and
 * each class's papers (maximum marks and date sheet). Publishing a date sheet shows it to families and adds its
 * papers to the school calendar as EXAM entries for the class, through the communication module's CalendarService;
 * changes to a published date sheet update those entries. Hall tickets are built from the date sheet.
 */
@Service
@Transactional
public class ExamSetupService {

    static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final TermRepository terms;
    private final ExamRepository exams;
    private final ExamClassRepository examClasses;
    private final PaperRepository papers;
    private final MarkSheetRepository sheets;
    private final MarkRepository marks;
    private final TermRecordRepository records;
    private final HpcEntryRepository hpc;
    private final ExamSupport support;
    private final SchoolCalendar holidays;
    private final CalendarService calendar;
    private final StudentRoster roster;
    private final AuditService audit;

    ExamSetupService(TermRepository terms, ExamRepository exams, ExamClassRepository examClasses,
            PaperRepository papers, MarkSheetRepository sheets, MarkRepository marks, TermRecordRepository records,
            HpcEntryRepository hpc, ExamSupport support, SchoolCalendar holidays, CalendarService calendar,
            StudentRoster roster, AuditService audit) {
        this.terms = terms;
        this.exams = exams;
        this.examClasses = examClasses;
        this.papers = papers;
        this.sheets = sheets;
        this.marks = marks;
        this.records = records;
        this.hpc = hpc;
        this.support = support;
        this.holidays = holidays;
        this.calendar = calendar;
        this.roster = roster;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ the structure

    @Transactional(readOnly = true)
    public Structure structure(UUID yearId) {
        TenantContext.require();
        List<YearRef> years = support.years().stream().map(YearRef::of).toList();
        Map<UUID, ClassView> classes = support.classes();
        List<ClassRef> classRefs = classes.values().stream().map(c -> new ClassRef(c.id(), c.name())).toList();
        boolean canSetup = ExamsAccess.can(Permissions.EXAMS_SETUP);
        boolean canPublish = ExamsAccess.can(Permissions.EXAMS_PUBLISH);
        Optional<YearInfo> year = support.yearOrCurrent(yearId);
        if (year.isEmpty()) {
            return new Structure(null, years, List.of(), BigDecimal.ZERO, classRefs, canSetup, canPublish);
        }
        List<Term> termList = terms.findByYear(year.get().id());
        List<Exam> examList = exams.findByYear(year.get().id());
        List<ExamClass> ecs = examList.isEmpty() ? List.of()
                : examClasses.findByExamIdIn(examList.stream().map(Exam::getId).toList());
        Map<UUID, List<Paper>> papersByEc = ecs.isEmpty() ? Map.of()
                : papers.findByExamClassIdIn(ecs.stream().map(ExamClass::getId).toList()).stream()
                        .collect(Collectors.groupingBy(Paper::getExamClassId));
        Map<UUID, List<ExamClass>> ecsByExam = ecs.stream().collect(Collectors.groupingBy(ExamClass::getExamId));
        List<Exam> ordered = support.chronological(examList);
        List<TermView> views = new ArrayList<>();
        for (Term t : termList) {
            List<ExamSummary> termExams = ordered.stream().filter(e -> e.getTermId().equals(t.getId()))
                    .map(e -> examSummary(e, ecsByExam.getOrDefault(e.getId(), List.of()), papersByEc, classes))
                    .toList();
            BigDecimal total = termExams.stream().map(ExamSummary::weight).reduce(BigDecimal.ZERO, BigDecimal::add);
            views.add(new TermView(t.getId(), t.getName(), t.getStartsOn(), t.getEndsOn(), t.getWeight(), total,
                    termExams));
        }
        BigDecimal termTotal = termList.stream().map(Term::getWeight).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Structure(YearRef.of(year.get()), years, views, termTotal, classRefs, canSetup, canPublish);
    }

    private ExamSummary examSummary(Exam e, List<ExamClass> ecs, Map<UUID, List<Paper>> papersByEc,
            Map<UUID, ClassView> classes) {
        LocalDate first = null;
        LocalDate last = null;
        List<ExamClassSummary> classViews = new ArrayList<>();
        List<ExamClass> sorted = ecs.stream().sorted((a, b) -> Integer.compare(classOrder(classes, a.getClassId()),
                classOrder(classes, b.getClassId()))).toList();
        for (ExamClass ec : sorted) {
            List<Paper> list = papersByEc.getOrDefault(ec.getId(), List.of());
            int scheduled = 0;
            for (Paper p : list) {
                if (p.getPaperDate() != null) {
                    scheduled++;
                    first = first == null || p.getPaperDate().isBefore(first) ? p.getPaperDate() : first;
                    last = last == null || p.getPaperDate().isAfter(last) ? p.getPaperDate() : last;
                }
            }
            ClassView c = classes.get(ec.getClassId());
            classViews.add(new ExamClassSummary(ec.getId(), ec.getClassId(), c == null ? "" : c.name(), list.size(),
                    scheduled, ec.getDateSheetPublishedAt(), ec.getResultsPublishedAt()));
        }
        return new ExamSummary(e.getId(), e.getName(), e.getWeight(), e.getInstructions(), first, last, classViews);
    }

    private static int classOrder(Map<UUID, ClassView> classes, UUID classId) {
        ClassView c = classes.get(classId);
        return c == null ? Integer.MAX_VALUE : c.displayOrder();
    }

    // ------------------------------------------------------------------ terms

    public Structure createTerm(TermForm form, Actor actor) {
        TenantContext.require();
        if (form.academicYearId() == null) {
            throw ApiException.badRequest("Choose the academic year.", "academicYearId");
        }
        YearInfo year = support.years().stream().filter(y -> y.id().equals(form.academicYearId())).findFirst()
                .orElseThrow(() -> ApiException.badRequest("Pick an academic year from the list.", "academicYearId"));
        String name = form.name().trim();
        checkTerm(null, year, name, form.startsOn(), form.endsOn());
        Term term = terms.saveAndFlush(new Term(year.id(), name, form.startsOn(), form.endsOn()));
        audit.record(actor, "exam_term.created", "exam_term", term.getId(), termDetails(term, year));
        return structure(year.id());
    }

    public Structure updateTerm(UUID id, TermForm form, Actor actor) {
        TenantContext.require();
        Term term = support.term(id);
        YearInfo year = support.year(term.getAcademicYearId());
        String name = form.name().trim();
        checkTerm(id, year, name, form.startsOn(), form.endsOn());
        term.update(name, form.startsOn(), form.endsOn());
        terms.saveAndFlush(term);
        audit.record(actor, "exam_term.updated", "exam_term", id, termDetails(term, year));
        return structure(year.id());
    }

    public Structure deleteTerm(UUID id, Actor actor) {
        TenantContext.require();
        Term term = support.term(id);
        if (exams.countByTermId(id) > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "In use", term.getName()
                    + " has exams. Delete them first.");
        }
        if (records.countByTermId(id) > 0 || hpc.countByTermId(id) > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "In use", term.getName()
                    + " has co-scholastic grades, remarks or progress cards, so it cannot be deleted.");
        }
        terms.delete(term);
        terms.flush();
        audit.record(actor, "exam_term.deleted", "exam_term", id, Map.of("name", term.getName()));
        return structure(term.getAcademicYearId());
    }

    private void checkTerm(UUID id, YearInfo year, String name, LocalDate startsOn, LocalDate endsOn) {
        if (endsOn.isBefore(startsOn)) {
            throw ApiException.badRequest("The end date must not be before the start date.", "endsOn");
        }
        if (startsOn.isBefore(year.startsOn()) || endsOn.isAfter(year.endsOn())) {
            throw ApiException.badRequest("A term must fall inside " + year.name() + " (" + year.startsOn()
                    + " to " + year.endsOn() + ").", "startsOn");
        }
        UUID except = id == null ? new UUID(0, 0) : id;
        if (terms.nameTaken(year.id(), name, except)) {
            throw ApiException.conflict("There is already a term called " + name + " in " + year.name() + ".",
                    "name");
        }
        terms.findByYear(year.id()).stream()
                .filter(t -> !t.getId().equals(id) && !t.getEndsOn().isBefore(startsOn)
                        && !t.getStartsOn().isAfter(endsOn))
                .findFirst()
                .ifPresent(t -> {
                    throw ApiException.conflict("These dates overlap " + t.getName() + " (" + t.getStartsOn()
                            + " to " + t.getEndsOn() + ").", "startsOn");
                });
    }

    private static Map<String, Object> termDetails(Term t, YearInfo year) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("name", t.getName());
        details.put("year", year.name());
        details.put("startsOn", t.getStartsOn().toString());
        details.put("endsOn", t.getEndsOn().toString());
        return details;
    }

    /** Sets the weight of every term of the year; they must add up to 100. */
    public Structure setTermWeights(UUID yearId, WeightsForm form, Actor actor) {
        TenantContext.require();
        YearInfo year = support.year(yearId);
        List<Term> list = terms.findByYear(yearId);
        Map<UUID, BigDecimal> weights = checkWeights(form, list.stream().map(Term::getId).toList(), "term");
        list.forEach(t -> t.weigh(weights.get(t.getId())));
        terms.saveAllAndFlush(list);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("year", year.name());
        list.forEach(t -> details.put(t.getName(), t.getWeight().toPlainString()));
        audit.record(actor, "exam_term.weights_updated", "academic_year", yearId, details);
        return structure(yearId);
    }

    /** Sets the weight of every exam of the term; they must add up to 100. */
    public Structure setExamWeights(UUID termId, WeightsForm form, Actor actor) {
        TenantContext.require();
        Term term = support.term(termId);
        List<Exam> list = exams.findByTerm(termId);
        Map<UUID, BigDecimal> weights = checkWeights(form, list.stream().map(Exam::getId).toList(), "exam");
        list.forEach(e -> e.weigh(weights.get(e.getId())));
        exams.saveAllAndFlush(list);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("term", term.getName());
        list.forEach(e -> details.put(e.getName(), e.getWeight().toPlainString()));
        audit.record(actor, "exam.weights_updated", "exam_term", termId, details);
        return structure(term.getAcademicYearId());
    }

    private static Map<UUID, BigDecimal> checkWeights(WeightsForm form, List<UUID> ids, String what) {
        if (ids.isEmpty()) {
            throw ApiException.badRequest("Add a " + what + " first.", "weights");
        }
        Map<UUID, BigDecimal> weights = new HashMap<>();
        for (WeightForm w : form.weights()) {
            if (weights.put(w.id(), w.weight()) != null) {
                throw ApiException.badRequest("Each " + what + " can have only one weight.", "weights");
            }
        }
        if (!weights.keySet().equals(new HashSet<>(ids))) {
            throw ApiException.badRequest("Give a weight for every " + what + " in the list, and only those.",
                    "weights");
        }
        if (!GradeMath.totals100(weights.values())) {
            BigDecimal total = weights.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            throw ApiException.badRequest("The weights add up to " + total.stripTrailingZeros().toPlainString()
                    + "%. They must total 100%.", "weights");
        }
        return weights;
    }

    // ------------------------------------------------------------------ exams

    public Structure createExam(ExamForm form, Actor actor) {
        TenantContext.require();
        if (form.termId() == null) {
            throw ApiException.badRequest("Choose the term.", "termId");
        }
        Term term = terms.findById(form.termId())
                .orElseThrow(() -> ApiException.badRequest("Pick a term from the list.", "termId"));
        String name = form.name().trim();
        if (exams.nameTaken(term.getId(), name, new UUID(0, 0))) {
            throw ApiException.conflict("There is already an exam called " + name + " in " + term.getName() + ".",
                    "name");
        }
        Map<UUID, ClassView> classes = support.classes();
        Set<UUID> classIds = checkClasses(form.classIds(), classes);
        Exam exam = exams.saveAndFlush(new Exam(term.getAcademicYearId(), term.getId(), name,
                GradingService.clean(form.instructions())));
        classIds.forEach(c -> examClasses.save(new ExamClass(exam.getId(), c)));
        examClasses.flush();
        audit.record(actor, "exam.created", "exam", exam.getId(), examDetails(exam, term, classIds, classes));
        return structure(term.getAcademicYearId());
    }

    /** Changes the name, instructions and classes. A class with marks entered cannot be taken off the exam. */
    public Structure updateExam(UUID id, ExamForm form, Actor actor) {
        TenantContext.require();
        Exam exam = support.exam(id);
        Term term = support.term(exam.getTermId());
        String name = form.name().trim();
        if (exams.nameTaken(term.getId(), name, id)) {
            throw ApiException.conflict("There is already an exam called " + name + " in " + term.getName() + ".",
                    "name");
        }
        Map<UUID, ClassView> classes = support.classes();
        Set<UUID> classIds = checkClasses(form.classIds(), classes);
        Map<UUID, ExamClass> existing = examClasses.findByExamId(id).stream()
                .collect(Collectors.toMap(ExamClass::getClassId, Function.identity()));
        for (ExamClass ec : existing.values()) {
            if (!classIds.contains(ec.getClassId())) {
                List<Paper> list = papers.findByExamClassId(ec.getId());
                if (!list.isEmpty() && sheets.countByPaperIdIn(list.stream().map(Paper::getId).toList()) > 0) {
                    String className = classes.containsKey(ec.getClassId())
                            ? classes.get(ec.getClassId()).name() : "a class";
                    throw new ApiException(HttpStatus.CONFLICT, "In use", "Marks have been entered for "
                            + className + ", so it cannot be taken off " + exam.getName() + ".",
                            Map.of("classIds", "Marks have been entered for " + className + "."));
                }
                removeCalendarEntries(list, actor, support.year(exam.getAcademicYearId()));
                examClasses.delete(ec);
            }
        }
        examClasses.flush();
        classIds.stream().filter(c -> !existing.containsKey(c)).forEach(c -> examClasses.save(new ExamClass(id, c)));
        examClasses.flush();
        exam.update(name, GradingService.clean(form.instructions()));
        exams.saveAndFlush(exam);
        audit.record(actor, "exam.updated", "exam", id, examDetails(exam, term, classIds, classes));
        return structure(term.getAcademicYearId());
    }

    public Structure deleteExam(UUID id, Actor actor) {
        TenantContext.require();
        Exam exam = support.exam(id);
        List<ExamClass> ecs = examClasses.findByExamId(id);
        List<Paper> list = ecs.isEmpty() ? List.of()
                : papers.findByExamClassIdIn(ecs.stream().map(ExamClass::getId).toList());
        if (!list.isEmpty() && sheets.countByPaperIdIn(list.stream().map(Paper::getId).toList()) > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "In use", "Marks have been entered for " + exam.getName()
                    + ", so it cannot be deleted.");
        }
        removeCalendarEntries(list, actor, support.year(exam.getAcademicYearId()));
        exams.delete(exam);
        exams.flush();
        audit.record(actor, "exam.deleted", "exam", id, Map.of("name", exam.getName()));
        return structure(exam.getAcademicYearId());
    }

    private static Set<UUID> checkClasses(List<UUID> classIds, Map<UUID, ClassView> classes) {
        Set<UUID> ids = new LinkedHashSet<>(classIds);
        if (!classes.keySet().containsAll(ids)) {
            throw ApiException.badRequest("Pick classes from this school's list.", "classIds");
        }
        return ids;
    }

    private static Map<String, Object> examDetails(Exam e, Term t, Collection<UUID> classIds,
            Map<UUID, ClassView> classes) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("name", e.getName());
        details.put("term", t.getName());
        details.put("classes", classIds.stream().map(c -> classes.containsKey(c) ? classes.get(c).name() : "?")
                .toList());
        return details;
    }

    // ------------------------------------------------------------------ papers and the date sheet

    @Transactional(readOnly = true)
    public ExamClassDetail examClass(UUID examId, UUID classId) {
        TenantContext.require();
        ExamClass ec = support.examClass(examId, classId);
        return detail(support.exam(examId), ec);
    }

    /**
     * Replaces the papers of an exam for a class. A paper with marks cannot be removed or have a component's maximum
     * set below a mark already entered; once results are published the maximum marks do not change. Changing a
     * published date sheet updates its calendar entries.
     */
    public ExamClassDetail savePapers(UUID examId, UUID classId, PapersForm form, Actor actor) {
        TenantContext.require();
        Exam exam = support.exam(examId);
        ExamClass ec = support.examClass(examId, classId);
        YearInfo year = support.year(exam.getAcademicYearId());
        ClassView schoolClass = support.classView(classId);
        Map<UUID, SubjectView> subjects = support.subjects();
        Set<UUID> classSubjects = schoolClass.subjects().stream().map(s -> s.id()).collect(Collectors.toSet());
        Map<UUID, PaperForm> wanted = new LinkedHashMap<>();
        for (PaperForm p : form.papers()) {
            String subject = ExamSupport.subjectName(subjects, p.subjectId());
            if (!classSubjects.contains(p.subjectId())) {
                throw ApiException.badRequest("Pick subjects that " + schoolClass.name()
                        + " studies (School setup lists them).", "papers");
            }
            if (wanted.put(p.subjectId(), p) != null) {
                throw ApiException.badRequest(subject + " is listed twice.", "papers");
            }
            BigDecimal total = ExamSupport.zeroIfNull(p.maxTheory()).add(ExamSupport.zeroIfNull(p.maxPractical()))
                    .add(ExamSupport.zeroIfNull(p.maxInternal()));
            if (total.signum() <= 0 || total.compareTo(BigDecimal.valueOf(1000)) > 0) {
                throw ApiException.badRequest("Give " + subject + " maximum marks between 1 and 1,000.", "papers");
            }
            boolean anySlot = p.date() != null || p.startTime() != null || p.durationMinutes() != null;
            boolean fullSlot = p.date() != null && p.startTime() != null && p.durationMinutes() != null;
            if (anySlot && !fullSlot) {
                throw ApiException.badRequest("Give " + subject + " a date, start time and duration together, "
                        + "or leave all three empty.", "papers");
            }
            if (p.date() != null && (p.date().isBefore(year.startsOn()) || p.date().isAfter(year.endsOn()))) {
                throw ApiException.badRequest("Schedule " + subject + " inside " + year.name() + " ("
                        + year.startsOn() + " to " + year.endsOn() + ").", "papers");
            }
        }
        Map<UUID, Paper> existing = papers.findByExamClassId(ec.getId()).stream()
                .collect(Collectors.toMap(Paper::getSubjectId, Function.identity()));
        Set<UUID> withSheets = existing.isEmpty() ? Set.of()
                : sheets.findByPaperIdIn(existing.values().stream().map(Paper::getId).toList()).stream()
                        .map(MarkSheet::getPaperId).collect(Collectors.toSet());
        List<Paper> removed = new ArrayList<>();
        for (Paper p : existing.values()) {
            if (!wanted.containsKey(p.getSubjectId())) {
                if (withSheets.contains(p.getId())) {
                    String detail = "Marks have been entered for " + ExamSupport.subjectName(subjects,
                            p.getSubjectId()) + ", so its paper cannot be removed.";
                    throw new ApiException(HttpStatus.CONFLICT, "In use", detail, Map.of("papers", detail));
                }
                removed.add(p);
            }
        }
        int changed = removed.size();
        for (PaperForm f : wanted.values()) {
            Paper p = existing.get(f.subjectId());
            BigDecimal theory = ExamSupport.zeroIfNull(f.maxTheory());
            BigDecimal practical = ExamSupport.zeroIfNull(f.maxPractical());
            BigDecimal internal = ExamSupport.zeroIfNull(f.maxInternal());
            if (p == null) {
                p = new Paper(ec.getId(), f.subjectId());
                changed++;
            } else {
                boolean maxChanged = p.getMaxTheory().compareTo(theory) != 0
                        || p.getMaxPractical().compareTo(practical) != 0 || p.getMaxInternal().compareTo(internal) != 0;
                if (maxChanged && withSheets.contains(p.getId())) {
                    checkMaxAgainstMarks(p, theory, practical, internal, ec, subjects);
                }
                if (maxChanged || !Objects.equals(p.getPaperDate(), f.date())
                        || !Objects.equals(p.getStartTime(), f.startTime())
                        || !Objects.equals(p.getDurationMinutes(), f.durationMinutes())
                        || !Objects.equals(p.getRoom(), GradingService.clean(f.room()))) {
                    changed++;
                }
            }
            p.update(theory, practical, internal, f.date(), f.startTime(), f.durationMinutes(),
                    GradingService.clean(f.room()));
            papers.save(p);
        }
        removeCalendarEntries(removed, actor, year);
        papers.deleteAll(removed);
        papers.flush();
        if (changed > 0) {
            ec.papersChanged();
            examClasses.saveAndFlush(ec);
            if (ec.dateSheetPublished()) {
                syncCalendar(exam, ec, schoolClass, actor, year);
            }
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("exam", exam.getName());
            details.put("class", schoolClass.name());
            details.put("papers", wanted.size());
            details.put("published", ec.dateSheetPublished());
            audit.record(actor, "date_sheet.updated", "date_sheet", ec.getId(), details);
        }
        return detail(exam, ec);
    }

    private void checkMaxAgainstMarks(Paper p, BigDecimal theory, BigDecimal practical, BigDecimal internal,
            ExamClass ec, Map<UUID, SubjectView> subjects) {
        String subject = ExamSupport.subjectName(subjects, p.getSubjectId());
        if (ec.resultsPublished()) {
            String detail = "Results are published, so the maximum marks of " + subject
                    + " cannot change. Withdraw the results first.";
            throw new ApiException(HttpStatus.CONFLICT, "Results published", detail, Map.of("papers", detail));
        }
        List<Object[]> rows = marks.highestOnPaper(p.getId());
        Object[] highest = rows.isEmpty() ? new Object[3] : rows.getFirst();
        BigDecimal[] limits = {theory, practical, internal};
        String[] names = {"theory", "practical", "internal assessment"};
        for (int i = 0; i < 3; i++) {
            BigDecimal top = (BigDecimal) highest[i];
            if (top != null && top.compareTo(limits[i]) > 0) {
                String detail = "A mark of " + top.stripTrailingZeros().toPlainString() + " is already entered for "
                        + subject + " " + names[i] + ", above the new maximum of "
                        + limits[i].stripTrailingZeros().toPlainString() + ".";
                throw new ApiException(HttpStatus.CONFLICT, "Marks entered", detail, Map.of("papers", detail));
            }
        }
    }

    /** Shows the date sheet to families and adds its papers to the school calendar. */
    public ExamClassDetail publishDateSheet(UUID examId, UUID classId, Actor actor) {
        TenantContext.require();
        Exam exam = support.exam(examId);
        ExamClass ec = support.examClass(examId, classId);
        ClassView schoolClass = support.classView(classId);
        List<Paper> list = papers.findByExamClassId(ec.getId());
        if (list.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "No papers", "Add the papers of " + schoolClass.name()
                    + " before publishing the date sheet.");
        }
        long unscheduled = list.stream().filter(p -> p.getPaperDate() == null).count();
        if (unscheduled > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "Date sheet incomplete", unscheduled
                    + (unscheduled == 1 ? " paper has" : " papers have") + " no date yet. Schedule every paper first.");
        }
        List<Clash> clashes = clashes(exam, ec, list);
        if (!clashes.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "Clashes", "The date sheet has " + clashes.size()
                    + (clashes.size() == 1 ? " clash" : " clashes") + ": " + clashes.getFirst().detail()
                    + " Fix them before publishing.");
        }
        YearInfo year = support.year(exam.getAcademicYearId());
        boolean again = ec.dateSheetPublished();
        ec.publishDateSheet(actor.name(), Instant.now());
        examClasses.saveAndFlush(ec);
        syncCalendar(exam, ec, schoolClass, actor, year);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("exam", exam.getName());
        details.put("class", schoolClass.name());
        details.put("papers", list.size());
        details.put("again", again);
        audit.record(actor, "date_sheet.published", "date_sheet", ec.getId(), details);
        return detail(exam, ec);
    }

    /** Hides the date sheet from families again and removes its calendar entries. */
    public ExamClassDetail withdrawDateSheet(UUID examId, UUID classId, Actor actor) {
        TenantContext.require();
        Exam exam = support.exam(examId);
        ExamClass ec = support.examClass(examId, classId);
        if (!ec.dateSheetPublished()) {
            throw new ApiException(HttpStatus.CONFLICT, "Not published", "This date sheet is not published.");
        }
        ClassView schoolClass = support.classView(classId);
        List<Paper> list = papers.findByExamClassId(ec.getId());
        removeCalendarEntries(list, actor, support.year(exam.getAcademicYearId()));
        papers.flush();
        ec.withdrawDateSheet();
        examClasses.saveAndFlush(ec);
        audit.record(actor, "date_sheet.withdrawn", "date_sheet", ec.getId(),
                Map.of("exam", exam.getName(), "class", schoolClass.name()));
        return detail(exam, ec);
    }

    /** Replaces the calendar entries of the papers with ones matching the date sheet. */
    private void syncCalendar(Exam exam, ExamClass ec, ClassView schoolClass, Actor actor, YearInfo year) {
        List<Paper> list = papers.findByExamClassId(ec.getId());
        removeCalendarEntries(list, actor, year);
        Map<UUID, SubjectView> subjects = support.subjects();
        NoticeCaller caller = calendarCaller(actor);
        for (Paper p : list) {
            if (p.getPaperDate() == null) {
                continue;
            }
            String title = exam.getName() + ": " + ExamSupport.subjectName(subjects, p.getSubjectId());
            String description = schoolClass.name() + (p.getRoom() == null ? "" : ", room " + p.getRoom());
            EntryView entry = calendar.create(caller, new EntryRequest(EntryKind.EXAM,
                    title.length() > 200 ? title.substring(0, 200) : title, description, p.getPaperDate(),
                    p.getPaperDate(), p.getStartTime(), p.endTime(), CalendarAudience.CLASSES,
                    List.of(schoolClass.id()), null, Set.of()));
            p.linkCalendar(entry.id());
        }
        papers.saveAllAndFlush(list);
    }

    /** Deletes the calendar entries made for the papers that still exist (staff may have removed some). */
    private void removeCalendarEntries(Collection<Paper> list, Actor actor, YearInfo year) {
        List<Paper> linked = list.stream().filter(p -> p.getCalendarEntryId() != null).toList();
        if (linked.isEmpty()) {
            return;
        }
        NoticeCaller caller = calendarCaller(actor);
        Set<UUID> present = new HashSet<>();
        LocalDate from = year.startsOn();
        while (!from.isAfter(year.endsOn())) {
            LocalDate to = from.plusDays(CalendarService.MAX_RANGE_DAYS - 1);
            if (to.isAfter(year.endsOn())) {
                to = year.endsOn();
            }
            calendar.entries(caller, from, to, null).entries().forEach(e -> present.add(e.id()));
            from = to.plusDays(1);
        }
        for (Paper p : linked) {
            if (present.contains(p.getCalendarEntryId())) {
                calendar.delete(caller, p.getCalendarEntryId());
            }
            p.linkCalendar(null);
        }
    }

    /**
     * The person publishing a date sheet, as the calendar sees them: exams.setup is what allows this, so they may add
     * and remove the date sheet's exam entries even without calendar.manage.
     */
    private static NoticeCaller calendarCaller(Actor actor) {
        return new NoticeCaller(actor.id(), actor.name(), false, false, true, true, Set.of());
    }

    ExamClassDetail detail(Exam exam, ExamClass ec) {
        Term term = support.term(exam.getTermId());
        YearInfo year = support.year(exam.getAcademicYearId());
        ClassView schoolClass = support.classView(ec.getClassId());
        Map<UUID, SubjectView> subjects = support.subjects();
        List<Paper> list = support.orderedPapers(papers.findByExamClassId(ec.getId()), subjects);
        Set<UUID> withSheets = list.isEmpty() ? Set.of()
                : sheets.findByPaperIdIn(list.stream().map(Paper::getId).toList()).stream()
                        .map(MarkSheet::getPaperId).collect(Collectors.toSet());
        List<PaperView> views = list.stream().map(p -> support.paperView(p, subjects, withSheets.contains(p.getId())))
                .toList();
        List<SubjectRef> classSubjects = schoolClass.subjects().stream()
                .map(s -> new SubjectRef(s.id(), s.name(), s.code())).toList();
        return new ExamClassDetail(exam.getId(), exam.getName(), term.getId(), term.getName(), year.id(), year.name(),
                ec.getId(), ec.getClassId(), schoolClass.name(), exam.getInstructions(), views,
                clashes(exam, ec, list), classSubjects, ec.getDateSheetPublishedAt(), ec.getDateSheetPublishedBy(),
                ec.getResultsPublishedAt(), ec.getResultsPublishedBy(), ExamsAccess.can(Permissions.EXAMS_SETUP),
                ExamsAccess.can(Permissions.EXAMS_PUBLISH));
    }

    /**
     * Problems with the class's papers in this exam: two papers of the class at the same time (in this exam or any
     * other exam of the year that covers the class), a paper on a whole-school holiday, or on a Sunday.
     */
    List<Clash> clashes(Exam exam, ExamClass ec, List<Paper> list) {
        List<Clash> result = new ArrayList<>();
        List<Paper> scheduled = list.stream().filter(p -> p.getPaperDate() != null).toList();
        if (scheduled.isEmpty()) {
            return result;
        }
        Map<UUID, SubjectView> subjects = support.subjects();
        List<ExamClass> sameClass = examClasses.findByYearAndClass(exam.getAcademicYearId(), ec.getClassId());
        Map<UUID, UUID> examOf = sameClass.stream().collect(Collectors.toMap(ExamClass::getId, ExamClass::getExamId));
        Map<UUID, String> examNames = new HashMap<>();
        exams.findAllById(new HashSet<>(examOf.values())).forEach(e -> examNames.put(e.getId(), e.getName()));
        List<Paper> others = papers.findByExamClassIdIn(examOf.keySet()).stream()
                .filter(p -> !p.getExamClassId().equals(ec.getId()) && p.getPaperDate() != null).toList();
        for (int i = 0; i < scheduled.size(); i++) {
            Paper a = scheduled.get(i);
            for (int j = i + 1; j < scheduled.size(); j++) {
                Paper b = scheduled.get(j);
                if (ExamSupport.overlap(a, b)) {
                    result.add(new Clash(b.getId(), "OVERLAP", ExamSupport.subjectName(subjects, b.getSubjectId())
                            + " and " + ExamSupport.subjectName(subjects, a.getSubjectId())
                            + " are at the same time on " + b.getPaperDate() + ".", a.getId()));
                }
            }
            for (Paper b : others) {
                if (ExamSupport.overlap(a, b)) {
                    result.add(new Clash(a.getId(), "OVERLAP", ExamSupport.subjectName(subjects, a.getSubjectId())
                            + " is at the same time as " + ExamSupport.subjectName(subjects, b.getSubjectId()) + " ("
                            + examNames.getOrDefault(examOf.get(b.getExamClassId()), "another exam") + ") on "
                            + a.getPaperDate() + ".", b.getId()));
                }
            }
        }
        LocalDate first = scheduled.stream().map(Paper::getPaperDate).min(LocalDate::compareTo).orElseThrow();
        LocalDate last = scheduled.stream().map(Paper::getPaperDate).max(LocalDate::compareTo).orElseThrow();
        Map<LocalDate, String> closed = ChronoUnit.DAYS.between(first, last) <= SchoolCalendar.MAX_RANGE_DAYS
                ? holidays.holidaysBetween(first, last) : Map.of();
        for (Paper p : scheduled) {
            String subject = ExamSupport.subjectName(subjects, p.getSubjectId());
            String holiday = closed.get(p.getPaperDate());
            if (holiday != null) {
                result.add(new Clash(p.getId(), "HOLIDAY", subject + " is on " + p.getPaperDate()
                        + ", a school holiday (" + holiday + ").", null));
            } else if (p.getPaperDate().getDayOfWeek() == DayOfWeek.SUNDAY) {
                result.add(new Clash(p.getId(), "SUNDAY", subject + " is on a Sunday (" + p.getPaperDate() + ").",
                        null));
            }
        }
        return result;
    }

    // ------------------------------------------------------------------ hall tickets

    /** Hall tickets for a class, or one section of it, with the class's date sheet. */
    @Transactional(readOnly = true)
    public HallTickets hallTickets(UUID examId, UUID classId, UUID sectionId) {
        TenantContext.require();
        Exam exam = support.exam(examId);
        ExamClass ec = support.examClass(examId, classId);
        Term term = support.term(exam.getTermId());
        YearInfo year = support.year(exam.getAcademicYearId());
        ClassView schoolClass = support.classView(classId);
        List<SectionInfo> sections = support.sectionsOfClass(classId);
        if (sectionId != null) {
            sections = sections.stream().filter(s -> s.id().equals(sectionId)).toList();
            if (sections.isEmpty()) {
                throw ApiException.notFound("Section");
            }
        }
        Map<UUID, SubjectView> subjects = support.subjects();
        List<PaperView> paperViews = support.orderedPapers(papers.findByExamClassId(ec.getId()), subjects).stream()
                .map(p -> support.paperView(p, subjects, false)).toList();
        List<HallTicketStudent> students = new ArrayList<>();
        for (SectionInfo s : sections) {
            for (RosterStudent r : roster.activeInSection(year.id(), s.id())) {
                students.add(new HallTicketStudent(r.id(), r.fullName(), r.admissionNo(), r.rollNo(), s.id(),
                        s.name(), s.label()));
            }
        }
        return new HallTickets(support.school(), exam.getId(), exam.getName(), term.getName(), year.name(),
                classId, schoolClass.name(), exam.getInstructions(), paperViews, students);
    }
}
