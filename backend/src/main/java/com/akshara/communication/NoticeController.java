package com.akshara.communication;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
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
import org.springframework.web.bind.annotation.RestController;

import com.akshara.communication.CircularService.AudienceOptions;
import com.akshara.communication.CircularService.CircularDetail;
import com.akshara.communication.CircularService.CircularList;
import com.akshara.communication.CircularService.Estimate;
import com.akshara.communication.CommunicationSettingsService.SettingsView;
import com.akshara.communication.CommunicationTypes.Status;
import com.akshara.communication.NoticeForms.ApproveRequest;
import com.akshara.communication.NoticeForms.CircularRequest;
import com.akshara.communication.NoticeForms.EstimateRequest;
import com.akshara.communication.NoticeForms.RejectRequest;
import com.akshara.communication.NoticeForms.SettingsRequest;
import com.akshara.communication.NoticeForms.WithdrawRequest;

/**
 * Writing and sending circulars. notices.send writes, submits, takes back and withdraws (without notices.approve:
 * only one's own circulars, to one's own class-teacher sections); notices.approve approves or sends back circulars
 * waiting for approval and sees every circular. The school's settings need settings.manage.
 */
@RestController
@RequestMapping("/api/notices")
public class NoticeController {

    static final String SEND = "hasAuthority('notices.send')";
    static final String APPROVE = "hasAuthority('notices.approve')";
    static final String SETTINGS = "hasAuthority('settings.manage')";

    private final NoticeAccess access;
    private final CircularService circulars;
    private final CommunicationSettingsService settings;

    NoticeController(NoticeAccess access, CircularService circulars, CommunicationSettingsService settings) {
        this.access = access;
        this.circulars = circulars;
        this.settings = settings;
    }

    /** The caller's circulars (every circular for approvers), newest change first, with counts per status. */
    @GetMapping
    @PreAuthorize(SEND)
    public CircularList list(@RequestParam(required = false) Status status) {
        return circulars.list(access.current(), status);
    }

    @GetMapping("/audience-options")
    @PreAuthorize(SEND)
    public AudienceOptions audienceOptions() {
        return circulars.options(access.current());
    }

    /** How many people and messages the audience and channels come to, and the indicative cost. */
    @PostMapping("/estimate")
    @PreAuthorize(SEND)
    public Estimate estimate(@Valid @RequestBody EstimateRequest request) {
        return circulars.estimate(access.current(), request);
    }

    @GetMapping("/{id}")
    @PreAuthorize(SEND)
    public CircularDetail detail(@PathVariable UUID id) {
        return circulars.detail(access.current(), id);
    }

    @PostMapping
    @PreAuthorize(SEND)
    public ResponseEntity<CircularDetail> create(@Valid @RequestBody CircularRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(circulars.create(access.current(), request,
                Instant.now()));
    }

    @PutMapping("/{id}")
    @PreAuthorize(SEND)
    public CircularDetail update(@PathVariable UUID id, @Valid @RequestBody CircularRequest request) {
        return circulars.update(access.current(), id, request, Instant.now());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(SEND)
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        circulars.delete(access.current(), id);
        return ResponseEntity.noContent().build();
    }

    /** Sends the draft, schedules it, or (when the caller needs approval) asks for approval. */
    @PostMapping("/{id}/submit")
    @PreAuthorize(SEND)
    public CircularDetail submit(@PathVariable UUID id) {
        return circulars.submit(access.current(), id, Instant.now());
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize(APPROVE)
    public CircularDetail approve(@PathVariable UUID id, @Valid @RequestBody(required = false) ApproveRequest request) {
        return circulars.approve(access.current(), id, request == null ? null : request.note(), Instant.now());
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize(APPROVE)
    public CircularDetail reject(@PathVariable UUID id, @Valid @RequestBody RejectRequest request) {
        return circulars.reject(access.current(), id, request.note(), Instant.now());
    }

    /** Takes back a request for approval or a schedule; the circular becomes a draft again. */
    @PostMapping("/{id}/cancel")
    @PreAuthorize(SEND)
    public CircularDetail cancel(@PathVariable UUID id) {
        return circulars.cancel(access.current(), id);
    }

    @PostMapping("/{id}/withdraw")
    @PreAuthorize(SEND)
    public CircularDetail withdraw(@PathVariable UUID id, @Valid @RequestBody WithdrawRequest request) {
        return circulars.withdraw(access.current(), id, request.reason(), Instant.now());
    }

    @GetMapping("/settings")
    @PreAuthorize(SETTINGS)
    public SettingsView settings() {
        return settings.current();
    }

    @PutMapping("/settings")
    @PreAuthorize(SETTINGS)
    public SettingsView updateSettings(@Valid @RequestBody SettingsRequest request) {
        return settings.update(request.teacherCircularsNeedApproval(), request.enquiryAckEnabled(),
                request.enquiryAckChannel(), access.current().actor());
    }
}
