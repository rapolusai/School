package com.akshara.govreport;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.govreport.GovReportForms.IntakeForm;
import com.akshara.govreport.GovReportForms.LotteryForm;
import com.akshara.govreport.GovReportForms.RteApplicationForm;
import com.akshara.govreport.GovReportForms.ScreeningForm;
import com.akshara.govreport.GovReportViews.ApplicationPick;
import com.akshara.govreport.GovReportViews.IntakeDetail;
import com.akshara.govreport.GovReportViews.IntakeView;
import com.akshara.govreport.GovReportViews.RteApplicationView;
import com.akshara.govreport.GovReportViews.RunDetail;

/** The RTE 25% intake: seats and rules, applications and their screening, the lottery and its runs. */
@RestController
@RequestMapping("/api/govreport/rte")
@Validated
public class RteController {

    private final RteService rte;

    RteController(RteService rte) {
        this.rte = rte;
    }

    @GetMapping("/intakes")
    @PreAuthorize(GovReportAuth.READ)
    public List<IntakeView> intakes(@RequestParam(required = false) UUID yearId) {
        return rte.intakes(yearId);
    }

    @PostMapping("/intakes")
    @PreAuthorize(GovReportAuth.MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public IntakeView create(@Valid @RequestBody IntakeForm form) {
        return rte.create(form, GovReportAuth.actor());
    }

    @GetMapping("/intakes/{id}")
    @PreAuthorize(GovReportAuth.READ)
    public IntakeDetail detail(@PathVariable UUID id) {
        return rte.detail(id);
    }

    @PutMapping("/intakes/{id}")
    @PreAuthorize(GovReportAuth.MANAGE)
    public IntakeView update(@PathVariable UUID id, @Valid @RequestBody IntakeForm form) {
        return rte.update(id, form, GovReportAuth.actor());
    }

    @PostMapping("/intakes/{id}/close")
    @PreAuthorize(GovReportAuth.MANAGE)
    public IntakeView close(@PathVariable UUID id) {
        return rte.close(id, GovReportAuth.actor());
    }

    @GetMapping("/intakes/{id}/candidates")
    @PreAuthorize(GovReportAuth.READ)
    public List<ApplicationPick> candidates(@PathVariable UUID id) {
        return rte.candidates(id);
    }

    @PostMapping("/intakes/{id}/applications")
    @PreAuthorize(GovReportAuth.MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public RteApplicationView addApplication(@PathVariable UUID id, @Valid @RequestBody RteApplicationForm form) {
        return rte.addApplication(id, form.applicationId(), GovReportAuth.actor());
    }

    @PutMapping("/applications/{id}")
    @PreAuthorize(GovReportAuth.MANAGE)
    public RteApplicationView screen(@PathVariable UUID id, @Valid @RequestBody ScreeningForm form) {
        return rte.screen(id, form, GovReportAuth.actor());
    }

    @PostMapping("/applications/{id}/withdraw")
    @PreAuthorize(GovReportAuth.MANAGE)
    public RteApplicationView withdraw(@PathVariable UUID id) {
        return rte.withdraw(id, GovReportAuth.actor());
    }

    @PostMapping("/intakes/{id}/lottery")
    @PreAuthorize(GovReportAuth.MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public RunDetail runLottery(@PathVariable UUID id, @Valid @RequestBody LotteryForm form) {
        return rte.runLottery(id, form, GovReportAuth.actor());
    }

    @GetMapping("/runs/{runId}")
    @PreAuthorize(GovReportAuth.READ)
    public RunDetail run(@PathVariable UUID runId) {
        return rte.run(runId);
    }
}
