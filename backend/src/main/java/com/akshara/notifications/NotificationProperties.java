package com.akshara.notifications;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Outbox settings ({@code akshara.notifications.*}). The dispatcher wakes up every {@code interval}, sends at most
 * {@code batchSize} messages per school per round, and retries a failed message after {@code retryBase}, doubling
 * the wait each time up to {@code retryMax}, until {@code maxAttempts}.
 */
@ConfigurationProperties("akshara.notifications")
public record NotificationProperties(@DefaultValue Dispatch dispatch) {

    public record Dispatch(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("PT15S") Duration interval,
            @DefaultValue("50") int batchSize,
            @DefaultValue("20") int schoolsPerRound,
            @DefaultValue("5") int maxAttempts,
            @DefaultValue("PT1M") Duration retryBase,
            @DefaultValue("PT1H") Duration retryMax) {
    }
}
