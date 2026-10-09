package com.akshara.fees;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.fees.FeeForms.ConcessionForm;
import com.akshara.fees.FeeForms.HeadForm;
import com.akshara.fees.FeeForms.LateFeeRuleForm;
import com.akshara.fees.FeeForms.ReasonForm;
import com.akshara.fees.FeeForms.StructureForm;
import com.akshara.fees.FeeViews.ConcessionView;
import com.akshara.fees.FeeViews.HeadView;
import com.akshara.fees.FeeViews.LateFeeRuleView;
import com.akshara.fees.FeeViews.StructureResult;
import com.akshara.fees.FeeViews.StructureSummary;
import com.akshara.fees.FeeViews.StructureView;

/** Fee heads, structures, concessions and the late-fee rule. Reading needs fees.read; changes need fees.manage. */
@RestController
@RequestMapping("/api/fees")
public class FeeSetupController {

    static final String READ = "hasAuthority('fees.read')";
    static final String COLLECT = "hasAuthority('fees.collect')";
    static final String MANAGE = "hasAuthority('fees.manage')";

    private final FeeSetupService setup;

    public FeeSetupController(FeeSetupService setup) {
        this.setup = setup;
    }

    // ------------------------------------------------------------------ heads

    @GetMapping("/heads")
    @PreAuthorize(READ)
    public List<HeadView> heads() {
        return setup.heads();
    }

    @PostMapping("/heads")
    @PreAuthorize(MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public HeadView createHead(@Valid @RequestBody HeadForm request) {
        return setup.createHead(request, null);
    }

    @PutMapping("/heads/{id}")
    @PreAuthorize(MANAGE)
    public HeadView updateHead(@PathVariable UUID id, @Valid @RequestBody HeadForm request) {
        return setup.updateHead(id, request, null);
    }

    /** Adds the usual heads the school does not have yet. */
    @PostMapping("/heads/defaults")
    @PreAuthorize(MANAGE)
    public List<HeadView> addDefaultHeads() {
        return setup.addDefaultHeads(null);
    }

    // ------------------------------------------------------------------ structures

    @GetMapping("/structures")
    @PreAuthorize(READ)
    public List<StructureSummary> structures(@RequestParam(required = false) UUID yearId) {
        return setup.structures(yearId);
    }

    @GetMapping("/structures/{id}")
    @PreAuthorize(READ)
    public StructureView structure(@PathVariable UUID id) {
        return setup.structure(id);
    }

    @PostMapping("/structures")
    @PreAuthorize(MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public StructureResult createStructure(@Valid @RequestBody StructureForm request) {
        return setup.createStructure(request, null);
    }

    @PutMapping("/structures/{id}")
    @PreAuthorize(MANAGE)
    public StructureResult updateStructure(@PathVariable UUID id, @Valid @RequestBody StructureForm request) {
        return setup.updateStructure(id, request, null);
    }

    @PostMapping("/structures/{id}/publish")
    @PreAuthorize(MANAGE)
    public StructureResult publish(@PathVariable UUID id) {
        return setup.publish(id, null);
    }

    // ------------------------------------------------------------------ concessions

    @GetMapping("/concessions")
    @PreAuthorize(READ)
    public List<ConcessionView> concessions(@RequestParam(required = false) UUID yearId,
            @RequestParam(required = false) UUID studentId) {
        return setup.concessions(yearId, studentId);
    }

    @PostMapping("/concessions")
    @PreAuthorize(MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public ConcessionView grant(@Valid @RequestBody ConcessionForm request) {
        return setup.grant(request, null);
    }

    @PostMapping("/concessions/{id}/revoke")
    @PreAuthorize(MANAGE)
    public ConcessionView revoke(@PathVariable UUID id, @Valid @RequestBody ReasonForm request) {
        return setup.revoke(id, request.reason(), null);
    }

    // ------------------------------------------------------------------ late fee

    @GetMapping("/late-fee-rule")
    @PreAuthorize(READ)
    public LateFeeRuleView lateFeeRule() {
        return setup.lateFeeRule();
    }

    @PutMapping("/late-fee-rule")
    @PreAuthorize(MANAGE)
    public LateFeeRuleView updateLateFeeRule(@Valid @RequestBody LateFeeRuleForm request) {
        return setup.updateLateFeeRule(request, null);
    }
}
