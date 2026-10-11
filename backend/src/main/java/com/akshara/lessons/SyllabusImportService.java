package com.akshara.lessons;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
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
import com.akshara.lessons.LessonsViews.ImportError;
import com.akshara.lessons.LessonsViews.SyllabusImportResult;
import com.akshara.lessons.LessonsViews.SyllabusPreviewRow;
import com.akshara.lessons.SyllabusService.TopicDraft;
import com.akshara.lessons.SyllabusService.UnitDraft;
import com.akshara.shared.TenantContext;

/**
 * Adds syllabi from CSV text, one topic per row: class, subject, unit, topic, and optionally periods (1 by default)
 * and target_month (2026-07 or Jul 2026). Rows of the same class and subject make one syllabus, units in the order
 * they first appear. A class and subject that already has a syllabus in the year is refused (edit it instead). Every
 * row is checked first; a dry run only reports, with a preview, and a real run writes everything in one transaction
 * or, when any row has a problem, nothing.
 */
@Service
public class SyllabusImportService {

    public static final List<String> COLUMNS = List.of("class", "subject", "unit", "topic", "periods",
            "target_month");
    static final List<String> REQUIRED = List.of("class", "subject", "unit", "topic");

    private static final List<DateTimeFormatter> MONTH_FORMATS = List.of(
            DateTimeFormatter.ofPattern("uuuu-MM", Locale.ENGLISH),
            new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("MMM uuuu").toFormatter(Locale.ENGLISH),
            new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("MMMM uuuu")
                    .toFormatter(Locale.ENGLISH),
            DateTimeFormatter.ofPattern("M/uuuu", Locale.ENGLISH));

    /** The units and topics of one class and subject, as rows add them. */
    private record Group(UUID classId, UUID subjectId, Map<String, String> unitTitles,
            Map<String, List<TopicDraft>> topics) {
    }

    private final SyllabusService syllabi;
    private final SyllabusRepository repository;
    private final LessonsSupport support;
    private final LessonsAccess access;
    private final AuditService audit;

    SyllabusImportService(SyllabusService syllabi, SyllabusRepository repository, LessonsSupport support,
            LessonsAccess access, AuditService audit) {
        this.syllabi = syllabi;
        this.repository = repository;
        this.support = support;
        this.access = access;
        this.audit = audit;
    }

    @Transactional
    public SyllabusImportResult run(String csv, UUID yearId, boolean dryRun, Actor actor) {
        TenantContext.require();
        YearInfo year = support.yearInBody(yearId, "academicYearId");
        LessonsImport.Sheet sheet = LessonsImport.read(csv, COLUMNS, REQUIRED, "topics");
        Map<String, ClassView> classes = new LinkedHashMap<>();
        support.classes().values().forEach(c -> classes.put(LessonsImport.key(c.name()), c));
        Set<String> existing = new HashSet<>();
        repository.findByAcademicYearId(year.id()).forEach(s -> existing.add(s.getClassId() + "|" + s.getSubjectId()));
        LessonsAccess.Scope scope = access.scope(year.id());

        List<ImportError> errors = new ArrayList<>();
        Set<Integer> invalidLines = new HashSet<>();
        Map<String, Group> groups = new LinkedHashMap<>();
        List<SyllabusPreviewRow> preview = new ArrayList<>();
        int valid = 0;
        for (LessonsCsvReader.Row row : sheet.rows()) {
            int before = errors.size();
            String className = sheet.cell(row, "class");
            String subjectName = sheet.cell(row, "subject");
            ClassView c = null;
            SubjectRef subject = null;
            if (className.isEmpty()) {
                errors.add(new ImportError(row.line(), "class", "Enter the class."));
            } else {
                c = classes.get(LessonsImport.key(className));
                if (c == null) {
                    errors.add(new ImportError(row.line(), "class", "No class is named " + className + "."));
                }
            }
            if (subjectName.isEmpty()) {
                errors.add(new ImportError(row.line(), "subject", "Enter the subject."));
            } else if (c != null) {
                String key = LessonsImport.key(subjectName);
                subject = c.subjects().stream()
                        .filter(s -> LessonsImport.key(s.name()).equals(key)
                                || (s.code() != null && LessonsImport.key(s.code()).equals(key)))
                        .findFirst().orElse(null);
                if (subject == null) {
                    errors.add(new ImportError(row.line(), "subject", subjectName + " is not a subject of "
                            + c.name() + "."));
                }
            }
            if (c != null && subject != null) {
                if (existing.contains(c.id() + "|" + subject.id())) {
                    errors.add(new ImportError(row.line(), "subject", c.name() + " already has a " + subject.name()
                            + " syllabus in " + year.name() + ". Edit it instead."));
                } else if (!SyllabusService.canEdit(scope, c, subject.id())) {
                    errors.add(new ImportError(row.line(), "subject", "You do not teach " + subject.name() + " in "
                            + c.name() + "."));
                }
            }
            String unit = text(row, sheet, "unit", "unit", errors);
            String topic = text(row, sheet, "topic", "topic", errors);
            int periods = 1;
            String periodsCell = sheet.cell(row, "periods");
            if (!periodsCell.isEmpty()) {
                try {
                    periods = Integer.parseInt(periodsCell);
                } catch (NumberFormatException e) {
                    periods = -1;
                }
                if (periods < 1 || periods > 200) {
                    errors.add(new ImportError(row.line(), "periods",
                            "Periods must be a whole number from 1 to 200."));
                }
            }
            YearMonth month = null;
            String monthCell = sheet.cell(row, "target_month");
            if (!monthCell.isEmpty()) {
                month = parseMonth(monthCell);
                if (month == null) {
                    errors.add(new ImportError(row.line(), "target_month",
                            "Write the month as 2026-07 or Jul 2026."));
                } else if (!SyllabusService.monthInYear(month, year)) {
                    errors.add(new ImportError(row.line(), "target_month", "The month is not within " + year.name()
                            + "."));
                }
            }
            if (errors.size() > before) {
                invalidLines.add(row.line());
                continue;
            }
            valid++;
            ClassView cls = c;
            SubjectRef sub = subject;
            Group g = groups.computeIfAbsent(cls.id() + "|" + sub.id(),
                    k -> new Group(cls.id(), sub.id(), new LinkedHashMap<>(), new LinkedHashMap<>()));
            String unitKey = LessonsImport.key(unit);
            g.unitTitles().putIfAbsent(unitKey, unit);
            g.topics().computeIfAbsent(unitKey, k -> new ArrayList<>())
                    .add(new TopicDraft(null, topic, periods, month));
            if (preview.size() < LessonsImport.PREVIEW_ROWS) {
                preview.add(new SyllabusPreviewRow(row.line(), cls.name(), sub.name(), unit, topic, periods, month));
            }
        }
        int invalid = invalidLines.size();
        List<ImportError> reported = errors.size() > LessonsImport.MAX_REPORTED_ERRORS
                ? errors.subList(0, LessonsImport.MAX_REPORTED_ERRORS) : errors;
        boolean commit = !dryRun && invalid == 0;
        if (commit) {
            int topicCount = 0;
            for (Group g : groups.values()) {
                List<UnitDraft> drafts = g.unitTitles().entrySet().stream()
                        .map(e -> new UnitDraft(null, e.getValue(), g.topics().get(e.getKey())))
                        .toList();
                if (drafts.stream().mapToInt(d -> d.topics().size()).sum() > SyllabusService.MAX_TOPICS) {
                    throw com.akshara.shared.ApiException.badRequest("A syllabus can have at most "
                            + SyllabusService.MAX_TOPICS + " topics.", "csv");
                }
                syllabi.save(year, g.classId(), g.subjectId(), drafts, actor);
                topicCount += drafts.stream().mapToInt(d -> d.topics().size()).sum();
            }
            audit.record(actor, "syllabus.imported", "syllabus", null, Map.of("year", year.name(),
                    "syllabi", groups.size(), "topics", topicCount));
        }
        return new SyllabusImportResult(dryRun, commit, sheet.rows().size(), valid, invalid, groups.size(),
                List.copyOf(reported), sheet.ignored(), preview);
    }

    private static String text(LessonsCsvReader.Row row, LessonsImport.Sheet sheet, String column, String label,
            List<ImportError> errors) {
        String value = sheet.cell(row, column).replaceAll("\\p{Cntrl}+", " ").replaceAll("\\s+", " ").strip();
        if (value.isEmpty()) {
            errors.add(new ImportError(row.line(), column, "Enter the " + label + "."));
            return null;
        }
        if (value.length() > 200) {
            errors.add(new ImportError(row.line(), column, "Keep the " + label + " to 200 characters."));
            return null;
        }
        return value;
    }

    static YearMonth parseMonth(String value) {
        for (DateTimeFormatter f : MONTH_FORMATS) {
            try {
                return YearMonth.parse(value.strip(), f);
            } catch (DateTimeParseException e) {
                // try the next format
            }
        }
        return null;
    }
}
