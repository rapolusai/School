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

import com.akshara.health.HealthForms.TrainingForm;
import com.akshara.health.HealthForms.VerificationForm;
import com.akshara.health.HealthViews.StaffChecks;
import com.akshara.health.HealthViews.TrainingView;
import com.akshara.health.HealthViews.VerificationView;

/** Staff police verification and training records (docs/api/phase-3-health.md); all safety.manage. */
@RestController
@RequestMapping("/api/health/staff")
@PreAuthorize(HealthAuth.SAFETY)
public class StaffChecksController {

    private final StaffChecksService checks;

    StaffChecksController(StaffChecksService checks) {
        this.checks = checks;
    }

    @GetMapping("/checks")
    public StaffChecks list() {
        return checks.list();
    }

    @GetMapping("/checks/due")
    public List<VerificationView> due() {
        return checks.due();
    }

    @PutMapping("/{userId}/verification")
    public VerificationView saveVerification(@PathVariable UUID userId, @Valid @RequestBody VerificationForm form) {
        return checks.saveVerification(userId, form, HealthAuth.actor());
    }

    @PostMapping("/trainings")
    @ResponseStatus(HttpStatus.CREATED)
    public TrainingView addTraining(@Valid @RequestBody TrainingForm form) {
        return checks.addTraining(form, HealthAuth.actor());
    }

    @DeleteMapping("/trainings/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeTraining(@PathVariable UUID id) {
        checks.removeTraining(id, HealthAuth.actor());
    }
}
