package com.akshara.notifications;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Absence alert and quiet-hour settings of the school (settings.manage). */
@RestController
@RequestMapping("/api/notifications/settings")
public class NotificationSettingsController {

    static final String MANAGE = "hasAuthority('settings.manage')";
    static final String TIME = "^([01][0-9]|2[0-3]):[0-5][0-9]$";
    static final String TIME_MESSAGE = "Use a 24-hour time such as 21:00.";
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private final NotificationSettingsService settings;

    public NotificationSettingsController(NotificationSettingsService settings) {
        this.settings = settings;
    }

    public record SettingsView(boolean absenceAlertsEnabled, AlertChannel absenceAlertChannel, String alertLanguage,
            boolean quietHoursEnabled, String quietHoursStart, String quietHoursEnd) {

        static SettingsView of(MessageSettings s) {
            return new SettingsView(s.absenceAlertsEnabled(), s.absenceAlertChannel(), s.alertLanguage(),
                    s.quietHoursEnabled(), HH_MM.format(s.quietHoursStart()), HH_MM.format(s.quietHoursEnd()));
        }
    }

    public record SettingsRequest(
            @NotNull Boolean absenceAlertsEnabled,
            @NotNull AlertChannel absenceAlertChannel,
            @NotNull @Pattern(regexp = "^(en|hi)$", message = "Choose English or Hindi.") String alertLanguage,
            @NotNull Boolean quietHoursEnabled,
            @NotNull @Pattern(regexp = TIME, message = TIME_MESSAGE) String quietHoursStart,
            @NotNull @Pattern(regexp = TIME, message = TIME_MESSAGE) String quietHoursEnd) {
    }

    @GetMapping
    @PreAuthorize(MANAGE)
    public SettingsView get() {
        return SettingsView.of(settings.current());
    }

    @PutMapping
    @PreAuthorize(MANAGE)
    public SettingsView update(@Valid @RequestBody SettingsRequest request) {
        MessageSettings values = new MessageSettings(request.absenceAlertsEnabled(), request.absenceAlertChannel(),
                request.alertLanguage(), request.quietHoursEnabled(), LocalTime.parse(request.quietHoursStart()),
                LocalTime.parse(request.quietHoursEnd()));
        return SettingsView.of(settings.update(values, null));
    }
}
