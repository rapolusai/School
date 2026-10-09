package com.akshara.notifications;

import java.time.LocalTime;

/**
 * A school's message settings: whether parents get absence alerts, on which channel and in which language, and the
 * quiet hours during which messages wait in the queue until the morning.
 */
public record MessageSettings(boolean absenceAlertsEnabled, AlertChannel absenceAlertChannel, String alertLanguage,
        boolean quietHoursEnabled, LocalTime quietHoursStart, LocalTime quietHoursEnd) {

    public static final MessageSettings DEFAULTS = new MessageSettings(true, AlertChannel.WHATSAPP_SMS,
            MessageTemplates.ENGLISH, true, LocalTime.of(21, 0), LocalTime.of(7, 0));
}
