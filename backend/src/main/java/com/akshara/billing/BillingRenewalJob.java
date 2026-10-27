package com.akshara.billing;

import java.time.Clock;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Runs {@link BillingRenewals} for every school once a day ({@code akshara.billing.renewals.cron}, 02:15 India time by
 * default): renewal invoices, past due, and suspension after the grace period. Safe on every API instance: a round
 * takes a database advisory lock, and an instance that cannot take it skips the round. Switched off with
 * {@code akshara.billing.renewals.enabled=false}. The clock is injectable for tests.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "akshara.billing.renewals.enabled", havingValue = "true")
class BillingRenewalJob {

    private static final Logger log = LoggerFactory.getLogger(BillingRenewalJob.class);

    private final BillingRenewals renewals;
    private final Clock clock;

    @Autowired
    BillingRenewalJob(BillingRenewals renewals) {
        this(renewals, Clock.systemUTC());
    }

    BillingRenewalJob(BillingRenewals renewals, Clock clock) {
        this.renewals = renewals;
        this.clock = clock;
    }

    @Scheduled(cron = "${akshara.billing.renewals.cron:0 15 2 * * *}", zone = "Asia/Kolkata")
    void renew() {
        run();
    }

    /** One round as of the clock's day in India. */
    BillingRenewals.Round run() {
        Instant now = clock.instant();
        try {
            BillingRenewals.Round round = renewals.runAll(BillingDay.of(now), now);
            if (!round.ran()) {
                log.info("Billing renewals skipped: another instance is running them");
            } else if (round.renewed() + round.pastDue() + round.restored() + round.suspended() + round.failed() > 0) {
                log.info("Billing renewals: {} renewed, {} past due, {} active again, {} suspended, {} failed",
                        round.renewed(), round.pastDue(), round.restored(), round.suspended(), round.failed());
            }
            return round;
        } catch (RuntimeException e) {
            log.warn("Billing renewal round failed ({})", e.getClass().getSimpleName());
            return null;
        }
    }
}
