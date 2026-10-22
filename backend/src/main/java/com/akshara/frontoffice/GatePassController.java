package com.akshara.frontoffice;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.frontoffice.FrontOfficeForms.DecisionForm;
import com.akshara.frontoffice.FrontOfficeForms.OfficePassForm;
import com.akshara.frontoffice.FrontOfficeForms.ReleaseForm;
import com.akshara.frontoffice.FrontOfficeViews.GatePassView;
import com.akshara.frontoffice.FrontOfficeViews.GateScreen;
import com.akshara.frontoffice.FrontOfficeViews.StudentPickupPersons;
import com.akshara.frontoffice.FrontOfficeViews.StudentRef;

/**
 * Early pickup gate passes for the office (raise, approve, reject) and the gate (the screen, release with the OTP,
 * a student's pickup persons). No response ever carries the OTP.
 */
@RestController
@RequestMapping("/api/frontoffice")
public class GatePassController {

    private final GatePassService passes;
    private final PickupPersonService pickupPersons;

    GatePassController(GatePassService passes, PickupPersonService pickupPersons) {
        this.passes = passes;
        this.pickupPersons = pickupPersons;
    }

    @GetMapping("/gate-passes")
    @PreAuthorize(FrontOfficeAuth.READ)
    public List<GatePassView> list(@RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate date) {
        return passes.list(date);
    }

    @GetMapping("/gate-passes/{id}")
    @PreAuthorize(FrontOfficeAuth.READ_OR_GATE)
    public GatePassView detail(@PathVariable UUID id) {
        return passes.detail(id);
    }

    @GetMapping("/students")
    @PreAuthorize(FrontOfficeAuth.MANAGE)
    public List<StudentRef> students(@RequestParam(required = false) String q) {
        return passes.searchStudents(q);
    }

    @PostMapping("/gate-passes")
    @PreAuthorize(FrontOfficeAuth.MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public GatePassView raise(@Valid @RequestBody OfficePassForm form) {
        return passes.raise(form, FrontOfficeAuth.actor());
    }

    @PostMapping("/gate-passes/{id}/approve")
    @PreAuthorize(FrontOfficeAuth.MANAGE)
    public GatePassView approve(@PathVariable UUID id, @RequestBody(required = false) @Valid DecisionForm form) {
        return passes.approve(id, form, FrontOfficeAuth.actor());
    }

    @PostMapping("/gate-passes/{id}/reject")
    @PreAuthorize(FrontOfficeAuth.MANAGE)
    public GatePassView reject(@PathVariable UUID id, @RequestBody(required = false) @Valid DecisionForm form) {
        return passes.reject(id, form, FrontOfficeAuth.actor());
    }

    /* ------------------------------------------------------------------ the gate */

    @GetMapping("/gate")
    @PreAuthorize(FrontOfficeAuth.GATE)
    public GateScreen gate() {
        return passes.screen();
    }

    @PostMapping("/gate-passes/{id}/release")
    @PreAuthorize(FrontOfficeAuth.GATE)
    public GatePassView release(@PathVariable UUID id, @Valid @RequestBody ReleaseForm form) {
        return passes.release(id, form, FrontOfficeAuth.actor());
    }

    @GetMapping("/students/{studentId}/pickup-persons")
    @PreAuthorize(FrontOfficeAuth.READ_OR_GATE)
    public StudentPickupPersons pickupPersons(@PathVariable UUID studentId) {
        return pickupPersons.ofStudent(studentId);
    }
}
