package com.akshara.communication;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.communication.CommunicationSettings.Values;
import com.akshara.communication.CommunicationTypes.AckChannel;
import com.akshara.shared.TenantContext;

/** Reads and changes the current school's communication settings. */
@Service
@Transactional
public class CommunicationSettingsService {

    /** The settings as shown and changed on the settings page. */
    public record SettingsView(boolean teacherCircularsNeedApproval, boolean enquiryAckEnabled,
            AckChannel enquiryAckChannel) {

        static SettingsView of(Values v) {
            return new SettingsView(v.teacherCircularsNeedApproval(), v.enquiryAckEnabled(), v.enquiryAckChannel());
        }
    }

    private final CommunicationSettingsRepository settings;
    private final AuditService audit;

    CommunicationSettingsService(CommunicationSettingsRepository settings, AuditService audit) {
        this.settings = settings;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public SettingsView current() {
        return SettingsView.of(values());
    }

    private Values values() {
        TenantContext.require();
        return settings.findCurrent().map(CommunicationSettings::values).orElse(Values.DEFAULTS);
    }

    public SettingsView update(boolean teacherCircularsNeedApproval, boolean enquiryAckEnabled,
            AckChannel enquiryAckChannel, Actor actor) {
        TenantContext.require();
        Values values = new Values(teacherCircularsNeedApproval, enquiryAckEnabled,
                enquiryAckChannel == null ? AckChannel.SMS : enquiryAckChannel);
        CommunicationSettings row = settings.findCurrent().orElse(null);
        Values before = row == null ? Values.DEFAULTS : row.values();
        if (row == null) {
            settings.saveAndFlush(new CommunicationSettings(values));
        } else {
            row.apply(values);
            settings.saveAndFlush(row);
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("teacherCircularsNeedApproval", values.teacherCircularsNeedApproval());
        details.put("enquiryAckEnabled", values.enquiryAckEnabled());
        details.put("enquiryAckChannel", values.enquiryAckChannel().name());
        if (before.teacherCircularsNeedApproval() != values.teacherCircularsNeedApproval()) {
            details.put("approvalChanged", true);
        }
        audit.record(actor, "communication_settings.updated", "communication_settings", TenantContext.require(),
                details);
        return SettingsView.of(values);
    }
}
