package com.akshara.transport;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.akshara.notifications.MessageSettings;
import com.akshara.notifications.MessageTemplates;
import com.akshara.notifications.NotificationQueue;
import com.akshara.notifications.NotificationQueue.Outcome;
import com.akshara.notifications.NotificationQueue.Recipient;
import com.akshara.notifications.NotificationQueue.Related;
import com.akshara.notifications.NotificationSettingsService;
import com.akshara.students.StudentRoster.GuardianContact;
import com.akshara.transport.TransportPeople.StudentInfo;

/**
 * Tells parents about the bus through the notifications outbox (the simulated sender; nothing leaves the system):
 * their child boarded, was dropped at their stop, the bus is approaching their stop, or the bus is late. Each goes to
 * the student's primary parent or guardian on the school's alert channel and language; quiet hours hold them like
 * absence alerts. The school switches each kind on or off in the transport settings (boarded and dropped on,
 * approaching off, delays on by default). A mark corrected before its message went out cancels that message.
 */
@Component
class TransportAlerts {

    private static final String PHONE = "^\\+?[0-9]{8,15}$";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
            .withZone(TransportRules.INDIA);

    private final NotificationQueue queue;
    private final NotificationSettingsService messageSettings;
    private final TransportSettingsRepository settings;
    private final TransportPeople people;

    TransportAlerts(NotificationQueue queue, NotificationSettingsService messageSettings,
            TransportSettingsRepository settings, TransportPeople people) {
        this.queue = queue;
        this.messageSettings = messageSettings;
        this.settings = settings;
        this.people = people;
    }

    static String boardedKey(UUID tripId, UUID studentId) {
        return "transport:boarded:" + tripId + ":" + studentId;
    }

    static String droppedKey(UUID tripId, UUID studentId) {
        return "transport:dropped:" + tripId + ":" + studentId;
    }

    static String approachingKey(UUID tripId, UUID studentId) {
        return "transport:approaching:" + tripId + ":" + studentId;
    }

    static String delayedKey(UUID eventId, UUID studentId) {
        return "transport:delayed:" + eventId + ":" + studentId;
    }

    private record Switches(boolean boarded, boolean dropped, boolean approaching, boolean delay) {
    }

    private Switches switches() {
        return settings.findFirstBy()
                .map(s -> new Switches(s.isBoardedAlerts(), s.isDroppedAlerts(), s.isApproachingAlerts(),
                        s.isDelayAlerts()))
                .orElse(new Switches(TransportSettings.DEFAULT_BOARDED, TransportSettings.DEFAULT_DROPPED,
                        TransportSettings.DEFAULT_APPROACHING, TransportSettings.DEFAULT_DELAY));
    }

    /** The student's mark changed: cancels a message about the earlier mark that has not gone out yet. */
    int correct(UUID tripId, UUID studentId, BoardingStatus previous, BoardingStatus now) {
        if (previous == BoardingStatus.BOARDED && now != BoardingStatus.BOARDED) {
            return queue.skip(boardedKey(tripId, studentId), "Marked " + now.name().toLowerCase(Locale.ROOT)
                    + " before the message was sent");
        }
        if (previous == BoardingStatus.DROPPED && now != BoardingStatus.DROPPED) {
            return queue.skip(droppedKey(tripId, studentId), "Marked " + now.name().toLowerCase(Locale.ROOT)
                    + " before the message was sent");
        }
        return 0;
    }

    /** A student boarded at {@code place}: their stop in the morning, or null for the school in the afternoon. */
    int boarded(UUID tripId, String routeName, StudentInfo student, String place, Instant at) {
        if (!switches().boarded()) {
            return 0;
        }
        return send(MessageTemplates.TRANSPORT_BOARDED, List.of(student), routeName, place, at,
                s -> boardedKey(tripId, s), Map.of());
    }

    int dropped(UUID tripId, String routeName, StudentInfo student, String stopName, Instant at) {
        if (!switches().dropped()) {
            return 0;
        }
        return send(MessageTemplates.TRANSPORT_DROPPED, List.of(student), routeName, stopName, at,
                s -> droppedKey(tripId, s), Map.of());
    }

    /** The bus reached the stop before these students' stop. */
    int approaching(UUID tripId, String routeName, Collection<StudentInfo> students, String stopName, Instant at) {
        if (students.isEmpty() || !switches().approaching()) {
            return 0;
        }
        return send(MessageTemplates.TRANSPORT_APPROACHING, students, routeName, stopName, at,
                s -> approachingKey(tripId, s), Map.of());
    }

    /** The attendant reported a delay ({@code eventId} is the delay's entry in the boarding log). */
    int delayed(UUID eventId, String routeName, Collection<StudentInfo> students, int minutes, String reason,
            Instant at) {
        if (students.isEmpty() || !switches().delay()) {
            return 0;
        }
        return send(MessageTemplates.TRANSPORT_DELAYED, students, routeName, "", at, s -> delayedKey(eventId, s),
                Map.of("minutes", String.valueOf(minutes), "reason", reason));
    }

    private int send(String template, Collection<StudentInfo> students, String routeName, String stopName,
            Instant at, java.util.function.Function<UUID, String> key, Map<String, String> extra) {
        MessageSettings school = messageSettings.current();
        String schoolName = people.schoolName();
        Map<UUID, GuardianContact> contacts = people.guardians(students.stream().map(StudentInfo::id).toList());
        int queued = 0;
        for (StudentInfo student : students) {
            GuardianContact contact = contacts.get(student.id());
            if (contact == null || contact.phone() == null || !contact.phone().matches(PHONE)) {
                // No number to send to: the mark is saved all the same.
                continue;
            }
            Map<String, String> params = new LinkedHashMap<>();
            params.put("student", student.name());
            params.put("school", schoolName);
            params.put("route", routeName);
            // An afternoon pickup happens at school: the message names the school.
            params.put("stop", stopName == null ? schoolName : stopName);
            params.put("time", TIME.format(at));
            params.putAll(extra);
            Recipient recipient = Recipient.phone(school.absenceAlertChannel(), contact.phone(), contact.name(),
                    Map.of("guardian", contact.name()), key.apply(student.id()));
            String label = student.classLabel() == null ? student.name()
                    : student.name() + " (" + student.classLabel() + ")";
            queued += (int) queue.enqueue(template, school.alertLanguage(), params,
                    new Related("student", student.id(), label), List.of(recipient), at).stream()
                    .filter(q -> q.outcome() != Outcome.DUPLICATE)
                    .count();
        }
        return queued;
    }
}
