package com.akshara.identity;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.audit.AuditService;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;

/**
 * Creates roles and people inside the current school. Callers hash passwords before calling in, so slow hashing never
 * holds a database connection.
 */
@Service
public class UserService {

    private final UserRepository users;
    private final RoleRepository roles;
    private final AuditService audit;
    private final RefreshTokenRepository tokens;

    public UserService(UserRepository users, RoleRepository roles, AuditService audit,
            RefreshTokenRepository tokens) {
        this.users = users;
        this.roles = roles;
        this.audit = audit;
        this.tokens = tokens;
    }

    /** Seeds the standard roles for a brand-new school. Runs inside the caller's transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Role> createDefaultRoles() {
        TenantContext.require();
        return roles.saveAll(RoleCatalog.DEFAULTS.stream()
                .map(t -> new Role(t.code(), t.name(), t.permissions(), true))
                .toList());
    }

    /** Adds a person to the current school and records it in the audit trail. */
    @Transactional
    public UserAccount createUser(String name, String email, String passwordHash, Collection<String> roleCodes,
            AuditService.Actor actor) {
        TenantContext.require();
        String cleanEmail = email.trim();
        if (users.existsByEmail(cleanEmail)) {
            throw ApiException.conflict("Someone in this school already uses that email.", "email");
        }
        Set<String> codes = new TreeSet<>(roleCodes);
        List<Role> found = roles.findByCodeIn(codes);
        if (found.size() != codes.size()) {
            Set<String> unknown = new TreeSet<>(codes);
            found.forEach(r -> unknown.remove(r.getCode()));
            throw ApiException.badRequest("Unknown role: " + String.join(", ", unknown) + ".", "roles");
        }
        UserAccount user = users.save(new UserAccount(cleanEmail, name.trim(), passwordHash, Set.copyOf(found)));
        Map<String, Object> details = Map.of("email", cleanEmail, "roles", List.copyOf(codes));
        if (actor == null) {
            audit.record("user.created", "user", user.getId(), details);
        } else {
            audit.record(actor, "user.created", "user", user.getId(), details);
        }
        return user;
    }

    /**
     * Stops a person of the current school signing in (for example when they leave) and ends their open sessions.
     * The account and everything it did stay on record. Returns false when the account was already disabled.
     */
    @Transactional
    public boolean disableUser(UUID userId, Map<String, ?> details, AuditService.Actor actor) {
        TenantContext.require();
        UserAccount user = users.findById(userId).orElseThrow(() -> ApiException.notFound("User"));
        if (!user.disable()) {
            return false;
        }
        users.flush();
        tokens.revokeAllOf(userId, Instant.now());
        if (actor == null) {
            audit.record("user.disabled", "user", userId, details);
        } else {
            audit.record(actor, "user.disabled", "user", userId, details);
        }
        return true;
    }
}
