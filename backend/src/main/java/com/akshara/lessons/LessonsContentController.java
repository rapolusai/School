package com.akshara.lessons;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.akshara.files.FileUploads;
import com.akshara.lessons.LessonsForms.ImportRequest;
import com.akshara.lessons.LessonsForms.MaterialForm;
import com.akshara.lessons.LessonsForms.QuestionForm;
import com.akshara.lessons.LessonsViews.MaterialView;
import com.akshara.lessons.LessonsViews.QuestionImportResult;
import com.akshara.lessons.LessonsViews.QuestionView;
import com.akshara.lessons.MaterialService.MaterialFilter;
import com.akshara.lessons.QuestionService.QuestionFilter;
import com.akshara.shared.CurrentUser;

/**
 * Study material and the question bank for staff. Reading material needs lessons.read (or manage / approve); adding
 * and changing material and questions needs lessons.manage (a teacher changes their own). The question bank is for
 * staff who manage lessons only.
 */
@RestController
@RequestMapping("/api/lessons")
public class LessonsContentController {

    private final MaterialService materials;
    private final QuestionService questions;
    private final QuestionImportService questionImport;

    LessonsContentController(MaterialService materials, QuestionService questions,
            QuestionImportService questionImport) {
        this.materials = materials;
        this.questions = questions;
        this.questionImport = questionImport;
    }

    // ------------------------------------------------------------------ study material

    @GetMapping("/material")
    @PreAuthorize(LessonsController.READ)
    public List<MaterialView> material(@RequestParam(required = false) UUID yearId,
            @RequestParam(required = false) UUID classId, @RequestParam(required = false) UUID sectionId,
            @RequestParam(required = false) UUID subjectId, @RequestParam(defaultValue = "false") boolean mine) {
        return materials.list(new MaterialFilter(yearId, classId, sectionId, subjectId, mine),
                CurrentUser.id().orElse(null), LessonsSupport.today());
    }

    @GetMapping("/material/{id}")
    @PreAuthorize(LessonsController.READ)
    public MaterialView materialDetail(@PathVariable UUID id) {
        return materials.detail(id, LessonsSupport.today());
    }

    @PostMapping("/material")
    @PreAuthorize(LessonsController.MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public MaterialView createMaterial(@Valid @RequestBody MaterialForm request) {
        return materials.create(request, LessonsAccess.actor(), LessonsSupport.today());
    }

    @PutMapping("/material/{id}")
    @PreAuthorize(LessonsController.MANAGE)
    public MaterialView updateMaterial(@PathVariable UUID id, @Valid @RequestBody MaterialForm request) {
        return materials.update(id, request, LessonsAccess.actor(), LessonsSupport.today());
    }

    @DeleteMapping("/material/{id}")
    @PreAuthorize(LessonsController.MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMaterial(@PathVariable UUID id) {
        materials.delete(id, LessonsAccess.actor());
    }

    /** Adds a file (multipart part "file"; at most 5 per piece, 5 MB each, the same types as homework files). */
    @PostMapping(value = "/material/{id}/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(LessonsController.MANAGE)
    public MaterialView addFile(@PathVariable UUID id, @RequestPart("file") MultipartFile file) {
        return materials.addFile(id, FileUploads.read(file, "file"), LessonsAccess.actor(), LessonsSupport.today());
    }

    @DeleteMapping("/material/{id}/files/{fileId}")
    @PreAuthorize(LessonsController.MANAGE)
    public MaterialView removeFile(@PathVariable UUID id, @PathVariable UUID fileId) {
        return materials.removeFile(id, fileId, LessonsAccess.actor(), LessonsSupport.today());
    }

    // ------------------------------------------------------------------ question bank

    @GetMapping("/questions")
    @PreAuthorize(LessonsController.MANAGE)
    public List<QuestionView> questions(@RequestParam(required = false) UUID classId,
            @RequestParam(required = false) UUID subjectId, @RequestParam(required = false) UUID topicId,
            @RequestParam(required = false) QuestionType type, @RequestParam(required = false) Difficulty difficulty,
            @RequestParam(required = false) String q, @RequestParam(defaultValue = "false") boolean mine) {
        return questions.list(new QuestionFilter(classId, subjectId, topicId, type, difficulty, q, mine));
    }

    @GetMapping("/questions/{id}")
    @PreAuthorize(LessonsController.MANAGE)
    public QuestionView question(@PathVariable UUID id) {
        return questions.detail(id);
    }

    @PostMapping("/questions")
    @PreAuthorize(LessonsController.MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public QuestionView createQuestion(@Valid @RequestBody QuestionForm request) {
        return questions.create(request, LessonsAccess.actor());
    }

    @PutMapping("/questions/{id}")
    @PreAuthorize(LessonsController.MANAGE)
    public QuestionView updateQuestion(@PathVariable UUID id, @Valid @RequestBody QuestionForm request) {
        return questions.update(id, request, LessonsAccess.actor());
    }

    @DeleteMapping("/questions/{id}")
    @PreAuthorize(LessonsController.MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteQuestion(@PathVariable UUID id) {
        questions.delete(id, LessonsAccess.actor());
    }

    /**
     * Checks (dryRun=true, the default) or imports question bank CSV rows. A real import with any bad row writes
     * nothing and answers 400 with the row problems.
     */
    @PostMapping("/questions/import")
    @PreAuthorize(LessonsController.MANAGE)
    public ResponseEntity<?> importQuestions(@RequestParam(defaultValue = "true") boolean dryRun,
            @Valid @RequestBody ImportRequest request) {
        QuestionImportResult result = questionImport.run(request.csv(), dryRun, LessonsAccess.actor());
        if (!dryRun && !result.committed()) {
            return LessonsController.importProblem(result.invalidRows(), result);
        }
        return ResponseEntity.ok(result);
    }
}
