package com.akshara.communication;

import java.time.Instant;
import java.util.UUID;

import com.akshara.communication.CommunicationTypes.Category;

/**
 * Published (as a Spring application event, inside the sending transaction) when a circular is sent: straight away,
 * on approval, by the scheduler, or as a calendar reminder. For a future push-notification module and dashboards.
 * Carries ids and counts only.
 */
public record CircularSent(UUID tenantId, UUID circularId, Category category, boolean calendarReminder,
        int inAppRecipients, int messagesQueued, Instant sentAt) {
}
