package com.akshara.attendance;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.akshara.academics.AcademicsDirectory.SectionInfo;
import com.akshara.notifications.MessageSettings;
import com.akshara.notifications.MessageTemplates;
import com.akshara.notifications.NotificationQueue;
import com.akshara.notifications.NotificationQueue.Outcome;
import com.akshara.notifications.NotificationQueue.Recipient;
import com.akshara.notifications.NotificationQueue.Related;
import com.akshara.notifications.NotificationSettingsService;
import com.akshara.platform.TenantDirectory;
import com.akshara.shared.TenantContext;
import com.akshara.students.StudentRoster;
import com.akshara.students.StudentRoster.GuardianContact;
import com.akshara.students.StudentRoster.RosterStudent;

/**
 * Tells parents when their child is marked absent: one alert to the primary parent or guardian the first time a
 * student is absent on a day, queued in the same transaction as the register. If the student is marked present (or
 * late, and so on) before the alert goes out, the alert is skipped. Uses the school's message settings for whether,
 * how and in which language to send.
 */
@Component
class AbsenceAlerts {

    record Result(int queued, int cancelled) {
    }

    private static final String PHONE = "^\\+?[0-9]{8,15}$";

    private final NotificationQueue queue;
    private final NotificationSettingsService settings;
    private final StudentRoster roster;
    private final TenantDirectory tenants;

    AbsenceAlerts(NotificationQueue queue, NotificationSettingsService settings, StudentRoster roster,
            TenantDirectory tenants) {
        this.queue = queue;
        this.settings = settings;
        this.roster = roster;
        this.tenants = tenants;
    }

    static String dedupeKey(UUID studentId, LocalDate date) {
        return "absence:" + studentId + ":" + date;
    }

    /**
     * @param newlyAbsent students who are absent now and were not absent before this save
     * @param noLongerAbsent students who were absent before this save and are not now, with their new mark
     */
    Result apply(SectionInfo section, LocalDate date, Collection<RosterStudent> newlyAbsent,
            Map<UUID, AttendanceStatus> noLongerAbsent, Instant at) {
        int cancelled = 0;
        for (Map.Entry<UUID, AttendanceStatus> e : noLongerAbsent.entrySet()) {
            cancelled += queue.skip(dedupeKey(e.getKey(), date),
                    "Marked " + e.getValue().name().toLowerCase(Locale.ROOT).replace('_', ' ')
                            + " before the alert was sent");
        }
        if (newlyAbsent.isEmpty()) {
            return new Result(0, cancelled);
        }
        MessageSettings school = settings.current();
        if (!school.absenceAlertsEnabled()) {
            return new Result(0, cancelled);
        }
        String schoolName = tenants.profile(TenantContext.require()).map(p -> p.name()).orElse("");
        Map<UUID, GuardianContact> contacts = roster.primaryGuardians(newlyAbsent.stream().map(RosterStudent::id)
                .toList());
        int queued = 0;
        for (RosterStudent student : newlyAbsent) {
            GuardianContact contact = contacts.get(student.id());
            if (contact == null || contact.phone() == null || !contact.phone().matches(PHONE)) {
                // No number to send to: the register is saved all the same.
                continue;
            }
            Map<String, String> params = new LinkedHashMap<>();
            params.put("student", student.fullName());
            params.put("class", section.label());
            params.put("school", schoolName);
            params.put("date", date.toString());
            Recipient recipient = Recipient.phone(school.absenceAlertChannel(), contact.phone(), contact.name(),
                    Map.of("guardian", contact.name()), dedupeKey(student.id(), date));
            queued += (int) queue.enqueue(MessageTemplates.ABSENCE_ALERT, school.alertLanguage(), params,
                    new Related("student", student.id(), student.fullName() + " (" + section.label() + ")"),
                    List.of(recipient), at).stream()
                    .filter(q -> q.outcome() != Outcome.DUPLICATE)
                    .count();
        }
        return new Result(queued, cancelled);
    }
}
