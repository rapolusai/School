package com.akshara.lessons;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.lessons.CoverageService.CoverageFilter;
import com.akshara.lessons.LessonsForms.CopyForm;
import com.akshara.lessons.LessonsForms.ImportRequest;
import com.akshara.lessons.LessonsForms.PlanForm;
import com.akshara.lessons.LessonsForms.ReviewForm;
import com.akshara.lessons.LessonsForms.SettingsForm;
import com.akshara.lessons.LessonsForms.SyllabusForm;
import com.akshara.lessons.LessonsForms.TaughtForm;
import com.akshara.lessons.LessonsViews.CopyResult;
import com.akshara.lessons.LessonsViews.CoverageDetail;
import com.akshara.lessons.LessonsViews.CoverageReport;
import com.akshara.lessons.LessonsViews.Options;
import com.akshara.lessons.LessonsViews.PlanDetail;
import com.akshara.lessons.LessonsViews.PlanRow;
import com.akshara.lessons.LessonsViews.Settings;
import com.akshara.lessons.LessonsViews.SyllabusDetail;
import com.akshara.lessons.LessonsViews.SyllabusImportResult;
import com.akshara.lessons.LessonsViews.SyllabusRow;
import com.akshara.lessons.LessonsViews.TopicOption;
import com.akshara.lessons.PlanService.PlanFilter;
import com.akshara.shared.ApiExceptionHandler;
import com.akshara.shared.CurrentUser;

/**
 * Lessons for staff: settings and pickers, the syllabus of each class and subject, lesson plans with their approval,
 * and syllabus coverage. Reading needs lessons.read (or lessons.manage / lessons.approve); a teacher's own plans need
 * lessons.manage; approving needs lessons.approve. Ids of another school are 404.
 */
@RestController
@RequestMapping("/api/lessons")
public class LessonsController {

    static final String READ = "hasAnyAuthority('lessons.read', 'lessons.manage', 'lessons.approve')";
    static final String MANAGE = "hasAuthority('lessons.manage')";
    static final String APPROVE = "hasAuthority('lessons.approve')";
    /** Changing a syllabus: approvers (any) and teachers (the subjects they teach). */
    static final String EDIT_SYLLABUS = "hasAnyAuthority('lessons.manage', 'lessons.approve')";

    private final LessonsSetupService setup;
    private final SyllabusService syllabi;
    private final SyllabusImportService syllabusImport;
    private final PlanService plans;
    private final CoverageService coverage;

    LessonsController(LessonsSetupService setup, SyllabusService syllabi, SyllabusImportService syllabusImport,
            PlanService plans, CoverageService coverage) {
        this.setup = setup;
        this.syllabi = syllabi;
        this.syllabusImport = syllabusImport;
        this.plans = plans;
        this.coverage = coverage;
    }

    // ------------------------------------------------------------------ settings and pickers

    @GetMapping("/settings")
    @PreAuthorize("hasAnyAuthority('lessons.read', 'lessons.manage', 'lessons.approve', 'settings.manage')")
    public Settings settings() {
        return setup.settings();
    }

    @PutMapping("/settings")
    @PreAuthorize("hasAuthority('settings.manage')")
    public Settings updateSettings(@Valid @RequestBody SettingsForm request) {
        return setup.updateSettings(request, LessonsAccess.actor());
    }

    /** Years, classes with sections and subjects, and what the caller teaches (the current year by default). */
    @GetMapping("/options")
    @PreAuthorize(READ)
    public Options options(@RequestParam(required = false) UUID yearId) {
        return setup.options(yearId);
    }

    /** The topics of a class and subject's syllabus, for pickers. */
    @GetMapping("/topics")
    @PreAuthorize(READ)
    public List<TopicOption> topics(@RequestParam(required = false) UUID yearId, @RequestParam UUID classId,
            @RequestParam UUID subjectId) {
        return syllabi.topicOptions(yearId, classId, subjectId);
    }

    // ------------------------------------------------------------------ syllabus

    @GetMapping("/syllabi")
    @PreAuthorize(READ)
    public List<SyllabusRow> syllabi(@RequestParam(required = false) UUID yearId) {
        return syllabi.list(yearId);
    }

    @GetMapping("/syllabi/{id}")
    @PreAuthorize(READ)
    public SyllabusDetail syllabus(@PathVariable UUID id) {
        return syllabi.detail(id);
    }

    @PostMapping("/syllabi")
    @PreAuthorize(EDIT_SYLLABUS)
    @ResponseStatus(HttpStatus.CREATED)
    public SyllabusDetail createSyllabus(@Valid @RequestBody SyllabusForm request) {
        return syllabi.create(request, LessonsAccess.actor());
    }

    @PutMapping("/syllabi/{id}")
    @PreAuthorize(EDIT_SYLLABUS)
    public SyllabusDetail updateSyllabus(@PathVariable UUID id, @Valid @RequestBody SyllabusForm request) {
        return syllabi.update(id, request, LessonsAccess.actor());
    }

    @DeleteMapping("/syllabi/{id}")
    @PreAuthorize(EDIT_SYLLABUS)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSyllabus(@PathVariable UUID id) {
        syllabi.delete(id, LessonsAccess.actor());
    }

    /** Copies one year's syllabi to another year, with the target months moved along. */
    @PostMapping("/syllabi/copy")
    @PreAuthorize(EDIT_SYLLABUS)
    public CopyResult copySyllabi(@Valid @RequestBody CopyForm request) {
        return syllabi.copy(request, LessonsAccess.actor());
    }

    /**
     * Checks (dryRun=true, the default) or imports syllabus CSV rows into a year (the current year by default). A
     * real import with any bad row writes nothing and answers 400 with the row problems.
     */
    @PostMapping("/syllabi/import")
    @PreAuthorize(EDIT_SYLLABUS)
    public ResponseEntity<?> importSyllabi(@RequestParam(defaultValue = "true") boolean dryRun,
            @RequestParam(required = false) UUID yearId, @Valid @RequestBody ImportRequest request) {
        SyllabusImportResult result = syllabusImport.run(request.csv(), yearId, dryRun, LessonsAccess.actor());
        if (!dryRun && !result.committed()) {
            return importProblem(result.invalidRows(), result);
        }
        return ResponseEntity.ok(result);
    }

    static ResponseEntity<ProblemDetail> importProblem(int invalidRows, Object result) {
        ResponseEntity<ProblemDetail> problem = ApiExceptionHandler.problem(HttpStatus.BAD_REQUEST, "Check the file",
                invalidRows + (invalidRows == 1 ? " row needs" : " rows need") + " attention. Nothing was imported.",
                Map.of());
        problem.getBody().setProperty("result", result);
        return problem;
    }

    // ------------------------------------------------------------------ lesson plans

    @GetMapping("/plans")
    @PreAuthorize(READ)
    public List<PlanRow> plans(@RequestParam(required = false) UUID yearId,
            @RequestParam(defaultValue = "false") boolean mine, @RequestParam(required = false) PlanStatus status,
            @RequestParam(required = false) UUID sectionId, @RequestParam(required = false) UUID subjectId,
            @RequestParam(required = false) UUID teacherId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return plans.list(new PlanFilter(yearId, mine, status, sectionId, subjectId, teacherId, from, to),
                CurrentUser.id().orElse(null));
    }

    /** Plans waiting for approval (not the caller's own). */
    @GetMapping("/plans/queue")
    @PreAuthorize(APPROVE)
    public List<PlanRow> queue(@RequestParam(required = false) UUID yearId) {
        return plans.queue(yearId, CurrentUser.requireId());
    }

    @GetMapping("/plans/{id}")
    @PreAuthorize(READ)
    public PlanDetail plan(@PathVariable UUID id) {
        return plans.detail(id, CurrentUser.id().orElse(null));
    }

    @PostMapping("/plans")
    @PreAuthorize(MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public PlanDetail createPlan(@Valid @RequestBody PlanForm request) {
        return plans.create(request, LessonsAccess.actor());
    }

    @PutMapping("/plans/{id}")
    @PreAuthorize(MANAGE)
    public PlanDetail updatePlan(@PathVariable UUID id, @Valid @RequestBody PlanForm request) {
        return plans.update(id, request, LessonsAccess.actor());
    }

    @DeleteMapping("/plans/{id}")
    @PreAuthorize(MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePlan(@PathVariable UUID id) {
        plans.delete(id, LessonsAccess.actor());
    }

    @PostMapping("/plans/{id}/submit")
    @PreAuthorize(MANAGE)
    public PlanDetail submitPlan(@PathVariable UUID id) {
        return plans.submit(id, LessonsAccess.actor(), Instant.now());
    }

    @PostMapping("/plans/{id}/approve")
    @PreAuthorize(APPROVE)
    public PlanDetail approvePlan(@PathVariable UUID id, @Valid @RequestBody(required = false) ReviewForm request) {
        return plans.approve(id, request == null ? null : request.comment(), LessonsAccess.actor(), Instant.now());
    }

    @PostMapping("/plans/{id}/return")
    @PreAuthorize(APPROVE)
    public PlanDetail returnPlan(@PathVariable UUID id, @Valid @RequestBody ReviewForm request) {
        return plans.returnPlan(id, request.comment(), LessonsAccess.actor(), Instant.now());
    }

    @PostMapping("/plans/{id}/taught")
    @PreAuthorize(MANAGE)
    public PlanDetail markTaught(@PathVariable UUID id, @Valid @RequestBody TaughtForm request) {
        return plans.taught(id, request.taughtOn(), LessonsAccess.actor(), LessonsSupport.today());
    }

    @PostMapping("/plans/{id}/untaught")
    @PreAuthorize(MANAGE)
    public PlanDetail markUntaught(@PathVariable UUID id) {
        return plans.untaught(id, LessonsAccess.actor());
    }

    // ------------------------------------------------------------------ coverage

    @GetMapping("/coverage")
    @PreAuthorize(READ)
    public CoverageReport coverage(@RequestParam(required = false) UUID yearId,
            @RequestParam(required = false) UUID classId, @RequestParam(required = false) UUID subjectId,
            @RequestParam(required = false) UUID sectionId, @RequestParam(defaultValue = "false") boolean behindOnly) {
        return coverage.report(new CoverageFilter(yearId, classId, subjectId, sectionId, behindOnly),
                LessonsSupport.today());
    }

    @GetMapping("/coverage.csv")
    @PreAuthorize(READ)
    public ResponseEntity<byte[]> coverageCsv(@RequestParam(required = false) UUID yearId,
            @RequestParam(required = false) UUID classId, @RequestParam(required = false) UUID subjectId,
            @RequestParam(required = false) UUID sectionId, @RequestParam(defaultValue = "false") boolean behindOnly) {
        LocalDate today = LessonsSupport.today();
        return LessonsCsv.download(coverage.csv(new CoverageFilter(yearId, classId, subjectId, sectionId,
                behindOnly), today), "syllabus-coverage-" + today + ".csv");
    }

    /** One section's coverage of a subject, topic by topic. */
    @GetMapping("/coverage/detail")
    @PreAuthorize(READ)
    public CoverageDetail coverageDetail(@RequestParam(required = false) UUID yearId, @RequestParam UUID sectionId,
            @RequestParam UUID subjectId) {
        return coverage.detail(yearId, sectionId, subjectId, LessonsSupport.today());
    }
}
