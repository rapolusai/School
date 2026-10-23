package com.akshara.assist;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.academics.AcademicsDirectory;
import com.akshara.academics.AcademicsDirectory.SectionInfo;
import com.akshara.academics.AcademicsDirectory.YearInfo;
import com.akshara.assist.AssistSettings.Weights;
import com.akshara.assist.AssistViews.NoteView;
import com.akshara.assist.AssistViews.PersonRef;
import com.akshara.assist.AssistViews.Reason;
import com.akshara.assist.AssistViews.RunResult;
import com.akshara.assist.AssistViews.WatchDetail;
import com.akshara.assist.AssistViews.WatchList;
import com.akshara.assist.AssistViews.WatchRow;
import com.akshara.assist.RiskScorer.Score;
import com.akshara.assist.RiskScorer.Signals;
import com.akshara.attendance.AttendanceCounts;
import com.akshara.attendance.AttendanceInsights;
import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.exams.ExamSetupService;
import com.akshara.exams.ExamsViews.ExamSummary;
import com.akshara.exams.ExamsViews.ResultRow;
import com.akshara.exams.ExamsViews.Structure;
import com.akshara.exams.ExamsViews.TermView;
import com.akshara.exams.OverallResult;
import com.akshara.exams.ResultsService;
import com.akshara.fees.FeeHeadKind;
import com.akshara.fees.FeeInsights;
import com.akshara.fees.FeeInsights.KindDue;
import com.akshara.homework.HomeworkInsights;
import com.akshara.homework.HomeworkInsights.SubmissionRate;
import com.akshara.identity.RoleCatalog;
import com.akshara.identity.UserAccount;
import com.akshara.identity.UserRepository;
import com.akshara.notifications.MessageSettings;
import com.akshara.notifications.MessageTemplates;
import com.akshara.notifications.NotificationQueue;
import com.akshara.notifications.NotificationQueue.Recipient;
import com.akshara.notifications.NotificationQueue.Related;
import com.akshara.notifications.NotificationSettingsService;
import com.akshara.platform.TenantDirectory;
import com.akshara.shared.ApiException;
import com.akshara.shared.Permissions;
import com.akshara.shared.TenantContext;
import com.akshara.students.StudentRoster;
import com.akshara.students.StudentRoster.GuardianContact;
import com.akshara.students.StudentRoster.RosterStudent;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The early warning watch list. A run (nightly, or "run now") computes a rule-based score per active student from
 * signals other modules expose publicly: attendance in the last 30 and 90 days, the marks trend across the last two
 * exams of the class, how long fee dues have been overdue and the online homework submission rate in the last 30
 * days. The infirmary and discipline signals are skipped: no module exposes them yet. Weights come from the
 * school's settings; the score and its reasons are explainable (each contribution is listed). Staff assign an entry
 * to a teacher or counsellor, add notes, set the follow-up status and record that a parent was informed through the
 * notifications outbox (simulated). Nothing here is ever shown to parents or students.
 */
@Service
@Transactional
public class RiskService {

    static final int SHORT_DAYS = 30;
    static final int LONG_DAYS = 90;
    private static final TypeReference<List<Reason>> REASONS = new TypeReference<>() {
    };

    private final WatchEntryRepository entries;
    private final WatchNoteRepository notes;
    private final AssistSettingsService settings;
    private final AcademicsDirectory academics;
    private final StudentRoster roster;
    private final AttendanceInsights attendance;
    private final ExamSetupService examSetup;
    private final ResultsService results;
    private final FeeInsights fees;
    private final HomeworkInsights homework;
    private final UserRepository users;
    private final NotificationQueue outbox;
    private final NotificationSettingsService messageSettings;
    private final TenantDirectory tenants;
    private final AuditService audit;
    private final JsonMapper json;

    RiskService(WatchEntryRepository entries, WatchNoteRepository notes, AssistSettingsService settings,
            AcademicsDirectory academics, StudentRoster roster, AttendanceInsights attendance,
            ExamSetupService examSetup, ResultsService results, FeeInsights fees, HomeworkInsights homework,
            UserRepository users, NotificationQueue outbox, NotificationSettingsService messageSettings,
            TenantDirectory tenants, AuditService audit, JsonMapper json) {
        this.entries = entries;
        this.notes = notes;
        this.settings = settings;
        this.academics = academics;
        this.roster = roster;
        this.attendance = attendance;
        this.examSetup = examSetup;
        this.results = results;
        this.fees = fees;
        this.homework = homework;
        this.users = users;
        this.outbox = outbox;
        this.messageSettings = messageSettings;
        this.tenants = tenants;
        this.audit = audit;
        this.json = json;
    }

    // ------------------------------------------------------------------ computing

    /** "Run now" from the screen: 409 when the feature is off. */
    public RunResult runNow(Actor actor) {
        TenantContext.require();
        settings.require(AssistFeature.EARLY_WARNING);
        return compute(actor, LocalDate.now(UsageService.INDIA), Instant.now());
    }

    /** Scores every active student of the current year and rewrites the watch list. */
    public RunResult compute(Actor actor, LocalDate today, Instant now) {
        TenantContext.require();
        Weights weights = settings.current().weights();
        Optional<YearInfo> year = academics.currentYear();
        Map<UUID, WatchEntry> existing = entries.findAll().stream()
                .collect(Collectors.toMap(WatchEntry::getStudentId, Function.identity()));
        int students = 0;
        int listed = 0;
        if (year.isPresent()) {
            UUID yearId = year.get().id();
            Map<UUID, RosterStudent> all = new LinkedHashMap<>();
            Map<UUID, SectionInfo> sectionOf = new HashMap<>();
            for (SectionInfo section : academics.sections()) {
                for (RosterStudent s : roster.activeInSection(yearId, section.id())) {
                    all.put(s.id(), s);
                    sectionOf.put(s.id(), section);
                }
            }
            students = all.size();
            Map<UUID, Signals> signals = signals(yearId, all, sectionOf, today);
            for (RosterStudent s : all.values()) {
                Score score = RiskScorer.score(signals.get(s.id()), weights);
                WatchEntry entry = existing.remove(s.id());
                if (score.level() == null) {
                    if (entry != null && (entry.getStatus() != WatchStatus.OPEN || entry.getAssignedToId() != null)) {
                        // Someone is following this student up: keep the entry, with the score as it now is.
                        entry.score(s.fullName(), sectionOf.get(s.id()).id(), sectionOf.get(s.id()).label(),
                                score.score(), RiskLevel.LOW, json.writeValueAsString(score.reasons()), now);
                        listed++;
                    } else if (entry != null) {
                        entries.delete(entry);
                    }
                    continue;
                }
                if (entry == null) {
                    entry = new WatchEntry(s.id(), now);
                }
                entry.score(s.fullName(), sectionOf.get(s.id()).id(), sectionOf.get(s.id()).label(), score.score(),
                        score.level(), json.writeValueAsString(score.reasons()), now);
                entries.save(entry);
                listed++;
            }
        }
        // Students who left the roll since the last run.
        existing.values().forEach(entries::delete);
        entries.flush();
        settings.recordRun(now, students, listed);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("students", students);
        details.put("listed", listed);
        details.put("threshold", weights.threshold());
        audit.record(actor, "assist_watch.computed", "assist_watch", null, details);
        return new RunResult(now, students, listed);
    }

    private Map<UUID, Signals> signals(UUID yearId, Map<UUID, RosterStudent> all, Map<UUID, SectionInfo> sectionOf,
            LocalDate today) {
        List<UUID> ids = List.copyOf(all.keySet());
        Map<UUID, AttendanceCounts> last30 = attendance.countsPerStudent(ids, today.minusDays(SHORT_DAYS - 1), today);
        Map<UUID, AttendanceCounts> last90 = attendance.countsPerStudent(ids, today.minusDays(LONG_DAYS - 1), today);
        Map<UUID, double[]> marks = new HashMap<>();
        Map<UUID, String> latestExamName = new HashMap<>();
        marks(yearId, sectionOf, today, marks, latestExamName);
        Map<UUID, long[]> dues = dues(yearId, today);
        Map<UUID, SubmissionRate> homeworkRates = homework.submissionRatePerStudent(yearId,
                sectionOf.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().id())),
                today.minusDays(SHORT_DAYS - 1), today);
        Map<UUID, Signals> result = new HashMap<>();
        for (UUID id : ids) {
            double[] m = marks.get(id);
            long[] d = dues.get(id);
            SubmissionRate rate = homeworkRates.get(id);
            result.put(id, new Signals(percent(last30.get(id)), percent(last90.get(id)),
                    m == null || Double.isNaN(m[0]) ? null : m[0], m == null || Double.isNaN(m[1]) ? null : m[1],
                    latestExamName.get(id), d == null ? 0 : d[0], d == null ? 0 : (int) d[1],
                    rate == null ? null : rate.percent()));
        }
        return result;
    }

    private static Double percent(AttendanceCounts counts) {
        return counts == null ? null : counts.presentPercent();
    }

    /** Each student's percentage in the last two exams of their class that have started ([latest, previous]). */
    private void marks(UUID yearId, Map<UUID, SectionInfo> sectionOf, LocalDate today, Map<UUID, double[]> marks,
            Map<UUID, String> latestExamName) {
        Structure structure = examSetup.structure(yearId);
        List<ExamSummary> exams = new ArrayList<>();
        for (TermView term : structure.terms()) {
            for (ExamSummary exam : term.exams()) {
                if (exam.firstPaperOn() == null || !exam.firstPaperOn().isAfter(today)) {
                    exams.add(exam);
                }
            }
        }
        Map<UUID, List<ExamSummary>> perClass = new HashMap<>();
        for (ExamSummary exam : exams) {
            exam.classes().forEach(c -> perClass.computeIfAbsent(c.classId(), k -> new ArrayList<>()).add(exam));
        }
        Map<UUID, SectionInfo> sections = sectionOf.values().stream()
                .collect(Collectors.toMap(SectionInfo::id, Function.identity(), (a, b) -> a));
        for (SectionInfo section : sections.values()) {
            List<ExamSummary> ofClass = perClass.getOrDefault(section.classId(), List.of());
            if (ofClass.isEmpty()) {
                continue;
            }
            ExamSummary latest = ofClass.getLast();
            ExamSummary previous = ofClass.size() > 1 ? ofClass.get(ofClass.size() - 2) : null;
            Map<UUID, Double> latestPercent = percents(latest.id(), section.id());
            Map<UUID, Double> previousPercent = previous == null ? Map.of() : percents(previous.id(), section.id());
            for (Map.Entry<UUID, Double> e : latestPercent.entrySet()) {
                Double prev = previousPercent.get(e.getKey());
                marks.put(e.getKey(), new double[] {e.getValue(), prev == null ? Double.NaN : prev});
                latestExamName.put(e.getKey(), latest.name());
            }
        }
    }

    private Map<UUID, Double> percents(UUID examId, UUID sectionId) {
        Map<UUID, Double> result = new HashMap<>();
        for (ResultRow row : results.sectionResults(examId, sectionId).rows()) {
            if (row.percent() != null && row.result() != OverallResult.INCOMPLETE) {
                result.put(row.studentId(), row.percent().doubleValue());
            }
        }
        return result;
    }

    /** Each student's overdue balance in paise and the age in days of the oldest overdue instalment. */
    private Map<UUID, long[]> dues(UUID yearId, LocalDate today) {
        Map<UUID, long[]> result = new HashMap<>();
        for (FeeHeadKind kind : FeeHeadKind.values()) {
            for (KindDue due : fees.duesOfKind(yearId, kind)) {
                if (due.balancePaise() <= 0 || !due.dueDate().isBefore(today)) {
                    continue;
                }
                long age = ChronoUnit.DAYS.between(due.dueDate(), today);
                long[] d = result.computeIfAbsent(due.studentId(), k -> new long[2]);
                d[0] += due.balancePaise();
                d[1] = Math.max(d[1], age);
            }
        }
        return result;
    }

    // ------------------------------------------------------------------ reading

    @Transactional(readOnly = true)
    public WatchList list(UUID classId, WatchStatus status) {
        TenantContext.require();
        Map<UUID, UUID> classOfSection = academics.sections().stream()
                .collect(Collectors.toMap(SectionInfo::id, SectionInfo::classId));
        List<WatchRow> rows = new ArrayList<>();
        int high = 0;
        int medium = 0;
        int low = 0;
        for (WatchEntry e : entries.findAllByScore()) {
            if (classId != null && !classId.equals(classOfSection.get(e.getSectionId()))) {
                continue;
            }
            if (status != null && e.getStatus() != status) {
                continue;
            }
            switch (e.getLevel()) {
                case HIGH -> high++;
                case MEDIUM -> medium++;
                case LOW -> low++;
            }
            rows.add(row(e));
        }
        AssistSettings s = settings.current();
        return new WatchList(s.getLastRunAt(), s.getLastRunStudents(), s.getLastRunListed(), high, medium, low,
                assignees(), rows);
    }

    @Transactional(readOnly = true)
    public WatchDetail detail(UUID id) {
        TenantContext.require();
        WatchEntry e = entry(id);
        return new WatchDetail(row(e), notes.findByEntryIdOrderByCreatedAtDesc(id).stream()
                .map(n -> new NoteView(n.getId(), n.getAuthorName(), n.getBody(), n.getCreatedAt())).toList());
    }

    WatchRow row(WatchEntry e) {
        return new WatchRow(e.getId(), e.getStudentId(), e.getStudentName(), e.getSectionId(), e.getClassLabel(),
                e.getScore(), e.getLevel(), json.readValue(e.getReasonsJson(), REASONS), e.getComputedAt(),
                e.getStatus(), e.getAssignedToId(), e.getAssignedToName(), e.getParentInformedAt(),
                notes.countByEntryId(e.getId()));
    }

    /** Active staff who hold assist.use (teachers, the Principal, the School Admin), by name. */
    private List<PersonRef> assignees() {
        return users.findAllWithAnyRoleExcept(List.of(RoleCatalog.PARENT, RoleCatalog.STUDENT)).stream()
                .filter(u -> u.isActive() && u.permissions().contains(Permissions.ASSIST_USE))
                .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
                .map(u -> new PersonRef(u.getId(), u.getName())).toList();
    }

    // ------------------------------------------------------------------ follow-up

    public WatchDetail assign(UUID id, UUID userId, Actor actor) {
        TenantContext.require();
        WatchEntry e = entry(id);
        String name = null;
        if (userId != null) {
            UserAccount u = users.findById(userId).orElse(null);
            if (u == null || !u.isActive() || !u.permissions().contains(Permissions.ASSIST_USE)) {
                throw ApiException.badRequest("Choose a member of staff who can use assistance.", "userId");
            }
            name = u.getName();
        }
        e.assign(userId, name);
        entries.flush();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("student", e.getStudentName());
        details.put("assignedTo", name);
        audit.record(actor, "assist_watch.assigned", "assist_watch", e.getId(), details);
        return detail(id);
    }

    public WatchDetail setStatus(UUID id, WatchStatus status, Actor actor) {
        TenantContext.require();
        WatchEntry e = entry(id);
        e.setStatus(status);
        entries.flush();
        audit.record(actor, "assist_watch.status", "assist_watch", e.getId(),
                Map.of("student", e.getStudentName(), "status", status.name()));
        return detail(id);
    }

    public WatchDetail addNote(UUID id, String body, Actor actor) {
        TenantContext.require();
        WatchEntry e = entry(id);
        String text = body == null ? "" : body.strip();
        if (text.isEmpty()) {
            throw ApiException.badRequest("Write the note.", "body");
        }
        notes.saveAndFlush(new WatchNote(e.getId(), actor, text, Instant.now()));
        audit.record(actor, "assist_watch.note_added", "assist_watch", e.getId(),
                Map.of("student", e.getStudentName()));
        return detail(id);
    }

    /**
     * Queues a short message to the student's primary parent through the outbox (simulated: nothing is really
     * sent) and records when. The message names the student and asks the parent to contact the school; it never
     * carries the score or the reasons.
     */
    public WatchDetail informParent(UUID id, Actor actor) {
        TenantContext.require();
        WatchEntry e = entry(id);
        GuardianContact guardian = roster.primaryGuardians(List.of(e.getStudentId())).get(e.getStudentId());
        if (guardian == null || guardian.phone() == null || guardian.phone().isBlank()) {
            throw new ApiException(HttpStatus.CONFLICT, "No parent phone",
                    "There is no parent phone number on " + e.getStudentName() + "'s record.");
        }
        MessageSettings ms = messageSettings.current();
        String school = tenants.profile(TenantContext.require()).map(p -> p.name()).orElse("");
        String first = RemarkService.firstWord(e.getStudentName());
        boolean hindi = MessageTemplates.HINDI.equals(ms.alertLanguage());
        Map<String, String> params = new LinkedHashMap<>();
        params.put("school", school);
        params.put("title", hindi ? first + " की प्रगति के बारे में" : "About " + first + "'s progress");
        params.put("summary", hindi
                ? actor.name() + " " + first + " की पढ़ाई पर आपसे बात करना चाहते हैं। कृपया विद्यालय से संपर्क करें।"
                : actor.name() + " would like to talk with you about " + first + "'s progress at school. Please "
                        + "contact the school office.");
        Instant now = Instant.now();
        outbox.enqueue(MessageTemplates.CIRCULAR, ms.alertLanguage(), params,
                new Related("student", e.getStudentId(), e.getStudentName()),
                List.of(Recipient.phone(ms.absenceAlertChannel(), guardian.phone(), guardian.name(), Map.of(),
                        "assist.watch:" + e.getId() + ":" + LocalDate.ofInstant(now, UsageService.INDIA))), now);
        e.parentInformed(now);
        entries.flush();
        audit.record(actor, "assist_watch.parent_informed", "assist_watch", e.getId(),
                Map.of("student", e.getStudentName()));
        return detail(id);
    }

    private WatchEntry entry(UUID id) {
        return entries.findById(id).orElseThrow(() -> ApiException.notFound("Watch list entry"));
    }

    /** For the at-risk report: every entry by score (the report service filters). */
    @Transactional(readOnly = true)
    List<WatchRow> allRows() {
        return entries.findAllByScore().stream().map(this::row).toList();
    }
}
