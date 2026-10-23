package com.akshara.assist;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.academics.AcademicsService;
import com.akshara.academics.AcademicsService.ClassView;
import com.akshara.academics.AcademicsService.SubjectRef;
import com.akshara.assist.AssistForms.PaperDraftForm;
import com.akshara.assist.AssistForms.PaperSaveForm;
import com.akshara.assist.AssistProvider.AssistRequest;
import com.akshara.assist.AssistProvider.AssistResponse;
import com.akshara.assist.AssistProvider.Templates;
import com.akshara.assist.AssistViews.Blueprint;
import com.akshara.assist.AssistViews.PaperQuestion;
import com.akshara.assist.AssistViews.PaperRow;
import com.akshara.assist.AssistViews.PaperSection;
import com.akshara.assist.AssistViews.PaperView;
import com.akshara.assist.PaperBuilder.Built;
import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.lessons.QuestionService;
import com.akshara.lessons.QuestionService.QuestionFilter;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Draft question papers: from the lessons module's question bank (the questions the caller may see) and the
 * syllabus topics chosen, the simulated provider picks and orders questions for a blueprint and writes the
 * instructions. The teacher edits and saves the paper here (the lessons and exams modules expose no public write
 * for papers); it prints on A4. No export.
 */
@Service
@Transactional
public class PaperService {

    private static final TypeReference<List<PaperSection>> SECTIONS = new TypeReference<>() {
    };

    private final PaperDraftRepository papers;
    private final AssistSettingsService settings;
    private final UsageService usage;
    private final AssistProvider provider;
    private final QuestionService questions;
    private final AcademicsService academics;
    private final AuditService audit;
    private final JsonMapper json;

    PaperService(PaperDraftRepository papers, AssistSettingsService settings, UsageService usage,
            AssistProvider provider, QuestionService questions, AcademicsService academics, AuditService audit,
            JsonMapper json) {
        this.papers = papers;
        this.settings = settings;
        this.usage = usage;
        this.provider = provider;
        this.questions = questions;
        this.academics = academics;
        this.audit = audit;
        this.json = json;
    }

    record ClassSubject(ClassView schoolClass, SubjectRef subject) {
    }

    // ------------------------------------------------------------------ drafting

    /** An unsaved draft from the bank: one provider call for the instructions. */
    public PaperView draft(PaperDraftForm form, Actor actor) {
        TenantContext.require();
        settings.require(AssistFeature.PAPER);
        ClassSubject cs = classSubject(form.classId(), form.subjectId());
        Blueprint blueprint = PaperBuilder.check(form.blueprint());
        usage.checkLimit(1);
        Built built = PaperBuilder.build(questions.list(new QuestionFilter(form.classId(), form.subjectId(), null,
                null, null, null, false)), blueprint);
        if (built.sections().stream().allMatch(s -> s.questions().isEmpty())) {
            throw new ApiException(HttpStatus.CONFLICT, "No questions",
                    "The question bank has no questions for " + cs.subject().name() + " in "
                            + cs.schoolClass().name() + " (and the chosen chapters). Add questions first.");
        }
        String language = settings.language();
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("title", form.title().strip());
        fields.put("classLabel", cs.schoolClass().name());
        fields.put("subject", cs.subject().name());
        fields.put("duration", String.valueOf(form.durationMinutes()));
        fields.put("totalMarks", built.totalMarks().toPlainString());
        List<String> lines = new ArrayList<>();
        List<PaperSection> sections = new ArrayList<>();
        for (PaperSection s : built.sections()) {
            lines.add(s.name() + "|" + s.questions().size() + "|" + s.marksEach().toPlainString());
            sections.add(new PaperSection(s.name(), sectionInstructions(s, language), s.marksEach(), s.wanted(),
                    s.questions()));
        }
        AssistRequest request = new AssistRequest(AssistFeature.PAPER, language, Templates.PAPER_INSTRUCTIONS,
                fields, lines);
        AssistResponse response = provider.generate(request);
        Instant now = Instant.now();
        usage.record(actor, AssistFeature.PAPER, request, response, now);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("title", form.title().strip());
        details.put("class", cs.schoolClass().name());
        details.put("subject", cs.subject().name());
        details.put("questions", sections.stream().mapToInt(s -> s.questions().size()).sum());
        details.put("provider", response.provider());
        audit.record(actor, "assist_paper.drafted", "assist_paper", null, details);
        return new PaperView(null, cs.schoolClass().id(), cs.schoolClass().name(), cs.subject().id(),
                cs.subject().name(), form.title().strip(), form.durationMinutes(), built.totalMarks(),
                response.text(), blueprint, sections, response.provider(),
                SimulatedAssistProvider.NAME.equals(response.provider()), built.warnings(), actor.name(), now, 0);
    }

    static String sectionInstructions(PaperSection s, String language) {
        int n = s.questions().size();
        String marks = s.marksEach().toPlainString();
        if ("hi".equals(language)) {
            return "सभी " + n + " प्रश्नों के उत्तर दें। प्रत्येक प्रश्न " + marks + " अंक का है।";
        }
        return "Answer all " + n + " questions. Each carries " + marks + " mark" + ("1".equals(marks) ? "" : "s")
                + ".";
    }

    private ClassSubject classSubject(UUID classId, UUID subjectId) {
        ClassView c = academics.classes().stream().filter(x -> x.id().equals(classId)).findFirst()
                .orElseThrow(() -> ApiException.badRequest("Choose a class.", "classId"));
        SubjectRef s = c.subjects().stream().filter(x -> x.id().equals(subjectId)).findFirst()
                .orElseThrow(() -> ApiException.badRequest("Choose a subject taught in " + c.name() + ".",
                        "subjectId"));
        return new ClassSubject(c, s);
    }

    // ------------------------------------------------------------------ saving

    public PaperView save(PaperSaveForm form, Actor actor) {
        TenantContext.require();
        ClassSubject cs = classSubject(form.classId(), form.subjectId());
        Checked checked = check(form);
        PaperDraft paper = new PaperDraft(cs.schoolClass().id(), cs.subject().id(), cs.schoolClass().name(),
                cs.subject().name(), provider.name(), actor, Instant.now());
        paper.update(form.title().strip(), form.durationMinutes(), checked.total(), checked.instructions(),
                json.writeValueAsString(checked.blueprint()), json.writeValueAsString(checked.sections()));
        paper = papers.saveAndFlush(paper);
        audit.record(actor, "assist_paper.saved", "assist_paper", paper.getId(), Map.of("title", paper.getTitle(),
                "class", paper.getClassName(), "subject", paper.getSubjectName(), "provider", paper.getProvider()));
        return view(paper);
    }

    public PaperView update(UUID id, PaperSaveForm form, Actor actor) {
        TenantContext.require();
        PaperDraft paper = paper(id);
        if (form.version() != null && form.version() != paper.getVersion()) {
            throw new ApiException(HttpStatus.CONFLICT, "Changed elsewhere",
                    "This paper was changed by someone else. Reload it and try again.");
        }
        Checked checked = check(form);
        paper.update(form.title().strip(), form.durationMinutes(), checked.total(), checked.instructions(),
                json.writeValueAsString(checked.blueprint()), json.writeValueAsString(checked.sections()));
        papers.flush();
        audit.record(actor, "assist_paper.updated", "assist_paper", paper.getId(), Map.of("title", paper.getTitle()));
        return view(paper);
    }

    public void delete(UUID id, Actor actor) {
        TenantContext.require();
        PaperDraft paper = paper(id);
        papers.delete(paper);
        papers.flush();
        audit.record(actor, "assist_paper.deleted", "assist_paper", id, Map.of("title", paper.getTitle()));
    }

    record Checked(Blueprint blueprint, List<PaperSection> sections, BigDecimal total, String instructions) {
    }

    private static Checked check(PaperSaveForm form) {
        Blueprint blueprint = PaperBuilder.check(form.blueprint());
        List<PaperSection> sections = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        int count = 0;
        for (int i = 0; i < form.sections().size(); i++) {
            PaperSection s = form.sections().get(i);
            String field = "sections[" + i + "]";
            String name = s.name() == null ? "" : s.name().strip();
            if (name.isEmpty() || name.length() > 40) {
                throw ApiException.badRequest("Name each section (up to 40 characters).", field + ".name");
            }
            List<PaperQuestion> qs = new ArrayList<>();
            for (int j = 0; j < (s.questions() == null ? 0 : s.questions().size()); j++) {
                PaperQuestion q = s.questions().get(j);
                String text = q.text() == null ? "" : q.text().strip();
                if (text.isEmpty() || text.length() > 2000) {
                    throw ApiException.badRequest("Each question needs its text (up to 2000 characters).",
                            field + ".questions[" + j + "].text");
                }
                if (q.marks() == null || q.marks().signum() <= 0 || q.marks().compareTo(BigDecimal.valueOf(100)) > 0) {
                    throw ApiException.badRequest("Give each question marks above 0, up to 100.",
                            field + ".questions[" + j + "].marks");
                }
                BigDecimal marks = q.marks().stripTrailingZeros();
                total = total.add(marks);
                qs.add(new PaperQuestion(q.questionId(), text, q.type(), q.difficulty(), q.bloomLevel(), marks,
                        q.options() == null ? List.of() : q.options().stream().map(o -> o == null ? "" : o.strip())
                                .toList()));
                count++;
            }
            if (count > PaperBuilder.MAX_SECTIONS * PaperBuilder.MAX_QUESTIONS) {
                throw ApiException.badRequest("A paper holds at most "
                        + PaperBuilder.MAX_SECTIONS * PaperBuilder.MAX_QUESTIONS + " questions.", field);
            }
            String instructions = s.instructions() == null || s.instructions().isBlank() ? null
                    : s.instructions().strip();
            if (instructions != null && instructions.length() > 500) {
                throw ApiException.badRequest("Use at most 500 characters.", field + ".instructions");
            }
            sections.add(new PaperSection(name, instructions, s.marksEach() == null ? BigDecimal.ONE
                    : s.marksEach().stripTrailingZeros(), s.wanted(), qs));
        }
        if (count == 0) {
            throw ApiException.badRequest("Keep at least one question.", "sections");
        }
        String instructions = form.instructions() == null || form.instructions().isBlank() ? null
                : form.instructions().strip();
        return new Checked(blueprint, sections, total.stripTrailingZeros(), instructions);
    }

    // ------------------------------------------------------------------ reading

    @Transactional(readOnly = true)
    public List<PaperRow> list() {
        TenantContext.require();
        return papers.findAllNewestFirst().stream().map(p -> new PaperRow(p.getId(), p.getTitle(),
                p.getClassName(), p.getSubjectName(), p.getTotalMarks(), p.getDurationMinutes(),
                p.getCreatedByName(), p.getUpdatedAt())).toList();
    }

    @Transactional(readOnly = true)
    public PaperView get(UUID id) {
        TenantContext.require();
        return view(paper(id));
    }

    PaperView view(PaperDraft p) {
        return new PaperView(p.getId(), p.getClassId(), p.getClassName(), p.getSubjectId(), p.getSubjectName(),
                p.getTitle(), p.getDurationMinutes(), p.getTotalMarks().stripTrailingZeros(), p.getInstructions(),
                json.readValue(p.getBlueprintJson(), Blueprint.class), json.readValue(p.getSectionsJson(), SECTIONS),
                p.getProvider(), SimulatedAssistProvider.NAME.equals(p.getProvider()), List.of(),
                p.getCreatedByName(), p.getUpdatedAt(), p.getVersion());
    }

    private PaperDraft paper(UUID id) {
        return papers.findById(id).orElseThrow(() -> ApiException.notFound("Paper"));
    }

    /** For the demo data: a draft saved as it was drafted. */
    public PaperView draftAndSave(PaperDraftForm form, Actor actor) {
        PaperView draft = draft(form, actor);
        return save(new PaperSaveForm(draft.classId(), draft.subjectId(), draft.title(), draft.durationMinutes(),
                draft.instructions(), draft.blueprint(), draft.sections(), null), actor);
    }
}
