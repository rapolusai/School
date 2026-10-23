package com.akshara.assist;

import java.time.Instant;
import java.time.LocalDate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Recomputes the early warning watch list every night ({@code akshara.assist.early-warning.cron}, 01:30 India
 * time by default) for every school that has it on. Switched off with
 * {@code akshara.assist.early-warning.enabled=false}.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "akshara.assist.early-warning.enabled", havingValue = "true")
class RiskJob {

    private static final Logger log = LoggerFactory.getLogger(RiskJob.class);

    private final RiskRunner runner;

    RiskJob(RiskRunner runner) {
        this.runner = runner;
    }

    @Scheduled(cron = "${akshara.assist.early-warning.cron:0 30 1 * * *}", zone = "Asia/Kolkata")
    void nightly() {
        try {
            int schools = runner.runAll(LocalDate.now(UsageService.INDIA), Instant.now());
            if (schools > 0) {
                log.info("Early warning computed for {} schools", schools);
            }
        } catch (RuntimeException e) {
            log.warn("Early warning round failed ({})", e.getClass().getSimpleName());
        }
    }
}
