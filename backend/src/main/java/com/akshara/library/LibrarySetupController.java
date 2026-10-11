package com.akshara.library;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.library.LibraryForms.RuleForm;
import com.akshara.library.LibraryForms.SettingsForm;
import com.akshara.library.LibraryViews.RuleView;
import com.akshara.library.LibraryViews.SettingsView;

/** The library's settings and borrowing rules. Every member of staff may read the rules; changes need library.manage. */
@RestController
@RequestMapping("/api/library")
public class LibrarySetupController {

    private final LibrarySetupService setup;

    LibrarySetupController(LibrarySetupService setup) {
        this.setup = setup;
    }

    @GetMapping("/settings")
    @PreAuthorize(LibraryAuth.MANAGE)
    public SettingsView settings() {
        return setup.settings();
    }

    @PutMapping("/settings")
    @PreAuthorize(LibraryAuth.MANAGE)
    public SettingsView updateSettings(@Valid @RequestBody SettingsForm form) {
        return setup.updateSettings(form, LibraryAuth.actor());
    }

    @GetMapping("/rules")
    @PreAuthorize(LibraryAuth.READ)
    public List<RuleView> rules() {
        return setup.rules();
    }

    @PutMapping("/rules/{memberType}")
    @PreAuthorize(LibraryAuth.MANAGE)
    public RuleView updateRule(@PathVariable MemberType memberType, @Valid @RequestBody RuleForm form) {
        return setup.updateRule(memberType, form, LibraryAuth.actor());
    }
}
