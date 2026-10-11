package com.akshara.chat;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.chat.ChatForms.AwayForm;
import com.akshara.chat.ChatForms.SettingsForm;
import com.akshara.chat.ChatViews.Availability;
import com.akshara.chat.ChatViews.Away;
import com.akshara.chat.ChatViews.Settings;
import com.akshara.communication.SchoolCalendar;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;

/**
 * The school's hours for messages (staff availability), teachers' "away" notes, and when a teacher answers next: the
 * notice a parent sees when writing outside those hours, and the moment a notification to the teacher is held until.
 */
@Service
@Transactional
class ChatSettingsService {

    private final ChatSettingsRepository settings;
    private final StaffAwayRepository away;
    private final SchoolCalendar calendar;
    private final AuditService audit;

    ChatSettingsService(ChatSettingsRepository settings, StaffAwayRepository away, SchoolCalendar calendar,
            AuditService audit) {
        this.settings = settings;
        this.away = away;
        this.calendar = calendar;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    Settings current() {
        TenantContext.require();
        return settings.findFirstBy().map(ChatSettingsService::view)
                .orElse(new Settings(ChatSettings.fromBits(ChatSettings.DEFAULT_DAYS), ChatSettings.DEFAULT_FROM,
                        ChatSettings.DEFAULT_TO, true, false));
    }

    Settings update(SettingsForm form, Actor actor, Instant now) {
        TenantContext.require();
        if (form.availableDays().stream().anyMatch(d -> d == null)) {
            throw ApiException.badRequest("Choose the days staff answer messages.", "availableDays");
        }
        if (!form.availableTo().isAfter(form.availableFrom())) {
            throw ApiException.badRequest("The end must be after the start.", "availableTo");
        }
        ChatSettings row = row(now);
        row.availability(new LinkedHashSet<>(form.availableDays()), form.availableFrom(), form.availableTo(),
                form.notifyNewMessages());
        settings.saveAndFlush(row);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("availableDays", row.getAvailableDays().stream().map(DayOfWeek::name).toList());
        details.put("availableFrom", row.getAvailableFrom().toString());
        details.put("availableTo", row.getAvailableTo().toString());
        details.put("notifyNewMessages", row.isNotifyNewMessages());
        audit.record(actor, "chat_settings.updated", "chat_settings", row.getId(), details);
        return view(row);
    }

    /** The school's settings row, made with the defaults when it has none yet. */
    ChatSettings row(Instant now) {
        return settings.findFirstBy().orElseGet(() -> settings.saveAndFlush(new ChatSettings(now)));
    }

    /** Switches PTM reminders on or off. Returns the settings row's id. */
    UUID ptmReminders(boolean enabled, Instant now) {
        ChatSettings row = row(now);
        row.ptmReminders(enabled);
        settings.saveAndFlush(row);
        return row.getId();
    }

    boolean notifyNewMessages() {
        return settings.findFirstBy().map(ChatSettings::isNotifyNewMessages).orElse(true);
    }

    boolean ptmRemindersEnabled() {
        return settings.findFirstBy().map(ChatSettings::isPtmRemindersEnabled).orElse(false);
    }

    ChatHours hours() {
        return settings.findFirstBy()
                .map(s -> new ChatHours(Set.copyOf(s.getAvailableDays()), s.getAvailableFrom(), s.getAvailableTo()))
                .orElse(ChatHours.DEFAULT);
    }

    // ------------------------------------------------------------------ away

    @Transactional(readOnly = true)
    Away away(UUID userId, LocalDate today) {
        return away.findByUserId(userId).filter(a -> a.awayOn(today))
                .map(a -> new Away(true, a.getReturnsOn(), a.getNote()))
                .orElse(new Away(false, null, null));
    }

    /** Sets or clears (null {@code returnsOn}) the caller's away note. */
    Away setAway(UUID userId, AwayForm form, Actor actor, LocalDate today, Instant now) {
        TenantContext.require();
        StaffAway existing = away.findByUserId(userId).orElse(null);
        if (form.returnsOn() == null) {
            if (existing != null) {
                away.delete(existing);
                away.flush();
                audit.record(actor, "chat_away.updated", "chat_away", userId, Map.of("away", false));
            }
            return new Away(false, null, null);
        }
        if (!form.returnsOn().isAfter(today)) {
            throw ApiException.badRequest("Choose the day you are back, after today.", "returnsOn");
        }
        if (form.returnsOn().isAfter(today.plusDays(ChatRules.MAX_AWAY_DAYS))) {
            throw ApiException.badRequest("You can be away for at most " + ChatRules.MAX_AWAY_DAYS + " days.",
                    "returnsOn");
        }
        String note = form.note() == null || form.note().isBlank() ? null : form.note().trim();
        if (existing == null) {
            existing = new StaffAway(userId, form.returnsOn(), note, now);
        } else {
            existing.change(form.returnsOn(), note);
        }
        away.saveAndFlush(existing);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("away", true);
        details.put("returnsOn", form.returnsOn().toString());
        audit.record(actor, "chat_away.updated", "chat_away", userId, details);
        return new Away(true, existing.getReturnsOn(), existing.getNote());
    }

    /** The staff among {@code userIds} who are away today, by user id. */
    Map<UUID, StaffAway> awayOf(Collection<UUID> userIds, LocalDate today) {
        Map<UUID, StaffAway> result = new HashMap<>();
        if (userIds.isEmpty()) {
            return result;
        }
        away.findByUserIdIn(Set.copyOf(userIds)).stream().filter(a -> a.awayOn(today))
                .forEach(a -> result.put(a.getUserId(), a));
        return result;
    }

    // ------------------------------------------------------------------ availability

    /** When the member of staff answers messages: open now, or the next moment they do, and their away note. */
    Availability availability(UUID staffUserId, Instant now) {
        LocalDate today = now.atZone(ChatRules.INDIA).toLocalDate();
        ChatHours hours = hours();
        StaffAway gone = awayOf(List.of(staffUserId), today).get(staffUserId);
        Instant next = hours.nextAllowed(now, holidaysFrom(today), gone == null ? null : gone.getReturnsOn());
        boolean open = next.equals(now);
        List<DayOfWeek> days = hours.days().stream().sorted().toList();
        return new Availability(open, open ? null : next, days, hours.from(), hours.to(),
                gone == null ? null : gone.getReturnsOn(), gone == null ? null : gone.getNote());
    }

    /** When a notification to the member of staff about a message sent {@code now} may go out. */
    Instant nextAllowed(UUID staffUserId, Instant now) {
        LocalDate today = now.atZone(ChatRules.INDIA).toLocalDate();
        StaffAway gone = awayOf(List.of(staffUserId), today).get(staffUserId);
        return hours().nextAllowed(now, holidaysFrom(today), gone == null ? null : gone.getReturnsOn());
    }

    /** Whole-school holidays from today as far as the next working hour can be (an absence plus a long break). */
    private Predicate<LocalDate> holidaysFrom(LocalDate today) {
        Set<LocalDate> days = calendar.holidaysBetween(today,
                today.plusDays(ChatRules.MAX_AWAY_DAYS + ChatHours.LOOK_AHEAD_DAYS + 1L)).keySet();
        return days::contains;
    }

    private static Settings view(ChatSettings s) {
        return new Settings(s.getAvailableDays(), s.getAvailableFrom(), s.getAvailableTo(), s.isNotifyNewMessages(),
                s.isPtmRemindersEnabled());
    }
}
