package com.akshara.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
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
import com.akshara.notifications.Channel;
import com.akshara.notifications.MessageTemplates;
import com.akshara.notifications.NotificationQueue;
import com.akshara.notifications.NotificationQueue.Recipient;
import com.akshara.notifications.NotificationQueue.Related;
import com.akshara.platform.TenantDirectory;
import com.akshara.platform.TenantView;
import com.akshara.shared.ApiException;
import com.akshara.shared.Ids;
import com.akshara.shared.LoginRateLimiter;
import com.akshara.shared.PasswordRules;
import com.akshara.shared.TenantContext;

/**
 * "Forgot password" for people of a school: a six-digit code goes to the person's sign-in email, and the code sets a
 * new password. Someone added without a password (staff brought in from a spreadsheet) uses the same steps to set
 * their first one.
 *
 * <ul>
 *   <li>Asking for a code always gets the same answer, whether or not the school and email exist, so the form
 *       cannot be used to find out who has an account.</li>
 *   <li>Only a hash of the code is stored, salted with the request's id. A code works for {@link #VALIDITY} and
 *       {@link #MAX_ATTEMPTS} tries; asking again replaces it.</li>
 *   <li>The message goes through the outbox like every other message; its text never appears in the school's
 *       message log ({@link MessageTemplates#isSecret}) and it is not held back by quiet hours.</li>
 *   <li>A new password ends every open session of that person.</li>
 * </ul>
 */
@Service
public class PasswordResetService {

    static final Duration VALIDITY = Duration.ofMinutes(15);
    static final int MAX_ATTEMPTS = 5;

    static final String WRONG_CODE = "That code is wrong or has expired. Check the latest email, or ask for a new code.";

    private final TenantDirectory tenants;
    private final UserRepository users;
    private final PasswordResetRepository resets;
    private final UserService userService;
    private final PasswordEncoder passwordEncoder;
    private final NotificationQueue queue;
    private final AuditService audit;
    private final LoginRateLimiter rateLimiter;
    private final TransactionTemplate tx;
    private final SecureRandom random = new SecureRandom();

    public PasswordResetService(TenantDirectory tenants, UserRepository users, PasswordResetRepository resets,
            UserService userService, PasswordEncoder passwordEncoder, NotificationQueue queue, AuditService audit,
            LoginRateLimiter rateLimiter, PlatformTransactionManager transactionManager) {
        this.tenants = tenants;
        this.users = users;
        this.resets = resets;
        this.userService = userService;
        this.passwordEncoder = passwordEncoder;
        this.queue = queue;
        this.audit = audit;
        this.rateLimiter = rateLimiter;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /**
     * Sends a code when the school and an active person with that email exist; otherwise does nothing. Either way the
     * caller answers the same. Limited per IP and per school and email.
     */
    public void request(String schoolCode, String email, String clientIp) {
        String cleanEmail = email.trim();
        String school = schoolCode.trim().toLowerCase(Locale.ROOT);
        String perPerson = "reset|" + school + "|" + cleanEmail.toLowerCase(Locale.ROOT);
        String perIp = "reset-ip|" + clientIp;
        rateLimiter.attempt(perPerson);
        rateLimiter.attempt(perIp);

        Optional<TenantView> found = tenants.findByCode(school);
        if (found.isEmpty() || !found.get().status().allowsSignIn()) {
            return;
        }
        TenantView tenant = found.get();
        TenantContext.runAs(tenant.id(), () -> tx.execute(status -> {
            UserAccount user = users.findByEmailWithRoles(cleanEmail).orElse(null);
            if (user == null || !user.isActive()) {
                return null;
            }
            Instant now = Instant.now();
            List<PasswordReset> open = resets.findUnfinished(user.getId());
            open.forEach(r -> r.markReplaced(now));

            UUID id = Ids.newId();
            String code = String.format("%06d", random.nextInt(1_000_000));
            resets.save(new PasswordReset(id, user.getId(), hash(id, code), now, VALIDITY));

            queue.enqueue(MessageTemplates.PASSWORD_RESET, MessageTemplates.ENGLISH,
                    Map.of("school", tenant.name(), "code", code),
                    new Related("user", user.getId(), user.getName()),
                    List.of(new Recipient(Channel.EMAIL, user.getEmail(), user.getName(), Map.of(), null,
                            "password-reset:" + id)));
            audit.record(new Actor(user.getId(), user.getName()), "auth.password_reset_requested", "user",
                    user.getId(), Map.of("replacedCodes", open.size()));
            return null;
        }));
    }

    /**
     * Sets a new password with the code from the email. A wrong, used, replaced or expired code is a 400 that says
     * nothing about whether the person exists; the fifth wrong try uses the code up.
     */
    public void confirm(String schoolCode, String email, String code, String newPassword, String clientIp) {
        String cleanEmail = email.trim();
        String school = schoolCode.trim().toLowerCase(Locale.ROOT);
        String key = "reset-confirm|" + clientIp + "|" + school + "|" + cleanEmail.toLowerCase(Locale.ROOT);
        rateLimiter.attempt(key);
        // Before the code is checked, so a weak choice does not use the code up.
        PasswordRules.check(newPassword, "newPassword");

        TenantView tenant = tenants.findByCode(school).filter(t -> t.status().allowsSignIn()).orElse(null);
        String typed = code == null ? "" : code.replaceAll("\\s", "");
        UUID userId = tenant == null ? null : TenantContext.runAs(tenant.id(), () -> tx.execute(status -> {
            UserAccount user = users.findByEmailWithRoles(cleanEmail).orElse(null);
            if (user == null || !user.isActive()) {
                return null;
            }
            PasswordReset reset = resets.lockLatestUnfinished(user.getId()).orElse(null);
            Instant now = Instant.now();
            if (reset == null || !reset.isOpen(now, MAX_ATTEMPTS)) {
                return null;
            }
            int tries = reset.recordAttempt();
            if (!matches(reset.getId(), reset.getCodeHash(), typed)) {
                audit.record(new Actor(user.getId(), user.getName()), "auth.password_reset_wrong_code", "user",
                        user.getId(), Map.of("tries", tries));
                return null;
            }
            reset.markUsed(now);
            return user.getId();
        }));
        if (userId == null) {
            throw ApiException.badRequest(WRONG_CODE, "code");
        }
        rateLimiter.reset(key);

        // Hashed outside any transaction, so the slow hash never holds a database connection.
        String hash = passwordEncoder.encode(newPassword);
        TenantContext.runAs(tenant.id(), () -> {
            userService.setPassword(userId, hash, Map.of("via", "password_reset"), null);
            return null;
        });
    }

    static String hash(UUID resetId, String code) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(resetId.toString().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) ':');
            digest.update(code.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static boolean matches(UUID resetId, String storedHash, String typed) {
        if (typed == null || !typed.matches("^[0-9]{6}$")) {
            return false;
        }
        return MessageDigest.isEqual(hash(resetId, typed).getBytes(StandardCharsets.UTF_8),
                storedHash.getBytes(StandardCharsets.UTF_8));
    }
}
