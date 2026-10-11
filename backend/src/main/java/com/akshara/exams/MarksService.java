package com.akshara.exams;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.academics.AcademicsDirectory;
import com.akshara.academics.AcademicsDirectory.SectionInfo;
import com.akshara.academics.AcademicsDirectory.YearInfo;
import com.akshara.academics.AcademicsService.SubjectView;
import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.exams.ExamsForms.MarkEntryForm;
import com.akshara.exams.ExamsForms.MarkSheetForm;
import com.akshara.exams.ExamsViews.ExamMarks;
import com.akshara.exams.ExamsViews.MarkRow;
import com.akshara.exams.ExamsViews.MarkSheetView;
import com.akshara.exams.ExamsViews.MarksOverview;
import com.akshara.exams.ExamsViews.SheetRow;
import com.akshara.exams.ExamsViews.YearRef;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;
import com.akshara.students.StudentRoster;
import com.akshara.students.StudentRoster.RosterStudent;

/**
 * Marks entry: one sheet per paper and section. Subject teachers (timetable) and class teachers enter marks for their
 * own sections; School Admin and Principal (exams.publish) for every section. A sheet is saved as a draft any number
 * of times and then submitted, which locks it; only exams.publish unlocks a locked sheet, with a reason. Saves carry
 * the sheet's version, so a save based on an older copy is refused (409) instead of overwriting someone else's
 * marks. Every save, lock and unlock is audited (counts only, never the marks).
 */
@Service
@Transactional
public class MarksService {

    static final String NOT_STARTED = "NOT_STARTED";

    private final ExamRepository exams;
    private final ExamClassRepository examClasses;
    private final PaperRepository papers;
    private final MarkSheetRepository sheets;
    private final MarkRepository marks;
    private final ExamSupport support;
    private final ExamsAccess access;
    private final GradingService grading;
    private final AcademicsDirectory academics;
    private final StudentRoster roster;
    private final AuditService audit;

    MarksService(ExamRepository exams, ExamClassRepository examClasses, PaperRepository papers,
            MarkSheetRepository sheets, MarkRepository marks, ExamSupport support, ExamsAccess access,
            GradingService grading, AcademicsDirectory academics, StudentRoster roster, AuditService audit) {
        this.exams = exams;
        this.examClasses = examClasses;
        this.papers = papers;
        this.sheets = sheets;
        this.marks = marks;
        this.support = support;
        this.access = access;
        this.grading = grading;
        this.academics = academics;
        this.roster = roster;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ progress

    /**
     * Every paper and section of the year's exams with its state. A teacher sees the sheets they may fill in (locked
     * ones too); School Admin, Principal and staff who only read see every sheet.
     */
    @Transactional(readOnly = true)
    public MarksOverview overview(UUID yearId) {
        TenantContext.require();
        List<YearRef> years = support.years().stream().map(YearRef::of).toList();
        Optional<YearInfo> year = support.yearOrCurrent(yearId);
        if (year.isEmpty()) {
            return new MarksOverview(null, years, false, List.of());
        }
        ExamScope scope = access.scope(year.get().id());
        boolean everything = scope.wholeSchool() || !scope.canEnter();
        Map<UUID, List<SheetRow>> rowsByExam = rows(year.get(), scope).stream()
                .filter(r -> everything || scope.canEnterMarks(r.sectionId(), r.subjectId()))
                .collect(Collectors.groupingBy(r -> r.examClassId(), LinkedHashMap::new, Collectors.toList()));
        List<ExamMarks> result = new ArrayList<>();
        List<Exam> ordered = support.chronological(exams.findByYear(year.get().id()));
        Map<UUID, UUID> examOfClass = new HashMap<>();
        examClasses.findByExamIdIn(ordered.stream().map(Exam::getId).toList())
                .forEach(ec -> examOfClass.put(ec.getId(), ec.getExamId()));
        Map<UUID, String> termNames = new HashMap<>();
        for (Exam e : ordered) {
            List<SheetRow> list = rowsByExam.entrySet().stream()
                    .filter(entry -> e.getId().equals(examOfClass.get(entry.getKey())))
                    .flatMap(entry -> entry.getValue().stream()).toList();
            if (list.isEmpty()) {
                continue;
            }
            String termName = termNames.computeIfAbsent(e.getTermId(), t -> support.term(t).getName());
            int locked = (int) list.stream().filter(r -> SheetStatus.LOCKED.name().equals(r.status())).count();
            int draft = (int) list.stream().filter(r -> SheetStatus.DRAFT.name().equals(r.status())).count();
            result.add(new ExamMarks(e.getId(), e.getName(), termName, list.size(), locked, draft,
                    list.size() - locked - draft, list));
        }
        return new MarksOverview(YearRef.of(year.get()), years, scope.wholeSchool(), result);
    }

    /**
     * One row per paper and section with students, for every exam of the year, in class and section order, then by
     * the paper's date. {@code canEdit} follows the scope (and is false once a sheet is locked).
     */
    List<SheetRow> rows(YearInfo year, ExamScope scope) {
        List<Exam> examList = exams.findByYear(year.id());
        if (examList.isEmpty()) {
            return List.of();
        }
        List<ExamClass> ecs = examClasses.findByExamIdIn(examList.stream().map(Exam::getId).toList());
        if (ecs.isEmpty()) {
            return List.of();
        }
        Map<UUID, SubjectView> subjects = support.subjects();
        Map<UUID, List<Paper>> papersByEc = papers.findByExamClassIdIn(ecs.stream().map(ExamClass::getId).toList())
                .stream().collect(Collectors.groupingBy(Paper::getExamClassId));
        List<UUID> paperIds = papersByEc.values().stream().flatMap(List::stream).map(Paper::getId).toList();
        Map<String, MarkSheet> sheetByKey = paperIds.isEmpty() ? Map.of()
                : sheets.findByPaperIdIn(paperIds).stream()
                        .collect(Collectors.toMap(s -> s.getPaperId() + ":" + s.getSectionId(), Function.identity()));
        Map<UUID, Integer> entered = enteredPerSheet(sheetByKey.values().stream().map(MarkSheet::getId).toList());
        Map<UUID, List<SectionInfo>> sectionsByClass = academics.sections().stream()
                .collect(Collectors.groupingBy(SectionInfo::classId, LinkedHashMap::new, Collectors.toList()));
        Map<UUID, Long> students = roster.activePerSection(year.id());
        Map<UUID, Integer> classOrder = new HashMap<>();
        List<SectionInfo> allSections = sectionsByClass.values().stream().flatMap(List::stream).toList();
        for (int i = 0; i < allSections.size(); i++) {
            classOrder.putIfAbsent(allSections.get(i).classId(), i);
        }
        List<SheetRow> rows = new ArrayList<>();
        List<ExamClass> sortedEcs = ecs.stream()
                .sorted(Comparator.comparing((ExamClass ec) -> classOrder.getOrDefault(ec.getClassId(),
                        Integer.MAX_VALUE))).toList();
        for (ExamClass ec : sortedEcs) {
            List<Paper> list = support.orderedPapers(papersByEc.getOrDefault(ec.getId(), List.of()), subjects);
            for (SectionInfo section : sectionsByClass.getOrDefault(ec.getClassId(), List.of())) {
                int count = students.getOrDefault(section.id(), 0L).intValue();
                if (count == 0) {
                    continue;
                }
                for (Paper p : list) {
                    MarkSheet sheet = sheetByKey.get(p.getId() + ":" + section.id());
                    String status = sheet == null ? NOT_STARTED : sheet.getStatus().name();
                    boolean canEdit = scope.canEnterMarks(section.id(), p.getSubjectId())
                            && (sheet == null || !sheet.locked());
                    rows.add(new SheetRow(ec.getId(), ec.getClassId(), section.className(), p.getId(),
                            p.getSubjectId(), ExamSupport.subjectName(subjects, p.getSubjectId()), section.id(),
                            section.label(), p.getPaperDate(), status, count,
                            sheet == null ? 0 : entered.getOrDefault(sheet.getId(), 0), canEdit,
                            sheet == null ? null : sheet.getUpdatedByName(),
                            sheet == null ? null : sheet.getUpdatedAt(), ec.resultsPublished()));
                }
            }
        }
        return rows;
    }

    private Map<UUID, Integer> enteredPerSheet(Collection<UUID> sheetIds) {
        if (sheetIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Integer> result = new HashMap<>();
        for (Object[] row : marks.countEnteredPerSheet(sheetIds)) {
            result.put((UUID) row[0], ((Number) row[1]).intValue());
        }
        return result;
    }

    // ------------------------------------------------------------------ one sheet

    /** Where a sheet belongs: its paper, exam, class and section. 404 when the section is not in the exam's class. */
    record Place(Paper paper, ExamClass examClass, Exam exam, YearInfo year, SectionInfo section, String subject) {
    }

    Place place(UUID paperId, UUID sectionId) {
        Paper paper = papers.findById(paperId).orElseThrow(() -> ApiException.notFound("Paper"));
        ExamClass ec = examClasses.findById(paper.getExamClassId()).orElseThrow(() -> ApiException.notFound("Paper"));
        Exam exam = support.exam(ec.getExamId());
        SectionInfo section = support.section(sectionId);
        if (!section.classId().equals(ec.getClassId())) {
            throw ApiException.notFound("Section");
        }
        return new Place(paper, ec, exam, support.year(exam.getAcademicYearId()), section,
                ExamSupport.subjectName(support.subjects(), paper.getSubjectId()));
    }

    @Transactional(readOnly = true)
    public MarkSheetView sheet(UUID paperId, UUID sectionId) {
        TenantContext.require();
        Place place = place(paperId, sectionId);
        ExamScope scope = access.scope(place.year().id());
        return view(place, sheets.findByPaperIdAndSectionId(paperId, sectionId).orElse(null), scope);
    }

    /** Saves a draft: the lines sent are set (all blank clears a line); lines not sent stay as they were. */
    public MarkSheetView save(UUID paperId, UUID sectionId, MarkSheetForm form, Actor actor) {
        return save(paperId, sectionId, form, actor, null);
    }

    /**
     * {@link #save(UUID, UUID, MarkSheetForm, Actor)} with the caller's scope given (demo data acts for the whole
     * school); null works it out from the signed-in person.
     */
    public MarkSheetView save(UUID paperId, UUID sectionId, MarkSheetForm form, Actor actor, ExamScope given) {
        TenantContext.require();
        Place place = place(paperId, sectionId);
        ExamScope scope = given != null ? given : access.scope(place.year().id());
        requireEntry(scope, place);
        Instant now = Instant.now();
        MarkSheet sheet = sheets.lockByPaperAndSection(paperId, sectionId).orElse(null);
        checkVersion(sheet, form.version());
        if (sheet != null && sheet.locked()) {
            throw locked(sheet);
        }
        List<RosterStudent> students = roster.activeInSection(place.year().id(), sectionId);
        Map<UUID, Mark> existing = sheet == null ? Map.of() : marks.findBySheetId(sheet.getId()).stream()
                .collect(Collectors.toMap(Mark::getStudentId, Function.identity()));
        Map<UUID, String> names = names(students, existing.keySet(), place.year().id());
        Set<UUID> allowed = new HashSet<>(names.keySet());
        Map<UUID, MarkEntryForm> wanted = new LinkedHashMap<>();
        for (MarkEntryForm e : form.entries()) {
            if (!allowed.contains(e.studentId())) {
                throw ApiException.badRequest("Enter marks only for students of " + place.section().label() + ".",
                        "entries");
            }
            if (wanted.put(e.studentId(), e) != null) {
                throw ApiException.badRequest("Each student can appear only once.", "entries");
            }
            checkEntry(e, place.paper(), names.get(e.studentId()));
        }
        if (sheet == null) {
            sheet = sheets.saveAndFlush(new MarkSheet(paperId, sectionId, actor, now));
        }
        int changed = 0;
        for (MarkEntryForm e : wanted.values()) {
            Mark mark = existing.get(e.studentId());
            boolean blank = e.theory() == null && e.practical() == null && e.internal() == null && e.code() == null;
            if (mark == null) {
                if (blank) {
                    continue;
                }
                mark = new Mark(sheet.getId(), e.studentId(), now);
                mark.set(e.theory(), e.practical(), e.internal(), e.code(), now);
                marks.save(mark);
                changed++;
            } else if (mark.set(e.theory(), e.practical(), e.internal(), e.code(), now)) {
                changed++;
            }
        }
        marks.flush();
        sheet.saved(actor, now);
        sheets.saveAndFlush(sheet);
        int entered = enteredPerSheet(List.of(sheet.getId())).getOrDefault(sheet.getId(), 0);
        Map<String, Object> details = describe(place);
        details.put("changed", changed);
        details.put("entered", entered);
        details.put("students", students.size());
        audit.record(actor, "mark_sheet.saved", "mark_sheet", sheet.getId(), details);
        return view(place, sheet, scope);
    }

    /** Submits the sheet: every student of the section needs marks or a code. Locks it. */
    public MarkSheetView submit(UUID paperId, UUID sectionId, Long version, Actor actor) {
        return submit(paperId, sectionId, version, actor, null);
    }

    /** {@link #submit(UUID, UUID, Long, Actor)} with the caller's scope given; null works it out. */
    public MarkSheetView submit(UUID paperId, UUID sectionId, Long version, Actor actor, ExamScope given) {
        TenantContext.require();
        Place place = place(paperId, sectionId);
        ExamScope scope = given != null ? given : access.scope(place.year().id());
        requireEntry(scope, place);
        MarkSheet sheet = sheets.lockByPaperAndSection(paperId, sectionId).orElse(null);
        if (sheet == null) {
            throw new ApiException(HttpStatus.CONFLICT, "No marks", "Enter and save the marks before submitting.");
        }
        checkVersion(sheet, version);
        if (sheet.locked()) {
            throw locked(sheet);
        }
        List<RosterStudent> students = roster.activeInSection(place.year().id(), sectionId);
        Map<UUID, Mark> existing = marks.findBySheetId(sheet.getId()).stream()
                .collect(Collectors.toMap(Mark::getStudentId, Function.identity()));
        GradeMath.Max max = place.paper().max();
        List<String> missing = students.stream()
                .filter(s -> !complete(existing.get(s.id()), max))
                .map(RosterStudent::fullName).toList();
        if (!missing.isEmpty()) {
            String detail = missing.size() + (missing.size() == 1 ? " student has" : " students have")
                    + " no marks yet (" + String.join(", ", missing.stream().limit(3).toList())
                    + (missing.size() > 3 ? ", …" : "") + "). Enter marks or AB, ML or EX for everyone first.";
            throw new ApiException(HttpStatus.CONFLICT, "Marks missing", detail, Map.of("entries", detail));
        }
        Instant now = Instant.now();
        sheet.lock(actor, now);
        sheets.saveAndFlush(sheet);
        Map<String, Object> details = describe(place);
        details.put("students", students.size());
        audit.record(actor, "mark_sheet.locked", "mark_sheet", sheet.getId(), details);
        return view(place, sheet, scope);
    }

    /** Opens a locked sheet for changes again (exams.publish only), with the reason. Not while results are out. */
    public MarkSheetView unlock(UUID paperId, UUID sectionId, String reason, Actor actor) {
        TenantContext.require();
        Place place = place(paperId, sectionId);
        MarkSheet sheet = sheets.lockByPaperAndSection(paperId, sectionId).orElse(null);
        if (sheet == null || !sheet.locked()) {
            throw new ApiException(HttpStatus.CONFLICT, "Not locked", "These marks are not locked.");
        }
        if (place.examClass().resultsPublished()) {
            throw new ApiException(HttpStatus.CONFLICT, "Results published", "The results of "
                    + place.exam().getName() + " for " + place.section().className()
                    + " are published. Withdraw them before unlocking marks.");
        }
        String why = reason.trim();
        sheet.unlock(actor, why, Instant.now());
        sheets.saveAndFlush(sheet);
        Map<String, Object> details = describe(place);
        details.put("reason", why);
        audit.record(actor, "mark_sheet.unlocked", "mark_sheet", sheet.getId(), details);
        return view(place, sheet, access.scope(place.year().id()));
    }

    private static void requireEntry(ExamScope scope, Place place) {
        if (!scope.canEnterMarks(place.section().id(), place.paper().getSubjectId())) {
            throw new AccessDeniedException("Not this person's section or subject");
        }
    }

    private static void checkVersion(MarkSheet sheet, Long version) {
        boolean same = sheet == null ? version == null : version != null && version == sheet.getVersion();
        if (!same) {
            String who = sheet == null || sheet.getUpdatedByName() == null ? "someone else" : sheet.getUpdatedByName();
            throw new ApiException(HttpStatus.CONFLICT, "Changed meanwhile", "These marks were changed by " + who
                    + " after you opened them. Reload to see the latest marks, then enter your changes again.",
                    Map.of("version", "Reload to see the latest marks."));
        }
    }

    private static ApiException locked(MarkSheet sheet) {
        return new ApiException(HttpStatus.CONFLICT, "Locked", "These marks were submitted by "
                + (sheet.getSubmittedByName() == null ? "a teacher" : sheet.getSubmittedByName())
                + " and are locked. Ask the Principal or School Admin to unlock them.");
    }

    /** Checks one line: a code with no marks, or marks from 0 to each component's maximum. */
    private static void checkEntry(MarkEntryForm e, Paper paper, String name) {
        BigDecimal[] values = {e.theory(), e.practical(), e.internal()};
        BigDecimal[] limits = {paper.getMaxTheory(), paper.getMaxPractical(), paper.getMaxInternal()};
        String[] parts = {"theory", "practical", "internal assessment"};
        if (e.code() != null) {
            if (e.theory() != null || e.practical() != null || e.internal() != null) {
                throw ApiException.badRequest(name + ": give marks or " + e.code() + ", not both.", "entries");
            }
            return;
        }
        for (int i = 0; i < 3; i++) {
            if (values[i] == null) {
                continue;
            }
            if (limits[i].signum() == 0) {
                throw ApiException.badRequest(name + ": this paper has no " + parts[i] + " marks.", "entries");
            }
            if (values[i].signum() < 0 || values[i].compareTo(limits[i]) > 0) {
                throw ApiException.badRequest(name + ": " + parts[i] + " marks must be between 0 and "
                        + limits[i].stripTrailingZeros().toPlainString() + ".", "entries");
            }
        }
    }

    /** Marks for every component of the paper, or a code. */
    static boolean complete(Mark mark, GradeMath.Max max) {
        if (mark == null) {
            return false;
        }
        if (mark.getCode() != null) {
            return true;
        }
        return (max.theory().signum() == 0 || mark.getTheory() != null)
                && (max.practical().signum() == 0 || mark.getPractical() != null)
                && (max.internal().signum() == 0 || mark.getInternal() != null);
    }

    private Map<UUID, String> names(List<RosterStudent> students, Set<UUID> withMarks, UUID yearId) {
        Map<UUID, String> names = new LinkedHashMap<>();
        students.forEach(s -> names.put(s.id(), s.fullName()));
        Set<UUID> left = withMarks.stream().filter(id -> !names.containsKey(id)).collect(Collectors.toSet());
        roster.students(left, yearId).values().forEach(s -> names.put(s.id(), s.fullName()));
        return names;
    }

    private static Map<String, Object> describe(Place place) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("exam", place.exam().getName());
        details.put("subject", place.subject());
        details.put("section", place.section().label());
        return details;
    }

    MarkSheetView view(Place place, MarkSheet sheet, ExamScope scope) {
        Paper paper = place.paper();
        GradeMath.Max max = paper.max();
        List<RosterStudent> students = roster.activeInSection(place.year().id(), place.section().id());
        Map<UUID, Mark> existing = sheet == null ? Map.of() : marks.findBySheetId(sheet.getId()).stream()
                .collect(Collectors.toMap(Mark::getStudentId, Function.identity()));
        List<RosterStudent> all = new ArrayList<>(students);
        Set<UUID> inSection = students.stream().map(RosterStudent::id).collect(Collectors.toSet());
        Set<UUID> left = existing.keySet().stream().filter(id -> !inSection.contains(id)).collect(Collectors.toSet());
        roster.students(left, place.year().id()).values().stream()
                .sorted(Comparator.comparing(RosterStudent::fullName)).forEach(all::add);
        GradingService.Graded graded = grading.graded(place.examClass().getClassId(), place.section().className(),
                place.year().id());
        int entered = 0;
        List<MarkRow> rows = new ArrayList<>();
        for (RosterStudent s : all) {
            Mark m = existing.get(s.id());
            GradeMath.SubjectScore score = GradeMath.subject(m == null || m.blank() ? null : m.entry(), max,
                    graded.scale());
            if (m != null && !m.blank()) {
                entered++;
            }
            rows.add(new MarkRow(s.id(), s.fullName(), s.admissionNo(), s.rollNo(), inSection.contains(s.id()),
                    m == null ? null : m.getTheory(), m == null ? null : m.getPractical(),
                    m == null ? null : m.getInternal(), m == null ? null : m.getCode(), score.obtained(),
                    score.percent(), score.band() == null ? null : score.band().grade()));
        }
        boolean canEdit = scope.canEnterMarks(place.section().id(), paper.getSubjectId())
                && (sheet == null || !sheet.locked());
        return new MarkSheetView(sheet == null ? null : sheet.getId(), sheet == null ? null : sheet.getVersion(),
                place.exam().getId(), place.exam().getName(), support.term(place.exam().getTermId()).getName(),
                place.examClass().getId(), place.examClass().getClassId(), place.section().className(),
                place.section().id(), place.section().label(), paper.getId(), paper.getSubjectId(), place.subject(),
                paper.getMaxTheory(), paper.getMaxPractical(), paper.getMaxInternal(), max.total(),
                paper.getPaperDate(), sheet == null ? SheetStatus.DRAFT : sheet.getStatus(),
                sheet == null ? null : sheet.getUpdatedByName(), sheet == null ? null : sheet.getUpdatedAt(),
                sheet == null ? null : sheet.getSubmittedByName(), sheet == null ? null : sheet.getSubmittedAt(),
                sheet == null ? null : sheet.getUnlockedByName(), sheet == null ? null : sheet.getUnlockedAt(),
                sheet == null ? null : sheet.getUnlockReason(), canEdit,
                scope.wholeSchool() && sheet != null && sheet.locked() && !place.examClass().resultsPublished(),
                place.examClass().resultsPublished(), entered, rows);
    }

    /** Sheets of the exam's class that are not locked yet (sections with students only), for publishing results. */
    List<SheetRow> pendingFor(ExamClass ec, YearInfo year) {
        return rows(year, ExamScope.NONE).stream()
                .filter(r -> r.examClassId().equals(ec.getId()) && !SheetStatus.LOCKED.name().equals(r.status()))
                .toList();
    }

    /** For the dashboard: today in India. */
    static LocalDate today() {
        return ExamSupport.today();
    }
}
