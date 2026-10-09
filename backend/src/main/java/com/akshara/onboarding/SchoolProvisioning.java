package com.akshara.onboarding;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.identity.RoleCatalog;
import com.akshara.identity.UserAccount;
import com.akshara.identity.UserService;
import com.akshara.platform.Board;
import com.akshara.platform.Plan;
import com.akshara.platform.TenantDirectory;
import com.akshara.platform.TenantStatus;
import com.akshara.platform.TenantView;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;

/**
 * Creates a school with its standard roles and first School Admin. The school row is written first; everything else
 * is written as that school, so row-level security checks every insert. If the second step fails the school row is
 * removed again.
 */
@Service
public class SchoolProvisioning {

    private static final Logger log = LoggerFactory.getLogger(SchoolProvisioning.class);

    /** Codes that would be confusing as a school's sign-in code or clash with future subdomains. */
    static final Set<String> RESERVED_CODES = Set.of("admin", "api", "app", "www", "platform", "support", "help",
            "status", "login", "signup", "akshara");

    public record NewSchool(String schoolName, String schoolCode, Board board, String city, Plan plan,
            String adminName, String adminEmail, String password) {
    }

    public record Provisioned(TenantView tenant, UUID adminUserId) {
    }

    private final TenantDirectory tenants;
    private final UserService userService;
    private final AuditService audit;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate tx;

    public SchoolProvisioning(TenantDirectory tenants, UserService userService, AuditService audit,
            PasswordEncoder passwordEncoder, PlatformTransactionManager transactionManager) {
        this.tenants = tenants;
        this.userService = userService;
        this.audit = audit;
        this.passwordEncoder = passwordEncoder;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /**
     * @param createdBy the platform admin creating the school, or null for a self-service sign-up
     */
    public Provisioned provision(NewSchool school, TenantStatus status, Instant trialEndsAt, Actor createdBy) {
        String code = school.schoolCode().trim();
        if (RESERVED_CODES.contains(code)) {
            throw ApiException.conflict("That school code is already taken. Try another.", "schoolCode");
        }
        String passwordHash = passwordEncoder.encode(school.password());
        TenantView tenant = tenants.register(code, school.schoolName().trim(), school.board(),
                blankToNull(school.city()), school.plan(), status, trialEndsAt);
        try {
            UUID adminId = TenantContext.runAs(tenant.id(), () -> tx.execute(s -> {
                userService.createDefaultRoles();
                Actor actor = createdBy != null ? createdBy : new Actor(null, school.adminName().trim());
                UserAccount admin = userService.createUser(school.adminName(), school.adminEmail(), passwordHash,
                        List.of(RoleCatalog.SCHOOL_ADMIN), actor);
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("code", tenant.code());
                details.put("plan", tenant.plan().name());
                details.put("source", createdBy == null ? "signup" : "platform");
                audit.record(actor, "school.created", "school", tenant.id(), details);
                return admin.getId();
            }));
            log.info("Provisioned school {} ({})", tenant.code(), tenant.id());
            return new Provisioned(tenant, adminId);
        } catch (RuntimeException e) {
            try {
                tenants.removeEmpty(tenant.id());
            } catch (RuntimeException cleanup) {
                e.addSuppressed(cleanup);
                log.error("Could not remove half-created school {}", tenant.id(), cleanup);
            }
            throw e;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
