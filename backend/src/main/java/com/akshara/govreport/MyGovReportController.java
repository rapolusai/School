package com.akshara.govreport;

import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.govreport.GovReportForms.FamilyFieldsForm;
import com.akshara.govreport.GovReportViews.FamilyView;
import com.akshara.shared.CurrentUser;

/**
 * The family app: the government fields a parent may fill for their own children (the mother tongue, and the
 * APAAR ID once; the office may lock it). Anyone signed in; only their own children.
 */
@RestController
@RequestMapping("/api/me/govreport")
@PreAuthorize("hasAuthority('dashboard.view')")
public class MyGovReportController {

    private final StudentFieldsService fields;

    MyGovReportController(StudentFieldsService fields) {
        this.fields = fields;
    }

    @GetMapping
    public FamilyView mine() {
        return fields.mine(CurrentUser.requireId());
    }

    @PutMapping("/children/{studentId}")
    public FamilyView save(@PathVariable UUID studentId, @Valid @RequestBody FamilyFieldsForm form) {
        return fields.saveMine(CurrentUser.requireId(), studentId, form, GovReportAuth.actor());
    }
}
