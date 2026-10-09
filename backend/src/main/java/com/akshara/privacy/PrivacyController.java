package com.akshara.privacy;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

import com.akshara.audit.AuditService.Actor;
import com.akshara.privacy.DataRequestService.RequestQuery;
import com.akshara.privacy.PrivacyForms.AssignForm;
import com.akshara.privacy.PrivacyForms.CloseForm;
import com.akshara.privacy.PrivacyForms.EraseForm;
import com.akshara.privacy.PrivacyForms.NoticeForm;
import com.akshara.privacy.PrivacyForms.OfficerForm;
import com.akshara.privacy.PrivacyForms.PaperConsentForm;
import com.akshara.privacy.PrivacyForms.ReplyForm;
import com.akshara.privacy.PrivacyViews.ConsentPage;
import com.akshara.privacy.PrivacyViews.Download;
import com.akshara.privacy.PrivacyViews.Notice;
import com.akshara.privacy.PrivacyViews.NoticeAdmin;
import com.akshara.privacy.PrivacyViews.Officer;
import com.akshara.privacy.PrivacyViews.RequestDetail;
import com.akshara.privacy.PrivacyViews.RequestPage;
import com.akshara.privacy.PrivacyViews.StaffRef;
import com.akshara.privacy.PrivacyViews.StudentConsent;
import com.akshara.shared.CurrentUser;

/**
 * Data protection for staff with privacy.manage (School Admin and Principal by default): the privacy notice and
 * grievance officer, consent across the school and paper consent, and the data request queue with exports and
 * erasure. Ids of another school are 404.
 */
@RestController
@RequestMapping("/api/privacy")
@PreAuthorize("hasAuthority('privacy.manage')")
public class PrivacyController {

    private final PrivacyNoticeService notices;
    private final ConsentService consents;
    private final DataRequestService requests;
    private final DataExportService exports;
    private final ErasureService erasure;

    PrivacyController(PrivacyNoticeService notices, ConsentService consents, DataRequestService requests,
            DataExportService exports, ErasureService erasure) {
        this.notices = notices;
        this.consents = consents;
        this.requests = requests;
        this.exports = exports;
        this.erasure = erasure;
    }

    // ------------------------------------------------------------------ notice and officer

    @GetMapping("/notice")
    public NoticeAdmin notice() {
        return notices.admin();
    }

    @GetMapping("/notice/versions/{version}")
    public Notice version(@PathVariable int version) {
        return notices.version(version);
    }

    @PostMapping("/notice")
    @ResponseStatus(HttpStatus.CREATED)
    public Notice publish(@Valid @RequestBody NoticeForm request) {
        return notices.publish(request, actor());
    }

    @PutMapping("/grievance-officer")
    public Officer officer(@Valid @RequestBody OfficerForm request) {
        return notices.updateOfficer(request, actor());
    }

    // ------------------------------------------------------------------ consent

    @GetMapping("/consents")
    public ConsentPage consents(@RequestParam(name = "q", required = false) String search,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return consents.consents(search, page, size);
    }

    @GetMapping("/students/{studentId}/consents")
    public StudentConsent studentConsents(@PathVariable UUID studentId) {
        return consents.studentConsents(studentId);
    }

    /** A signed paper consent form, entered at admission or later. */
    @PostMapping("/students/{studentId}/consents")
    @ResponseStatus(HttpStatus.CREATED)
    public StudentConsent recordPaper(@PathVariable UUID studentId, @Valid @RequestBody PaperConsentForm request) {
        return consents.recordPaper(studentId, request, actor());
    }

    // ------------------------------------------------------------------ requests

    @GetMapping("/requests")
    public RequestPage queue(@RequestParam(required = false) String status,
            @RequestParam(required = false) RequestType type,
            @RequestParam(name = "q", required = false) String search,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return requests.queue(new RequestQuery(status, type, search, page, size));
    }

    @GetMapping("/requests/{id}")
    public RequestDetail request(@PathVariable UUID id) {
        return requests.detail(id);
    }

    /** People a request can be assigned to: active staff with privacy.manage. */
    @GetMapping("/staff")
    public List<StaffRef> staff() {
        return requests.staff();
    }

    @PostMapping("/requests/{id}/assign")
    public RequestDetail assign(@PathVariable UUID id, @Valid @RequestBody AssignForm request) {
        return requests.assign(id, request.assigneeId(), actor());
    }

    @PostMapping("/requests/{id}/replies")
    public RequestDetail reply(@PathVariable UUID id, @Valid @RequestBody ReplyForm request) {
        return requests.reply(id, request.body(), actor());
    }

    /** Makes the data export of an access request; the parent can download it for 7 days. */
    @PostMapping("/requests/{id}/export")
    public RequestDetail export(@PathVariable UUID id) {
        return exports.create(id, actor());
    }

    @GetMapping("/requests/{id}/export")
    public ResponseEntity<byte[]> download(@PathVariable UUID id) {
        return zip(exports.download(id, actor()));
    }

    /** Erases the child of an erasure request, once they have left; the admission number confirms it. */
    @PostMapping("/requests/{id}/erase")
    public RequestDetail erase(@PathVariable UUID id, @Valid @RequestBody EraseForm request) {
        return erasure.erase(id, request, actor());
    }

    @PostMapping("/requests/{id}/close")
    public RequestDetail close(@PathVariable UUID id, @Valid @RequestBody CloseForm request) {
        return requests.close(id, request, actor());
    }

    // ------------------------------------------------------------------ helpers

    static Actor actor() {
        return new Actor(CurrentUser.requireId(), CurrentUser.name().orElse("Staff"));
    }

    static ResponseEntity<byte[]> zip(Download download) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(download.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + download.fileName() + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(download.content());
    }
}
