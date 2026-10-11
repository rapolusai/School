package com.akshara.payroll;

import java.time.Instant;
import java.time.YearMonth;
import java.util.UUID;

/**
 * Published (as a Spring application event, inside the approving transaction) when a month's payroll is approved and
 * locked, for a later accounts module to post the salary journal. Amounts are in paise: {@code grossPaise} is what
 * the staff earned, {@code deductionsPaise} what was kept back from them (PF, ESI, professional tax, TDS and other
 * deductions), {@code netPaise} what the bank transfer pays out, and {@code employerContributionsPaise} the school's
 * own PF (EPF and EPS), EDLI, PF administration charges and ESI on top. Listeners that must not act on a rolled-back
 * approval should use {@code @TransactionalEventListener}.
 */
public record PayrollApproved(UUID tenantId, UUID runId, YearMonth month, int staffCount, long grossPaise,
        long deductionsPaise, long netPaise, long employerContributionsPaise, long pfEmployeePaise,
        long pfEmployerPaise, long edliPaise, long pfAdminPaise, long esiEmployeePaise, long esiEmployerPaise,
        long professionalTaxPaise, long tdsPaise, UUID approvedById, Instant approvedAt) {
}
