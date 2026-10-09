package com.akshara.homework;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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

import com.akshara.academics.AcademicsDirectory;
import com.akshara.academics.AcademicsDirectory.SectionInfo;
import com.akshara.academics.AcademicsDirectory.YearInfo;
import com.akshara.academics.AcademicsService;
import com.akshara.academics.AcademicsService.SubjectRef;
import com.akshara.academics.AcademicsService.SubjectView;
import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.files.FileOwner;
import com.akshara.files.FileStore;
import com.akshara.files.FileUploads;
import com.akshara.files.IncomingFile;
import com.akshara.files.StoredFileInfo;
import com.akshara.homework.HomeworkViews.Counts;
import com.akshara.homework.HomeworkViews.FileRef;
import com.akshara.homework.HomeworkViews.HomeworkDetail;
import com.akshara.homework.HomeworkViews.HomeworkOptions;
import com.akshara.homework.HomeworkViews.HomeworkPage;
import com.akshara.homework.HomeworkViews.HomeworkRow;
import com.akshara.homework.HomeworkViews.HomeworkSettingsView;
import com.akshara.homework.HomeworkViews.SectionOption;
import com.akshara.homework.HomeworkViews.SectionRef;
import com.akshara.homework.HomeworkViews.SubjectOption;
import com.akshara.homework.HomeworkViews.Tracker;
import com.akshara.homework.HomeworkViews.TrackerRow;
import com.akshara.homework.HomeworkViews.YearRef;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;
import com.akshara.students.StudentRoster;
import com.akshara.students.StudentRoster.RosterStudent;

/**
 * Homework set by staff: create, change until the due date, delete while nobody has submitted, attach files, follow
 * who has submitted, and review submissions. Teachers work within their {@link HomeworkScope}. Every change is
 * audited.
 */
@Service
@Transactional
public class HomeworkService {

    public static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    public static final String MODULE = "homework";
    public static final String HOMEWORK_FILE = "homework";
    public static final String SUBMISSION_FILE = "submission";
    static final int MAX_SECTIONS = 30;
    static final int MAX_ATTACHMENTS = 5;
    static final int MAX_TEXT = 5000;
    static final int MAX_PAGE = 100;

    /** What staff type when setting homework. {@code assignedOn} null means today. */
    public record HomeworkInput(List<UUID> sectionIds, UUID subjectId, String title, String instructions,
            LocalDate assignedOn, LocalDate dueOn, boolean onlineSubmission) {
    }

    private final HomeworkRepository homework;
    private final HomeworkSectionRepository links;
    private final SubmissionRepository submissions;
    private final HomeworkSettingsRepository settings;
    private final AcademicsDirectory academics;
    private final AcademicsService academicsService;
    private final StudentRoster roster;
    private final FileStore files;
    private final HomeworkReminders reminders;
    private final AuditService audit;

    HomeworkService(HomeworkRepository homework, HomeworkSectionRepository links, SubmissionRepository submissions,
            HomeworkSettingsRepository settings, AcademicsDirectory academics, AcademicsService academicsService,
            StudentRoster roster, FileStore files, HomeworkReminders reminders, AuditService audit) {
        this.homework = homework;
        this.links = links;
        this.submissions = submissions;
        this.settings = settings;
        this.academics = academics;
        this.academicsService = academicsService;
        this.roster = roster;
        this.files = files;
        this.reminders = reminders;
        this.audit = audit;
    }

    public static LocalDate today() {
        return LocalDate.now(INDIA);
    }

    static FileOwner homeworkFiles(UUID homeworkId) {
        return new FileOwner(MODULE, HOMEWORK_FILE, homeworkId);
    }

    static FileOwner submissionFiles(UUID submissionId) {
        return new FileOwner(MODULE, SUBMISSION_FILE, submissionId);
    }

    // ------------------------------------------------------------------ what the person may set

    @Transactional(readOnly = true)
    public HomeworkOptions options(HomeworkScope scope) {
        TenantContext.require();
        Optional<YearInfo> year = academics.currentYear();
        List<SectionOption> sections = new ArrayList<>();
        if (year.isPresent()) {
            Map<UUID, List<SubjectRef>> byClass = new HashMap<>();
            for (SectionInfo s : academics.sections()) {
                List<SubjectRef> subjects = byClass.computeIfAbsent(s.classId(), academicsService::classSubjects);
                List<SubjectOption> allowed = subjects.stream()
                        .filter(sub -> scope.canManage(s.id(), sub.id()))
                        .map(sub -> new SubjectOption(sub.id(), sub.name()))
                        .toList();
                if (!allowed.isEmpty()) {
                    sections.add(new SectionOption(s.id(), s.label(), s.classId(), allowed));
                }
            }
        }
        return new HomeworkOptions(YearRef.of(year.orElse(null)), today(), MAX_ATTACHMENTS, FileUploads.MAX_BYTES,
                sections);
    }

    // ------------------------------------------------------------------ lists and details

    /** {@code when}: open (due today or later; the default), past, or all. */
    @Transactional(readOnly = true)
    public HomeworkPage list(HomeworkScope scope, UUID sectionId, UUID subjectId, String when, int page, int size,
            LocalDate today) {
        TenantContext.require();
        int pageSize = Math.max(1, Math.min(size, MAX_PAGE));
        int pageNo = Math.max(0, page);
        Optional<YearInfo> year = academics.currentYear();
        if (year.isEmpty()) {
            return new HomeworkPage(List.of(), pageNo, pageSize, 0);
        }
        List<Homework> all = homework.findByAcademicYearId(year.get().id());
        Map<UUID, List<UUID>> sectionsOf = sectionsOf(all.stream().map(Homework::getId).toList());
        String filter = when == null ? "open" : when.toLowerCase(Locale.ROOT);
        Comparator<Homework> order = "open".equals(filter)
                ? Comparator.comparing(Homework::getDueOn).thenComparing(Homework::getTitle)
                : Comparator.comparing(Homework::getDueOn).reversed().thenComparing(Homework::getTitle);
        List<Homework> visible = all.stream()
                .filter(h -> scope.canSee(sectionsOf.getOrDefault(h.getId(), List.of()), h.getSubjectId()))
                .filter(h -> sectionId == null || sectionsOf.getOrDefault(h.getId(), List.of()).contains(sectionId))
                .filter(h -> subjectId == null || h.getSubjectId().equals(subjectId))
                .filter(h -> switch (filter) {
                    case "past" -> h.getDueOn().isBefore(today);
                    case "all" -> true;
                    default -> !h.getDueOn().isBefore(today);
                })
                .sorted(order)
                .toList();
        List<Homework> slice = visible.stream().skip((long) pageNo * pageSize).limit(pageSize).toList();
        List<UUID> ids = slice.stream().map(Homework::getId).toList();
        Map<UUID, SectionInfo> sections = sectionMap();
        Map<UUID, String> subjects = subjectNames();
        Map<UUID, Long> enrolled = roster.activePerSection(year.get().id());
        Map<UUID, List<Submission>> subs = submissions.findByHomeworkIdIn(ids).stream()
                .collect(Collectors.groupingBy(Submission::getHomeworkId));
        Map<UUID, List<StoredFileInfo>> attachments = files.list(MODULE, HOMEWORK_FILE, ids);
        List<HomeworkRow> rows = slice.stream().map(h -> {
            List<UUID> own = sectionsOf.getOrDefault(h.getId(), List.of());
            int students = own.stream().mapToInt(s -> enrolled.getOrDefault(s, 0L).intValue()).sum();
            return new HomeworkRow(h.getId(), h.getTitle(), h.getSubjectId(), subjects.get(h.getSubjectId()),
                    refs(own, sections), h.getAssignedOn(), h.getDueOn(), h.isOnlineSubmission(),
                    attachments.getOrDefault(h.getId(), List.of()).size(),
                    counts(students, subs.getOrDefault(h.getId(), List.of())), h.getCreatedByName(),
                    scope.canManageAll(own, h.getSubjectId()) && !today.isAfter(h.getDueOn()));
        }).toList();
        return new HomeworkPage(rows, pageNo, pageSize, visible.size());
    }

    @Transactional(readOnly = true)
    public HomeworkDetail detail(HomeworkScope scope, UUID id, LocalDate today) {
        TenantContext.require();
        Homework hw = homework.findById(id).orElseThrow(() -> ApiException.notFound("Homework"));
        List<UUID> own = sectionIds(id);
        if (!scope.canSee(own, hw.getSubjectId())) {
            throw ApiException.notFound("Homework");
        }
        return detail(scope, hw, own, today);
    }

    private HomeworkDetail detail(HomeworkScope scope, Homework hw, List<UUID> own, LocalDate today) {
        Map<UUID, SectionInfo> sections = sectionMap();
        Map<UUID, Long> enrolled = roster.activePerSection(hw.getAcademicYearId());
        int students = own.stream().mapToInt(s -> enrolled.getOrDefault(s, 0L).intValue()).sum();
        List<Submission> subs = submissions.findByHomeworkId(hw.getId());
        boolean mine = scope.canManageAll(own, hw.getSubjectId());
        return new HomeworkDetail(hw.getId(), hw.getTitle(), hw.getInstructions(), hw.getSubjectId(),
                subjectNames().get(hw.getSubjectId()), refs(own, sections), hw.getAssignedOn(), hw.getDueOn(),
                hw.isOnlineSubmission(), files.list(homeworkFiles(hw.getId())).stream().map(FileRef::of).toList(),
                hw.getCreatedByName(), hw.getCreatedAt(), hw.getUpdatedAt(), counts(students, subs),
                mine && !today.isAfter(hw.getDueOn()), mine && subs.isEmpty(), today);
    }

    static Counts counts(int students, List<Submission> subs) {
        int submitted = subs.size();
        int late = (int) subs.stream().filter(Submission::isLate).count();
        int reviewed = (int) subs.stream().filter(s -> s.getStatus() == SubmissionStatus.REVIEWED).count();
        int redo = (int) subs.stream().filter(s -> s.getStatus() == SubmissionStatus.NEEDS_REDO).count();
        int waiting = (int) subs.stream().filter(s -> s.getStatus() == SubmissionStatus.SUBMITTED).count();
        return new Counts(students, submitted, late, Math.max(0, students - submitted), reviewed, redo, waiting);
    }

    // ------------------------------------------------------------------ setting homework

    public HomeworkDetail create(HomeworkScope scope, HomeworkInput input, Actor by, Instant at, LocalDate today) {
        TenantContext.require();
        YearInfo year = academics.currentYear().orElseThrow(() -> ApiException.badRequest(
                "Set up the current academic year in School setup first.", "sectionIds"));
        List<SectionInfo> sections = sections(input.sectionIds());
        checkSubject(sections, input.subjectId());
        if (!scope.canManageAll(sections.stream().map(SectionInfo::id).toList(), input.subjectId())) {
            throw notYours();
        }
        String title = title(input.title());
        String instructions = instructions(input.instructions());
        LocalDate assignedOn = input.assignedOn() != null ? input.assignedOn() : today;
        if (assignedOn.isAfter(today)) {
            throw ApiException.badRequest("Homework cannot be set for a future date.", "assignedOn");
        }
        if (assignedOn.isBefore(year.startsOn())) {
            throw ApiException.badRequest("Pick a date in the current academic year, " + year.name() + ".",
                    "assignedOn");
        }
        checkDue(input.dueOn(), assignedOn, today, year);
        Homework hw = homework.saveAndFlush(new Homework(year.id(), input.subjectId(), title, instructions, assignedOn,
                input.dueOn(), input.onlineSubmission(), by, at));
        links.saveAllAndFlush(sections.stream().map(s -> new HomeworkSection(hw.getId(), s.id())).toList());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("title", title);
        details.put("subject", subjectNames().get(input.subjectId()));
        details.put("sections", sections.stream().map(SectionInfo::label).toList());
        details.put("dueOn", input.dueOn().toString());
        int reminded = assignedOn.equals(today) ? reminders.onAssigned(hw, sections, at) : 0;
        if (reminded > 0) {
            details.put("remindersQueued", reminded);
        }
        audit.record(by, "homework.created", "homework", hw.getId(), details);
        return detail(scope, hw, sections.stream().map(SectionInfo::id).toList(), today);
    }

    /**
     * Changes homework until its due date. Once students have submitted, its subject cannot change and sections can
     * only be added.
     */
    public HomeworkDetail update(HomeworkScope scope, UUID id, HomeworkInput input, LocalDate today) {
        TenantContext.require();
        Homework hw = editable(scope, id, today);
        List<UUID> before = sectionIds(id);
        List<SectionInfo> sections = sections(input.sectionIds());
        List<UUID> after = sections.stream().map(SectionInfo::id).toList();
        checkSubject(sections, input.subjectId());
        if (!scope.canManageAll(after, input.subjectId())) {
            throw notYours();
        }
        long submitted = submissions.countByHomeworkId(id);
        if (submitted > 0 && !hw.getSubjectId().equals(input.subjectId())) {
            throw ApiException.conflict("Students have already submitted this homework, so its subject cannot "
                    + "change.", "subjectId");
        }
        if (submitted > 0 && !after.containsAll(before)) {
            throw ApiException.conflict("Students have already submitted this homework, so sections can be added "
                    + "but not removed.", "sectionIds");
        }
        String title = title(input.title());
        String instructions = instructions(input.instructions());
        YearInfo year = academics.year(hw.getAcademicYearId()).orElseThrow();
        checkDue(input.dueOn(), hw.getAssignedOn(), today, year);
        hw.update(input.subjectId(), title, instructions, input.dueOn(), input.onlineSubmission());
        homework.saveAndFlush(hw);
        List<HomeworkSection> current = links.findByHomeworkId(id);
        links.deleteAll(current.stream().filter(l -> !after.contains(l.getSectionId())).toList());
        links.flush();
        links.saveAllAndFlush(after.stream().filter(s -> !before.contains(s)).map(s -> new HomeworkSection(id, s))
                .toList());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("title", title);
        details.put("subject", subjectNames().get(input.subjectId()));
        details.put("sections", sections.stream().map(SectionInfo::label).toList());
        details.put("dueOn", input.dueOn().toString());
        details.put("onlineSubmission", input.onlineSubmission());
        audit.record("homework.updated", "homework", id, details);
        return detail(scope, hw, after, today);
    }

    /** Deletes homework that nobody has submitted yet, with its files. */
    public void delete(HomeworkScope scope, UUID id) {
        TenantContext.require();
        Homework hw = homework.findById(id).orElseThrow(() -> ApiException.notFound("Homework"));
        List<UUID> own = sectionIds(id);
        mustManage(scope, hw, own);
        long submitted = submissions.countByHomeworkId(id);
        if (submitted > 0) {
            String detail = submitted + (submitted == 1 ? " student has" : " students have")
                    + " already submitted this homework, so it cannot be deleted.";
            throw new ApiException(HttpStatus.CONFLICT, "In use", detail, Map.of("homework", detail));
        }
        int removedFiles = files.deleteAll(homeworkFiles(id));
        links.deleteAll(links.findByHomeworkId(id));
        links.flush();
        homework.delete(hw);
        homework.flush();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("title", hw.getTitle());
        details.put("subject", subjectNames().get(hw.getSubjectId()));
        details.put("dueOn", hw.getDueOn().toString());
        details.put("files", removedFiles);
        audit.record("homework.deleted", "homework", id, details);
    }

    public FileRef addAttachment(HomeworkScope scope, UUID id, IncomingFile file, Actor by, LocalDate today) {
        TenantContext.require();
        Homework hw = editable(scope, id, today);
        if (files.list(homeworkFiles(id)).size() >= MAX_ATTACHMENTS) {
            throw ApiException.conflict("A homework can have at most " + MAX_ATTACHMENTS + " files. Remove one "
                    + "first.", "file");
        }
        StoredFileInfo stored = files.put(homeworkFiles(id), file, by.id());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("homework", hw.getTitle());
        details.put("file", stored.name());
        details.put("size", stored.size());
        audit.record(by, "homework.attachment_added", "homework", id, details);
        return FileRef.of(stored);
    }

    public void removeAttachment(HomeworkScope scope, UUID id, UUID fileId, LocalDate today) {
        TenantContext.require();
        Homework hw = editable(scope, id, today);
        StoredFileInfo info = files.info(fileId).filter(f -> f.belongsTo(homeworkFiles(id)))
                .orElseThrow(() -> ApiException.notFound("File"));
        files.delete(homeworkFiles(id), fileId);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("homework", hw.getTitle());
        details.put("file", info.name());
        audit.record("homework.attachment_removed", "homework", id, details);
    }

    // ------------------------------------------------------------------ tracker and review

    /** Every student of the homework's sections (those in the person's scope) with their submission. */
    @Transactional(readOnly = true)
    public Tracker tracker(HomeworkScope scope, UUID id, LocalDate today) {
        TenantContext.require();
        Homework hw = homework.findById(id).orElseThrow(() -> ApiException.notFound("Homework"));
        List<UUID> own = sectionIds(id);
        List<UUID> visible = own.stream().filter(s -> scope.canManage(s, hw.getSubjectId())).toList();
        if (visible.isEmpty()) {
            throw ApiException.notFound("Homework");
        }
        Map<UUID, SectionInfo> sections = sectionMap();
        Map<UUID, Submission> byStudent = submissions.findByHomeworkId(id).stream()
                .collect(Collectors.toMap(Submission::getStudentId, Function.identity()));
        Map<UUID, List<StoredFileInfo>> submissionFiles = files.list(MODULE, SUBMISSION_FILE,
                byStudent.values().stream().map(Submission::getId).toList());
        List<TrackerRow> rows = new ArrayList<>();
        List<Submission> counted = new ArrayList<>();
        for (UUID sectionId : sectionOrder(visible, sections)) {
            SectionInfo section = sections.get(sectionId);
            for (RosterStudent student : roster.activeInSection(hw.getAcademicYearId(), sectionId)) {
                Submission sub = byStudent.get(student.id());
                if (sub != null) {
                    counted.add(sub);
                }
                rows.add(row(student, section, sub, submissionFiles));
            }
        }
        HomeworkDetail detail = detail(scope, hw, own, today);
        return new Tracker(detail, counts(rows.size(), counted), rows);
    }

    /** Marks a submission reviewed (it can no longer change) or sends it back to be done again. */
    public TrackerRow review(HomeworkScope scope, UUID homeworkId, UUID submissionId, SubmissionStatus status,
            String grade, String remark, Actor by, Instant at) {
        TenantContext.require();
        Homework hw = homework.findById(homeworkId).orElseThrow(() -> ApiException.notFound("Homework"));
        Submission sub = submissions.findById(submissionId).filter(s -> s.getHomeworkId().equals(homeworkId))
                .orElseThrow(() -> ApiException.notFound("Submission"));
        UUID sectionId = roster.sectionOf(sub.getStudentId(), hw.getAcademicYearId()).orElse(null);
        if (sectionId == null || !sectionIds(homeworkId).contains(sectionId)
                || !scope.canManage(sectionId, hw.getSubjectId())) {
            throw ApiException.notFound("Submission");
        }
        if (status != SubmissionStatus.REVIEWED && status != SubmissionStatus.NEEDS_REDO) {
            throw ApiException.badRequest("Mark it reviewed or ask for it to be done again.", "status");
        }
        String cleanGrade = blankToNull(grade);
        if (cleanGrade != null && cleanGrade.length() > 10) {
            throw ApiException.badRequest("Keep the grade to 10 characters.", "grade");
        }
        String cleanRemark = blankToNull(remark == null ? null : remark.replaceAll("\\p{Cntrl}+", " "));
        if (cleanRemark != null && cleanRemark.length() > 500) {
            throw ApiException.badRequest("Keep the remark to 500 characters.", "remark");
        }
        sub.review(status, cleanGrade, cleanRemark, by, at);
        submissions.saveAndFlush(sub);
        RosterStudent student = roster.student(sub.getStudentId(), hw.getAcademicYearId()).orElseThrow();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("homework", hw.getTitle());
        details.put("student", student.fullName());
        details.put("status", status.name());
        if (cleanGrade != null) {
            details.put("grade", cleanGrade);
        }
        audit.record(by, "homework.reviewed", "submission", submissionId, details);
        return row(student, sectionMap().get(sectionId), sub,
                files.list(MODULE, SUBMISSION_FILE, List.of(sub.getId())));
    }

    private static TrackerRow row(RosterStudent student, SectionInfo section, Submission sub,
            Map<UUID, List<StoredFileInfo>> submissionFiles) {
        List<FileRef> attached = sub == null ? List.of()
                : submissionFiles.getOrDefault(sub.getId(), List.of()).stream().map(FileRef::of).toList();
        return new TrackerRow(student.id(), student.fullName(), student.admissionNo(), student.rollNo(),
                section == null ? null : section.id(), section == null ? null : section.label(),
                sub == null ? "MISSING" : sub.getStatus().name(), sub == null ? null : sub.getId(),
                sub == null ? null : sub.getSubmittedAt(), sub != null && sub.isLate(),
                sub == null ? 0 : sub.getAttempts(), sub == null ? null : sub.getBody(), attached,
                sub == null ? null : sub.getGrade(), sub == null ? null : sub.getRemark(),
                sub == null ? null : sub.getReviewedByName(), sub == null ? null : sub.getReviewedAt());
    }

    // ------------------------------------------------------------------ settings

    @Transactional(readOnly = true)
    public HomeworkSettingsView settings() {
        TenantContext.require();
        return new HomeworkSettingsView(reminders.enabled());
    }

    public HomeworkSettingsView updateSettings(boolean remindersEnabled) {
        TenantContext.require();
        HomeworkSettings row = settings.findFirstBy().orElse(null);
        boolean before = row != null && row.isRemindersEnabled();
        if (row == null) {
            row = settings.saveAndFlush(new HomeworkSettings(remindersEnabled));
        } else {
            row.update(remindersEnabled);
            settings.saveAndFlush(row);
        }
        if (before != remindersEnabled) {
            audit.record("homework_settings.updated", "homework_settings", row.getId(),
                    Map.of("remindersEnabled", remindersEnabled));
        }
        return new HomeworkSettingsView(remindersEnabled);
    }

    // ------------------------------------------------------------------ helpers

    private Homework editable(HomeworkScope scope, UUID id, LocalDate today) {
        Homework hw = homework.findById(id).orElseThrow(() -> ApiException.notFound("Homework"));
        mustManage(scope, hw, sectionIds(id));
        if (today.isAfter(hw.getDueOn())) {
            String detail = "Homework can be changed only until its due date.";
            throw new ApiException(HttpStatus.CONFLICT, "Past due date", detail, Map.of("dueOn", detail));
        }
        return hw;
    }

    /** 404 when the person cannot even see the homework, 403 when they can see but not change it. */
    private static void mustManage(HomeworkScope scope, Homework hw, List<UUID> own) {
        if (scope.canManageAll(own, hw.getSubjectId())) {
            return;
        }
        if (scope.canSee(own, hw.getSubjectId())) {
            throw notYours();
        }
        throw ApiException.notFound("Homework");
    }

    private static ApiException notYours() {
        String detail = "You can set homework only for the sections and subjects you teach.";
        return new ApiException(HttpStatus.FORBIDDEN, "Not allowed", detail, Map.of("sectionIds", detail));
    }

    /** Sections in a request body: each must be a section of this school (400 otherwise). */
    private List<SectionInfo> sections(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            throw ApiException.badRequest("Pick at least one section.", "sectionIds");
        }
        Set<UUID> wanted = new LinkedHashSet<>(ids);
        if (wanted.contains(null)) {
            throw ApiException.badRequest("Pick sections of this school.", "sectionIds");
        }
        if (wanted.size() > MAX_SECTIONS) {
            throw ApiException.badRequest("Pick at most " + MAX_SECTIONS + " sections.", "sectionIds");
        }
        Map<UUID, SectionInfo> all = sectionMap();
        List<SectionInfo> result = new ArrayList<>();
        for (UUID id : wanted) {
            SectionInfo s = all.get(id);
            if (s == null) {
                throw ApiException.badRequest("Pick sections of this school.", "sectionIds");
            }
            result.add(s);
        }
        return result;
    }

    private void checkSubject(List<SectionInfo> sections, UUID subjectId) {
        if (subjectId == null) {
            throw ApiException.badRequest("Pick a subject.", "subjectId");
        }
        Map<UUID, Set<UUID>> byClass = new HashMap<>();
        for (SectionInfo s : sections) {
            Set<UUID> subjects = byClass.computeIfAbsent(s.classId(), c -> academicsService.classSubjects(c).stream()
                    .map(SubjectRef::id).collect(Collectors.toSet()));
            if (!subjects.contains(subjectId)) {
                throw ApiException.badRequest(s.className() + " does not study this subject (see School setup).",
                        "subjectId");
            }
        }
    }

    private static void checkDue(LocalDate dueOn, LocalDate assignedOn, LocalDate today, YearInfo year) {
        if (dueOn == null) {
            throw ApiException.badRequest("Pick the due date.", "dueOn");
        }
        if (dueOn.isBefore(assignedOn)) {
            throw ApiException.badRequest("The due date cannot be before the homework is set.", "dueOn");
        }
        if (dueOn.isBefore(today)) {
            throw ApiException.badRequest("The due date cannot be in the past.", "dueOn");
        }
        if (dueOn.isAfter(year.endsOn())) {
            throw ApiException.badRequest("Pick a due date in the academic year " + year.name() + ".", "dueOn");
        }
    }

    private static String title(String value) {
        String title = value == null ? "" : value.replaceAll("\\p{Cntrl}+", " ").strip().replaceAll("\\s+", " ");
        if (title.isEmpty()) {
            throw ApiException.badRequest("Give the homework a title.", "title");
        }
        if (title.length() > 200) {
            throw ApiException.badRequest("Keep the title to 200 characters.", "title");
        }
        return title;
    }

    /** Plain text: line breaks and tabs are kept, other control characters are dropped. */
    static String text(String value, int max, String field, String tooLong) {
        if (value == null) {
            return "";
        }
        String clean = value.replace("\r\n", "\n").replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "").strip();
        if (clean.length() > max) {
            throw ApiException.badRequest(tooLong, field);
        }
        return clean;
    }

    private static String instructions(String value) {
        return text(value, MAX_TEXT, "instructions", "Keep the instructions to " + MAX_TEXT + " characters.");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    List<UUID> sectionIds(UUID homeworkId) {
        return links.findByHomeworkId(homeworkId).stream().map(HomeworkSection::getSectionId).toList();
    }

    Map<UUID, List<UUID>> sectionsOf(Collection<UUID> homeworkIds) {
        if (homeworkIds.isEmpty()) {
            return Map.of();
        }
        return links.findByHomeworkIdIn(homeworkIds).stream().collect(Collectors.groupingBy(
                HomeworkSection::getHomeworkId, Collectors.mapping(HomeworkSection::getSectionId,
                        Collectors.toList())));
    }

    Map<UUID, SectionInfo> sectionMap() {
        return academics.sections().stream()
                .collect(Collectors.toMap(SectionInfo::id, Function.identity(), (a, b) -> a, LinkedHashMap::new));
    }

    Map<UUID, String> subjectNames() {
        return academicsService.subjects().stream().collect(Collectors.toMap(SubjectView::id, SubjectView::name));
    }

    private static List<UUID> sectionOrder(Collection<UUID> ids, Map<UUID, SectionInfo> sections) {
        List<UUID> order = List.copyOf(sections.keySet());
        return ids.stream().sorted(Comparator.comparingInt(order::indexOf)).toList();
    }

    static List<SectionRef> refs(Collection<UUID> ids, Map<UUID, SectionInfo> sections) {
        return sectionOrder(ids, sections).stream()
                .map(id -> new SectionRef(id, Optional.ofNullable(sections.get(id)).map(SectionInfo::label)
                        .orElse(null)))
                .filter(r -> Objects.nonNull(r.label()))
                .toList();
    }
}
