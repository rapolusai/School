package com.akshara.notifications;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * The only sender built in: records every message as {@link MessageStatus#SIMULATED} and contacts no provider. Real
 * providers are added as further {@link MessageSender} beans with a higher precedence. Logs ids only, never a
 * recipient or the text.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
class SimulatedMessageSender implements MessageSender {

    private static final Logger log = LoggerFactory.getLogger(SimulatedMessageSender.class);

    @Override
    public boolean supports(Channel channel) {
        return true;
    }

    @Override
    public SendResult send(OutgoingMessage message) {
        log.debug("Simulated {} message {}", message.channel(), message.id());
        return SendResult.simulated();
    }
}
