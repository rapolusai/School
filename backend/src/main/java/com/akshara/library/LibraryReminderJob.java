package com.akshara.library;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Sends the library's overdue reminders to parents on a schedule ({@code akshara.library.reminders.cron}, 10:00 India
 * time Monday to Saturday by default), only for schools that switched library messages on. Each book is reminded at
 * most once a week. Switched off with {@code akshara.library.reminders.enabled=false}.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "akshara.library.reminders.enabled", havingValue = "true")
class LibraryReminderJob {

    private static final Logger log = LoggerFactory.getLogger(LibraryReminderJob.class);

    private final LibraryReminderRunner runner;

    LibraryReminderJob(LibraryReminderRunner runner) {
        this.runner = runner;
    }

    @Scheduled(cron = "${akshara.library.reminders.cron:0 0 10 * * MON-SAT}", zone = "Asia/Kolkata")
    void remindOverdueBooks() {
        try {
            int queued = runner.remindOverdue(LoanMath.today(), Instant.now());
            if (queued > 0) {
                log.info("Queued {} library overdue reminders", queued);
            }
        } catch (RuntimeException e) {
            log.warn("Library reminder round failed ({})", e.getClass().getSimpleName());
        }
    }
}
