package com.akshara.homework;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.persistence.EntityManager;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.academics.AcademicsDirectory;
import com.akshara.academics.AcademicsDirectory.SectionInfo;
import com.akshara.academics.AcademicsDirectory.YearInfo;
import com.akshara.academics.AcademicsService;
import com.akshara.academics.AcademicsService.SubjectView;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;
import com.akshara.students.StudentRoster;
import com.akshara.students.StudentRoster.RosterStudent;

/**
 * Read-only homework figures for dashboards and reports: submissions waiting for review and completion by section and
 * subject. Both use the signed-in person's homework scope, as the homework screens do, and count the active students
 * of each section, as the submission tracker does.
 */
@Service
@Transactional(readOnly = true)
public class HomeworkInsights {

    public static final int MAX_RANGE_DAYS = 400;

    /**
     * One section and subject. {@code expected} is the students of the section summed over the homework with online
     * submission; {@code completionPercent} is submitted out of expected. Homework without online submission is only
     * counted in {@code homework}.
     */
    public record CompletionLine(UUID sectionId, String sectionLabel, UUID classId, String className, UUID subjectId,
            String subjectName, int homework, int online, int expected, int submitted, int late, int reviewed,
            int needsRedo, int waiting, Double completionPercent) {
    }

    /** {@code total} counts each piece of homework once, however many sections it was set for. */
    public record Completion(LocalDate from, LocalDate to, UUID classId, UUID subjectId, List<CompletionLine> rows,
            CompletionLine total) {
    }

    private record Key(UUID sectionId, UUID subjectId) {
    }

    private static final class Tally {
        final Set<UUID> homework = new HashSet<>();
        final Set<UUID> online = new HashSet<>();
        int expected;
        int submitted;
        int late;
        int reviewed;
        int needsRedo;
        int waiting;

        void add(Tally t) {
            homework.addAll(t.homework);
            online.addAll(t.online);
            expected += t.expected;
            submitted += t.submitted;
            late += t.late;
            reviewed += t.reviewed;
            needsRedo += t.needsRedo;
            waiting += t.waiting;
        }
    }

    private final HomeworkAccess access;
    private final HomeworkRepository homework;
    private final HomeworkSectionRepository links;
    private final SubmissionRepository submissions;
    private final AcademicsDirectory academics;
    private final AcademicsService academicsService;
    private final StudentRoster roster;
    private final EntityManager entityManager;

    HomeworkInsights(HomeworkAccess access, HomeworkRepository homework, HomeworkSectionRepository links,
            SubmissionRepository submissions, AcademicsDirectory academics, AcademicsService academicsService,
            StudentRoster roster, EntityManager entityManager) {
        this.access = access;
        this.homework = homework;
        this.links = links;
        this.submissions = submissions;
        this.academics = academics;
        this.academicsService = academicsService;
        this.roster = roster;
        this.entityManager = entityManager;
    }

    /** Submissions of this year's homework waiting for the signed-in person to review. */
    public int waitingForReview() {
        TenantContext.require();
        HomeworkScope scope = access.current();
        Optional<YearInfo> year = academics.currentYear();
        if (year.isEmpty() || scope.equals(HomeworkScope.NONE)) {
            return 0;
        }
        List<Homework> all = homework.findByAcademicYearId(year.get().id()).stream()
                .filter(Homework::isOnlineSubmission).toList();
        return tally(scope, all, null).values().stream().mapToInt(t -> t.waiting).sum();
    }

    /**
     * Homework due between two days (both included), by section and subject, in the person's scope. {@code classId}
     * and {@code subjectId} narrow it down.
     */
    public Completion completion(LocalDate from, LocalDate to, UUID classId, UUID subjectId) {
        TenantContext.require();
        if (to.isBefore(from)) {
            throw ApiException.badRequest("The end date must not be before the start date.", "to");
        }
        if (ChronoUnit.DAYS.between(from, to) >= MAX_RANGE_DAYS) {
            throw ApiException.badRequest("Choose at most " + MAX_RANGE_DAYS + " days.", "to");
        }
        HomeworkScope scope = access.current();
        List<Homework> due = entityManager.createQuery("select h from Homework h where h.dueOn between :from and :to",
                Homework.class)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList().stream()
                .filter(h -> subjectId == null || h.getSubjectId().equals(subjectId))
                .toList();
        Map<UUID, SectionInfo> sections = new LinkedHashMap<>();
        academics.sections().stream().filter(s -> classId == null || s.classId().equals(classId))
                .forEach(s -> sections.put(s.id(), s));
        Map<Key, Tally> tallies = tally(scope, due, sections.keySet());
        Map<UUID, String> subjects = academicsService.subjects().stream()
                .collect(Collectors.toMap(SubjectView::id, SubjectView::name));
        List<UUID> sectionOrder = new ArrayList<>(sections.keySet());
        List<CompletionLine> rows = tallies.entrySet().stream()
                .sorted((a, b) -> {
                    int bySection = Integer.compare(sectionOrder.indexOf(a.getKey().sectionId()),
                            sectionOrder.indexOf(b.getKey().sectionId()));
                    return bySection != 0 ? bySection
                            : name(subjects, a.getKey().subjectId()).compareTo(name(subjects, b.getKey().subjectId()));
                })
                .map(e -> {
                    SectionInfo s = sections.get(e.getKey().sectionId());
                    return line(s.id(), s.label(), s.classId(), s.className(), e.getKey().subjectId(),
                            subjects.get(e.getKey().subjectId()), e.getValue());
                })
                .toList();
        Tally all = new Tally();
        tallies.values().forEach(all::add);
        return new Completion(from, to, classId, subjectId, rows, line(null, null, null, null, null, null, all));
    }

    private static String name(Map<UUID, String> subjects, UUID id) {
        return subjects.getOrDefault(id, "").toLowerCase(Locale.ROOT);
    }

    private static CompletionLine line(UUID sectionId, String label, UUID classId, String className, UUID subjectId,
            String subjectName, Tally t) {
        return new CompletionLine(sectionId, label, classId, className, subjectId, subjectName, t.homework.size(),
                t.online.size(), t.expected, t.submitted, t.late, t.reviewed, t.needsRedo, t.waiting,
                percent(t.submitted, t.expected));
    }

    /**
     * Counts per section and subject for the homework, over the sections the person may manage for its subject
     * (and among {@code onlySections} when given). Submissions count when their student is an active student of the
     * section in the homework's year.
     */
    private Map<Key, Tally> tally(HomeworkScope scope, List<Homework> list, Collection<UUID> onlySections) {
        Map<Key, Tally> tallies = new LinkedHashMap<>();
        if (list.isEmpty()) {
            return tallies;
        }
        List<UUID> ids = list.stream().map(Homework::getId).toList();
        Map<UUID, List<UUID>> sectionsOf = links.findByHomeworkIdIn(ids).stream().collect(Collectors.groupingBy(
                HomeworkSection::getHomeworkId, Collectors.mapping(HomeworkSection::getSectionId,
                        Collectors.toList())));
        Map<UUID, List<Submission>> subs = submissions.findByHomeworkIdIn(ids).stream()
                .collect(Collectors.groupingBy(Submission::getHomeworkId));
        Map<String, Set<UUID>> rolls = new HashMap<>();
        for (Homework h : list) {
            for (UUID sectionId : sectionsOf.getOrDefault(h.getId(), List.of())) {
                if (!scope.canManage(sectionId, h.getSubjectId())
                        || (onlySections != null && !onlySections.contains(sectionId))) {
                    continue;
                }
                Tally t = tallies.computeIfAbsent(new Key(sectionId, h.getSubjectId()), k -> new Tally());
                t.homework.add(h.getId());
                if (!h.isOnlineSubmission()) {
                    continue;
                }
                t.online.add(h.getId());
                Set<UUID> roll = rolls.computeIfAbsent(h.getAcademicYearId() + "|" + sectionId,
                        k -> roster.activeInSection(h.getAcademicYearId(), sectionId).stream()
                                .map(RosterStudent::id).collect(Collectors.toSet()));
                t.expected += roll.size();
                for (Submission s : subs.getOrDefault(h.getId(), List.of())) {
                    if (!roll.contains(s.getStudentId())) {
                        continue;
                    }
                    t.submitted++;
                    if (s.isLate()) {
                        t.late++;
                    }
                    switch (s.getStatus()) {
                        case SUBMITTED -> t.waiting++;
                        case REVIEWED -> t.reviewed++;
                        case NEEDS_REDO -> t.needsRedo++;
                    }
                }
            }
        }
        return tallies;
    }

    static Double percent(int part, int whole) {
        if (whole <= 0) {
            return null;
        }
        return BigDecimal.valueOf(part).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(whole), 1, RoundingMode.HALF_UP).doubleValue();
    }
}
