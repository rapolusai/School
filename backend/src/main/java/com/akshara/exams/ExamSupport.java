package com.akshara.exams;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.akshara.academics.AcademicsDirectory;
import com.akshara.academics.AcademicsDirectory.SectionInfo;
import com.akshara.academics.AcademicsDirectory.YearInfo;
import com.akshara.academics.AcademicsService;
import com.akshara.academics.AcademicsService.ClassView;
import com.akshara.academics.AcademicsService.SubjectView;
import com.akshara.exams.ExamsViews.PaperView;
import com.akshara.exams.ExamsViews.SchoolHeader;
import com.akshara.platform.TenantDirectory;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;

/** Lookups the exam services share: exams, terms and classes by id (404 for another school's), names and order. */
@Component
class ExamSupport {

    static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    private final ExamRepository exams;
    private final ExamClassRepository examClasses;
    private final TermRepository terms;
    private final PaperRepository papers;
    private final AcademicsDirectory academics;
    private final AcademicsService academicsService;
    private final TenantDirectory tenants;

    ExamSupport(ExamRepository exams, ExamClassRepository examClasses, TermRepository terms, PaperRepository papers,
            AcademicsDirectory academics, AcademicsService academicsService, TenantDirectory tenants) {
        this.exams = exams;
        this.examClasses = examClasses;
        this.terms = terms;
        this.papers = papers;
        this.academics = academics;
        this.academicsService = academicsService;
        this.tenants = tenants;
    }

    /** Today in India. */
    static LocalDate today() {
        return LocalDate.now(INDIA);
    }

    Exam exam(UUID id) {
        return exams.findById(id).orElseThrow(() -> ApiException.notFound("Exam"));
    }

    Term term(UUID id) {
        return terms.findById(id).orElseThrow(() -> ApiException.notFound("Term"));
    }

    /** The class's part of an exam; 404 when the exam or class is unknown or the exam does not cover the class. */
    ExamClass examClass(UUID examId, UUID classId) {
        exam(examId);
        return examClasses.findByExamIdAndClassId(examId, classId)
                .orElseThrow(() -> ApiException.notFound("Exam class"));
    }

    /** The exam's class locked until the transaction ends (404 as {@link #examClass}). */
    ExamClass lockExamClass(UUID examId, UUID classId) {
        exam(examId);
        return examClasses.lockByExamAndClass(examId, classId)
                .orElseThrow(() -> ApiException.notFound("Exam class"));
    }

    YearInfo year(UUID id) {
        return academics.year(id).orElseThrow(() -> ApiException.notFound("Academic year"));
    }

    /** The year asked for, else the current one; empty when neither exists. */
    Optional<YearInfo> yearOrCurrent(UUID id) {
        if (id != null) {
            return Optional.of(year(id));
        }
        return academics.currentYear().or(() -> academics.years().stream().findFirst());
    }

    List<YearInfo> years() {
        return academics.years();
    }

    SectionInfo section(UUID id) {
        return academics.section(id).orElseThrow(() -> ApiException.notFound("Section"));
    }

    List<SectionInfo> sectionsOfClass(UUID classId) {
        return academics.sectionsOfClass(classId);
    }

    /** Every class in display order, with sections and subjects. */
    Map<UUID, ClassView> classes() {
        Map<UUID, ClassView> result = new LinkedHashMap<>();
        academicsService.classes().forEach(c -> result.put(c.id(), c));
        return result;
    }

    ClassView classView(UUID classId) {
        ClassView c = classes().get(classId);
        if (c == null) {
            throw ApiException.notFound("Class");
        }
        return c;
    }

    Map<UUID, SubjectView> subjects() {
        return academicsService.subjects().stream().collect(Collectors.toMap(SubjectView::id, Function.identity()));
    }

    SchoolHeader school() {
        return tenants.profile(TenantContext.require())
                .map(p -> new SchoolHeader(p.name(), p.code(), p.board() == null ? null : p.board().name(), p.city(),
                        p.address(), p.phone()))
                .orElse(new SchoolHeader("", "", null, null, null, null));
    }

    /** Papers in date sheet order: by date and start time (unscheduled last), then subject name. */
    List<Paper> orderedPapers(Collection<Paper> list, Map<UUID, SubjectView> subjects) {
        return list.stream().sorted(Comparator
                .comparing(Paper::getPaperDate, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Paper::getStartTime, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(p -> subjectName(subjects, p.getSubjectId()).toLowerCase()))
                .toList();
    }

    List<Paper> papersOf(UUID examClassId) {
        return orderedPapers(papers.findByExamClassId(examClassId), subjects());
    }

    PaperView paperView(Paper p, Map<UUID, SubjectView> subjects, boolean hasMarks) {
        SubjectView s = subjects.get(p.getSubjectId());
        return new PaperView(p.getId(), p.getSubjectId(), s == null ? "" : s.name(), s == null ? null : s.code(),
                p.getMaxTheory(), p.getMaxPractical(), p.getMaxInternal(), p.max().total(), p.getPaperDate(),
                p.getStartTime(), p.getDurationMinutes(), p.endTime(), p.getRoom(), hasMarks);
    }

    static String subjectName(Map<UUID, SubjectView> subjects, UUID id) {
        SubjectView s = subjects.get(id);
        return s == null ? "" : s.name();
    }

    /**
     * Exams in the order they are held: by their first paper (unscheduled last), then when they were added. Used
     * for report card columns and for "the previous exam" in result analysis.
     */
    List<Exam> chronological(Collection<Exam> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        Map<UUID, LocalDate> firstPaper = firstPaperDates(list.stream().map(Exam::getId).collect(Collectors.toSet()));
        Map<UUID, LocalDate> termStart = new HashMap<>();
        terms.findAllById(list.stream().map(Exam::getTermId).collect(Collectors.toSet()))
                .forEach(t -> termStart.put(t.getId(), t.getStartsOn()));
        return list.stream().sorted(Comparator
                .comparing((Exam e) -> termStart.get(e.getTermId()), Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(e -> firstPaper.get(e.getId()), Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Exam::getCreatedAt))
                .toList();
    }

    /** The first paper date of each exam (any class), for the exams that have one. */
    Map<UUID, LocalDate> firstPaperDates(Set<UUID> examIds) {
        Map<UUID, UUID> examOfClass = examClasses.findByExamIdIn(examIds).stream()
                .collect(Collectors.toMap(ExamClass::getId, ExamClass::getExamId));
        Map<UUID, LocalDate> first = new HashMap<>();
        if (examOfClass.isEmpty()) {
            return first;
        }
        for (Paper p : papers.findByExamClassIdIn(examOfClass.keySet())) {
            if (p.getPaperDate() != null) {
                first.merge(examOfClass.get(p.getExamClassId()), p.getPaperDate(),
                        (a, b) -> a.isBefore(b) ? a : b);
            }
        }
        return first;
    }

    /** True when two papers on the same day overlap in time. */
    static boolean overlap(Paper a, Paper b) {
        if (a.getPaperDate() == null || b.getPaperDate() == null || !a.getPaperDate().equals(b.getPaperDate())
                || a.getStartTime() == null || b.getStartTime() == null) {
            return false;
        }
        LocalTime aEnd = a.endTime() == null ? a.getStartTime() : a.endTime();
        LocalTime bEnd = b.endTime() == null ? b.getStartTime() : b.endTime();
        if (a.getStartTime().equals(b.getStartTime())) {
            return true;
        }
        return a.getStartTime().isBefore(bEnd) && b.getStartTime().isBefore(aEnd);
    }

    static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
