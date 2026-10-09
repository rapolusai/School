package com.akshara.homework;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.files.FileStore;
import com.akshara.files.FileUploads;
import com.akshara.files.IncomingFile;
import com.akshara.files.StoredFileInfo;
import com.akshara.homework.HomeworkViews.FileRef;
import com.akshara.homework.HomeworkViews.StudentHomework;
import com.akshara.homework.HomeworkViews.StudentHomeworkDetail;
import com.akshara.homework.HomeworkViews.StudentHomeworkRow;
import com.akshara.homework.HomeworkViews.SubmissionView;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;
import com.akshara.students.StudentRoster;
import com.akshara.students.StudentService;
import com.akshara.students.StudentService.ChildView;

/**
 * Homework as students and parents see it. A student sees the homework of their own section from the day it is set,
 * downloads its files and submits text and/or files (late after the due date), again and again until a teacher
 * reviews it. A parent sees their own children's homework and submissions, read-only. Anyone else's is 404.
 */
@Service
@Transactional
public class StudentHomeworkService {

    static final int MAX_FILES = 5;
    static final String PENDING = "PENDING";

    private final HomeworkRepository homework;
    private final HomeworkSectionRepository links;
    private final SubmissionRepository submissions;
    private final AcademicsDirectory academics;
    private final StudentService students;
    private final StudentRoster roster;
    private final FileStore files;
    private final HomeworkService staff;
    private final AuditService audit;

    StudentHomeworkService(HomeworkRepository homework, HomeworkSectionRepository links,
            SubmissionRepository submissions, AcademicsDirectory academics, StudentService students,
            StudentRoster roster, FileStore files, HomeworkService staff, AuditService audit) {
        this.homework = homework;
        this.links = links;
        this.submissions = submissions;
        this.academics = academics;
        this.students = students;
        this.roster = roster;
        this.files = files;
        this.staff = staff;
        this.audit = audit;
    }

    /** The student behind a sign-in, with their section this year (null when not enrolled). */
    private record Learner(UUID id, String name, UUID yearId, SectionInfo section) {
    }

    private Learner student(UUID userId) {
        ChildView me = students.studentOf(userId).orElseThrow(() -> ApiException.notFound("Student record"));
        return learner(me);
    }

    private Learner child(UUID userId, UUID studentId) {
        if (!roster.childIdsOf(userId).contains(studentId)) {
            throw ApiException.notFound("Student");
        }
        ChildView child = students.childrenOf(userId).stream().filter(c -> c.id().equals(studentId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Student"));
        return learner(child);
    }

    private Learner learner(ChildView c) {
        Optional<YearInfo> year = academics.currentYear();
        SectionInfo section = year.flatMap(y -> roster.sectionOf(c.id(), y.id())).flatMap(academics::section)
                .orElse(null);
        return new Learner(c.id(), c.fullName(), year.map(YearInfo::id).orElse(null), section);
    }

    // ------------------------------------------------------------------ lists

    @Transactional(readOnly = true)
    public StudentHomework mine(UUID userId, LocalDate today) {
        TenantContext.require();
        return list(student(userId), today);
    }

    @Transactional(readOnly = true)
    public StudentHomework childHomework(UUID userId, UUID studentId, LocalDate today) {
        TenantContext.require();
        return list(child(userId, studentId), today);
    }

    private StudentHomework list(Learner learner, LocalDate today) {
        if (learner.section() == null) {
            return new StudentHomework(learner.id(), learner.name(), null, null, today, List.of());
        }
        List<Homework> visible = visible(learner, today);
        List<UUID> ids = visible.stream().map(Homework::getId).toList();
        Map<UUID, Submission> mine = ids.isEmpty() ? Map.of()
                : submissions.findByStudentIdAndHomeworkIdIn(learner.id(), ids).stream()
                        .collect(Collectors.toMap(Submission::getHomeworkId, Function.identity()));
        Map<UUID, List<StoredFileInfo>> attachments = files.list(HomeworkService.MODULE, HomeworkService.HOMEWORK_FILE,
                ids);
        Map<UUID, String> subjects = staff.subjectNames();
        Comparator<Homework> order = Comparator
                .comparing((Homework h) -> h.getDueOn().isBefore(today))
                .thenComparing(h -> h.getDueOn().isBefore(today) ? today.toEpochDay() - h.getDueOn().toEpochDay()
                        : h.getDueOn().toEpochDay() - today.toEpochDay())
                .thenComparing(Homework::getTitle);
        List<StudentHomeworkRow> rows = visible.stream().sorted(order).map(h -> {
            Submission s = mine.get(h.getId());
            return new StudentHomeworkRow(h.getId(), h.getTitle(), h.getSubjectId(), subjects.get(h.getSubjectId()),
                    h.getAssignedOn(), h.getDueOn(), h.isOnlineSubmission(),
                    attachments.getOrDefault(h.getId(), List.of()).size(),
                    s == null ? PENDING : s.getStatus().name(), s != null && s.isLate(),
                    s == null && h.getDueOn().isBefore(today), s == null ? null : s.getSubmittedAt(),
                    s == null ? null : s.getGrade());
        }).toList();
        return new StudentHomework(learner.id(), learner.name(), learner.section().id(), learner.section().label(),
                today, rows);
    }

    /** The homework of the learner's section this year that has been set by today. */
    private List<Homework> visible(Learner learner, LocalDate today) {
        return homework.findForSections(learner.yearId(), List.of(learner.section().id())).stream()
                .filter(h -> !h.getAssignedOn().isAfter(today))
                .toList();
    }

    /** True when the learner may see the homework: set for their section, this year, by today. */
    boolean canSee(UUID studentId, Homework hw, LocalDate today) {
        if (hw.getAssignedOn().isAfter(today)) {
            return false;
        }
        Optional<UUID> section = roster.sectionOf(studentId, hw.getAcademicYearId());
        return section.isPresent() && staff.sectionIds(hw.getId()).contains(section.get());
    }

    // ------------------------------------------------------------------ one homework

    @Transactional(readOnly = true)
    public StudentHomeworkDetail detail(UUID userId, UUID homeworkId, LocalDate today) {
        TenantContext.require();
        Learner learner = student(userId);
        Homework hw = find(learner, homeworkId, today);
        return detail(learner, hw, today, true);
    }

    @Transactional(readOnly = true)
    public StudentHomeworkDetail childDetail(UUID userId, UUID studentId, UUID homeworkId, LocalDate today) {
        TenantContext.require();
        Learner learner = child(userId, studentId);
        Homework hw = find(learner, homeworkId, today);
        return detail(learner, hw, today, false);
    }

    private Homework find(Learner learner, UUID homeworkId, LocalDate today) {
        Homework hw = homework.findById(homeworkId).orElseThrow(() -> ApiException.notFound("Homework"));
        if (!canSee(learner.id(), hw, today)) {
            throw ApiException.notFound("Homework");
        }
        return hw;
    }

    private StudentHomeworkDetail detail(Learner learner, Homework hw, LocalDate today, boolean self) {
        Submission sub = submissions.findByHomeworkIdAndStudentId(hw.getId(), learner.id()).orElse(null);
        SubmissionView view = sub == null ? null : new SubmissionView(sub.getId(), sub.getBody(),
                files.list(HomeworkService.submissionFiles(sub.getId())).stream().map(FileRef::of).toList(),
                sub.getSubmittedAt(), sub.isLate(), sub.getAttempts(), sub.getStatus(), sub.getGrade(),
                sub.getRemark(), sub.getReviewedByName(), sub.getReviewedAt());
        boolean canSubmit = self && hw.isOnlineSubmission()
                && (sub == null || sub.getStatus() != SubmissionStatus.REVIEWED);
        return new StudentHomeworkDetail(hw.getId(), learner.id(), learner.name(), hw.getTitle(), hw.getInstructions(),
                hw.getSubjectId(), staff.subjectNames().get(hw.getSubjectId()), hw.getAssignedOn(), hw.getDueOn(),
                hw.isOnlineSubmission(),
                files.list(HomeworkService.homeworkFiles(hw.getId())).stream().map(FileRef::of).toList(),
                hw.getCreatedByName(), view, canSubmit, sub == null && hw.getDueOn().isBefore(today), MAX_FILES,
                FileUploads.MAX_BYTES, today);
    }

    // ------------------------------------------------------------------ submitting

    /**
     * Submits (or resubmits) the signed-in student's answer: text and/or files. {@code keepFileIds} are files of the
     * previous attempt to keep; the others are removed. Late when sent after the due date (India time).
     */
    public StudentHomeworkDetail submit(UUID userId, UUID homeworkId, String text, Collection<UUID> keepFileIds,
            List<IncomingFile> newFiles, Actor by, Instant at) {
        TenantContext.require();
        LocalDate day = LocalDate.ofInstant(at, HomeworkService.INDIA);
        Learner learner = student(userId);
        Homework hw = find(learner, homeworkId, day);
        if (!hw.isOnlineSubmission()) {
            String detail = "This homework is handed in at school, not online.";
            throw new ApiException(HttpStatus.CONFLICT, "Not online", detail, Map.of("homework", detail));
        }
        Submission sub = submissions.findByHomeworkIdAndStudentId(homeworkId, learner.id()).orElse(null);
        if (sub != null && sub.getStatus() == SubmissionStatus.REVIEWED) {
            String detail = "Your teacher has already reviewed this homework, so it cannot be changed.";
            throw new ApiException(HttpStatus.CONFLICT, "Already reviewed", detail, Map.of("homework", detail));
        }
        String body = HomeworkService.text(text, HomeworkService.MAX_TEXT, "text",
                "Keep your answer to " + HomeworkService.MAX_TEXT + " characters.");
        List<StoredFileInfo> previous = sub == null ? List.of()
                : files.list(HomeworkService.submissionFiles(sub.getId()));
        Set<UUID> keep = keepFileIds == null ? Set.of() : Set.copyOf(keepFileIds);
        List<StoredFileInfo> kept = previous.stream().filter(f -> keep.contains(f.id())).toList();
        List<IncomingFile> added = newFiles == null ? List.of() : newFiles;
        if (kept.size() + added.size() > MAX_FILES) {
            throw ApiException.badRequest("Attach at most " + MAX_FILES + " files.", "files");
        }
        if (body.isEmpty() && kept.isEmpty() && added.isEmpty()) {
            throw ApiException.badRequest("Write your answer or attach a file.", "text");
        }
        boolean late = day.isAfter(hw.getDueOn());
        boolean first = sub == null;
        if (first) {
            sub = submissions.saveAndFlush(new Submission(homeworkId, learner.id(), body.isEmpty() ? null : body,
                    late, by.id(), at));
        } else {
            sub.resubmit(body.isEmpty() ? null : body, late, by.id(), at);
            submissions.saveAndFlush(sub);
        }
        for (StoredFileInfo f : previous) {
            if (!keep.contains(f.id())) {
                files.delete(HomeworkService.submissionFiles(sub.getId()), f.id());
            }
        }
        for (IncomingFile f : added) {
            files.put(HomeworkService.submissionFiles(sub.getId()), f, by.id());
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("homework", hw.getTitle());
        details.put("student", learner.name());
        details.put("attempt", sub.getAttempts());
        details.put("late", late);
        details.put("files", kept.size() + added.size());
        audit.record(by, "homework.submitted", "submission", sub.getId(), details);
        return detail(learner, hw, day, true);
    }

    /** For file access: the student behind a sign-in, if any. */
    Optional<UUID> studentIdOf(UUID userId) {
        return students.studentOf(userId).map(ChildView::id);
    }

    /** For file access: whether the parent signed in has a child among the students. */
    boolean isParentOf(UUID userId, Collection<UUID> studentIds) {
        Set<UUID> children = roster.childIdsOf(userId);
        return studentIds.stream().anyMatch(children::contains);
    }

    /** For file access: the parent's children enrolled in any of the sections in the year. */
    List<UUID> childrenIn(UUID userId, UUID yearId, Collection<UUID> sectionIds) {
        List<UUID> result = new ArrayList<>();
        for (UUID child : roster.childIdsOf(userId)) {
            roster.sectionOf(child, yearId).filter(sectionIds::contains).ifPresent(s -> result.add(child));
        }
        return result;
    }

    List<UUID> sectionIds(UUID homeworkId) {
        return links.findByHomeworkId(homeworkId).stream().map(HomeworkSection::getSectionId).toList();
    }
}
