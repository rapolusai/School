package com.akshara.communication;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Communication settings ({@code akshara.communication.*}). The scheduler wakes up every {@code interval} to send
 * scheduled circulars and calendar reminders that are due. {@code cost} holds the indicative prices, in paise, used
 * for the message estimate shown before sending: one SMS part, one WhatsApp message and one email. Nothing is charged
 * here; the estimate only helps the school decide.
 */
@ConfigurationProperties("akshara.communication")
public record CommunicationProperties(@DefaultValue Scheduler scheduler, @DefaultValue Cost cost) {

    public record Scheduler(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("PT1M") Duration interval,
            @DefaultValue("20") int batchSize,
            @DefaultValue("20") int schoolsPerRound) {
    }

    public record Cost(
            @DefaultValue("20") long smsPartPaise,
            @DefaultValue("12") long whatsappPaise,
            @DefaultValue("0") long emailPaise) {
    }
}
