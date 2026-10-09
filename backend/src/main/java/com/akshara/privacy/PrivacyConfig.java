package com.akshara.privacy;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Runs the export clean-up on a timer ({@code akshara.privacy.export-cleanup.interval}, hourly by default), so a file
 * is gone within an hour of its 7 days. Tests switch it off with {@code akshara.privacy.export-cleanup.enabled=false}
 * and call the clean-up themselves.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "akshara.privacy.export-cleanup.enabled", havingValue = "true", matchIfMissing = true)
class PrivacyConfig {

    private static final Logger log = LoggerFactory.getLogger(PrivacyConfig.class);

    private final ExportCleanup cleanup;

    PrivacyConfig(ExportCleanup cleanup) {
        this.cleanup = cleanup;
    }

    @Scheduled(initialDelayString = "${akshara.privacy.export-cleanup.initial-delay:PT2M}",
            fixedDelayString = "${akshara.privacy.export-cleanup.interval:PT1H}")
    void deleteExpiredExports() {
        try {
            int deleted = cleanup.purgeExpired(Instant.now());
            if (deleted > 0) {
                log.info("Deleted {} expired data export file(s)", deleted);
            }
        } catch (RuntimeException e) {
            // Keep the schedule alive; the next round tries again.
            log.warn("Export clean-up round failed ({})", e.getClass().getSimpleName());
        }
    }
}
