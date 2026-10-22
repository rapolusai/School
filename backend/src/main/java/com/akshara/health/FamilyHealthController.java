package com.akshara.health;

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

import com.akshara.audit.AuditService.Actor;
import com.akshara.health.HealthForms.ConsentForm;
import com.akshara.health.HealthForms.ItemForm;
import com.akshara.health.HealthViews.ItemView;
import com.akshara.health.HealthViews.MedicalRecordView;
import com.akshara.shared.CurrentUser;

/**
 * The family app's "Health" page (docs/api/phase-3-health.md): a parent (child.view) sees their child's record and
 * infirmary visits, and keeps the allergies, conditions and consent. Another parent's child answers 404.
 */
@RestController
@RequestMapping("/api/me/children/{studentId}/health")
public class FamilyHealthController {

    private final MedicalRecordService records;

    FamilyHealthController(MedicalRecordService records) {
        this.records = records;
    }

    @GetMapping
    @PreAuthorize(HealthAuth.PARENT)
    public MedicalRecordView record(@PathVariable UUID studentId) {
        return records.childView(CurrentUser.requireId(), studentId);
    }

    @PutMapping("/consent")
    @PreAuthorize(HealthAuth.PARENT)
    public MedicalRecordView consent(@PathVariable UUID studentId, @Valid @RequestBody ConsentForm form) {
        return records.childConsent(CurrentUser.requireId(), studentId, form, actor());
    }

    @PostMapping("/items")
    @PreAuthorize(HealthAuth.PARENT)
    @ResponseStatus(HttpStatus.CREATED)
    public ItemView addItem(@PathVariable UUID studentId, @Valid @RequestBody ItemForm form) {
        return records.childAddItem(CurrentUser.requireId(), studentId, form, actor());
    }

    @PutMapping("/items/{itemId}")
    @PreAuthorize(HealthAuth.PARENT)
    public ItemView updateItem(@PathVariable UUID studentId, @PathVariable UUID itemId,
            @Valid @RequestBody ItemForm form) {
        return records.childUpdateItem(CurrentUser.requireId(), studentId, itemId, form, actor());
    }

    @DeleteMapping("/items/{itemId}")
    @PreAuthorize(HealthAuth.PARENT)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeItem(@PathVariable UUID studentId, @PathVariable UUID itemId) {
        records.childRemoveItem(CurrentUser.requireId(), studentId, itemId, actor());
    }

    private static Actor actor() {
        return new Actor(CurrentUser.requireId(), CurrentUser.name().orElse("Parent"));
    }
}
