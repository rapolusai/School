package com.akshara.shared;

import java.time.Duration;
import java.time.Instant;

/**
 * Counts hits per key in fixed time windows. The window of a key starts at its first hit and lasts as long as the
 * caller says; once it is over the next hit starts a new one. The application's store keeps the counts in PostgreSQL
 * ({@code platform.rate_limit}), so every API instance sees the same counts.
 */
public interface RateLimitStore {

    /** Counts one hit for the key and returns the hits in its current window, this one included. */
    int hit(String key, Duration window, Instant now);

    /** The hits in the key's current window, without counting one; 0 when there are none or the window is over. */
    int hits(String key, Instant now);

    /** Forgets the key, for example after a successful sign-in. */
    void clear(String key);
}
