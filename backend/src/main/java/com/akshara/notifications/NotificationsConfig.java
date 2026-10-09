package com.akshara.notifications;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Runs the dispatcher on a timer ({@code akshara.notifications.dispatch.interval}, 15 seconds by default). Tests and
 * one-off tasks switch it off with {@code akshara.notifications.dispatch.enabled=false} and call the dispatcher
 * themselves.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "akshara.notifications.dispatch.enabled", havingValue = "true", matchIfMissing = true)
class NotificationsConfig {

    private static final Logger log = LoggerFactory.getLogger(NotificationsConfig.class);

    private final MessageDispatcher dispatcher;

    NotificationsConfig(MessageDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Scheduled(initialDelayString = "${akshara.notifications.dispatch.interval:PT15S}",
            fixedDelayString = "${akshara.notifications.dispatch.interval:PT15S}")
    void dispatchDueMessages() {
        try {
            dispatcher.dispatchDue(Instant.now());
        } catch (RuntimeException e) {
            // Keep the schedule alive; the next round retries. The type is enough to investigate without leaking data.
            log.warn("Message dispatch round failed ({})", e.getClass().getSimpleName());
        }
    }
}
