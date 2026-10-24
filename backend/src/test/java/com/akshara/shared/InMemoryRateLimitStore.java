package com.akshara.shared;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/** The same fixed windows as {@code JdbcRateLimitStore}, in memory, for the limiters' unit tests. */
public class InMemoryRateLimitStore implements RateLimitStore {

    private record Window(Instant expiresAt, int hits) {
    }

    private final Map<String, Window> windows = new HashMap<>();

    @Override
    public synchronized int hit(String key, Duration window, Instant now) {
        Window current = windows.get(key);
        Window next = current == null || !current.expiresAt().isAfter(now) ? new Window(now.plus(window), 1)
                : new Window(current.expiresAt(), current.hits() + 1);
        windows.put(key, next);
        return next.hits();
    }

    @Override
    public synchronized int hits(String key, Instant now) {
        Window current = windows.get(key);
        return current == null || !current.expiresAt().isAfter(now) ? 0 : current.hits();
    }

    @Override
    public synchronized void clear(String key) {
        windows.remove(key);
    }
}
