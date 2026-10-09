package com.akshara.communication;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.communication.AudienceResolver.Audience;
import com.akshara.communication.CircularSender.Template;
import com.akshara.communication.CommunicationTypes.Category;
import com.akshara.communication.CommunicationTypes.EntryKind;
import com.akshara.communication.CommunicationTypes.NoticeChannel;
import com.akshara.communication.CommunicationTypes.ReminderChannel;
import com.akshara.communication.CommunicationTypes.Source;
import com.akshara.identity.RoleCatalog;
import com.akshara.notifications.MessageTemplates;
import com.akshara.notifications.NotificationSettingsService;

/**
 * Sends a calendar entry's reminder, inside the caller's transaction: a circular from the calendar, sent at once to the
 * entry's audience (the whole school; the parents and students of its classes; or all staff), on notice boards and,
 * when the entry asks for it, by SMS or WhatsApp. The entry remembers that its reminder went out.
 */
@Component
class CalendarReminders {

    private static final Actor CALENDAR = new Actor(null, null);
    private static final DateTimeFormatter ENGLISH_DATE = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter HINDI_DATE = DateTimeFormatter.ofPattern("dd MMMM yyyy",
            Locale.forLanguageTag("hi-IN"));
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final CalendarEntryRepository entries;
    private final CalendarEntryClassRepository entryClasses;
    private final CircularRepository circulars;
    private final CircularSender sender;
    private final AudienceResolver audiences;
    private final NotificationSettingsService messageSettings;
    private final AuditService audit;

    CalendarReminders(CalendarEntryRepository entries, CalendarEntryClassRepository entryClasses,
            CircularRepository circulars, CircularSender sender, AudienceResolver audiences,
            NotificationSettingsService messageSettings, AuditService audit) {
        this.entries = entries;
        this.entryClasses = entryClasses;
        this.circulars = circulars;
        this.sender = sender;
        this.audiences = audiences;
        this.messageSettings = messageSettings;
        this.audit = audit;
    }

    /** Sends the entry's reminder now. Returns the circular's id, or null when the audience has nobody left. */
    UUID send(CalendarEntry entry, Instant now) {
        Audience audience = audienceOf(entry);
        UUID circularId = null;
        if (!audience.isEmpty()) {
            String language = MessageTemplates.language(messageSettings.current().alertLanguage());
            boolean hindi = MessageTemplates.HINDI.equals(language);
            String title = (hindi ? "अनुस्मारक: " : "Reminder: ") + entry.getTitle();
            if (title.length() > NoticeForms.MAX_TITLE) {
                title = title.substring(0, NoticeForms.MAX_TITLE - 3) + "...";
            }
            Circular circular = new Circular(Source.CALENDAR, entry.getId(), CALENDAR, now);
            circular.edit(title, body(entry, hindi), category(entry.getKind()), audience.wholeSchool(),
                    audiences.label(audience), channels(entry), null);
            circulars.saveAndFlush(circular);
            sender.saveAudience(circular, audience);
            Map<String, String> params = new LinkedHashMap<>();
            params.put("school", sender.schoolName());
            params.put("title", entry.getTitle());
            params.put("date", entry.getStartsOn().toString());
            sender.send(circular, CALENDAR, null, now, new Template(MessageTemplates.CALENDAR_REMINDER, params));
            circularId = circular.getId();
        }
        entry.reminderSent(now);
        entries.saveAndFlush(entry);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("title", entry.getTitle());
        details.put("startsOn", entry.getStartsOn().toString());
        details.put("reminderDays", entry.getReminderDays());
        if (circularId != null) {
            details.put("circularId", circularId.toString());
        } else {
            details.put("nobodyToRemind", true);
        }
        audit.record(CALENDAR, "calendar_entry.reminder_sent", "calendar_entry", entry.getId(), details);
        return circularId;
    }

    private Audience audienceOf(CalendarEntry entry) {
        return switch (entry.getAudience()) {
            case SCHOOL -> Audience.everyone();
            case CLASSES -> {
                Set<UUID> classIds = entryClasses.findByEntryIdIn(List.of(entry.getId())).stream()
                        .map(CalendarEntryClass::getClassId).collect(Collectors.toCollection(LinkedHashSet::new));
                yield classIds.isEmpty() ? new Audience(false, Set.of(), Set.of(), Set.of())
                        : new Audience(false, classIds, Set.of(), Set.of(RoleCatalog.PARENT, RoleCatalog.STUDENT));
            }
            case STAFF -> new Audience(false, Set.of(), Set.of(), audiences.roleNames().keySet().stream()
                    .filter(NoticeAccess::isStaffRole).collect(Collectors.toSet()));
        };
    }

    static Category category(EntryKind kind) {
        return switch (kind) {
            case HOLIDAY -> Category.HOLIDAY;
            case EXAM -> Category.ACADEMIC;
            case EVENT, PTM -> Category.EVENT;
            case OTHER -> Category.GENERAL;
        };
    }

    private static Set<NoticeChannel> channels(CalendarEntry entry) {
        Set<NoticeChannel> channels = new LinkedHashSet<>();
        if (entry.reminderChannels().contains(ReminderChannel.SMS)) {
            channels.add(NoticeChannel.SMS);
        }
        if (entry.reminderChannels().contains(ReminderChannel.WHATSAPP)) {
            channels.add(NoticeChannel.WHATSAPP);
        }
        return channels;
    }

    /** The when (dates and time) on the first line, then the entry's description. */
    static String body(CalendarEntry entry, boolean hindi) {
        DateTimeFormatter dates = hindi ? HINDI_DATE : ENGLISH_DATE;
        StringBuilder when = new StringBuilder(dates.format(entry.getStartsOn()));
        if (!entry.getEndsOn().equals(entry.getStartsOn())) {
            when.append(hindi ? " से " : " to ").append(dates.format(entry.getEndsOn()));
        }
        if (entry.getStartTime() != null) {
            when.append(", ").append(TIME.format(entry.getStartTime()));
            if (entry.getEndTime() != null) {
                when.append("–").append(TIME.format(entry.getEndTime()));
            }
        }
        String text = entry.getTitle() + "\n" + when;
        if (entry.getDescription() != null && !entry.getDescription().isBlank()) {
            text += "\n\n" + entry.getDescription();
        }
        return text.length() > NoticeForms.MAX_BODY ? text.substring(0, NoticeForms.MAX_BODY) : text;
    }
}
