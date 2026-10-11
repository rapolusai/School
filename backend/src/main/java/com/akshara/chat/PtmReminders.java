package com.akshara.chat;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.akshara.chat.ChatPeople.GuardianPhone;
import com.akshara.chat.ChatPeople.Person;
import com.akshara.chat.ChatPeople.StudentInfo;
import com.akshara.notifications.MessageSettings;
import com.akshara.notifications.MessageTemplates;
import com.akshara.notifications.NotificationQueue;
import com.akshara.notifications.NotificationQueue.Recipient;
import com.akshara.notifications.NotificationQueue.Related;
import com.akshara.notifications.NotificationSettingsService;
import com.akshara.platform.TenantDirectory;
import com.akshara.shared.TenantContext;

/**
 * PTM reminders to parents, the day before and an hour before their slot, only when the school switches them on (off
 * by default). They are queued in the notifications outbox when the slot is booked, to go out at those moments
 * (simulated: nothing leaves the system), and dropped when the booking is cancelled or moved. The parent who booked
 * gets them; for a booking the school made, the student's primary parent or guardian.
 */
@Component
class PtmReminders {

    static final Duration DAY_BEFORE = Duration.ofHours(24);
    static final Duration HOUR_BEFORE = Duration.ofHours(1);
    static final String RELATED = "ptm_booking";

    private static final String PHONE = "^\\+?[0-9]{8,15}$";

    private final NotificationQueue queue;
    private final NotificationSettingsService messageSettings;
    private final TenantDirectory tenants;
    private final ChatSettingsService settings;
    private final ChatPeople people;

    PtmReminders(NotificationQueue queue, NotificationSettingsService messageSettings, TenantDirectory tenants,
            ChatSettingsService settings, ChatPeople people) {
        this.queue = queue;
        this.messageSettings = messageSettings;
        this.tenants = tenants;
        this.settings = settings;
        this.people = people;
    }

    static String dayKey(UUID bookingId) {
        return "ptm-day:" + bookingId;
    }

    static String hourKey(UUID bookingId) {
        return "ptm-hour:" + bookingId;
    }

    /** Queues the reminders of a booking that are still ahead. Returns how many were queued. */
    int schedule(PtmEvent event, PtmBooking booking, PtmSlot slot, Instant now) {
        if (!settings.ptmRemindersEnabled() || event.isCancelled() || !booking.isBooked()) {
            return 0;
        }
        Instant start = event.at(slot.getStartsAt());
        Instant dayBefore = start.minus(DAY_BEFORE);
        Instant hourBefore = start.minus(HOUR_BEFORE);
        if (!hourBefore.isAfter(now)) {
            return 0;
        }
        String guardianName;
        String phone;
        if (booking.getParentUserId() != null) {
            Person parent = people.person(booking.getParentUserId()).orElse(null);
            Optional<String> number = people.parentPhone(booking.getParentUserId(), booking.getStudentId());
            if (parent == null || number.isEmpty()) {
                return 0;
            }
            guardianName = parent.name();
            phone = number.get().trim();
        } else {
            GuardianPhone primary = people.primaryPhone(booking.getStudentId()).orElse(null);
            if (primary == null) {
                return 0;
            }
            guardianName = primary.name();
            phone = primary.phone().trim();
        }
        if (!phone.matches(PHONE)) {
            return 0;
        }
        StudentInfo student = people.student(booking.getStudentId()).orElse(null);
        Person teacher = people.person(booking.getTeacherUserId()).orElse(null);
        if (student == null || !student.active() || teacher == null) {
            return 0;
        }
        String school = tenants.profile(TenantContext.require()).map(p -> p.name()).orElse("");
        Map<String, String> params = new LinkedHashMap<>();
        params.put("teacher", teacher.name());
        params.put("student", student.name());
        params.put("school", school);
        params.put("date", event.getMeetingDate().toString());
        params.put("time", slot.getStartsAt().toString());
        String template = MessageTemplates.PTM_REMINDER;
        if (event.getMode() == MeetingMode.VIDEO) {
            template = MessageTemplates.PTM_REMINDER_VIDEO;
        } else {
            params.put("place", event.getLocation() == null || event.getLocation().isBlank() ? school
                    : event.getLocation());
        }
        MessageSettings channel = messageSettings.current();
        Related related = new Related(RELATED, booking.getId(), student.name());
        int queued = 0;
        if (dayBefore.isAfter(now)) {
            queue.enqueue(template, channel.alertLanguage(), params, related, List.of(Recipient.phone(
                    channel.absenceAlertChannel(), phone, guardianName, Map.of("guardian", guardianName),
                    dayKey(booking.getId()))), dayBefore);
            queued++;
        }
        queue.enqueue(template, channel.alertLanguage(), params, related, List.of(Recipient.phone(
                channel.absenceAlertChannel(), phone, guardianName, Map.of("guardian", guardianName),
                hourKey(booking.getId()))), hourBefore);
        return queued + 1;
    }

    /** Drops the booking's reminders that have not gone out yet. */
    void unschedule(PtmBooking booking, String reason) {
        queue.skip(dayKey(booking.getId()), reason);
        queue.skip(hourKey(booking.getId()), reason);
    }
}
