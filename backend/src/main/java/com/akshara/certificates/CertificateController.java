package com.akshara.certificates;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.certificates.CertificateForms.EditForm;
import com.akshara.certificates.CertificateForms.PrepareForm;
import com.akshara.certificates.CertificateForms.ReasonForm;
import com.akshara.certificates.CertificateForms.SettingsForm;
import com.akshara.certificates.CertificateForms.TemplateForm;
import com.akshara.certificates.CertificateViews.CertificateDetail;
import com.akshara.certificates.CertificateViews.CertificatePage;
import com.akshara.certificates.CertificateViews.SettingsView;
import com.akshara.certificates.CertificateViews.TemplateView;

/** Certificates: the register, the issue flow, the templates and the settings (docs/api/phase-2-certificates.md). */
@RestController
@RequestMapping("/api/certificates")
@Validated
public class CertificateController {

    private final CertificateService certificates;
    private final CertificateSetupService setup;

    CertificateController(CertificateService certificates, CertificateSetupService setup) {
        this.certificates = certificates;
        this.setup = setup;
    }

    // ------------------------------------------------------------------ settings and templates

    @GetMapping("/settings")
    @PreAuthorize(CertificateAuth.READ)
    public SettingsView settings() {
        return setup.settings();
    }

    @PutMapping("/settings")
    @PreAuthorize(CertificateAuth.ISSUE)
    public SettingsView updateSettings(@Valid @RequestBody SettingsForm form) {
        return setup.updateSettings(form, CertificateAuth.actor());
    }

    @GetMapping("/templates")
    @PreAuthorize(CertificateAuth.READ)
    public List<TemplateView> templates() {
        return setup.templates();
    }

    @GetMapping("/templates/{type}")
    @PreAuthorize(CertificateAuth.READ)
    public TemplateView template(@PathVariable CertificateType type) {
        return setup.template(type);
    }

    @PutMapping("/templates/{type}")
    @PreAuthorize(CertificateAuth.ISSUE)
    public TemplateView updateTemplate(@PathVariable CertificateType type, @Valid @RequestBody TemplateForm form) {
        return setup.updateTemplate(type, form, CertificateAuth.actor());
    }

    // ------------------------------------------------------------------ the register and the flow

    @GetMapping
    @PreAuthorize(CertificateAuth.READ)
    public CertificatePage list(@RequestParam(required = false) CertificateStatus status,
            @RequestParam(required = false) CertificateType type,
            @RequestParam(required = false) UUID studentId,
            @RequestParam(required = false) UUID staffUserId,
            @RequestParam(required = false) LocalDate issuedFrom,
            @RequestParam(required = false) LocalDate issuedTo,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(CertificateService.MAX_PAGE_SIZE) int size) {
        return certificates.list(status, type, studentId, staffUserId, issuedFrom, issuedTo, page, size);
    }

    @PostMapping
    @PreAuthorize(CertificateAuth.ISSUE)
    @ResponseStatus(HttpStatus.CREATED)
    public CertificateDetail prepare(@Valid @RequestBody PrepareForm form) {
        return certificates.prepare(form, CertificateAuth.actor());
    }

    @GetMapping("/{id}")
    @PreAuthorize(CertificateAuth.READ)
    public CertificateDetail detail(@PathVariable UUID id) {
        return certificates.detail(id);
    }

    @PutMapping("/{id}")
    @PreAuthorize(CertificateAuth.ISSUE)
    public CertificateDetail edit(@PathVariable UUID id, @Valid @RequestBody EditForm form) {
        return certificates.edit(id, form, CertificateAuth.actor());
    }

    @PostMapping("/{id}/sign")
    @PreAuthorize(CertificateAuth.SIGN)
    public CertificateDetail sign(@PathVariable UUID id) {
        return certificates.sign(id, CertificateAuth.actor());
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize(CertificateAuth.ISSUE)
    public CertificateDetail cancel(@PathVariable UUID id, @Valid @RequestBody ReasonForm form) {
        return certificates.cancel(id, form.reason(), CertificateAuth.actor());
    }

    @PostMapping("/{id}/correct")
    @PreAuthorize(CertificateAuth.ISSUE)
    @ResponseStatus(HttpStatus.CREATED)
    public CertificateDetail correct(@PathVariable UUID id, @Valid @RequestBody ReasonForm form) {
        return certificates.correct(id, form.reason(), CertificateAuth.actor());
    }
}
