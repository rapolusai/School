package com.akshara.inventory;

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

import com.akshara.inventory.InventoryForms.ApproversForm;
import com.akshara.inventory.InventoryForms.CategoryForm;
import com.akshara.inventory.InventoryForms.LocationForm;
import com.akshara.inventory.InventoryViews.ApproverView;
import com.akshara.inventory.InventoryViews.CategoryView;
import com.akshara.inventory.InventoryViews.LocationView;
import com.akshara.inventory.InventoryViews.Lookups;
import com.akshara.inventory.InventoryViews.PersonView;

/** Masters and settings: purchase approvers, categories, locations and the lookups forms need. */
@RestController
@RequestMapping("/api/inventory")
@PreAuthorize(InventoryAccess.READ_OR_APPROVE)
public class InventorySetupController {

    private final InventorySetupService setup;

    InventorySetupController(InventorySetupService setup) {
        this.setup = setup;
    }

    @GetMapping("/lookups")
    public Lookups lookups() {
        return setup.lookups();
    }

    @GetMapping("/approvers")
    public List<ApproverView> approvers() {
        return setup.approvers();
    }

    @GetMapping("/approvers/candidates")
    @PreAuthorize(InventoryAccess.MANAGE)
    public List<PersonView> approverCandidates() {
        return setup.approverCandidates();
    }

    @PutMapping("/approvers")
    @PreAuthorize(InventoryAccess.MANAGE)
    public List<ApproverView> saveApprovers(@Valid @RequestBody ApproversForm form) {
        return setup.saveApprovers(form, InventoryAccess.actor());
    }

    @GetMapping("/categories")
    public List<CategoryView> categories() {
        return setup.categories();
    }

    @PostMapping("/categories")
    @PreAuthorize(InventoryAccess.MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public CategoryView createCategory(@Valid @RequestBody CategoryForm form) {
        return setup.createCategory(form, InventoryAccess.actor());
    }

    @PutMapping("/categories/{id}")
    @PreAuthorize(InventoryAccess.MANAGE)
    public CategoryView updateCategory(@PathVariable UUID id, @Valid @RequestBody CategoryForm form) {
        return setup.updateCategory(id, form, InventoryAccess.actor());
    }

    @DeleteMapping("/categories/{id}")
    @PreAuthorize(InventoryAccess.MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteCategory(@PathVariable UUID id) {
        setup.deleteCategory(id, InventoryAccess.actor());
    }

    @GetMapping("/locations")
    public List<LocationView> locations() {
        return setup.locations();
    }

    @PostMapping("/locations")
    @PreAuthorize(InventoryAccess.MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public LocationView createLocation(@Valid @RequestBody LocationForm form) {
        return setup.createLocation(form, InventoryAccess.actor());
    }

    @PutMapping("/locations/{id}")
    @PreAuthorize(InventoryAccess.MANAGE)
    public LocationView updateLocation(@PathVariable UUID id, @Valid @RequestBody LocationForm form) {
        return setup.updateLocation(id, form, InventoryAccess.actor());
    }

    @DeleteMapping("/locations/{id}")
    @PreAuthorize(InventoryAccess.MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteLocation(@PathVariable UUID id) {
        setup.deleteLocation(id, InventoryAccess.actor());
    }
}
