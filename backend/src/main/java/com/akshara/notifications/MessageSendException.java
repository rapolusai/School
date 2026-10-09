package com.akshara.notifications;

/**
 * A provider did not take a message. The message is shown in the school's message log, so it must describe the
 * problem ("Provider timed out", "Number is not on WhatsApp") without any phone number, address or message text.
 * {@code retryable} is false when trying again cannot help (an invalid number); the message then fails at once and
 * its fallback channel, if any, takes over.
 */
public class MessageSendException extends Exception {

    private final boolean retryable;

    public MessageSendException(String safeReason, boolean retryable) {
        super(safeReason);
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }
}
