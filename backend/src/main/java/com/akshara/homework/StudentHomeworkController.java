package com.akshara.homework;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.akshara.files.FileUploads;
import com.akshara.files.IncomingFile;
import com.akshara.homework.HomeworkViews.StudentHomework;
import com.akshara.homework.HomeworkViews.StudentHomeworkDetail;
import com.akshara.shared.ApiException;
import com.akshara.shared.CurrentUser;

/**
 * Homework for students (their own section; they can submit) and parents (their own children; read-only). A sign-in
 * without a linked student record, or a child who is not the parent's, is 404.
 */
@RestController
public class StudentHomeworkController {

    private final StudentHomeworkService learners;

    StudentHomeworkController(StudentHomeworkService learners) {
        this.learners = learners;
    }

    @GetMapping("/api/me/homework")
    @PreAuthorize("hasAuthority('dashboard.view')")
    public StudentHomework mine() {
        return learners.mine(CurrentUser.requireId(), HomeworkService.today());
    }

    @GetMapping("/api/me/homework/{id}")
    @PreAuthorize("hasAuthority('dashboard.view')")
    public StudentHomeworkDetail detail(@PathVariable UUID id) {
        return learners.detail(CurrentUser.requireId(), id, HomeworkService.today());
    }

    /**
     * Submits or resubmits (multipart): "text" (optional), "files" (up to 5 new files) and "keepFileIds" (files of
     * the previous attempt to keep). At least some text or one file is needed.
     */
    @PostMapping(value = "/api/me/homework/{id}/submission", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('dashboard.view')")
    public StudentHomeworkDetail submit(@PathVariable UUID id, @RequestParam(required = false) String text,
            @RequestParam(name = "files", required = false) List<MultipartFile> files,
            @RequestParam(name = "keepFileIds", required = false) List<UUID> keepFileIds) {
        List<MultipartFile> parts = files == null ? List.of()
                : files.stream().filter(f -> f != null && !f.isEmpty()).toList();
        if (parts.size() > StudentHomeworkService.MAX_FILES) {
            throw ApiException.badRequest("Attach at most " + StudentHomeworkService.MAX_FILES + " files.", "files");
        }
        List<IncomingFile> incoming = parts.stream().map(f -> FileUploads.read(f, "files")).toList();
        return learners.submit(CurrentUser.requireId(), id, text, keepFileIds, incoming, HomeworkController.actor(),
                Instant.now());
    }

    @GetMapping("/api/me/children/{studentId}/homework")
    @PreAuthorize("hasAuthority('child.view')")
    public StudentHomework child(@PathVariable UUID studentId) {
        return learners.childHomework(CurrentUser.requireId(), studentId, HomeworkService.today());
    }

    @GetMapping("/api/me/children/{studentId}/homework/{id}")
    @PreAuthorize("hasAuthority('child.view')")
    public StudentHomeworkDetail childDetail(@PathVariable UUID studentId, @PathVariable UUID id) {
        return learners.childDetail(CurrentUser.requireId(), studentId, id, HomeworkService.today());
    }
}
