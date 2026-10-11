package com.akshara.exams;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
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

import com.akshara.audit.AuditService.Actor;
import com.akshara.exams.ExamsForms.ClassSchemeForm;
import com.akshara.exams.ExamsForms.ExamForm;
import com.akshara.exams.ExamsForms.PapersForm;
import com.akshara.exams.ExamsForms.SchemeForm;
import com.akshara.exams.ExamsForms.SettingsForm;
import com.akshara.exams.ExamsForms.TermForm;
import com.akshara.exams.ExamsForms.WeightsForm;
import com.akshara.exams.ExamsViews.ClassSchemes;
import com.akshara.exams.ExamsViews.ExamClassDetail;
import com.akshara.exams.ExamsViews.HallTickets;
import com.akshara.exams.ExamsViews.Scheme;
import com.akshara.exams.ExamsViews.Settings;
import com.akshara.exams.ExamsViews.Structure;
import com.akshara.shared.CurrentUser;

/**
 * Exam setup for staff: grading schemes and settings, the scheme of each class, terms and exams with their weightage,
 * each class's papers (the date sheet), publishing date sheets and hall tickets. Reading needs exams.read; changing
 * needs exams.setup (School Admin and Principal by default). Ids of another school are 404.
 */
@RestController
@RequestMapping("/api/exams")
public class ExamsController {

    static final String READ = "hasAuthority('exams.read')";
    static final String SETUP = "hasAuthority('exams.setup')";
    static final String PUBLISH = "hasAuthority('exams.publish')";
    /** Marks entry: exams.manage (own sections and subjects) or exams.publish (every section). */
    static final String ENTER = "hasAnyAuthority('exams.manage', 'exams.publish')";
    /** Reading marks: whoever may read results or enter marks. */
    static final String READ_OR_ENTER = "hasAnyAuthority('exams.read', 'exams.manage', 'exams.publish')";

    private final GradingService grading;
    private final ExamSetupService setup;

    ExamsController(GradingService grading, ExamSetupService setup) {
        this.grading = grading;
        this.setup = setup;
    }

    // ------------------------------------------------------------------ grading

    @GetMapping("/grading-schemes")
    @PreAuthorize(READ)
    public List<Scheme> schemes() {
        return grading.list();
    }

    @GetMapping("/grading-schemes/{id}")
    @PreAuthorize(READ)
    public Scheme scheme(@PathVariable UUID id) {
        return grading.scheme(id);
    }

    @PostMapping("/grading-schemes")
    @PreAuthorize(SETUP)
    @ResponseStatus(HttpStatus.CREATED)
    public Scheme createScheme(@Valid @RequestBody SchemeForm request) {
        return grading.create(request, actor());
    }

    @PutMapping("/grading-schemes/{id}")
    @PreAuthorize(SETUP)
    public Scheme updateScheme(@PathVariable UUID id, @Valid @RequestBody SchemeForm request) {
        return grading.update(id, request, actor());
    }

    @DeleteMapping("/grading-schemes/{id}")
    @PreAuthorize(SETUP)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteScheme(@PathVariable UUID id) {
        grading.delete(id, actor());
    }

    /** Adds back any of the built-in schemes (CBSE, ICSE, State) the school deleted or renamed away. */
    @PostMapping("/grading-schemes/restore")
    @PreAuthorize(SETUP)
    public List<Scheme> restoreSchemes() {
        return grading.restoreBuiltInSchemes(actor());
    }

    @GetMapping("/settings")
    @PreAuthorize(READ)
    public Settings settings() {
        return grading.settings();
    }

    @PutMapping("/settings")
    @PreAuthorize(SETUP)
    public Settings updateSettings(@Valid @RequestBody SettingsForm request) {
        return grading.updateSettings(request, actor());
    }

    /** The grading scheme of each class in a year (the current year by default). */
    @GetMapping("/class-schemes")
    @PreAuthorize(READ)
    public ClassSchemes classSchemes(@RequestParam(required = false) UUID yearId) {
        return grading.classSchemes(yearId);
    }

    @PutMapping("/class-schemes")
    @PreAuthorize(SETUP)
    public ClassSchemes assignSchemes(@RequestParam(required = false) UUID yearId,
            @Valid @RequestBody ClassSchemeForm request) {
        return grading.assign(yearId, request, actor());
    }

    // ------------------------------------------------------------------ terms and exams

    /** A year's terms and exams (the current year by default). */
    @GetMapping("/structure")
    @PreAuthorize(READ)
    public Structure structure(@RequestParam(required = false) UUID yearId) {
        return setup.structure(yearId);
    }

    @PostMapping("/terms")
    @PreAuthorize(SETUP)
    @ResponseStatus(HttpStatus.CREATED)
    public Structure createTerm(@Valid @RequestBody TermForm request) {
        return setup.createTerm(request, actor());
    }

    @PutMapping("/terms/{id}")
    @PreAuthorize(SETUP)
    public Structure updateTerm(@PathVariable UUID id, @Valid @RequestBody TermForm request) {
        return setup.updateTerm(id, request, actor());
    }

    @DeleteMapping("/terms/{id}")
    @PreAuthorize(SETUP)
    public Structure deleteTerm(@PathVariable UUID id) {
        return setup.deleteTerm(id, actor());
    }

    /** The weight of each term of the year in the final result; they must add up to 100. */
    @PutMapping("/years/{yearId}/term-weights")
    @PreAuthorize(SETUP)
    public Structure termWeights(@PathVariable UUID yearId, @Valid @RequestBody WeightsForm request) {
        return setup.setTermWeights(yearId, request, actor());
    }

    /** The weight of each exam of the term in the term result; they must add up to 100. */
    @PutMapping("/terms/{termId}/exam-weights")
    @PreAuthorize(SETUP)
    public Structure examWeights(@PathVariable UUID termId, @Valid @RequestBody WeightsForm request) {
        return setup.setExamWeights(termId, request, actor());
    }

    @PostMapping
    @PreAuthorize(SETUP)
    @ResponseStatus(HttpStatus.CREATED)
    public Structure createExam(@Valid @RequestBody ExamForm request) {
        return setup.createExam(request, actor());
    }

    @PutMapping("/{id}")
    @PreAuthorize(SETUP)
    public Structure updateExam(@PathVariable UUID id, @Valid @RequestBody ExamForm request) {
        return setup.updateExam(id, request, actor());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(SETUP)
    public Structure deleteExam(@PathVariable UUID id) {
        return setup.deleteExam(id, actor());
    }

    // ------------------------------------------------------------------ papers and date sheets

    @GetMapping("/{examId}/classes/{classId}")
    @PreAuthorize(READ)
    public ExamClassDetail examClass(@PathVariable UUID examId, @PathVariable UUID classId) {
        return setup.examClass(examId, classId);
    }

    /** Replaces the class's papers: subjects, maximum marks and the date sheet slots. */
    @PutMapping("/{examId}/classes/{classId}/papers")
    @PreAuthorize(SETUP)
    public ExamClassDetail papers(@PathVariable UUID examId, @PathVariable UUID classId,
            @Valid @RequestBody PapersForm request) {
        return setup.savePapers(examId, classId, request, actor());
    }

    /** Shows the date sheet to families and adds the papers to the school calendar. */
    @PostMapping("/{examId}/classes/{classId}/date-sheet/publish")
    @PreAuthorize(SETUP)
    public ExamClassDetail publishDateSheet(@PathVariable UUID examId, @PathVariable UUID classId) {
        return setup.publishDateSheet(examId, classId, actor());
    }

    @PostMapping("/{examId}/classes/{classId}/date-sheet/withdraw")
    @PreAuthorize(SETUP)
    public ExamClassDetail withdrawDateSheet(@PathVariable UUID examId, @PathVariable UUID classId) {
        return setup.withdrawDateSheet(examId, classId, actor());
    }

    /** Hall tickets of every student of the class, or of one section. */
    @GetMapping("/{examId}/classes/{classId}/hall-tickets")
    @PreAuthorize(READ)
    public HallTickets hallTickets(@PathVariable UUID examId, @PathVariable UUID classId,
            @RequestParam(required = false) UUID sectionId) {
        return setup.hallTickets(examId, classId, sectionId);
    }

    static Actor actor() {
        return new Actor(CurrentUser.requireId(), CurrentUser.name().orElse("Staff"));
    }
}
