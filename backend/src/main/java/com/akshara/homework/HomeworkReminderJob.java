package com.akshara.homework;

import java.time.Instant;
import java.time.LocalDate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Sends the evening-before homework reminders on a schedule ({@code akshara.homework.reminders.cron}, every hour
 * from 17:00 to 20:00 India time by default). The first run of the evening sends them; later runs find nothing left
 * to send, unless a school failed earlier. Switched off with {@code akshara.homework.reminders.enabled=false}.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "akshara.homework.reminders.enabled", havingValue = "true")
class HomeworkReminderJob {

    private static final Logger log = LoggerFactory.getLogger(HomeworkReminderJob.class);

    private final HomeworkReminderRunner runner;

    HomeworkReminderJob(HomeworkReminderRunner runner) {
        this.runner = runner;
    }

    @Scheduled(cron = "${akshara.homework.reminders.cron:0 0 17-20 * * *}", zone = "Asia/Kolkata")
    void remindHomeworkDueTomorrow() {
        try {
            int queued = runner.remindDue(LocalDate.now(HomeworkService.INDIA).plusDays(1), Instant.now());
            if (queued > 0) {
                log.info("Queued {} homework reminders", queued);
            }
        } catch (RuntimeException e) {
            log.warn("Homework reminder round failed ({})", e.getClass().getSimpleName());
        }
    }
}
