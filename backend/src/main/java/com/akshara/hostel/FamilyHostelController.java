package com.akshara.hostel;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.audit.AuditService.Actor;
import com.akshara.hostel.HostelForms.PassRequest;
import com.akshara.hostel.HostelViews.FamilyHostel;
import com.akshara.hostel.HostelViews.PassView;
import com.akshara.shared.CurrentUser;

/**
 * The family app's hostel pages (docs/api/phase-3-hostel.md): a parent's boarding child, their outing and
 * home-leave passes, and requesting or cancelling one. A child who is not one of the parent's answers 404.
 */
@RestController
@RequestMapping("/api/me")
@PreAuthorize(HostelAuth.PARENT)
public class FamilyHostelController {

    private final FamilyHostelService family;
    private final HostelPassService passes;

    FamilyHostelController(FamilyHostelService family, HostelPassService passes) {
        this.family = family;
        this.passes = passes;
    }

    private static Actor parent() {
        return new Actor(CurrentUser.requireId(), CurrentUser.name().orElse("Parent"));
    }

    @GetMapping("/children/{studentId}/hostel")
    public FamilyHostel child(@PathVariable UUID studentId) {
        return family.ofChild(CurrentUser.requireId(), studentId);
    }

    @GetMapping("/children/{studentId}/hostel-passes")
    public List<PassView> passes(@PathVariable UUID studentId) {
        return passes.ofChild(CurrentUser.requireId(), studentId);
    }

    @PostMapping("/children/{studentId}/hostel-passes")
    @ResponseStatus(HttpStatus.CREATED)
    public PassView request(@PathVariable UUID studentId, @Valid @RequestBody PassRequest form) {
        return passes.request(CurrentUser.requireId(), studentId, form, parent());
    }

    @PostMapping("/children/{studentId}/hostel-passes/{id}/cancel")
    public PassView cancel(@PathVariable UUID studentId, @PathVariable UUID id) {
        return passes.cancel(CurrentUser.requireId(), studentId, id, parent());
    }
}
