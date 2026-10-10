package com.akshara.billing;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** Billing dates are calendar days in India: invoice dates, due dates, periods and financial years. */
final class BillingDay {

    static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    private BillingDay() {
    }

    static LocalDate today() {
        return LocalDate.now(ZONE);
    }

    static LocalDate of(Instant instant) {
        return instant.atZone(ZONE).toLocalDate();
    }
}
