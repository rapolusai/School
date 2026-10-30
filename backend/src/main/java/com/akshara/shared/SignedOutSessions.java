package com.akshara.shared;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sessions ended by signing out, in {@code platform.signed_out_session}, shared by every API instance. An access
 * token carries its session id; {@link SecurityConfig} refuses a token whose session is listed, so signing out ends
 * the access token at once instead of when it expires. A row is kept only as long as an access token can live.
 */
@Component
public class SignedOutSessions {

    /** How often (in sign-outs on this instance) rows that no longer matter are deleted. */
    static final int PRUNE_EVERY = 200;

    /** Covers clock differences between API instances. */
    private static final Duration SKEW = Duration.ofMinutes(1);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Duration keepFor;
    private final AtomicInteger calls = new AtomicInteger();

    SignedOutSessions(DataSource dataSource, PlatformTransactionManager transactions, AksharaProperties properties) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.tx = new TransactionTemplate(transactions);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.keepFor = properties.auth().accessTokenTtl().plus(SKEW);
    }

    /** Ends every access token of this session. Its own short transaction, so it holds even if the caller's fails. */
    public void signOut(UUID sessionId, Instant now) {
        tx.executeWithoutResult(s -> jdbc.update("""
                insert into platform.signed_out_session (session_id, expires_at) values (?, ?)
                on conflict (session_id) do update set expires_at = excluded.expires_at""",
                sessionId, at(now.plus(keepFor))));
        if (calls.incrementAndGet() % PRUNE_EVERY == 0) {
            tx.executeWithoutResult(s -> jdbc.update("delete from platform.signed_out_session where expires_at <= ?",
                    at(now)));
        }
    }

    /** Whether the session was signed out (and its tokens could still be alive). */
    public boolean isSignedOut(UUID sessionId, Instant now) {
        Boolean listed = jdbc.queryForObject(
                "select exists (select 1 from platform.signed_out_session where session_id = ? and expires_at > ?)",
                Boolean.class, sessionId, at(now));
        return Boolean.TRUE.equals(listed);
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
