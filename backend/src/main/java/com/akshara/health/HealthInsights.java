package com.akshara.health;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.health.HealthText.Due;
import com.akshara.health.HealthViews.DrillView;
import com.akshara.health.HealthViews.StaffChecks;
import com.akshara.shared.Permissions;
import com.akshara.shared.TenantContext;

/**
 * Figures for the staff dashboard's health and safety card: students in the infirmary now (health.read), and, for
 * safety.manage, staff verifications due or missing, trainings to renew, and whether a drill is due. Null figures are
 * not for this person; a null card when nothing on it is.
 */
@Service
@Transactional(readOnly = true)
public class HealthInsights {

    public record Card(Integer inInfirmary, Integer visitsToday, Integer verificationsDue, Integer verificationsMissing,
            Integer trainingsDue, LocalDate nextDrillDue, Due drillDue, Integer openFindings) {
    }

    private final InfirmaryVisitRepository visits;
    private final SafetyAuditRepository audits;
    private final SafetyService safety;
    private final StaffChecksService checks;

    HealthInsights(InfirmaryVisitRepository visits, SafetyAuditRepository audits, SafetyService safety,
            StaffChecksService checks) {
        this.visits = visits;
        this.audits = audits;
        this.safety = safety;
        this.checks = checks;
    }

    public Card card(Set<String> permissions, LocalDate today) {
        TenantContext.require();
        boolean health = permissions.contains(Permissions.HEALTH_READ)
                || permissions.contains(Permissions.HEALTH_MANAGE);
        boolean safe = permissions.contains(Permissions.SAFETY_MANAGE);
        if (!health && !safe) {
            return null;
        }
        Integer inInfirmary = null;
        Integer visitsToday = null;
        if (health) {
            inInfirmary = visits.open().size();
            visitsToday = visits.between(HealthText.startOfDay(today), HealthText.startOfDay(today.plusDays(1)))
                    .size();
        }
        Integer due = null;
        Integer missing = null;
        Integer trainings = null;
        LocalDate nextDrill = null;
        Due drillDue = null;
        Integer openFindings = null;
        if (safe) {
            StaffChecks list = checks.list();
            due = list.verificationsDue();
            missing = list.verificationsMissing();
            trainings = list.trainingsDue();
            List<DrillView> drills = safety.drills();
            nextDrill = SafetyService.nextDrillDue(drills);
            drillDue = HealthText.dueState(nextDrill, today);
            openFindings = (int) audits.listed().stream().filter(a -> a.getStatus() == AuditStatus.OPEN).count();
        }
        return new Card(inInfirmary, visitsToday, due, missing, trainings, nextDrill, drillDue, openFindings);
    }
}
