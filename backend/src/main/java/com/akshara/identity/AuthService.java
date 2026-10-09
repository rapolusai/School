package com.akshara.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.platform.TenantDirectory;
import com.akshara.platform.TenantView;
import com.akshara.shared.AksharaProperties;
import com.akshara.shared.ApiException;
import com.akshara.shared.Ids;
import com.akshara.shared.JwtService;
import com.akshara.shared.LoginRateLimiter;
import com.akshara.shared.TenantContext;

/**
 * Signs school users in and out. A sign-in looks the school up first, then switches to that school before reading any
 * user rows, so row-level security is in force for every identity query.
 */
@Service
public class AuthService {

    /** A second refresh with a token that was rotated this recently is treated as a race, not as theft. */
    static final Duration ROTATION_GRACE = Duration.ofSeconds(30);

    private static final String GENERIC_FAILURE = "Those details don't match. Check the school code, email and password.";

    public record Session(String accessToken, long expiresIn, Me user, String refreshToken) {
    }

    /** A refresh that failed. {@code clearCookie} is false when the browser may already hold a newer cookie. */
    public static class RefreshRejected extends ApiException {

        private final boolean clearCookie;

        RefreshRejected(boolean clearCookie) {
            super(org.springframework.http.HttpStatus.UNAUTHORIZED, "Sign in required",
                    "Your session has ended. Sign in again.");
            this.clearCookie = clearCookie;
        }

        public boolean clearCookie() {
            return clearCookie;
        }
    }

    private record Candidate(UUID id, String passwordHash, boolean active) {
    }

    private record ParsedToken(UUID tenantId, String hash) {
    }

    private final TenantDirectory tenants;
    private final UserRepository users;
    private final RefreshTokenRepository tokens;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwt;
    private final LoginRateLimiter rateLimiter;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final AksharaProperties properties;
    private final SecureRandom random = new SecureRandom();
    private final String dummyHash;

    public AuthService(TenantDirectory tenants, UserRepository users, RefreshTokenRepository tokens,
            PasswordEncoder passwordEncoder, JwtService jwt, LoginRateLimiter rateLimiter, AuditService audit,
            PlatformTransactionManager transactionManager, AksharaProperties properties) {
        this.tenants = tenants;
        this.users = users;
        this.tokens = tokens;
        this.passwordEncoder = passwordEncoder;
        this.jwt = jwt;
        this.rateLimiter = rateLimiter;
        this.audit = audit;
        this.tx = new TransactionTemplate(transactionManager);
        this.properties = properties;
        // Checked when the school or email is unknown, so every failure takes about as long as a wrong password.
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    public Session login(String schoolCode, String email, String password, String clientIp) {
        String cleanEmail = email.trim();
        String key = "school|" + clientIp + "|" + schoolCode.trim().toLowerCase(Locale.ROOT) + "|"
                + cleanEmail.toLowerCase(Locale.ROOT);
        rateLimiter.check(key);

        Optional<TenantView> school = tenants.findByCode(schoolCode);
        if (school.isEmpty()) {
            passwordEncoder.matches(password, dummyHash);
            throw failed(key);
        }
        TenantView tenant = school.get();

        TransactionTemplate readOnly = new TransactionTemplate(tx.getTransactionManager());
        readOnly.setReadOnly(true);
        Candidate candidate = TenantContext.runAs(tenant.id(), () -> readOnly.execute(status ->
                users.findByEmailWithRoles(cleanEmail)
                        .map(u -> new Candidate(u.getId(), u.getPasswordHash(), u.isActive()))
                        .orElse(null)));

        // Hash outside any transaction so a slow check never holds a database connection.
        boolean passwordOk = passwordEncoder.matches(password, candidate == null ? dummyHash : candidate.passwordHash());
        boolean allowed = passwordOk && candidate != null && candidate.active() && tenant.status().allowsSignIn();

        Session session = TenantContext.runAs(tenant.id(), () -> tx.execute(status -> {
            if (candidate == null) {
                return null;
            }
            UserAccount user = users.findByIdWithRoles(candidate.id()).orElse(null);
            if (user == null) {
                return null;
            }
            Actor actor = new Actor(user.getId(), user.getName());
            if (!allowed) {
                audit.record(actor, "auth.login_failed", "user", user.getId(), Map.of());
                return null;
            }
            user.recordLogin();
            String refreshToken = issueRefreshToken(user.getId(), Ids.newId(), tenant.id()).raw();
            audit.record(actor, "auth.login", "user", user.getId(), Map.of());
            return session(user, tenant, refreshToken);
        }));
        if (session == null) {
            throw failed(key);
        }
        rateLimiter.reset(key);
        return session;
    }

    public Session refresh(String cookieValue) {
        ParsedToken parsed = parse(cookieValue).orElseThrow(() -> new RefreshRejected(true));
        TenantView tenant = tenants.findById(parsed.tenantId()).orElseThrow(() -> new RefreshRejected(true));
        Instant now = Instant.now();

        Object outcome = TenantContext.runAs(tenant.id(), () -> tx.execute(status -> {
            RefreshToken token = tokens.findByTokenHash(parsed.hash()).orElse(null);
            if (token == null) {
                return new RefreshRejected(true);
            }
            if (token.isRevoked()) {
                if (token.wasJustRotated(now, ROTATION_GRACE)) {
                    return new RefreshRejected(false);
                }
                // An old token came back: someone may have copied it. End every session in this chain.
                UUID userId = token.getUserId();
                tokens.revokeFamily(token.getFamilyId(), now);
                users.findById(userId).ifPresent(u -> audit.record(new Actor(u.getId(), u.getName()),
                        "auth.refresh_reused", "user", u.getId(), Map.of()));
                return new RefreshRejected(true);
            }
            if (token.isExpired(now)) {
                return new RefreshRejected(true);
            }
            UserAccount user = users.findByIdWithRoles(token.getUserId()).orElse(null);
            if (user == null || !user.isActive() || !tenant.status().allowsSignIn()) {
                tokens.revokeFamily(token.getFamilyId(), now);
                return new RefreshRejected(true);
            }
            IssuedToken next = issueRefreshToken(user.getId(), token.getFamilyId(), tenant.id());
            token.rotateTo(next.entity());
            return session(user, tenant, next.raw());
        }));
        if (outcome instanceof RefreshRejected rejected) {
            throw rejected;
        }
        return (Session) outcome;
    }

    /** Ends the session chain the cookie belongs to. Unknown or malformed cookies are ignored. */
    public void logout(String cookieValue) {
        Optional<ParsedToken> parsed = parse(cookieValue);
        if (parsed.isEmpty() || tenants.findById(parsed.get().tenantId()).isEmpty()) {
            return;
        }
        TenantContext.runAs(parsed.get().tenantId(), () -> tx.execute(status -> {
            tokens.findByTokenHash(parsed.get().hash()).ifPresent(token -> {
                UUID userId = token.getUserId();
                tokens.revokeFamily(token.getFamilyId(), Instant.now());
                users.findById(userId).ifPresent(u -> audit.record(new Actor(u.getId(), u.getName()),
                        "auth.logout", "user", u.getId(), Map.of()));
            });
            return null;
        }));
    }

    private Session session(UserAccount user, TenantView tenant, String refreshToken) {
        String accessToken = jwt.issueSchoolToken(user.getId(), tenant.id(), user.getName(), user.roleCodes(),
                user.permissions());
        return new Session(accessToken, jwt.ttlSeconds(), Me.of(user, tenant), refreshToken);
    }

    private record IssuedToken(String raw, RefreshToken entity) {
    }

    private IssuedToken issueRefreshToken(UUID userId, UUID familyId, UUID tenantId) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String raw = tenantId + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        RefreshToken entity = tokens.save(
                new RefreshToken(userId, familyId, sha256(raw), properties.auth().refreshTokenTtl()));
        return new IssuedToken(raw, entity);
    }

    private static Optional<ParsedToken> parse(String cookieValue) {
        if (cookieValue == null || cookieValue.length() > 200) {
            return Optional.empty();
        }
        int dot = cookieValue.indexOf('.');
        if (dot != 36) {
            return Optional.empty();
        }
        try {
            UUID tenantId = UUID.fromString(cookieValue.substring(0, dot));
            return Optional.of(new ParsedToken(tenantId, sha256(cookieValue)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private ApiException failed(String key) {
        rateLimiter.recordFailure(key);
        return ApiException.unauthorized(GENERIC_FAILURE);
    }
}
