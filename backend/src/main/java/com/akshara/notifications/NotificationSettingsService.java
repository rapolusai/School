package com.akshara.notifications;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;

/** Reads and changes the current school's message settings. */
@Service
@Transactional
public class NotificationSettingsService {

    private final NotificationSettingsRepository settings;
    private final AuditService audit;

    public NotificationSettingsService(NotificationSettingsRepository settings, AuditService audit) {
        this.settings = settings;
        this.audit = audit;
    }

    /** The school's settings, or the defaults when it never changed them. */
    @Transactional(readOnly = true)
    public MessageSettings current() {
        TenantContext.require();
        return settings.findCurrent().map(NotificationSettings::values).orElse(MessageSettings.DEFAULTS);
    }

    public MessageSettings update(MessageSettings values, Actor actor) {
        TenantContext.require();
        if (values.alertLanguage() == null || !MessageTemplates.LANGUAGES.contains(values.alertLanguage())) {
            throw ApiException.badRequest("Choose English or Hindi.", "alertLanguage");
        }
        if (values.quietHoursEnabled() && values.quietHoursStart().equals(values.quietHoursEnd())) {
            throw ApiException.badRequest("Quiet hours must end at a different time than they start.",
                    "quietHoursEnd");
        }
        NotificationSettings row = settings.findCurrent().orElse(null);
        if (row == null) {
            settings.saveAndFlush(new NotificationSettings(values));
        } else {
            row.apply(values);
            settings.saveAndFlush(row);
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("absenceAlertsEnabled", values.absenceAlertsEnabled());
        details.put("absenceAlertChannel", values.absenceAlertChannel().name());
        details.put("alertLanguage", values.alertLanguage());
        details.put("quietHours", values.quietHoursEnabled()
                ? values.quietHoursStart() + "-" + values.quietHoursEnd() : "off");
        if (actor == null) {
            audit.record("notification_settings.updated", "notification_settings", TenantContext.require(), details);
        } else {
            audit.record(actor, "notification_settings.updated", "notification_settings", TenantContext.require(),
                    details);
        }
        return values;
    }
}
