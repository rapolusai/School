package com.akshara.govreport;

import java.time.LocalDate;
import java.time.ZoneId;

import com.akshara.audit.AuditService.Actor;
import com.akshara.shared.CurrentUser;

/** Who is using the government reporting screens: the audit actor and the permission expressions. */
final class GovReportAuth {

    static final String READ = "hasAuthority('govreport.read')";
    static final String MANAGE = "hasAuthority('govreport.manage')";

    static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    private GovReportAuth() {
    }

    static Actor actor() {
        return new Actor(CurrentUser.requireId(), CurrentUser.name().orElse("Staff"));
    }

    static LocalDate today() {
        return LocalDate.now(INDIA);
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
