package com.akshara.communication;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.akshara.admissions.AdmissionsService;
import com.akshara.admissions.AdmissionsService.ApplicationDetail;
import com.akshara.admissions.AdmissionsService.GuardianView;
import com.akshara.admissions.EnquiryReceived;
import com.akshara.communication.CommunicationSettings.Values;
import com.akshara.communication.CommunicationTypes.AckChannel;
import com.akshara.notifications.AlertChannel;
import com.akshara.notifications.MessageTemplates;
import com.akshara.notifications.NotificationQueue;
import com.akshara.notifications.NotificationQueue.Recipient;
import com.akshara.notifications.NotificationQueue.Related;
import com.akshara.notifications.NotificationSettingsService;
import com.akshara.platform.TenantDirectory;
import com.akshara.shared.TenantContext;

/**
 * Thanks a family who sent an enquiry through the school's public admissions form: one message to the parent who
 * enquired, by SMS (or WhatsApp with SMS as the fallback, when the school prefers that), only because the form records
 * their consent to be contacted about the enquiry. The dedupe key is the application, so it is never sent twice.
 *
 * <p>The admissions module publishes {@link EnquiryReceived} inside the transaction that stores the enquiry, so the
 * message is queued in that same transaction (the outbox pattern the notifications module is built on): it is stored
 * if and only if the enquiry is. If the event ever arrives outside a transaction, a new one is opened as that school.
 * Nothing here can fail the enquiry: the phone number is checked first, and other errors are logged by type only.
 */
@Component
class EnquiryAcknowledgements {

    private static final Logger log = LoggerFactory.getLogger(EnquiryAcknowledgements.class);
    private static final String PHONE = "^\\+?[0-9]{8,15}$";

    private final AdmissionsService admissions;
    private final CommunicationSettingsRepository settings;
    private final NotificationQueue queue;
    private final NotificationSettingsService messageSettings;
    private final TenantDirectory tenants;
    private final TransactionTemplate tx;

    EnquiryAcknowledgements(AdmissionsService admissions, CommunicationSettingsRepository settings,
            NotificationQueue queue, NotificationSettingsService messageSettings, TenantDirectory tenants,
            PlatformTransactionManager transactions) {
        this.admissions = admissions;
        this.settings = settings;
        this.queue = queue;
        this.messageSettings = messageSettings;
        this.tenants = tenants;
        this.tx = new TransactionTemplate(transactions);
    }

    static String dedupeKey(EnquiryReceived event) {
        return "enquiry-ack:" + event.applicationId();
    }

    @EventListener
    void on(EnquiryReceived event) {
        try {
            boolean sameSchool = TenantContext.current().filter(event.tenantId()::equals).isPresent();
            if (sameSchool && TransactionSynchronizationManager.isActualTransactionActive()) {
                acknowledge(event);
            } else {
                TenantContext.runAs(event.tenantId(), () -> tx.execute(s -> acknowledge(event)));
            }
        } catch (RuntimeException e) {
            log.warn("Enquiry acknowledgement for application {} was not queued ({})", event.applicationId(),
                    e.getClass().getSimpleName());
        }
    }

    /** Returns true when a message was queued (or was already queued). */
    boolean acknowledge(EnquiryReceived event) {
        Values school = settings.findCurrent().map(CommunicationSettings::values).orElse(Values.DEFAULTS);
        if (!school.enquiryAckEnabled()) {
            return false;
        }
        ApplicationDetail application = admissions.detail(event.applicationId());
        if (application.consentAt() == null) {
            return false;
        }
        Optional<GuardianView> parent = application.guardians().stream().filter(GuardianView::primary).findFirst()
                .or(() -> application.guardians().stream().findFirst());
        String phone = parent.map(GuardianView::phone).map(String::strip).orElse(null);
        if (phone == null || !phone.matches(PHONE)) {
            return false;
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("school", tenants.profile(event.tenantId()).map(p -> p.name()).orElse(""));
        params.put("child", application.childName());
        params.put("class", application.className());
        AlertChannel channel = school.enquiryAckChannel() == AckChannel.WHATSAPP_SMS ? AlertChannel.WHATSAPP_SMS
                : AlertChannel.SMS;
        String name = parent.get().name();
        queue.enqueue(MessageTemplates.ENQUIRY_ACKNOWLEDGEMENT, messageSettings.current().alertLanguage(), params,
                new Related("application", event.applicationId(), application.childName() + " ("
                        + application.className() + ")"),
                List.of(Recipient.phone(channel, phone, name, Map.of(), dedupeKey(event))), event.at());
        return true;
    }
}
