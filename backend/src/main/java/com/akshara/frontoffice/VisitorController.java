package com.akshara.frontoffice;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
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

import com.akshara.files.FileUploads;
import com.akshara.frontoffice.FrontOfficeForms.BlockForm;
import com.akshara.frontoffice.FrontOfficeForms.VisitorForm;
import com.akshara.frontoffice.FrontOfficeViews.BlockedView;
import com.akshara.frontoffice.FrontOfficeViews.StaffRef;
import com.akshara.frontoffice.FrontOfficeViews.VisitorDay;
import com.akshara.frontoffice.FrontOfficeViews.VisitorView;

/**
 * The visitor log (docs/api/phase-2-frontoffice.md). The office (frontoffice.manage) and the gate (gate.pass) record
 * visitors; frontoffice.read sees the log; the blocklist is the office's.
 */
@RestController
@RequestMapping("/api/frontoffice")
public class VisitorController {

    /** A check-in from the gate: an optional badge number and the blocklist override (office only). */
    public record CheckInForm(String badgeNo, Boolean override) {
    }

    private final VisitorService visitors;

    VisitorController(VisitorService visitors) {
        this.visitors = visitors;
    }

    @GetMapping("/visitors")
    @PreAuthorize(FrontOfficeAuth.READ_OR_GATE)
    public VisitorDay day(@RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate date) {
        return visitors.day(date);
    }

    /** The printable daily register. */
    @GetMapping("/visitors/register")
    @PreAuthorize(FrontOfficeAuth.READ)
    public List<VisitorView> register(@RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate date) {
        return visitors.register(date);
    }

    @GetMapping("/visitors/{id}")
    @PreAuthorize(FrontOfficeAuth.READ_OR_GATE)
    public VisitorView detail(@PathVariable UUID id) {
        return visitors.detail(id);
    }

    @GetMapping("/staff")
    @PreAuthorize(FrontOfficeAuth.READ_OR_GATE)
    public List<StaffRef> staff() {
        return visitors.staff();
    }

    @PostMapping("/visitors")
    @PreAuthorize(FrontOfficeAuth.GATE)
    @ResponseStatus(HttpStatus.CREATED)
    public VisitorView add(@Valid @RequestBody VisitorForm form) {
        return visitors.add(guardOverride(form), FrontOfficeAuth.actor());
    }

    @PutMapping("/visitors/{id}")
    @PreAuthorize(FrontOfficeAuth.MANAGE)
    public VisitorView update(@PathVariable UUID id, @Valid @RequestBody VisitorForm form) {
        return visitors.update(id, form, FrontOfficeAuth.actor());
    }

    @PostMapping("/visitors/{id}/check-in")
    @PreAuthorize(FrontOfficeAuth.GATE)
    public VisitorView checkIn(@PathVariable UUID id, @RequestBody(required = false) CheckInForm form) {
        String badge = form == null ? null : form.badgeNo();
        Boolean override = form != null && FrontOfficeAuth.canManage() ? form.override() : null;
        return visitors.checkIn(id, badge, override, FrontOfficeAuth.actor());
    }

    @PostMapping("/visitors/{id}/check-out")
    @PreAuthorize(FrontOfficeAuth.GATE)
    public VisitorView checkOut(@PathVariable UUID id) {
        return visitors.checkOut(id, FrontOfficeAuth.actor());
    }

    /** The visitor's photo (multipart "file": a JPEG, PNG or WEBP, downscaled by the browser). */
    @PostMapping(value = "/visitors/{id}/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(FrontOfficeAuth.GATE)
    public VisitorView photo(@PathVariable UUID id, @RequestPart("file") MultipartFile file) {
        return visitors.addPhoto(id, FileUploads.read(file, "file"), FrontOfficeAuth.actor());
    }

    /* ------------------------------------------------------------------ blocklist */

    @GetMapping("/blocklist")
    @PreAuthorize(FrontOfficeAuth.READ_OR_GATE)
    public List<BlockedView> blocklist() {
        return visitors.blocklist();
    }

    @PostMapping("/blocklist")
    @PreAuthorize(FrontOfficeAuth.MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public BlockedView block(@Valid @RequestBody BlockForm form) {
        return visitors.block(form, FrontOfficeAuth.actor());
    }

    @PutMapping("/blocklist/{id}")
    @PreAuthorize(FrontOfficeAuth.MANAGE)
    public BlockedView updateBlock(@PathVariable UUID id, @Valid @RequestBody BlockForm form) {
        return visitors.updateBlock(id, form, FrontOfficeAuth.actor());
    }

    @DeleteMapping("/blocklist/{id}")
    @PreAuthorize(FrontOfficeAuth.MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unblock(@PathVariable UUID id) {
        visitors.unblock(id, FrontOfficeAuth.actor());
    }

    /** Only the office may admit someone on the blocklist; the gate's override flag is ignored. */
    private static VisitorForm guardOverride(VisitorForm form) {
        if (Boolean.TRUE.equals(form.override()) && !FrontOfficeAuth.canManage()) {
            return new VisitorForm(form.name(), form.phone(), form.purpose(), form.purposeNote(),
                    form.toMeetUserId(), form.toMeetName(), form.peopleCount(), form.idType(), form.badgeNo(),
                    form.expectedOn(), false);
        }
        return form;
    }
}
