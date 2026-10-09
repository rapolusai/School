package com.akshara.notifications;

/**
 * Where a message is in its life: waiting to be sent, handed to a provider, given up on, recorded by the simulator
 * instead of being sent (no provider is connected), or deliberately not sent (no longer needed).
 */
public enum MessageStatus {
    QUEUED, SENT, FAILED, SIMULATED, SKIPPED
}
