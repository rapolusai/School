package com.akshara.communication;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Runs the communication scheduler on a timer ({@code akshara.communication.scheduler.interval}, a minute by
 * default). Tests and one-off tasks switch it off with {@code akshara.communication.scheduler.enabled=false} and call
 * {@link CommunicationScheduler} themselves.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "akshara.communication.scheduler.enabled", havingValue = "true", matchIfMissing = true)
class CommunicationConfig {

    private static final Logger log = LoggerFactory.getLogger(CommunicationConfig.class);

    private final CommunicationScheduler scheduler;

    CommunicationConfig(CommunicationScheduler scheduler) {
        this.scheduler = scheduler;
    }

    @Scheduled(initialDelayString = "${akshara.communication.scheduler.interval:PT1M}",
            fixedDelayString = "${akshara.communication.scheduler.interval:PT1M}")
    void sendDue() {
        try {
            scheduler.runDue(Instant.now());
        } catch (RuntimeException e) {
            // Keep the schedule alive; the next round retries. The type is enough to investigate without leaking data.
            log.warn("Communication round failed ({})", e.getClass().getSimpleName());
        }
    }
}
