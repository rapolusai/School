package com.akshara.shared;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Rate limit counts in {@code platform.rate_limit}, shared by every API instance. Each hit is one atomic
 * {@code insert ... on conflict do update}, so concurrent requests on any instance never lose or double a count. Only
 * the SHA-256 of a key is stored, never the IP address or email in it. Every write runs in its own short transaction,
 * so a failed sign-in is counted even when the caller's transaction rolls back. Rows whose window is over are
 * deleted every few hundred hits.
 */
@Component
class JdbcRateLimitStore implements RateLimitStore {

    /** How often (in hits on this instance) rows whose window has passed are deleted. */
    static final int PRUNE_EVERY = 500;

    private static final String HIT = """
            insert into platform.rate_limit as r (key_hash, window_start, expires_at, hits) values (?, ?, ?, 1)
            on conflict (key_hash) do update set
                hits = case when r.expires_at <= excluded.window_start then 1 else r.hits + 1 end,
                window_start = case when r.expires_at <= excluded.window_start then excluded.window_start
                    else r.window_start end,
                expires_at = case when r.expires_at <= excluded.window_start then excluded.expires_at
                    else r.expires_at end
            returning hits""";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final AtomicInteger calls = new AtomicInteger();

    JdbcRateLimitStore(DataSource dataSource, PlatformTransactionManager transactions) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.tx = new TransactionTemplate(transactions);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public int hit(String key, Duration window, Instant now) {
        Integer hits = tx.execute(s -> jdbc.queryForObject(HIT, Integer.class, hash(key), at(now),
                at(now.plus(window))));
        if (calls.incrementAndGet() % PRUNE_EVERY == 0) {
            prune(now);
        }
        return hits == null ? 1 : hits;
    }

    @Override
    public int hits(String key, Instant now) {
        List<Integer> found = tx.execute(s -> jdbc.queryForList(
                "select hits from platform.rate_limit where key_hash = ? and expires_at > ?", Integer.class,
                hash(key), at(now)));
        return found == null || found.isEmpty() ? 0 : found.getFirst();
    }

    @Override
    public void clear(String key) {
        tx.executeWithoutResult(s -> jdbc.update("delete from platform.rate_limit where key_hash = ?", hash(key)));
    }

    /** Deletes every row whose window is over. Returns how many went. */
    int prune(Instant now) {
        Integer deleted = tx.execute(s -> jdbc.update("delete from platform.rate_limit where expires_at <= ?",
                at(now)));
        return deleted == null ? 0 : deleted;
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    static String hash(String key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
