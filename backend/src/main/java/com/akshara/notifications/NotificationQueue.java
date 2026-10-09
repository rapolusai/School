package com.akshara.notifications;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.shared.TenantContext;

import tools.jackson.databind.json.JsonMapper;

/**
 * The outbox other modules write to. Call it inside your own transaction: the messages are stored with your change
 * and sent later by {@link MessageDispatcher}, so a rolled-back change never sends anything and a provider outage
 * never fails your request.
 *
 * <p>A dedupe key makes queuing idempotent: a second message with the same key in the same school is not created.
 * A message that was skipped (see {@link #skip}) is queued again when its key comes back.
 */
@Service
public class NotificationQueue {

    /**
     * One recipient. {@code params} are merged over the shared parameters (for example the guardian's own name).
     * {@code fallbackChannel}, when set, is tried with the same text if {@code channel} fails for good.
     */
    public record Recipient(Channel channel, String address, String name, Map<String, String> params,
            Channel fallbackChannel, String dedupeKey) {

        /** A parent's mobile number on the channel the school chose for alerts. */
        public static Recipient phone(AlertChannel preference, String phone, String name, Map<String, String> params,
                String dedupeKey) {
            return new Recipient(preference.first(), phone, name, params, preference.fallback(), dedupeKey);
        }
    }

    /** What the message is about, for the message log: for example a student, shown by name. */
    public record Related(String type, UUID id, String label) {
    }

    public enum Outcome {
        /** A new message is waiting to be sent. */
        CREATED,
        /** A message with this key had been skipped and is waiting to be sent again. */
        REQUEUED,
        /** A message with this key already exists; nothing changed. */
        DUPLICATE
    }

    public record Queued(UUID messageId, Channel channel, Outcome outcome) {
    }

    private final MessageRepository messages;
    private final JsonMapper json;

    public NotificationQueue(MessageRepository messages, JsonMapper json) {
        this.messages = messages;
        this.json = json;
    }

    /** Queues one message per recipient, to be sent as soon as the dispatcher next runs (outside quiet hours). */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Queued> enqueue(String templateKey, String language, Map<String, String> params, Related related,
            List<Recipient> recipients) {
        return enqueue(templateKey, language, params, related, recipients, Instant.now());
    }

    /** As {@link #enqueue(String, String, Map, Related, List)}, recorded as queued at {@code at}. */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Queued> enqueue(String templateKey, String language, Map<String, String> params, Related related,
            List<Recipient> recipients, Instant at) {
        TenantContext.require();
        String lang = MessageTemplates.language(language);
        List<Queued> result = new ArrayList<>();
        for (Recipient r : recipients) {
            checkAddress(r.channel(), r.address());
            Map<String, String> merged = new LinkedHashMap<>(params);
            if (r.params() != null) {
                merged.putAll(r.params());
            }
            String body = MessageTemplates.render(templateKey, lang, merged);
            String paramsJson = json.writeValueAsString(merged);
            Message existing = r.dedupeKey() == null ? null : messages.findByDedupeKey(r.dedupeKey()).orElse(null);
            if (existing != null) {
                if (existing.getStatus() == MessageStatus.SKIPPED) {
                    existing.requeue(r.name(), paramsJson, body, at);
                    messages.saveAndFlush(existing);
                    result.add(new Queued(existing.getId(), existing.getChannel(), Outcome.REQUEUED));
                } else {
                    result.add(new Queued(existing.getId(), existing.getChannel(), Outcome.DUPLICATE));
                }
                continue;
            }
            Message message = messages.saveAndFlush(new Message(r.channel(), r.address().trim(), r.name(), templateKey,
                    lang, paramsJson, body, r.fallbackChannel(),
                    related == null ? null : related.type(), related == null ? null : related.id(),
                    related == null ? null : related.label(), r.dedupeKey(), at));
            result.add(new Queued(message.getId(), message.getChannel(), Outcome.CREATED));
        }
        return result;
    }

    /**
     * Marks the message with this dedupe key (and its fallback, if one was queued) as skipped, as long as it has not
     * been sent yet. Returns how many messages were skipped.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int skip(String dedupeKey, String reason) {
        TenantContext.require();
        List<String> keys = new ArrayList<>();
        keys.add(dedupeKey);
        Arrays.stream(Channel.values()).forEach(c -> keys.add(fallbackKey(dedupeKey, c)));
        int skipped = 0;
        for (Message m : messages.findByDedupeKeyIn(keys)) {
            if (m.skip(reason)) {
                skipped++;
            }
        }
        messages.flush();
        return skipped;
    }

    /**
     * Marks every message about one thing (see {@link Related}) that has not been sent yet as skipped, for example
     * when a circular is withdrawn. Messages already sent stay as they are. Returns how many were skipped.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int skipRelated(String relatedType, UUID relatedId, String reason) {
        TenantContext.require();
        int skipped = 0;
        for (Message m : messages.queuedAbout(relatedType, relatedId)) {
            if (m.skip(reason)) {
                skipped++;
            }
        }
        messages.flush();
        return skipped;
    }

    /** The dedupe key of the fallback message created when the first channel fails. */
    static String fallbackKey(String dedupeKey, Channel channel) {
        return dedupeKey == null ? null : dedupeKey + ":" + channel.name().toLowerCase(Locale.ROOT);
    }

    private static void checkAddress(Channel channel, String address) {
        if (channel == null || address == null || address.isBlank()) {
            throw new IllegalArgumentException("A message needs a channel and an address");
        }
        boolean ok = channel == Channel.EMAIL ? address.contains("@") : address.trim().matches("^\\+?[0-9]{8,15}$");
        if (!ok) {
            throw new IllegalArgumentException("The address does not suit the " + channel + " channel");
        }
    }
}
