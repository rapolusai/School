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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.health.HealthForms.ConsentForm;
import com.akshara.health.HealthForms.GrowthForm;
import com.akshara.health.HealthForms.ItemForm;
import com.akshara.health.HealthForms.RecordForm;
import com.akshara.health.HealthViews.GrowthView;
import com.akshara.health.HealthViews.ItemView;
import com.akshara.health.HealthViews.MedicalRecordView;
import com.akshara.health.HealthViews.PersonRef;
import com.akshara.shared.CurrentUser;

/**
 * Students' medical records for staff (docs/api/phase-3-health.md). health.read opens a record (class teachers:
 * their sections only; with attendance.manage: every student) and every read is audited with the {@code screen}
 * it came from; health.manage edits.
 */
@RestController
@RequestMapping("/api/health/students")
public class MedicalRecordController {

    private final MedicalRecordService records;

    MedicalRecordController(MedicalRecordService records) {
        this.records = records;
    }

    @GetMapping
    @PreAuthorize(HealthAuth.READ)
    public List<PersonRef> search(@RequestParam(required = false) String q) {
        return records.searchStudents(q);
    }

    @GetMapping("/{studentId}/record")
    @PreAuthorize(HealthAuth.READ)
    public MedicalRecordView record(@PathVariable UUID studentId, @RequestParam(required = false) String screen) {
        return records.view(studentId, screen, records.scope(CurrentUser.requireId(), HealthAuth.permissions()),
                HealthAuth.actor());
    }

    @PutMapping("/{studentId}/record")
    @PreAuthorize(HealthAuth.MANAGE)
    public MedicalRecordView save(@PathVariable UUID studentId, @Valid @RequestBody RecordForm form) {
        return records.saveHead(studentId, form, HealthAuth.actor());
    }

    @PutMapping("/{studentId}/record/consent")
    @PreAuthorize(HealthAuth.MANAGE)
    public MedicalRecordView consent(@PathVariable UUID studentId, @Valid @RequestBody ConsentForm form) {
        return records.saveConsent(studentId, form, HealthAuth.actor());
    }

    @PostMapping("/{studentId}/record/items")
    @PreAuthorize(HealthAuth.MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public ItemView addItem(@PathVariable UUID studentId, @Valid @RequestBody ItemForm form) {
        return records.addItem(studentId, form, HealthAuth.actor());
    }

    @PutMapping("/{studentId}/record/items/{itemId}")
    @PreAuthorize(HealthAuth.MANAGE)
    public ItemView updateItem(@PathVariable UUID studentId, @PathVariable UUID itemId,
            @Valid @RequestBody ItemForm form) {
        return records.updateItem(studentId, itemId, form, HealthAuth.actor());
    }

    @DeleteMapping("/{studentId}/record/items/{itemId}")
    @PreAuthorize(HealthAuth.MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeItem(@PathVariable UUID studentId, @PathVariable UUID itemId) {
        records.removeItem(studentId, itemId, HealthAuth.actor());
    }

    @PostMapping("/{studentId}/record/growth")
    @PreAuthorize(HealthAuth.MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public GrowthView addGrowth(@PathVariable UUID studentId, @Valid @RequestBody GrowthForm form) {
        return records.addGrowth(studentId, form, HealthAuth.actor());
    }

    @DeleteMapping("/{studentId}/record/growth/{measurementId}")
    @PreAuthorize(HealthAuth.MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeGrowth(@PathVariable UUID studentId, @PathVariable UUID measurementId) {
        records.removeGrowth(studentId, measurementId, HealthAuth.actor());
    }
}
