package com.akshara.platform;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.akshara.shared.AksharaProperties;
import com.akshara.shared.ApiException;
import com.akshara.shared.Ids;
import com.akshara.shared.JwtService;
import com.akshara.shared.Permissions;
import com.akshara.shared.TenantContext;

/**
 * Super Admin sessions: the same rotated, httpOnly refresh cookie school users have, kept in
 * {@code platform.platform_refresh_token}. A platform cookie value is {@code "p." + 32 random bytes}, which a school
 * refresh refuses (it expects a school id before the dot), and a school cookie is refused here; the access tokens it
 * gives are platform tokens with no school, which school endpoints refuse.
 */
@Service
public class PlatformSessions {

    private static final Logger log = LoggerFactory.getLogger(PlatformSessions.class);

    /** A second refresh with a token that was rotated this recently is treated as a race, not as theft. */
    static final Duration ROTATION_GRACE = Duration.ofSeconds(30);

    static final String PREFIX = "p.";

    public record Session(String accessToken, long expiresIn, Map<String, Object> user, String refreshToken) {

        /** The JSON body: the same shape as a school sign-in, without the refresh token (that goes in the cookie). */
        public Map<String, Object> body() {
            return Map.of("accessToken", accessToken, "expiresIn", expiresIn, "user", user);
        }
    }

    /** A refresh that failed. {@code clearCookie} is false when the browser may already hold a newer cookie. */
    public static class RefreshRejected extends ApiException {

        private final boolean clearCookie;

        RefreshRejected(boolean clearCookie) {
            super(HttpStatus.UNAUTHORIZED, "Sign in required", "Your session has ended. Sign in again.");
            this.clearCookie = clearCookie;
        }

        public boolean clearCookie() {
            return clearCookie;
        }
    }

    private final PlatformAdminRepository admins;
    private final PlatformRefreshTokenRepository tokens;
    private final JwtService jwt;
    private final AksharaProperties properties;
    private final TransactionTemplate tx;
    private final SecureRandom random = new SecureRandom();

    PlatformSessions(PlatformAdminRepository admins, PlatformRefreshTokenRepository tokens, JwtService jwt,
            AksharaProperties properties, PlatformTransactionManager transactions) {
        this.admins = admins;
        this.tokens = tokens;
        this.jwt = jwt;
        this.properties = properties;
        this.tx = new TransactionTemplate(transactions);
    }

    /** Starts a new session chain for an admin who just proved their password. Runs in the caller's transaction. */
    Session start(PlatformAdmin admin) {
        return session(admin, issue(admin.getId(), Ids.newId()));
    }

    /** Rotates the cookie and returns a fresh access token. */
    public Session refresh(String cookieValue) {
        Optional<String> hash = parse(cookieValue);
        if (hash.isEmpty() || TenantContext.current().isPresent()) {
            throw new RefreshRejected(true);
        }
        Instant now = Instant.now();
        Object outcome = tx.execute(status -> {
            PlatformRefreshToken token = tokens.findByTokenHash(hash.get()).orElse(null);
            if (token == null) {
                return new RefreshRejected(true);
            }
            boolean retry = token.isRevoked();
            if (retry) {
                if (!token.wasJustRotated(now, ROTATION_GRACE)) {
                    // An old token came back: someone may have copied it. End every session in this chain.
                    tokens.revokeFamily(token.getFamilyId(), now);
                    log.warn("Reuse of a rotated Super Admin refresh token; chain {} ended", token.getFamilyId());
                    return new RefreshRejected(true);
                }
                if (!tokens.existsByFamilyIdAndRevokedAtIsNull(token.getFamilyId())) {
                    return new RefreshRejected(false);
                }
            }
            if (token.isExpired(now)) {
                return new RefreshRejected(true);
            }
            PlatformAdmin admin = admins.findById(token.getAdminId()).orElse(null);
            if (admin == null || !admin.isActive()) {
                tokens.revokeFamily(token.getFamilyId(), now);
                return new RefreshRejected(true);
            }
            IssuedToken next = issue(admin.getId(), token.getFamilyId());
            if (!retry) {
                // A retried token keeps its first rotation time, so its grace period never extends.
                token.rotateTo(next.entity());
            }
            return session(admin, next);
        });
        if (outcome instanceof RefreshRejected rejected) {
            throw rejected;
        }
        return (Session) outcome;
    }

    /** Ends the chain the cookie belongs to. Unknown or malformed cookies are ignored. */
    public void logout(String cookieValue) {
        Optional<String> hash = parse(cookieValue);
        if (hash.isEmpty()) {
            return;
        }
        tx.executeWithoutResult(status -> tokens.findByTokenHash(hash.get())
                .ifPresent(token -> tokens.revokeFamily(token.getFamilyId(), Instant.now())));
    }

    Duration ttl() {
        return properties.auth().platformRefreshTokenTtl();
    }

    private Session session(PlatformAdmin admin, IssuedToken refresh) {
        Map<String, Object> me = new LinkedHashMap<>();
        me.put("id", admin.getId());
        me.put("name", admin.getName());
        me.put("email", admin.getEmail());
        me.put("roles", List.of("SUPER_ADMIN"));
        me.put("permissions", List.of(Permissions.PLATFORM_ADMIN));
        me.put("platformAdmin", true);
        me.put("tenant", null);
        return new Session(jwt.issuePlatformToken(admin.getId(), admin.getName()), jwt.ttlSeconds(), me,
                refresh.raw());
    }

    private record IssuedToken(String raw, PlatformRefreshToken entity) {
    }

    private IssuedToken issue(UUID adminId, UUID familyId) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String raw = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        PlatformRefreshToken entity = tokens.save(new PlatformRefreshToken(adminId, familyId, sha256(raw), ttl()));
        return new IssuedToken(raw, entity);
    }

    /** The hash of a well-formed platform cookie value; empty for anything else, such as a school cookie. */
    static Optional<String> parse(String cookieValue) {
        if (cookieValue == null || cookieValue.length() > 100 || !cookieValue.startsWith(PREFIX)
                || cookieValue.length() < PREFIX.length() + 40) {
            return Optional.empty();
        }
        return Optional.of(sha256(cookieValue));
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
