package com.akshara.lessons;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.academics.AcademicsDirectory.YearInfo;
import com.akshara.academics.AcademicsService.ClassView;
import com.akshara.academics.AcademicsService.SubjectRef;
import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.lessons.LessonsForms.OptionForm;
import com.akshara.lessons.LessonsForms.QuestionForm;
import com.akshara.lessons.LessonsViews.ImportError;
import com.akshara.lessons.LessonsViews.QuestionImportResult;
import com.akshara.lessons.LessonsViews.QuestionPreviewRow;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;

/**
 * Adds questions to the bank from CSV text, one question per row. Columns: class, subject, type (mcq, mcq_multi,
 * true_false, short_answer, fill_blank), difficulty (easy, medium, hard), marks (1 by default), question, option_1
 * to option_6 (multiple choice), answer (the correct option numbers such as 2 or 1;3, TRUE or FALSE, the accepted
 * answers separated by |, or a model answer), and optionally explanation, topic (a topic of the class's syllabus this
 * year), bloom and shared (yes by default). Every row is checked like a question typed in the app; a dry run only
 * reports, and a real run adds every row or, when any row has a problem, nothing.
 */
@Service
public class QuestionImportService {

    public static final List<String> COLUMNS = List.of("class", "subject", "type", "difficulty", "marks",
            "question", "option_1", "option_2", "option_3", "option_4", "option_5", "option_6", "answer",
            "explanation", "topic", "bloom", "shared");
    static final List<String> REQUIRED = List.of("class", "subject", "type", "question");

    private static final Map<String, String> FIELD_COLUMNS = Map.of("classId", "class", "subjectId", "subject",
            "topicId", "topic", "text", "question", "options", "option_1", "answerText", "answer",
            "explanation", "explanation", "marks", "marks");

    private final QuestionService questions;
    private final SyllabusRepository syllabi;
    private final SyllabusTopicRepository topics;
    private final LessonsSupport support;
    private final AuditService audit;

    QuestionImportService(QuestionService questions, SyllabusRepository syllabi, SyllabusTopicRepository topics,
            LessonsSupport support, AuditService audit) {
        this.questions = questions;
        this.syllabi = syllabi;
        this.topics = topics;
        this.support = support;
        this.audit = audit;
    }

    @Transactional
    public QuestionImportResult run(String csv, boolean dryRun, Actor actor) {
        TenantContext.require();
        LessonsImport.Sheet sheet = LessonsImport.read(csv, COLUMNS, REQUIRED, "questions");
        Map<String, ClassView> classes = new LinkedHashMap<>();
        support.classes().values().forEach(c -> classes.put(LessonsImport.key(c.name()), c));
        UUID yearId = support.currentYear().map(YearInfo::id).orElse(null);
        Map<String, Map<String, UUID>> topicCache = new HashMap<>();

        List<ImportError> errors = new ArrayList<>();
        Set<Integer> invalidLines = new HashSet<>();
        List<QuestionService.Checked> valid = new ArrayList<>();
        List<QuestionPreviewRow> preview = new ArrayList<>();
        for (LessonsCsvReader.Row row : sheet.rows()) {
            int before = errors.size();
            ClassView c = null;
            SubjectRef subject = null;
            String className = sheet.cell(row, "class");
            if (className.isEmpty()) {
                errors.add(new ImportError(row.line(), "class", "Enter the class."));
            } else if ((c = classes.get(LessonsImport.key(className))) == null) {
                errors.add(new ImportError(row.line(), "class", "No class is named " + className + "."));
            }
            String subjectName = sheet.cell(row, "subject");
            if (subjectName.isEmpty()) {
                errors.add(new ImportError(row.line(), "subject", "Enter the subject."));
            } else if (c != null) {
                String key = LessonsImport.key(subjectName);
                subject = c.subjects().stream().filter(s -> LessonsImport.key(s.name()).equals(key)
                        || (s.code() != null && LessonsImport.key(s.code()).equals(key))).findFirst().orElse(null);
                if (subject == null) {
                    errors.add(new ImportError(row.line(), "subject", subjectName + " is not a subject of "
                            + c.name() + "."));
                }
            }
            QuestionType type = type(sheet.cell(row, "type"));
            if (type == null) {
                errors.add(new ImportError(row.line(), "type",
                        "Use mcq, mcq_multi, true_false, short_answer or fill_blank."));
            }
            Difficulty difficulty = Difficulty.MEDIUM;
            String difficultyCell = sheet.cell(row, "difficulty");
            if (!difficultyCell.isEmpty()) {
                difficulty = parse(Difficulty.class, difficultyCell);
                if (difficulty == null) {
                    errors.add(new ImportError(row.line(), "difficulty", "Use easy, medium or hard."));
                }
            }
            BloomLevel bloom = null;
            String bloomCell = sheet.cell(row, "bloom");
            if (!bloomCell.isEmpty()) {
                bloom = parse(BloomLevel.class, bloomCell.toUpperCase(Locale.ROOT).replace("ANALYZE", "ANALYSE"));
                if (bloom == null) {
                    errors.add(new ImportError(row.line(), "bloom",
                            "Use remember, understand, apply, analyse, evaluate or create."));
                }
            }
            BigDecimal marks = BigDecimal.ONE;
            String marksCell = sheet.cell(row, "marks");
            if (!marksCell.isEmpty()) {
                try {
                    marks = new BigDecimal(marksCell);
                } catch (NumberFormatException e) {
                    marks = null;
                }
                if (marks == null || marks.compareTo(new BigDecimal("0.25")) < 0
                        || marks.compareTo(BigDecimal.valueOf(100)) > 0 || marks.scale() > 2) {
                    errors.add(new ImportError(row.line(), "marks", "Marks must be from 0.25 to 100."));
                    marks = null;
                }
            }
            Boolean shared = true;
            String sharedCell = sheet.cell(row, "shared").toLowerCase(Locale.ROOT);
            if (!sharedCell.isEmpty()) {
                shared = switch (sharedCell) {
                    case "yes", "y", "true" -> true;
                    case "no", "n", "false" -> false;
                    default -> null;
                };
                if (shared == null) {
                    errors.add(new ImportError(row.line(), "shared", "Use yes or no."));
                }
            }
            UUID topicId = null;
            String topicCell = sheet.cell(row, "topic");
            if (!topicCell.isEmpty() && c != null && subject != null) {
                topicId = topicId(topicCache, yearId, c.id(), subject.id(), topicCell);
                if (topicId == null) {
                    errors.add(new ImportError(row.line(), "topic", "No topic of the " + c.name() + " "
                            + subject.name() + " syllabus is named " + topicCell + "."));
                }
            }
            List<OptionForm> options = new ArrayList<>();
            String answer = sheet.cell(row, "answer");
            if (type != null && type.hasOptions()) {
                Set<Integer> correct = new HashSet<>();
                for (String part : answer.split("[;,|\\s]+")) {
                    if (part.isBlank()) {
                        continue;
                    }
                    try {
                        correct.add(Integer.parseInt(part.strip()));
                    } catch (NumberFormatException e) {
                        errors.add(new ImportError(row.line(), "answer",
                                "Give the numbers of the correct options, such as 2 or 1;3."));
                        break;
                    }
                }
                for (int i = 1; i <= LessonsForms.MAX_OPTIONS; i++) {
                    String text = sheet.cell(row, "option_" + i);
                    if (!text.isEmpty()) {
                        options.add(new OptionForm(text, correct.contains(i)));
                    } else if (correct.contains(i)) {
                        errors.add(new ImportError(row.line(), "answer", "Option " + i + " is empty."));
                    }
                }
                answer = null;
            }
            if (errors.size() == before) {
                QuestionForm form = new QuestionForm(c.id(), subject.id(), topicId, type, difficulty, bloom,
                        sheet.cell(row, "question"), type == QuestionType.FILL_BLANK ? answer.replace("|", "\n")
                                : answer, sheet.cell(row, "explanation"), marks, shared,
                        type.hasOptions() ? options : null);
                try {
                    QuestionService.Checked checked = questions.check(form);
                    valid.add(checked);
                    if (preview.size() < LessonsImport.PREVIEW_ROWS) {
                        preview.add(new QuestionPreviewRow(row.line(), c.name(), subject.name(), type, difficulty,
                                checked.content().marks(), checked.content().text()));
                    }
                } catch (ApiException e) {
                    String column = e.errors().keySet().stream().findFirst().map(f -> FIELD_COLUMNS.getOrDefault(f, f))
                            .orElse("question");
                    errors.add(new ImportError(row.line(), column, e.getMessage()));
                }
            }
            if (errors.size() > before) {
                invalidLines.add(row.line());
            }
        }
        int invalid = invalidLines.size();
        List<ImportError> reported = errors.size() > LessonsImport.MAX_REPORTED_ERRORS
                ? errors.subList(0, LessonsImport.MAX_REPORTED_ERRORS) : errors;
        boolean commit = !dryRun && invalid == 0;
        if (commit) {
            valid.forEach(q -> questions.save(q, actor));
            audit.record(actor, "question.imported", "question", null, Map.of("questions", valid.size()));
        }
        return new QuestionImportResult(dryRun, commit, sheet.rows().size(), valid.size(), invalid,
                List.copyOf(reported), sheet.ignored(), preview);
    }

    private UUID topicId(Map<String, Map<String, UUID>> cache, UUID yearId, UUID classId, UUID subjectId,
            String title) {
        if (yearId == null) {
            return null;
        }
        Map<String, UUID> byTitle = cache.computeIfAbsent(classId + "|" + subjectId, k -> {
            Map<String, UUID> m = new HashMap<>();
            syllabi.findByAcademicYearIdAndClassIdAndSubjectId(yearId, classId, subjectId).ifPresent(s ->
                    topics.findBySyllabusIdOrderByPosition(s.getId())
                            .forEach(t -> m.putIfAbsent(LessonsImport.key(t.getTitle()), t.getId())));
            return m;
        });
        return byTitle.get(LessonsImport.key(title));
    }

    static QuestionType type(String value) {
        String v = value.strip().toUpperCase(Locale.ROOT).replaceAll("[\\s/()-]+", "_").replaceAll("_+$", "");
        return switch (v) {
            case "MCQ", "MCQ_SINGLE", "MCQ_ONE", "SINGLE", "MULTIPLE_CHOICE" -> QuestionType.MCQ_SINGLE;
            case "MCQ_MULTI", "MCQ_MULTIPLE", "MCQ_SEVERAL", "MULTI" -> QuestionType.MCQ_MULTI;
            case "TRUE_FALSE", "TF", "TRUE_OR_FALSE" -> QuestionType.TRUE_FALSE;
            case "SHORT_ANSWER", "SHORT" -> QuestionType.SHORT_ANSWER;
            case "FILL_BLANK", "FILL_IN_THE_BLANK", "FILL_IN_THE_BLANKS", "FILL", "BLANK" -> QuestionType.FILL_BLANK;
            default -> null;
        };
    }

    static <E extends Enum<E>> E parse(Class<E> type, String value) {
        try {
            return Enum.valueOf(type, value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
