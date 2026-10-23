package com.akshara.govreport;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
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

import com.akshara.govreport.GovReportForms.BulkFieldsForm;
import com.akshara.govreport.GovReportForms.CodeForm;
import com.akshara.govreport.GovReportForms.ImportForm;
import com.akshara.govreport.GovReportForms.NewCodeForm;
import com.akshara.govreport.GovReportForms.ProfileForm;
import com.akshara.govreport.GovReportForms.SchemeForm;
import com.akshara.govreport.GovReportForms.SchemeUpdateForm;
import com.akshara.govreport.GovReportTypes.CodeListType;
import com.akshara.govreport.GovReportTypes.UpdatedVia;
import com.akshara.govreport.GovReportViews.BulkResult;
import com.akshara.govreport.GovReportViews.CodeView;
import com.akshara.govreport.GovReportViews.CompletenessReport;
import com.akshara.govreport.GovReportViews.ImportResult;
import com.akshara.govreport.GovReportViews.ProfileView;
import com.akshara.govreport.GovReportViews.SchemeView;
import com.akshara.govreport.GovReportViews.SectionGrid;

/**
 * Government reporting: code lists, the school's government profile, students' government fields and the scheme
 * register. Reading needs govreport.read; changes need govreport.manage. docs/api/phase-3-govreport.md.
 */
@RestController
@RequestMapping("/api/govreport")
@Validated
public class GovReportController {

    private final CodeListService codes;
    private final SchoolProfileService profiles;
    private final StudentFieldsService fields;
    private final SchemeService schemes;

    GovReportController(CodeListService codes, SchoolProfileService profiles, StudentFieldsService fields,
            SchemeService schemes) {
        this.codes = codes;
        this.profiles = profiles;
        this.fields = fields;
        this.schemes = schemes;
    }

    // ------------------------------------------------------------------ code lists

    @GetMapping("/code-lists")
    @PreAuthorize(GovReportAuth.READ)
    public List<CodeView> codeLists(@RequestParam(required = false) CodeListType list) {
        return list == null ? codes.all() : codes.list(list);
    }

    @PostMapping("/code-lists")
    @PreAuthorize(GovReportAuth.MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public CodeView addCode(@Valid @RequestBody NewCodeForm form) {
        return codes.add(form, GovReportAuth.actor());
    }

    @PutMapping("/code-lists/{id}")
    @PreAuthorize(GovReportAuth.MANAGE)
    public CodeView updateCode(@PathVariable UUID id, @Valid @RequestBody CodeForm form) {
        return codes.update(id, form, GovReportAuth.actor());
    }

    // ------------------------------------------------------------------ the school profile

    @GetMapping("/profile")
    @PreAuthorize(GovReportAuth.READ)
    public ProfileView profile(@RequestParam(required = false) UUID yearId) {
        return profiles.profile(yearId);
    }

    @PutMapping("/profile")
    @PreAuthorize(GovReportAuth.MANAGE)
    public ProfileView saveProfile(@RequestParam(required = false) UUID yearId, @Valid @RequestBody ProfileForm form) {
        return profiles.save(yearId, form, GovReportAuth.actor());
    }

    // ------------------------------------------------------------------ students' government fields

    @GetMapping("/students")
    @PreAuthorize(GovReportAuth.READ)
    public SectionGrid grid(@RequestParam UUID sectionId, @RequestParam(required = false) UUID yearId) {
        return fields.grid(sectionId, yearId);
    }

    @PutMapping("/students")
    @PreAuthorize(GovReportAuth.MANAGE)
    public BulkResult saveFields(@Valid @RequestBody BulkFieldsForm form) {
        return fields.save(form.entries(), GovReportAuth.actor(), UpdatedVia.OFFICE);
    }

    @GetMapping("/students/template.csv")
    @PreAuthorize(GovReportAuth.READ)
    public ResponseEntity<byte[]> template() {
        GovCsv csv = new GovCsv(StudentFieldsService.IMPORT_COLUMNS.toArray(String[]::new));
        return GovCsv.download(csv, "government-fields-template.csv");
    }

    @PostMapping("/students/import")
    @PreAuthorize(GovReportAuth.MANAGE)
    public ImportResult importFields(@RequestParam(defaultValue = "false") boolean dryRun,
            @Valid @RequestBody ImportForm form) {
        return fields.importCsv(form.csv(), dryRun, GovReportAuth.actor());
    }

    @GetMapping("/students/completeness")
    @PreAuthorize(GovReportAuth.READ)
    public CompletenessReport completeness(@RequestParam(required = false) UUID yearId) {
        return fields.completeness(yearId);
    }

    // ------------------------------------------------------------------ schemes

    @GetMapping("/schemes")
    @PreAuthorize(GovReportAuth.READ)
    public List<SchemeView> schemes(@RequestParam(required = false) UUID yearId,
            @RequestParam(required = false) String schemeCode) {
        return schemes.list(yearId, schemeCode);
    }

    @GetMapping("/schemes.csv")
    @PreAuthorize(GovReportAuth.READ)
    public ResponseEntity<byte[]> schemesCsv(@RequestParam(required = false) UUID yearId,
            @RequestParam(required = false) String schemeCode) {
        String name = schemeCode == null || schemeCode.isBlank() ? "schemes.csv"
                : "scheme-" + schemeCode.toLowerCase() + ".csv";
        return GovCsv.download(schemes.export(yearId, schemeCode), name);
    }

    @PostMapping("/schemes")
    @PreAuthorize(GovReportAuth.MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public SchemeView enrol(@Valid @RequestBody SchemeForm form) {
        return schemes.add(form, GovReportAuth.actor());
    }

    @PutMapping("/schemes/{id}")
    @PreAuthorize(GovReportAuth.MANAGE)
    public SchemeView updateScheme(@PathVariable UUID id, @Valid @RequestBody SchemeUpdateForm form) {
        return schemes.update(id, form, GovReportAuth.actor());
    }

    @DeleteMapping("/schemes/{id}")
    @PreAuthorize(GovReportAuth.MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeScheme(@PathVariable UUID id) {
        schemes.remove(id, GovReportAuth.actor());
    }
}
