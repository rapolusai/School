package com.akshara.payroll;

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

import com.akshara.payroll.PayrollForms.ComponentFields;
import com.akshara.payroll.PayrollForms.NewComponent;
import com.akshara.payroll.PayrollForms.PayProfileForm;
import com.akshara.payroll.PayrollForms.PtSlabsForm;
import com.akshara.payroll.PayrollForms.SettingsForm;
import com.akshara.payroll.PayrollForms.StructureForm;
import com.akshara.payroll.PayrollViews.ComponentView;
import com.akshara.payroll.PayrollViews.PayProfileFull;
import com.akshara.payroll.PayrollViews.PayProfileView;
import com.akshara.payroll.PayrollViews.PayStaffDetail;
import com.akshara.payroll.PayrollViews.PayStaffList;
import com.akshara.payroll.PayrollViews.PtSlabsView;
import com.akshara.payroll.PayrollViews.SettingsView;
import com.akshara.payroll.PayrollViews.StructureView;

/**
 * Payroll set-up: the school's settings and statutory figures, salary components, and each staff member's salary
 * structures and pay details. payroll.read shows them (account numbers and PAN masked); payroll.manage changes them
 * and may see a person's pay details in full (audited). Ids of another school are 404.
 */
@RestController
@RequestMapping("/api/payroll")
public class PayrollController {

    private final PayrollSettingsService settings;
    private final SalaryComponentService components;
    private final PayStaffService staff;

    PayrollController(PayrollSettingsService settings, SalaryComponentService components, PayStaffService staff) {
        this.settings = settings;
        this.components = components;
        this.staff = staff;
    }

    // ------------------------------------------------------------------ settings

    @GetMapping("/settings")
    @PreAuthorize(PayrollAccess.READ)
    public SettingsView settings() {
        return settings.view();
    }

    @PutMapping("/settings")
    @PreAuthorize(PayrollAccess.MANAGE)
    public SettingsView saveSettings(@Valid @RequestBody SettingsForm request) {
        return settings.save(request, PayrollAccess.actor());
    }

    @GetMapping("/settings/pt-slabs/{state}")
    @PreAuthorize(PayrollAccess.READ)
    public PtSlabsView ptSlabs(@PathVariable String state) {
        return settings.ptSlabs(state);
    }

    @PutMapping("/settings/pt-slabs/{state}")
    @PreAuthorize(PayrollAccess.MANAGE)
    public PtSlabsView savePtSlabs(@PathVariable String state, @Valid @RequestBody PtSlabsForm request) {
        return settings.savePtSlabs(state, request, PayrollAccess.actor());
    }

    /** Drops the school's own slabs for the state, so the published ones apply again. */
    @DeleteMapping("/settings/pt-slabs/{state}")
    @PreAuthorize(PayrollAccess.MANAGE)
    public PtSlabsView resetPtSlabs(@PathVariable String state) {
        return settings.resetPtSlabs(state, PayrollAccess.actor());
    }

    // ------------------------------------------------------------------ components

    @GetMapping("/components")
    @PreAuthorize(PayrollAccess.READ)
    public List<ComponentView> components() {
        return components.list();
    }

    @PostMapping("/components")
    @PreAuthorize(PayrollAccess.MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public ComponentView createComponent(@Valid @RequestBody NewComponent request) {
        return components.create(request, PayrollAccess.actor());
    }

    /** Adds the standard components the school does not have yet. */
    @PostMapping("/components/standard")
    @PreAuthorize(PayrollAccess.MANAGE)
    public List<ComponentView> standardComponents() {
        return components.addStandard(PayrollAccess.actor());
    }

    @PutMapping("/components/{id}")
    @PreAuthorize(PayrollAccess.MANAGE)
    public ComponentView updateComponent(@PathVariable UUID id, @Valid @RequestBody ComponentFields request) {
        return components.update(id, request, PayrollAccess.actor());
    }

    // ------------------------------------------------------------------ staff, pay details, structures

    @GetMapping("/staff")
    @PreAuthorize(PayrollAccess.READ)
    public PayStaffList staff() {
        return staff.list();
    }

    @GetMapping("/staff/{userId}")
    @PreAuthorize(PayrollAccess.READ)
    public PayStaffDetail person(@PathVariable UUID userId) {
        return staff.detail(userId);
    }

    /** The account number and PAN in full. Every call is audited. */
    @GetMapping("/staff/{userId}/pay-profile/full")
    @PreAuthorize(PayrollAccess.MANAGE)
    public PayProfileFull fullProfile(@PathVariable UUID userId) {
        return staff.full(userId, PayrollAccess.actor());
    }

    @PutMapping("/staff/{userId}/pay-profile")
    @PreAuthorize(PayrollAccess.MANAGE)
    public PayProfileView saveProfile(@PathVariable UUID userId, @Valid @RequestBody PayProfileForm request) {
        return staff.saveProfile(userId, request, PayrollAccess.actor());
    }

    @PostMapping("/staff/{userId}/structures")
    @PreAuthorize(PayrollAccess.MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public StructureView createStructure(@PathVariable UUID userId, @Valid @RequestBody StructureForm request) {
        return staff.createStructure(userId, request, PayrollAccess.actor());
    }

    @DeleteMapping("/structures/{id}")
    @PreAuthorize(PayrollAccess.MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteStructure(@PathVariable UUID id) {
        staff.deleteStructure(id, PayrollAccess.actor());
    }
}
