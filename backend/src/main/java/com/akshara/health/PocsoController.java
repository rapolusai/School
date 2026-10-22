package com.akshara.health;

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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.health.HealthForms.CommitteeForm;
import com.akshara.health.HealthForms.ComplaintForm;
import com.akshara.health.HealthForms.MeetingForm;
import com.akshara.health.HealthForms.MemberForm;
import com.akshara.health.HealthViews.CommitteeView;
import com.akshara.health.HealthViews.ComplaintView;
import com.akshara.health.HealthViews.MeetingView;
import com.akshara.health.HealthViews.MemberView;
import com.akshara.shared.CurrentUser;
import com.akshara.shared.Permissions;

/**
 * The POCSO committee (docs/api/phase-3-health.md). safety.manage keeps the committee and its minutes; the
 * complaints register answers only to pocso.committee or a named member (403 otherwise), every read audited.
 */
@RestController
@RequestMapping("/api/health/pocso")
public class PocsoController {

    private final PocsoService pocso;

    PocsoController(PocsoService pocso) {
        this.pocso = pocso;
    }

    /** Any member of staff may look at who is on the committee (it is published to the school). */
    @GetMapping("/committee")
    @PreAuthorize(HealthAuth.STAFF)
    public CommitteeView committee() {
        return pocso.committee(CurrentUser.requireId(), HealthAuth.hasPocsoPermission(),
                HealthAuth.permissions().contains(Permissions.SAFETY_MANAGE));
    }

    @PutMapping("/committee")
    @PreAuthorize(HealthAuth.SAFETY)
    public CommitteeView saveCommittee(@Valid @RequestBody CommitteeForm form) {
        pocso.saveCommittee(form, HealthAuth.actor());
        return committee();
    }

    @PostMapping("/committee/members")
    @PreAuthorize(HealthAuth.SAFETY)
    @ResponseStatus(HttpStatus.CREATED)
    public MemberView addMember(@Valid @RequestBody MemberForm form) {
        return pocso.addMember(form, HealthAuth.actor());
    }

    @DeleteMapping("/committee/members/{id}")
    @PreAuthorize(HealthAuth.SAFETY)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(@PathVariable UUID id) {
        pocso.removeMember(id, HealthAuth.actor());
    }

    @PostMapping("/committee/meetings")
    @PreAuthorize(HealthAuth.SAFETY)
    @ResponseStatus(HttpStatus.CREATED)
    public MeetingView addMeeting(@Valid @RequestBody MeetingForm form) {
        return pocso.addMeeting(form, HealthAuth.actor());
    }

    @GetMapping("/complaints")
    @PreAuthorize(HealthAuth.STAFF)
    public List<ComplaintView> complaints() {
        committeeOnly();
        return pocso.complaints(HealthAuth.actor());
    }

    @GetMapping("/complaints/{id}")
    @PreAuthorize(HealthAuth.STAFF)
    public ComplaintView complaint(@PathVariable UUID id) {
        committeeOnly();
        return pocso.complaint(id, HealthAuth.actor());
    }

    @PostMapping("/complaints")
    @PreAuthorize(HealthAuth.STAFF)
    @ResponseStatus(HttpStatus.CREATED)
    public ComplaintView addComplaint(@Valid @RequestBody ComplaintForm form) {
        committeeOnly();
        return pocso.addComplaint(form, HealthAuth.actor());
    }

    @PutMapping("/complaints/{id}")
    @PreAuthorize(HealthAuth.STAFF)
    public ComplaintView updateComplaint(@PathVariable UUID id, @Valid @RequestBody ComplaintForm form) {
        committeeOnly();
        return pocso.updateComplaint(id, form, HealthAuth.actor());
    }

    private void committeeOnly() {
        pocso.requireCommittee(CurrentUser.requireId(), HealthAuth.hasPocsoPermission());
    }
}
