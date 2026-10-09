package com.akshara.communication;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.communication.AudienceResolver.Audience;
import com.akshara.communication.AudienceResolver.Contact;
import com.akshara.communication.AudienceResolver.Person;
import com.akshara.communication.AudienceResolver.Resolution;
import com.akshara.communication.CommunicationTypes.NoticeChannel;
import com.akshara.communication.CommunicationTypes.RecipientKind;
import com.akshara.communication.CommunicationTypes.Source;
import com.akshara.notifications.Channel;
import com.akshara.notifications.MessageTemplates;
import com.akshara.notifications.NotificationQueue;
import com.akshara.notifications.NotificationQueue.Outcome;
import com.akshara.notifications.NotificationQueue.Recipient;
import com.akshara.notifications.NotificationQueue.Related;
import com.akshara.notifications.NotificationSettingsService;
import com.akshara.platform.TenantDirectory;
import com.akshara.shared.TenantContext;

/**
 * Sends a circular, inside the caller's transaction: works out the recipients now, puts it on their notice boards,
 * queues one message per phone number or email address on each chosen channel (each with a dedupe key, so sending
 * twice never messages anyone twice), records the counts, audits it and publishes {@link CircularSent}. The outbox
 * sends the messages later and keeps them waiting through the school's quiet hours.
 */
@Component
class CircularSender {

    static final int SUMMARY_LENGTH = 300;

    private final CircularRepository circulars;
    private final CircularTargetRepository targets;
    private final CircularRecipientRepository recipients;
    private final AudienceResolver audiences;
    private final NotificationQueue queue;
    private final NotificationSettingsService messageSettings;
    private final TenantDirectory tenants;
    private final AuditService audit;
    private final ApplicationEventPublisher events;

    CircularSender(CircularRepository circulars, CircularTargetRepository targets,
            CircularRecipientRepository recipients, AudienceResolver audiences, NotificationQueue queue,
            NotificationSettingsService messageSettings, TenantDirectory tenants, AuditService audit,
            ApplicationEventPublisher events) {
        this.circulars = circulars;
        this.targets = targets;
        this.recipients = recipients;
        this.audiences = audiences;
        this.queue = queue;
        this.messageSettings = messageSettings;
        this.tenants = tenants;
        this.audit = audit;
        this.events = events;
    }

    /** The audience saved with a circular. */
    Audience audienceOf(Circular circular) {
        if (circular.isWholeSchool()) {
            return Audience.everyone();
        }
        Set<UUID> classIds = new LinkedHashSet<>();
        Set<UUID> sectionIds = new LinkedHashSet<>();
        Set<String> roles = new LinkedHashSet<>();
        for (CircularTarget t : targets.findByCircularId(circular.getId())) {
            if (t.getClassId() != null) {
                classIds.add(t.getClassId());
            } else if (t.getSectionId() != null) {
                sectionIds.add(t.getSectionId());
            } else if (t.getRoleCode() != null) {
                roles.add(t.getRoleCode());
            }
        }
        return new Audience(false, classIds, sectionIds, roles);
    }

    /** Replaces the saved audience of a circular. */
    void saveAudience(Circular circular, Audience audience) {
        targets.deleteByCircular(circular.getId());
        if (audience.wholeSchool()) {
            return;
        }
        List<CircularTarget> rows = new ArrayList<>();
        audience.classIds().forEach(id -> rows.add(CircularTarget.ofClass(circular.getId(), id)));
        audience.sectionIds().forEach(id -> rows.add(CircularTarget.ofSection(circular.getId(), id)));
        audience.roles().forEach(code -> rows.add(CircularTarget.ofRole(circular.getId(), code)));
        targets.saveAll(rows);
    }

    /** The text messages use: a template and its shared parameters. */
    record Template(String key, Map<String, String> params) {
    }

    /** The circular template: school, title and the start of the body. */
    Template circularTemplate(Circular circular) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("school", schoolName());
        params.put("title", circular.getTitle());
        params.put("summary", summary(circular.getBody(), SUMMARY_LENGTH));
        return new Template(MessageTemplates.CIRCULAR, params);
    }

    /** Sends the circular now, recorded as sent by {@code by}. */
    Resolution send(Circular circular, Actor by, Instant at) {
        return send(circular, by, by.name(), at, circularTemplate(circular));
    }

    /**
     * Sends the circular now. {@code auditActor} is who the audit log names (no one for the scheduler and calendar
     * reminders); {@code sentByName} is shown with the circular.
     */
    Resolution send(Circular circular, Actor auditActor, String sentByName, Instant at, Template template) {
        Resolution who = audiences.resolve(audienceOf(circular));
        List<CircularRecipient> rows = new ArrayList<>();
        for (Person p : who.people()) {
            rows.add(new CircularRecipient(circular.getId(), p.userId(), p.kind(), at));
        }
        recipients.saveAll(rows);

        Set<NoticeChannel> channels = circular.channels();
        String language = messageSettings.current().alertLanguage();
        Related related = new Related("circular", circular.getId(), circular.getTitle());
        String keyBase = "circular:" + circular.getId() + ":";
        int sms = channels.contains(NoticeChannel.SMS) ? enqueue(template, language, related,
                phoneRecipients(who.phones(), Channel.SMS, keyBase + "sms:"), at) : 0;
        int whatsapp = channels.contains(NoticeChannel.WHATSAPP) ? enqueue(template, language, related,
                phoneRecipients(who.phones(), Channel.WHATSAPP, keyBase + "whatsapp:"), at) : 0;
        int email = 0;
        if (channels.contains(NoticeChannel.EMAIL)) {
            // Email has room for the whole circular.
            Map<String, String> full = MessageTemplates.CIRCULAR.equals(template.key())
                    ? Map.of("summary", summary(circular.getBody(), NoticeForms.MAX_BODY)) : Map.of();
            List<Recipient> list = who.emails().stream().map(c -> new Recipient(Channel.EMAIL, c.address(), c.name(),
                    full, null, keyBase + "email:" + c.key())).toList();
            email = enqueue(template, language, related, list, at);
        }

        Map<RecipientKind, Long> kinds = new LinkedHashMap<>();
        who.people().forEach(p -> kinds.merge(p.kind(), 1L, Long::sum));
        Circular.Counts counts = new Circular.Counts(who.people().size(),
                kinds.getOrDefault(RecipientKind.STAFF, 0L).intValue(), who.parents(),
                kinds.getOrDefault(RecipientKind.STUDENT, 0L).intValue(), sms, whatsapp, email);
        circular.sent(at, sentByName, counts);
        circulars.saveAndFlush(circular);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("title", circular.getTitle());
        details.put("audience", circular.getAudienceLabel());
        details.put("inApp", counts.inApp());
        details.put("sms", sms);
        details.put("whatsapp", whatsapp);
        details.put("email", email);
        if (circular.getSource() == Source.CALENDAR) {
            details.put("calendarReminder", true);
        }
        audit.record(auditActor, "circular.sent", "circular", circular.getId(), details);
        events.publishEvent(new CircularSent(TenantContext.require(), circular.getId(), circular.getCategory(),
                circular.getSource() == Source.CALENDAR, counts.inApp(), sms + whatsapp + email, at));
        return who;
    }

    String schoolName() {
        return tenants.profile(TenantContext.require()).map(p -> p.name()).orElse("");
    }

    private static List<Recipient> phoneRecipients(List<Contact> phones, Channel channel, String keyPrefix) {
        return phones.stream().map(c -> new Recipient(channel, c.address(), c.name(), Map.of(), null,
                keyPrefix + c.key())).toList();
    }

    private int enqueue(Template template, String language, Related related, List<Recipient> list, Instant at) {
        if (list.isEmpty()) {
            return 0;
        }
        return (int) queue.enqueue(template.key(), language, template.params(), related, list, at).stream()
                .filter(q -> q.outcome() != Outcome.DUPLICATE)
                .count();
    }

    /** The body on one line, cut to {@code max} characters at a word where possible. */
    static String summary(String body, int max) {
        String flat = body == null ? "" : body.replaceAll("\\s+", " ").strip();
        if (flat.length() <= max) {
            return flat;
        }
        String cut = flat.substring(0, max - 3);
        int space = cut.lastIndexOf(' ');
        if (space > max / 2) {
            cut = cut.substring(0, space);
        }
        return cut.strip() + "...";
    }
}
