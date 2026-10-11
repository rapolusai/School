package com.akshara.lessons;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.academics.AcademicsDirectory.YearInfo;
import com.akshara.shared.CurrentUser;
import com.akshara.shared.Permissions;
import com.akshara.shared.TenantContext;

/**
 * Read-only lessons figures for the staff dashboard, in the current year: the signed-in teacher's draft or returned
 * plans for this week or earlier (plans to submit) and the short answers waiting for marking in their online tests;
 * and for approvers the plans waiting for approval (other people's).
 */
@Service
@Transactional(readOnly = true)
public class LessonsInsights {

    /** Each figure is 0 without the permission it needs (lessons.manage, or lessons.approve for plansToApprove). */
    public record Due(int plansToSubmit, int answersToMark, int plansToApprove) {
    }

    private final LessonPlanRepository plans;
    private final OnlineTestRepository tests;
    private final TestAttemptRepository attempts;
    private final LessonsSupport support;
    private final LessonsAccess access;

    LessonsInsights(LessonPlanRepository plans, OnlineTestRepository tests, TestAttemptRepository attempts,
            LessonsSupport support, LessonsAccess access) {
        this.plans = plans;
        this.tests = tests;
        this.attempts = attempts;
        this.support = support;
        this.access = access;
    }

    public Due due(LocalDate today) {
        TenantContext.require();
        Optional<YearInfo> year = support.currentYear();
        Optional<UUID> me = CurrentUser.id();
        if (year.isEmpty() || me.isEmpty()) {
            return new Due(0, 0, 0);
        }
        UUID yearId = year.get().id();
        int toSubmit = 0;
        int toMark = 0;
        if (LessonsAccess.can(Permissions.LESSONS_MANAGE)) {
            toSubmit = (int) plans.countToSubmit(yearId, me.get(), PlanService.monday(today));
            LessonsAccess.Scope scope = access.scope(yearId);
            List<UUID> mine = tests.findByAcademicYearId(yearId).stream()
                    .filter(t -> me.get().equals(t.getCreatedById()) || scope.owns(t.getCreatedById()))
                    .filter(t -> !t.isReleased())
                    .map(OnlineTest::getId).toList();
            toMark = mine.isEmpty() ? 0 : attempts.findByTestIdIn(mine).stream().filter(TestAttempt::isSubmitted)
                    .mapToInt(TestAttempt::getPendingMarking).sum();
        }
        int toApprove = LessonsAccess.can(Permissions.LESSONS_APPROVE)
                ? (int) plans.countToApprove(yearId, me.get()) : 0;
        return new Due(toSubmit, toMark, toApprove);
    }
}
