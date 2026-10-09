package com.akshara.notifications;

import java.util.Map;
import java.util.UUID;

/**
 * Hands messages of one or more channels to a provider (an SMS gateway, the WhatsApp Cloud API, an email service).
 * The dispatcher asks the senders in {@link org.springframework.core.annotation.Order} order and uses the first that
 * supports the message's channel; {@link SimulatedMessageSender} comes last and accepts everything, so a channel with
 * no real provider configured is simulated, never sent.
 *
 * <p>Implementations must not log or put recipients or message text into exception messages: the dispatcher stores
 * {@link MessageSendException#getMessage()} in the school's message log.
 */
public interface MessageSender {

    boolean supports(Channel channel);

    /**
     * Sends one message. Returns how it went ({@link MessageStatus#SENT} or {@link MessageStatus#SIMULATED}); throws
     * {@link MessageSendException} when the provider refused it or could not be reached.
     */
    SendResult send(OutgoingMessage message) throws MessageSendException;

    /** Everything a provider needs to deliver one message. {@code body} is already rendered. */
    record OutgoingMessage(UUID id, UUID tenantId, Channel channel, String recipient, String templateKey,
            String language, Map<String, String> params, String body) {
    }

    /** A successful hand-over. {@code providerRef} is the provider's id for the message, when it gives one. */
    record SendResult(MessageStatus status, String providerRef) {

        public SendResult {
            if (status != MessageStatus.SENT && status != MessageStatus.SIMULATED) {
                throw new IllegalArgumentException("A send result is SENT or SIMULATED");
            }
        }

        public static SendResult sent(String providerRef) {
            return new SendResult(MessageStatus.SENT, providerRef);
        }

        public static SendResult simulated() {
            return new SendResult(MessageStatus.SIMULATED, null);
        }
    }
}
