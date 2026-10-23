package com.akshara.assist;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
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

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.assist.AssistForms.DraftRemarksForm;
import com.akshara.assist.AssistProvider.AssistRequest;
import com.akshara.assist.AssistProvider.AssistResponse;
import com.akshara.assist.AssistProvider.Templates;
import com.akshara.assist.AssistViews.RemarkDraftView;
import com.akshara.assist.AssistViews.RemarkSheet;
import com.akshara.assist.AssistViews.RemarkStudent;
import com.akshara.assist.AssistViews.SubjectRef;
import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.exams.ExamsForms.RecordForm;
import com.akshara.exams.ExamsForms.RecordsForm;
import com.akshara.exams.ExamsViews.CardSubject;
import com.akshara.exams.ExamsViews.CoScholastic;
import com.akshara.exams.ExamsViews.Hpc;
import com.akshara.exams.ExamsViews.ReportCard;
import com.akshara.exams.ExamsViews.ReportCards;
import com.akshara.exams.ExamsViews.StudentRecord;
import com.akshara.exams.ExamsViews.TermRecords;
import com.akshara.exams.RecordsService;
import com.akshara.exams.ReportCardService;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;
import com.akshara.students.StudentService;
import com.akshara.students.StudentService.StudentQuery;
import com.akshara.students.StudentService.StudentRow;
import com.akshara.students.StudentStatus;

import tools.jackson.databind.json.JsonMapper;

/**
 * Report-card remark drafts: from a section's report cards for a term (the exams module's public types) the
 * provider drafts a 2–3 sentence remark per student, overall or for one subject, in the school's language and the
 * chosen tone. The request carries only the first name, class, marks, grades and attendance percentage, which the
 * teacher sees as "what was used". An accepted overall remark is written to the exams module's term record (the
 * report card) through {@link RecordsService}, which enforces who may edit the section; subject remarks stay here
 * for copying. Every draft and acceptance is audited with the provider's name.
 */
@Service
@Transactional
public class RemarkService {

    private final RemarkDraftRepository drafts;
    private final AssistSettingsService settings;
    private final UsageService usage;
    private final AssistProvider provider;
    private final ReportCardService reportCards;
    private final RecordsService records;
    private final StudentService students;
    private final AuditService audit;
    private final JsonMapper json;

    RemarkService(RemarkDraftRepository drafts, AssistSettingsService settings, UsageService usage,
            AssistProvider provider, ReportCardService reportCards, RecordsService records, StudentService students,
            AuditService audit, JsonMapper json) {
        this.drafts = drafts;
        this.settings = settings;
        this.usage = usage;
        this.provider = provider;
        this.reportCards = reportCards;
        this.records = records;
        this.students = students;
        this.audit = audit;
        this.json = json;
    }

    /** What was sent to the provider, kept with the draft. */
    record Inputs(Map<String, String> fields, List<String> lines) {
    }

    // ------------------------------------------------------------------ reading

    @Transactional(readOnly = true)
    public RemarkSheet sheet(UUID termId, UUID sectionId) {
        TenantContext.require();
        ReportCards cards = reportCards.section(sectionId, termId, null);
        TermRecords recs = records.records(termId, sectionId);
        Map<UUID, String> current = recs.students().stream().filter(s -> s.remark() != null)
                .collect(Collectors.toMap(StudentRecord::studentId, StudentRecord::remark));
        List<UUID> ids = cards.cards().stream().map(ReportCard::studentId).toList();
        Map<UUID, List<RemarkDraft>> byStudent = ids.isEmpty() ? Map.of()
                : drafts.findByTermIdAndStudentIdIn(termId, ids).stream()
                        .collect(Collectors.groupingBy(RemarkDraft::getStudentId));
        Set<SubjectRef> subjects = new LinkedHashSet<>();
        List<RemarkStudent> rows = new ArrayList<>();
        for (ReportCard card : cards.cards()) {
            card.subjects().forEach(s -> subjects.add(new SubjectRef(s.subjectId(), s.subjectName())));
            List<RemarkDraft> mine = byStudent.getOrDefault(card.studentId(), List.of());
            RemarkDraftView overall = mine.stream().filter(d -> d.getSubjectId() == null).findFirst()
                    .map(this::view).orElse(null);
            List<RemarkDraftView> perSubject = mine.stream().filter(d -> d.getSubjectId() != null)
                    .sorted((a, b) -> a.getSubjectName().compareToIgnoreCase(b.getSubjectName()))
                    .map(this::view).toList();
            rows.add(new RemarkStudent(card.studentId(), card.fullName(), card.rollNo(),
                    current.get(card.studentId()), overall, perSubject));
        }
        return new RemarkSheet(termId, cards.termName(), sectionId, cards.sectionLabel(), recs.canEdit(),
                settings.language(), List.copyOf(subjects), rows);
    }

    RemarkDraftView view(RemarkDraft d) {
        Inputs inputs = json.readValue(d.getInputsJson(), Inputs.class);
        return new RemarkDraftView(d.getId(), d.getStudentId(), d.getStudentName(), d.getSubjectId(),
                d.getSubjectName(), d.getTone(), d.getLanguage(), d.getProvider(),
                SimulatedAssistProvider.NAME.equals(d.getProvider()), d.getDraftText(), d.getAcceptedText(),
                d.getStatus().name(), inputs.fields(), inputs.lines(), d.getCreatedByName(), d.getAcceptedByName(),
                d.getAcceptedAt(), d.getUpdatedAt(), d.getVersion());
    }

    // ------------------------------------------------------------------ drafting

    /** Drafts for the students named (or every student of the section), one provider call each. */
    public List<RemarkDraftView> draft(DraftRemarksForm form, Actor actor) {
        TenantContext.require();
        settings.require(AssistFeature.REMARK);
        ReportCards cards = reportCards.section(form.sectionId(), form.termId(), null);
        List<ReportCard> wanted = cards.cards();
        if (form.studentIds() != null && !form.studentIds().isEmpty()) {
            Set<UUID> ids = new LinkedHashSet<>(form.studentIds());
            wanted = cards.cards().stream().filter(c -> ids.contains(c.studentId())).toList();
            if (wanted.size() != ids.size()) {
                throw ApiException.badRequest("Draft only for students of " + cards.sectionLabel() + ".",
                        "studentIds");
            }
        }
        if (wanted.isEmpty()) {
            throw ApiException.badRequest("There are no students in " + cards.sectionLabel() + " to draft for.",
                    "sectionId");
        }
        String subjectName = null;
        if (form.subjectId() != null) {
            subjectName = cards.cards().stream().flatMap(c -> c.subjects().stream())
                    .filter(s -> s.subjectId().equals(form.subjectId())).map(CardSubject::subjectName).findFirst()
                    .orElseThrow(() -> ApiException.badRequest("Choose a subject of " + cards.sectionLabel() + ".",
                            "subjectId"));
        }
        usage.checkLimit(wanted.size());
        Map<UUID, String> firstNames = firstNames(cards.academicYearId(), form.sectionId());
        Map<UUID, RemarkDraft> existing = drafts.findByTermIdAndStudentIdIn(form.termId(),
                wanted.stream().map(ReportCard::studentId).toList()).stream()
                .filter(d -> Objects.equals(d.getSubjectId(), form.subjectId()))
                .collect(Collectors.toMap(RemarkDraft::getStudentId, Function.identity()));
        String language = settings.language();
        Instant now = Instant.now();
        List<RemarkDraftView> result = new ArrayList<>();
        for (ReportCard card : wanted) {
            String first = firstNames.getOrDefault(card.studentId(), firstWord(card.fullName()));
            AssistRequest request = form.subjectId() == null
                    ? overallRequest(card, first, cards.sectionLabel(), form.tone(), language)
                    : subjectRequest(card, first, form.subjectId(), subjectName, form.tone(), language);
            AssistResponse response = provider.generate(request);
            RemarkDraft draft = existing.get(card.studentId());
            if (draft == null) {
                draft = new RemarkDraft(form.termId(), form.sectionId(), card.studentId(), card.fullName(),
                        form.subjectId(), subjectName, actor, now);
            }
            draft.redraft(form.tone(), language, response.provider(), response.text(),
                    json.writeValueAsString(new Inputs(request.fields(), request.lines())), actor, now);
            draft = drafts.save(draft);
            usage.record(actor, AssistFeature.REMARK, request, response, now);
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("student", card.fullName());
            details.put("term", cards.termName());
            details.put("section", cards.sectionLabel());
            details.put("subject", subjectName);
            details.put("tone", form.tone().name());
            details.put("language", language);
            details.put("provider", response.provider());
            audit.record(actor, "assist_remark.drafted", "assist_remark", draft.getId(), details);
            result.add(view(draft));
        }
        drafts.flush();
        return result;
    }

    /** The request for an overall remark: first name, class, overall percentage and grade, attendance, subjects. */
    static AssistRequest overallRequest(ReportCard card, String first, String classLabel, RemarkTone tone,
            String language) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("first", first);
        fields.put("classLabel", classLabel);
        fields.put("tone", tone.name());
        fields.put("percent", text(card.percent()));
        fields.put("grade", card.grade() == null ? "" : card.grade());
        fields.put("attendance", card.attendance() == null || card.attendance().presentPercent() == null ? ""
                : SimulatedAssistProvider.pct(card.attendance().presentPercent()));
        CardSubject best = null;
        CardSubject weak = null;
        List<String> lines = new ArrayList<>();
        for (CardSubject s : card.subjects()) {
            if (s.percent() == null) {
                continue;
            }
            lines.add(s.subjectName() + ": " + text(s.percent()) + "%" + (s.grade() == null ? "" : " (" + s.grade()
                    + ")"));
            if (best == null || s.percent().compareTo(best.percent()) > 0) {
                best = s;
            }
            if (weak == null || s.percent().compareTo(weak.percent()) < 0) {
                weak = s;
            }
        }
        fields.put("best", best == null ? "" : best.subjectName());
        fields.put("weak", weak == null ? "" : weak.subjectName());
        return new AssistRequest(AssistFeature.REMARK, language, Templates.REMARK_OVERALL, fields, lines);
    }

    static AssistRequest subjectRequest(ReportCard card, String first, UUID subjectId, String subjectName,
            RemarkTone tone, String language) {
        Optional<CardSubject> subject = card.subjects().stream().filter(s -> s.subjectId().equals(subjectId))
                .findFirst();
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("first", first);
        fields.put("subject", subjectName);
        fields.put("tone", tone.name());
        fields.put("percent", subject.map(s -> text(s.percent())).orElse(""));
        fields.put("grade", subject.map(CardSubject::grade).orElse(""));
        return new AssistRequest(AssistFeature.REMARK, language, Templates.REMARK_SUBJECT, fields, List.of());
    }

    private Map<UUID, String> firstNames(UUID yearId, UUID sectionId) {
        Map<UUID, String> names = new HashMap<>();
        int page = 0;
        while (true) {
            var result = students.list(new StudentQuery(yearId, null, sectionId, StudentStatus.ACTIVE, null, page,
                    StudentService.MAX_PAGE_SIZE));
            for (StudentRow row : result.items()) {
                names.put(row.id(), row.firstName());
            }
            if (result.items().size() < StudentService.MAX_PAGE_SIZE) {
                return names;
            }
            page++;
        }
    }

    static String firstWord(String fullName) {
        String trimmed = fullName == null ? "" : fullName.strip();
        int space = trimmed.indexOf(' ');
        return space < 0 ? trimmed : trimmed.substring(0, space);
    }

    private static String text(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    // ------------------------------------------------------------------ editing and accepting

    public RemarkDraftView edit(UUID id, String text, Actor actor) {
        TenantContext.require();
        RemarkDraft draft = draft(id);
        if (draft.getStatus() == RemarkDraft.Status.ACCEPTED) {
            throw new ApiException(org.springframework.http.HttpStatus.CONFLICT, "Already accepted",
                    "This remark was accepted; draft it again to change it.");
        }
        draft.edit(clean(text));
        drafts.flush();
        audit.record(actor, "assist_remark.edited", "assist_remark", draft.getId(),
                Map.of("student", draft.getStudentName(), "provider", draft.getProvider()));
        return view(draft);
    }

    /**
     * Accepts the text (as edited). An overall remark is written to the student's term record, which the exams
     * module allows only for the section's class teacher, the Principal and the School Admin.
     */
    public RemarkDraftView accept(UUID id, String text, Actor actor) {
        TenantContext.require();
        // Locked until the transaction ends: of two accepts at once, the second sees the first's and answers 409.
        RemarkDraft draft = drafts.findForUpdate(id).orElseThrow(() -> ApiException.notFound("Draft"));
        if (draft.getStatus() == RemarkDraft.Status.ACCEPTED) {
            throw new ApiException(org.springframework.http.HttpStatus.CONFLICT, "Already accepted",
                    "This remark was accepted; draft it again to change it.");
        }
        String remark = clean(text);
        if (draft.getSubjectId() == null) {
            TermRecords recs = records.records(draft.getTermId(), draft.getSectionId());
            StudentRecord existing = recs.students().stream().filter(s -> s.studentId().equals(draft.getStudentId()))
                    .findFirst().orElseThrow(() -> new ApiException(org.springframework.http.HttpStatus.CONFLICT,
                            "Not in the section", draft.getStudentName() + " is no longer in "
                                    + recs.sectionLabel() + "."));
            records.save(draft.getTermId(), draft.getSectionId(), new RecordsForm(List.of(recordForm(existing,
                    remark))), actor);
        }
        draft.accept(remark, actor, Instant.now());
        drafts.flush();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("student", draft.getStudentName());
        details.put("subject", draft.getSubjectName());
        details.put("provider", draft.getProvider());
        details.put("edited", !remark.equals(draft.getDraftText()));
        details.put("savedToReportCard", draft.getSubjectId() == null);
        audit.record(actor, "assist_remark.accepted", "assist_remark", draft.getId(), details);
        return view(draft);
    }

    /** The student's record as it stands with the new remark, so nothing else on it changes. */
    static RecordForm recordForm(StudentRecord r, String remark) {
        CoScholastic c = r.coScholastic() == null ? new CoScholastic(null, null, null, null) : r.coScholastic();
        Hpc h = r.hpc() == null ? new Hpc(null, null, null, null, null, null, null, null, null) : r.hpc();
        return new RecordForm(r.studentId(), c.workEducation(), c.artEducation(), c.healthPhysical(),
                c.discipline(), remark, h.physical(), h.socioEmotional(), h.cognitive(), h.language(), h.numeracy(),
                h.aesthetic(), h.teacherReflection(), h.selfReflection(), h.parentReflection());
    }

    private RemarkDraft draft(UUID id) {
        return drafts.findById(id).orElseThrow(() -> ApiException.notFound("Draft"));
    }

    private static String clean(String text) {
        String t = text == null ? "" : text.strip().replaceAll("\\s+", " ");
        if (t.isEmpty()) {
            throw ApiException.badRequest("Write the remark.", "text");
        }
        if (t.length() > 500) {
            throw ApiException.badRequest("Use at most 500 characters.", "text");
        }
        return t;
    }
}
