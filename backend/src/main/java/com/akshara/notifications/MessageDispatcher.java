package com.akshara.notifications;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.akshara.notifications.MessageSender.OutgoingMessage;
import com.akshara.notifications.MessageSender.SendResult;
import com.akshara.shared.TenantContext;

import tools.jackson.databind.json.JsonMapper;

/**
 * Sends queued messages: finds the schools with messages due, then, as each school in turn, takes a small batch and
 * sends every message in its own short transaction, holding the row with {@code for update skip locked} so two API
 * instances never send the same message. During the school's quiet hours messages wait until the window ends. A
 * failed attempt is retried with a growing wait until the attempts run out; a message that fails for good hands over
 * to its fallback channel. Only ids are logged.
 */
@Service
public class MessageDispatcher {

    private static final Logger log = LoggerFactory.getLogger(MessageDispatcher.class);

    private final MessageRepository messages;
    private final NotificationSettingsRepository settings;
    private final List<MessageSender> senders;
    private final NotificationProperties.Dispatch config;
    private final TransactionTemplate tx;
    private final JsonMapper json;

    public MessageDispatcher(MessageRepository messages, NotificationSettingsRepository settings,
            List<MessageSender> senders, NotificationProperties properties, PlatformTransactionManager transactions,
            JsonMapper json) {
        this.messages = messages;
        this.settings = settings;
        this.senders = List.copyOf(senders);
        this.config = properties.dispatch();
        this.tx = new TransactionTemplate(transactions);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.json = json;
    }

    /**
     * One round over every school with messages due at {@code now}. Must run with no school selected. Returns how
     * many messages were finished (sent, simulated or failed for good).
     */
    public int dispatchDue(Instant now) {
        if (TenantContext.current().isPresent()) {
            throw new IllegalStateException("The dispatcher works across schools; run it with no school selected");
        }
        List<UUID> schools = tx.execute(s -> messages.tenantsWithDueMessages(now, config.schoolsPerRound()));
        int finished = 0;
        for (UUID school : schools == null ? List.<UUID>of() : schools) {
            try {
                finished += dispatchSchool(school, now);
            } catch (RuntimeException e) {
                log.warn("Sending messages for school {} stopped early ({})", school, e.getClass().getSimpleName());
            }
        }
        return finished;
    }

    /** Sends at most one batch of the school's messages that are due at {@code now}. */
    public int dispatchSchool(UUID tenantId, Instant now) {
        return TenantContext.runAs(tenantId, () -> {
            MessageSettings schoolSettings = tx.execute(s -> settings.findCurrent()
                    .map(NotificationSettings::values).orElse(MessageSettings.DEFAULTS));
            Instant holdUntil = QuietHours.holdUntil(now, schoolSettings).orElse(null);
            List<UUID> due = tx.execute(s -> messages.dueIds(now, Limit.of(config.batchSize())));
            int finished = 0;
            for (UUID id : due == null ? List.<UUID>of() : due) {
                if (Boolean.TRUE.equals(tx.execute(s -> dispatchOne(id, now, holdUntil)))) {
                    finished++;
                }
            }
            return finished;
        });
    }

    /** Returns true when the message reached a final state. */
    private boolean dispatchOne(UUID id, Instant now, Instant holdUntil) {
        Message message = messages.lockDue(id, now).orElse(null);
        if (message == null) {
            return false;
        }
        if (holdUntil != null) {
            message.holdUntil(holdUntil);
            return false;
        }
        MessageSender sender = senders.stream().filter(s -> s.supports(message.getChannel())).findFirst()
                .orElseThrow(() -> new IllegalStateException("No sender for " + message.getChannel()));
        try {
            SendResult result = sender.send(outgoing(message));
            message.delivered(result.status(), result.providerRef(), now);
            log.debug("Message {} {}", id, result.status());
            return true;
        } catch (MessageSendException e) {
            return failed(message, e.getMessage(), e.retryable(), now);
        } catch (RuntimeException e) {
            // The exception text may come from a provider library and could contain the recipient: keep only its type.
            return failed(message, "Unexpected error (" + e.getClass().getSimpleName() + ")", true, now);
        }
    }

    private boolean failed(Message message, String reason, boolean retryable, Instant now) {
        int attempt = message.getAttempts() + 1;
        boolean giveUp = !retryable || attempt >= config.maxAttempts();
        Instant retryAt = giveUp ? null : now.plus(backoff(attempt));
        message.failed(reason == null || reason.isBlank() ? "Not sent" : reason, retryAt);
        if (giveUp) {
            log.info("Message {} failed after {} attempts", message.getId(), attempt);
            if (message.getFallbackChannel() != null) {
                fallBack(message, now);
            }
        } else {
            log.info("Message {} attempt {} failed; next try at {}", message.getId(), attempt, retryAt);
        }
        return giveUp;
    }

    /** Queues the same text on the fallback channel, once per dedupe key. */
    private void fallBack(Message message, Instant now) {
        String key = NotificationQueue.fallbackKey(message.getDedupeKey(), message.getFallbackChannel());
        Message existing = key == null ? null : messages.findByDedupeKey(key).orElse(null);
        if (existing != null) {
            if (existing.getStatus() == MessageStatus.SKIPPED) {
                existing.requeue(message.getRecipientName(), message.getParams(), message.getBody(), now);
            }
            return;
        }
        Message fallback = messages.save(new Message(message.getFallbackChannel(), message.getRecipient(),
                message.getRecipientName(), message.getTemplateKey(), message.getLanguage(), message.getParams(),
                message.getBody(), null, message.getRelatedType(), message.getRelatedId(), message.getRelatedLabel(),
                key, now));
        log.info("Message {} continues as {} message {}", message.getId(), fallback.getChannel(), fallback.getId());
    }

    /** retryBase, then twice that, and so on, never more than retryMax. */
    Duration backoff(int attempt) {
        Duration wait = config.retryBase().multipliedBy(1L << Math.min(attempt - 1, 20));
        return wait.compareTo(config.retryMax()) > 0 ? config.retryMax() : wait;
    }

    @SuppressWarnings("unchecked")
    private OutgoingMessage outgoing(Message m) {
        Map<String, String> params = json.readValue(m.getParams(), Map.class);
        return new OutgoingMessage(m.getId(), m.getTenantId(), m.getChannel(), m.getRecipient(), m.getTemplateKey(),
                m.getLanguage(), Map.copyOf(params), m.getBody());
    }
}
