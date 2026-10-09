package com.akshara.privacy;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.audit.AuditService.Actor;
import com.akshara.privacy.PrivacyForms.AcceptForm;
import com.akshara.privacy.PrivacyForms.ConsentChange;
import com.akshara.privacy.PrivacyForms.ReplyForm;
import com.akshara.privacy.PrivacyForms.RequestForm;
import com.akshara.privacy.PrivacyViews.ParentPrivacy;
import com.akshara.privacy.PrivacyViews.RequestDetail;
import com.akshara.privacy.PrivacyViews.RequestRow;
import com.akshara.shared.CurrentUser;

/**
 * A parent's own privacy page: the current notice, consent for each of their children, and their data requests.
 * Another family's child or request is 404, exactly like one that does not exist.
 */
@RestController
@RequestMapping("/api/me/privacy")
@PreAuthorize("hasAuthority('child.view')")
public class MyPrivacyController {

    private final ConsentService consents;
    private final DataRequestService requests;
    private final DataExportService exports;

    MyPrivacyController(ConsentService consents, DataRequestService requests, DataExportService exports) {
        this.consents = consents;
        this.requests = requests;
        this.exports = exports;
    }

    /** The notice, each child's consent, and whether the notice still has to be accepted. */
    @GetMapping
    public ParentPrivacy privacy() {
        return consents.forParent(CurrentUser.requireId());
    }

    /** Accepts the current notice for the listed children, with the optional choices for each. */
    @PostMapping("/consent")
    public ParentPrivacy accept(@Valid @RequestBody AcceptForm request) {
        return consents.accept(CurrentUser.requireId(), request, actor());
    }

    /** Gives or withdraws an optional purpose (PHOTOS or WHATSAPP) for one child. */
    @PutMapping("/children/{studentId}/consents/{purpose}")
    public ParentPrivacy change(@PathVariable UUID studentId, @PathVariable PrivacyPurpose purpose,
            @Valid @RequestBody ConsentChange request) {
        return consents.change(CurrentUser.requireId(), studentId, purpose, request.given(), actor());
    }

    @GetMapping("/requests")
    public List<RequestRow> myRequests() {
        return requests.mine(CurrentUser.requireId());
    }

    @PostMapping("/requests")
    @ResponseStatus(HttpStatus.CREATED)
    public RequestDetail submit(@Valid @RequestBody RequestForm request) {
        return requests.submit(CurrentUser.requireId(), request, actor());
    }

    @GetMapping("/requests/{id}")
    public RequestDetail request(@PathVariable UUID id) {
        return requests.mine(CurrentUser.requireId(), id);
    }

    @PostMapping("/requests/{id}/replies")
    public RequestDetail reply(@PathVariable UUID id, @Valid @RequestBody ReplyForm request) {
        return requests.parentReply(CurrentUser.requireId(), id, request.body(), actor());
    }

    /** The export of the parent's own access request, while it is available. */
    @GetMapping("/requests/{id}/export")
    public ResponseEntity<byte[]> download(@PathVariable UUID id) {
        return PrivacyController.zip(exports.downloadOwn(CurrentUser.requireId(), id, actor()));
    }

    private static Actor actor() {
        return new Actor(CurrentUser.requireId(), CurrentUser.name().orElse("Parent"));
    }
}
