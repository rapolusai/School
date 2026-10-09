package com.akshara.homework;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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

import com.akshara.audit.AuditService.Actor;
import com.akshara.files.FileUploads;
import com.akshara.homework.HomeworkService.HomeworkInput;
import com.akshara.homework.HomeworkViews.FileRef;
import com.akshara.homework.HomeworkViews.HomeworkDetail;
import com.akshara.homework.HomeworkViews.HomeworkOptions;
import com.akshara.homework.HomeworkViews.HomeworkPage;
import com.akshara.homework.HomeworkViews.HomeworkSettingsView;
import com.akshara.homework.HomeworkViews.Tracker;
import com.akshara.homework.HomeworkViews.TrackerRow;
import com.akshara.shared.CurrentUser;

/**
 * Homework for staff with homework.manage: School Admin and Principal for every section; teachers for the sections
 * and subjects they teach (timetable) and the sections they are class teacher of. Homework outside the caller's
 * sections is 404; changing homework they can see but do not own is 403.
 */
@RestController
@RequestMapping("/api/homework")
public class HomeworkController {

    static final String MANAGE = "hasAuthority('homework.manage')";

    private final HomeworkAccess access;
    private final HomeworkService homework;

    HomeworkController(HomeworkAccess access, HomeworkService homework) {
        this.access = access;
        this.homework = homework;
    }

    public record HomeworkRequest(@NotNull @Size(min = 1, max = HomeworkService.MAX_SECTIONS) List<UUID> sectionIds,
            @NotNull UUID subjectId, @NotNull @Size(max = 200) String title,
            @Size(max = HomeworkService.MAX_TEXT) String instructions, LocalDate assignedOn, @NotNull LocalDate dueOn,
            @NotNull Boolean onlineSubmission) {

        HomeworkInput input() {
            return new HomeworkInput(sectionIds, subjectId, title, instructions, assignedOn, dueOn, onlineSubmission);
        }
    }

    public record ReviewRequest(@NotNull SubmissionStatus status, @Size(max = 10) String grade,
            @Size(max = 500) String remark) {
    }

    public record SettingsRequest(@NotNull Boolean remindersEnabled) {
    }

    /** The sections and subjects the caller may set homework for. */
    @GetMapping("/options")
    @PreAuthorize(MANAGE)
    public HomeworkOptions options() {
        return homework.options(access.current());
    }

    /** when: open (due today or later, the default), past or all. */
    @GetMapping
    @PreAuthorize(MANAGE)
    public HomeworkPage list(@RequestParam(required = false) UUID sectionId,
            @RequestParam(required = false) UUID subjectId, @RequestParam(required = false) String when,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return homework.list(access.current(), sectionId, subjectId, when, page, size, HomeworkService.today());
    }

    @PostMapping
    @PreAuthorize(MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public HomeworkDetail create(@Valid @RequestBody HomeworkRequest request) {
        return homework.create(access.current(), request.input(), actor(), Instant.now(), HomeworkService.today());
    }

    @GetMapping("/{id}")
    @PreAuthorize(MANAGE)
    public HomeworkDetail detail(@PathVariable UUID id) {
        return homework.detail(access.current(), id, HomeworkService.today());
    }

    /** Changes homework until its due date. */
    @PutMapping("/{id}")
    @PreAuthorize(MANAGE)
    public HomeworkDetail update(@PathVariable UUID id, @Valid @RequestBody HomeworkRequest request) {
        return homework.update(access.current(), id, request.input(), HomeworkService.today());
    }

    /** Deletes homework nobody has submitted yet. */
    @DeleteMapping("/{id}")
    @PreAuthorize(MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        homework.delete(access.current(), id);
    }

    /** Adds one file (multipart part "file"): PDF, image, Office or text, at most 5 MB. */
    @PostMapping(value = "/{id}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public FileRef addAttachment(@PathVariable UUID id, @RequestPart("file") MultipartFile file) {
        return homework.addAttachment(access.current(), id, FileUploads.read(file, "file"), actor(),
                HomeworkService.today());
    }

    @DeleteMapping("/{id}/attachments/{fileId}")
    @PreAuthorize(MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeAttachment(@PathVariable UUID id, @PathVariable UUID fileId) {
        homework.removeAttachment(access.current(), id, fileId, HomeworkService.today());
    }

    /** Who has submitted, late or not, and who has not: the tracker. */
    @GetMapping("/{id}/submissions")
    @PreAuthorize(MANAGE)
    public Tracker tracker(@PathVariable UUID id) {
        return homework.tracker(access.current(), id, HomeworkService.today());
    }

    @PutMapping("/{id}/submissions/{submissionId}/review")
    @PreAuthorize(MANAGE)
    public TrackerRow review(@PathVariable UUID id, @PathVariable UUID submissionId,
            @Valid @RequestBody ReviewRequest request) {
        return homework.review(access.current(), id, submissionId, request.status(), request.grade(),
                request.remark(), actor(), Instant.now());
    }

    @GetMapping("/settings")
    @PreAuthorize("hasAnyAuthority('homework.manage', 'settings.manage')")
    public HomeworkSettingsView settings() {
        return homework.settings();
    }

    /** Switches reminders to parents on or off (off by default). */
    @PutMapping("/settings")
    @PreAuthorize("hasAuthority('settings.manage')")
    public HomeworkSettingsView updateSettings(@Valid @RequestBody SettingsRequest request) {
        return homework.updateSettings(request.remindersEnabled());
    }

    static Actor actor() {
        return new Actor(CurrentUser.id().orElse(null), CurrentUser.name().orElse(null));
    }
}
