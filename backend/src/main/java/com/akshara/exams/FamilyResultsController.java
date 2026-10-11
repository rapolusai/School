package com.akshara.exams;

import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.exams.ExamsViews.FamilyResults;
import com.akshara.exams.ExamsViews.ReportCards;
import com.akshara.shared.CurrentUser;

/**
 * Results for students (their own) and parents (their own children): published results only, the report card of a
 * term or the year, and the published date sheets still to come. A sign-in without a linked student record, or a
 * child who is not the parent's, is 404.
 */
@RestController
public class FamilyResultsController {

    private final ReportCardService reportCards;

    FamilyResultsController(ReportCardService reportCards) {
        this.reportCards = reportCards;
    }

    @GetMapping("/api/me/results")
    @PreAuthorize("hasAuthority('dashboard.view')")
    public FamilyResults mine(@RequestParam(required = false) UUID yearId) {
        return reportCards.mine(CurrentUser.requireId(), yearId);
    }

    @GetMapping("/api/me/report-card")
    @PreAuthorize("hasAuthority('dashboard.view')")
    public ReportCards myCard(@RequestParam(required = false) UUID termId,
            @RequestParam(required = false) UUID yearId) {
        return reportCards.myCard(CurrentUser.requireId(), termId, yearId);
    }

    @GetMapping("/api/me/children/{studentId}/results")
    @PreAuthorize("hasAuthority('child.view')")
    public FamilyResults child(@PathVariable UUID studentId, @RequestParam(required = false) UUID yearId) {
        return reportCards.forChild(CurrentUser.requireId(), studentId, yearId);
    }

    @GetMapping("/api/me/children/{studentId}/report-card")
    @PreAuthorize("hasAuthority('child.view')")
    public ReportCards childCard(@PathVariable UUID studentId, @RequestParam(required = false) UUID termId,
            @RequestParam(required = false) UUID yearId) {
        return reportCards.childCard(CurrentUser.requireId(), studentId, termId, yearId);
    }
}
