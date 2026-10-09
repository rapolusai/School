package com.akshara.admissions;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.admissions.AdmissionForms.AdmitRequest;
import com.akshara.admissions.AdmissionForms.ApplicationRequest;
import com.akshara.admissions.AdmissionForms.FeeRequest;
import com.akshara.admissions.AdmissionForms.NoteRequest;
import com.akshara.admissions.AdmissionForms.OfferRequest;
import com.akshara.admissions.AdmissionForms.OutcomeRequest;
import com.akshara.admissions.AdmissionForms.SlotRequest;
import com.akshara.admissions.AdmissionForms.StageRequest;
import com.akshara.admissions.AdmissionsService.ApplicationDetail;
import com.akshara.admissions.AdmissionsService.ApplicationPage;
import com.akshara.admissions.AdmissionsService.ApplicationQuery;
import com.akshara.admissions.AdmissionsService.BoardView;
import com.akshara.admissions.AdmissionsService.StaffRef;
import com.akshara.admissions.AdmissionsService.Summary;
import com.akshara.admissions.AdmissionsService.UpcomingSlot;

/** The admissions pipeline. Reading needs admissions.read; every change needs admissions.manage. */
@RestController
@RequestMapping("/api/admissions")
public class AdmissionsController {

    static final String READ = "hasAuthority('admissions.read')";
    static final String MANAGE = "hasAuthority('admissions.manage')";

    private final AdmissionsService admissions;

    public AdmissionsController(AdmissionsService admissions) {
        this.admissions = admissions;
    }

    @GetMapping("/summary")
    @PreAuthorize(READ)
    public Summary summary() {
        return admissions.summary();
    }

    @GetMapping("/board")
    @PreAuthorize(READ)
    public BoardView board(
            @RequestParam(required = false) UUID yearId,
            @RequestParam(required = false) UUID classId,
            @RequestParam(required = false) ApplicationSource source,
            @RequestParam(required = false) @Size(max = 100) String q) {
        return admissions.board(new ApplicationQuery(yearId, classId, null, source, q, 0, AdmissionsService.LANE_LIMIT));
    }

    @GetMapping("/applications")
    @PreAuthorize(READ)
    public ApplicationPage list(
            @RequestParam(required = false) UUID yearId,
            @RequestParam(required = false) UUID classId,
            @RequestParam(required = false) ApplicationStage stage,
            @RequestParam(required = false) ApplicationSource source,
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100_000) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(AdmissionsService.MAX_PAGE_SIZE) int size) {
        return admissions.list(new ApplicationQuery(yearId, classId, stage, source, q, page, size));
    }

    @GetMapping("/applications/{id}")
    @PreAuthorize(READ)
    public ApplicationDetail get(@PathVariable UUID id) {
        return admissions.detail(id);
    }

    @GetMapping("/slots/upcoming")
    @PreAuthorize(READ)
    public List<UpcomingSlot> upcomingSlots(@RequestParam(defaultValue = "14") @Min(1) @Max(366) int days) {
        return admissions.upcomingSlots(days);
    }

    /** Staff who can be a counsellor or an interviewer. */
    @GetMapping("/staff")
    @PreAuthorize(READ)
    public List<StaffRef> staff() {
        return admissions.staff();
    }

    @PostMapping("/applications")
    @PreAuthorize(MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApplicationDetail create(@Valid @RequestBody ApplicationRequest request) {
        return admissions.create(request, null);
    }

    @PutMapping("/applications/{id}")
    @PreAuthorize(MANAGE)
    public ApplicationDetail update(@PathVariable UUID id, @Valid @RequestBody ApplicationRequest request) {
        return admissions.update(id, request, null);
    }

    @PostMapping("/applications/{id}/stage")
    @PreAuthorize(MANAGE)
    public ApplicationDetail moveStage(@PathVariable UUID id, @Valid @RequestBody StageRequest request) {
        return admissions.moveStage(id, request, null);
    }

    @PostMapping("/applications/{id}/notes")
    @PreAuthorize(MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApplicationDetail addNote(@PathVariable UUID id, @Valid @RequestBody NoteRequest request) {
        return admissions.addNote(id, request.note(), null);
    }

    @PutMapping("/applications/{id}/fee")
    @PreAuthorize(MANAGE)
    public ApplicationDetail recordFee(@PathVariable UUID id, @Valid @RequestBody FeeRequest request) {
        return admissions.recordFee(id, request, null);
    }

    @PutMapping("/applications/{id}/offer")
    @PreAuthorize(MANAGE)
    public ApplicationDetail updateOffer(@PathVariable UUID id, @Valid @RequestBody OfferRequest request) {
        return admissions.updateOffer(id, request, null);
    }

    @PostMapping("/applications/{id}/slots")
    @PreAuthorize(MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApplicationDetail scheduleSlot(@PathVariable UUID id, @Valid @RequestBody SlotRequest request) {
        return admissions.scheduleSlot(id, request, null);
    }

    @PutMapping("/applications/{id}/slots/{slotId}")
    @PreAuthorize(MANAGE)
    public ApplicationDetail rescheduleSlot(@PathVariable UUID id, @PathVariable UUID slotId,
            @Valid @RequestBody SlotRequest request) {
        return admissions.rescheduleSlot(id, slotId, request, null);
    }

    @PostMapping("/applications/{id}/slots/{slotId}/outcome")
    @PreAuthorize(MANAGE)
    public ApplicationDetail recordOutcome(@PathVariable UUID id, @PathVariable UUID slotId,
            @Valid @RequestBody OutcomeRequest request) {
        return admissions.recordOutcome(id, slotId, request.notes(), null);
    }

    @PostMapping("/applications/{id}/slots/{slotId}/cancel")
    @PreAuthorize(MANAGE)
    public ApplicationDetail cancelSlot(@PathVariable UUID id, @PathVariable UUID slotId) {
        return admissions.cancelSlot(id, slotId, null);
    }

    /** Admits an offered application as a student. Repeating the call returns the same student. */
    @PostMapping("/applications/{id}/admit")
    @PreAuthorize(MANAGE)
    public ApplicationDetail admit(@PathVariable UUID id, @Valid @RequestBody AdmitRequest request) {
        return admissions.admit(id, request, null);
    }
}
