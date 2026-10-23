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

import com.akshara.health.HealthForms.AuditForm;
import com.akshara.health.HealthForms.CloseAuditForm;
import com.akshara.health.HealthForms.DrillForm;
import com.akshara.health.HealthForms.IncidentForm;
import com.akshara.health.HealthForms.KitCheckForm;
import com.akshara.health.HealthForms.KitForm;
import com.akshara.health.HealthViews.AuditView;
import com.akshara.health.HealthViews.DrillView;
import com.akshara.health.HealthViews.IncidentView;
import com.akshara.health.HealthViews.KitView;
import com.akshara.health.HealthViews.SafetyOverview;

/** Drills, safety audits, incidents and first-aid kits (docs/api/phase-3-health.md); all safety.manage. */
@RestController
@RequestMapping("/api/health/safety")
@PreAuthorize(HealthAuth.SAFETY)
public class SafetyController {

    private final SafetyService safety;

    SafetyController(SafetyService safety) {
        this.safety = safety;
    }

    @GetMapping
    public SafetyOverview overview() {
        return safety.overview();
    }

    @GetMapping("/drills")
    public List<DrillView> drills() {
        return safety.drills();
    }

    @PostMapping("/drills")
    @ResponseStatus(HttpStatus.CREATED)
    public DrillView addDrill(@Valid @RequestBody DrillForm form) {
        return safety.addDrill(form, HealthAuth.actor());
    }

    @PutMapping("/drills/{id}")
    public DrillView updateDrill(@PathVariable UUID id, @Valid @RequestBody DrillForm form) {
        return safety.updateDrill(id, form, HealthAuth.actor());
    }

    @DeleteMapping("/drills/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeDrill(@PathVariable UUID id) {
        safety.removeDrill(id, HealthAuth.actor());
    }

    @PostMapping("/audits")
    @ResponseStatus(HttpStatus.CREATED)
    public AuditView addAudit(@Valid @RequestBody AuditForm form) {
        return safety.addAudit(form, HealthAuth.actor());
    }

    @PutMapping("/audits/{id}")
    public AuditView updateAudit(@PathVariable UUID id, @Valid @RequestBody AuditForm form) {
        return safety.updateAudit(id, form, HealthAuth.actor());
    }

    @PostMapping("/audits/{id}/close")
    public AuditView closeAudit(@PathVariable UUID id, @RequestBody(required = false) @Valid CloseAuditForm form) {
        return safety.closeAudit(id, form == null ? new CloseAuditForm(null, null) : form, HealthAuth.actor());
    }

    @PostMapping("/audits/{id}/reopen")
    public AuditView reopenAudit(@PathVariable UUID id) {
        return safety.reopenAudit(id, HealthAuth.actor());
    }

    @PostMapping("/incidents")
    @ResponseStatus(HttpStatus.CREATED)
    public IncidentView addIncident(@Valid @RequestBody IncidentForm form) {
        return safety.addIncident(form, HealthAuth.actor());
    }

    @PutMapping("/incidents/{id}")
    public IncidentView updateIncident(@PathVariable UUID id, @Valid @RequestBody IncidentForm form) {
        return safety.updateIncident(id, form, HealthAuth.actor());
    }

    @PostMapping("/kits")
    @ResponseStatus(HttpStatus.CREATED)
    public KitView addKit(@Valid @RequestBody KitForm form) {
        return safety.addKit(form, HealthAuth.actor());
    }

    @PostMapping("/kits/{id}/check")
    public KitView checkKit(@PathVariable UUID id, @Valid @RequestBody KitCheckForm form) {
        return safety.checkKit(id, form, HealthAuth.actor());
    }

    @DeleteMapping("/kits/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeKit(@PathVariable UUID id) {
        safety.removeKit(id, HealthAuth.actor());
    }
}
