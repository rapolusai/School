package com.akshara.health;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
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

import com.akshara.health.HealthForms.CloseForm;
import com.akshara.health.HealthForms.MedicineForm;
import com.akshara.health.HealthForms.VisitForm;
import com.akshara.health.HealthViews.InfirmaryDay;
import com.akshara.health.HealthViews.MedicineView;
import com.akshara.health.HealthViews.PersonRef;
import com.akshara.health.HealthViews.VisitView;

/** The infirmary log and medicine stock (docs/api/phase-3-health.md). health.read sees it; health.manage works it. */
@RestController
@RequestMapping("/api/health/infirmary")
public class InfirmaryController {

    private final InfirmaryService infirmary;

    InfirmaryController(InfirmaryService infirmary) {
        this.infirmary = infirmary;
    }

    @GetMapping("/visits")
    @PreAuthorize(HealthAuth.READ)
    public InfirmaryDay day(@RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate date) {
        return infirmary.day(date);
    }

    @GetMapping("/visits/{id}")
    @PreAuthorize(HealthAuth.READ)
    public VisitView detail(@PathVariable UUID id) {
        return infirmary.detail(id);
    }

    @GetMapping("/people")
    @PreAuthorize(HealthAuth.MANAGE)
    public List<PersonRef> people(@RequestParam(required = false) String q) {
        return infirmary.search(q);
    }

    @PostMapping("/visits")
    @PreAuthorize(HealthAuth.MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public VisitView add(@Valid @RequestBody VisitForm form) {
        return infirmary.add(form, HealthAuth.actor());
    }

    @PutMapping("/visits/{id}")
    @PreAuthorize(HealthAuth.MANAGE)
    public VisitView update(@PathVariable UUID id, @Valid @RequestBody VisitForm form) {
        return infirmary.update(id, form, HealthAuth.actor());
    }

    @PostMapping("/visits/{id}/close")
    @PreAuthorize(HealthAuth.MANAGE)
    public VisitView close(@PathVariable UUID id, @Valid @RequestBody CloseForm form) {
        return infirmary.close(id, form, HealthAuth.actor());
    }

    @GetMapping("/medicines")
    @PreAuthorize(HealthAuth.READ)
    public List<MedicineView> medicines() {
        return infirmary.medicines();
    }

    @PostMapping("/medicines")
    @PreAuthorize(HealthAuth.MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public MedicineView addMedicine(@Valid @RequestBody MedicineForm form) {
        return infirmary.addMedicine(form, HealthAuth.actor());
    }

    @PutMapping("/medicines/{id}")
    @PreAuthorize(HealthAuth.MANAGE)
    public MedicineView updateMedicine(@PathVariable UUID id, @Valid @RequestBody MedicineForm form) {
        return infirmary.updateMedicine(id, form, HealthAuth.actor());
    }

    @DeleteMapping("/medicines/{id}")
    @PreAuthorize(HealthAuth.MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMedicine(@PathVariable UUID id) {
        infirmary.removeMedicine(id, HealthAuth.actor());
    }
}
