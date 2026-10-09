package com.akshara.notifications;

/** How a school wants alerts to parents sent: WhatsApp with SMS when WhatsApp fails, or SMS only. */
public enum AlertChannel {
    WHATSAPP_SMS(Channel.WHATSAPP, Channel.SMS),
    SMS(Channel.SMS, null);

    private final Channel first;
    private final Channel fallback;

    AlertChannel(Channel first, Channel fallback) {
        this.first = first;
        this.fallback = fallback;
    }

    public Channel first() {
        return first;
    }

    /** The channel used when the first one fails for good, or null. */
    public Channel fallback() {
        return fallback;
    }
}
