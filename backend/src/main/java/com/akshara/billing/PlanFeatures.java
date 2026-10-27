package com.akshara.billing;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.util.AntPathMatcher;

import com.akshara.billing.PlanCatalog.PlanInfo;
import com.akshara.platform.Plan;
import com.akshara.platform.TenantStatus;

/**
 * The one place that says what a plan feature unlocks: for each feature code a plan can leave out
 * ({@link PlanInfo#notIncluded()}), the app modules it covers (the web app's menu keys) and the API paths behind
 * them. A feature with no entry here (such as {@code api_access}) is a promise in the catalogue, not a switch.
 *
 * <p>Features (and the student limit) are enforced only for paying schools: a school in its trial can try everything
 * and load its whole roll, so it can choose a plan.
 */
public final class PlanFeatures {

    /** Problem type of a request for a feature the school's plan does not include. */
    public static final String TYPE = "urn:akshara:problem:plan-feature";

    /** One feature: the modules (menu keys) it unlocks and the API paths it guards (Ant patterns). */
    public record Gate(String feature, List<String> modules, List<String> paths) {
    }

    static final List<Gate> GATES = List.of(
            new Gate("transport_library", List.of("transport", "library", "inventory"),
                    List.of("/api/transport", "/api/transport/**", "/api/me/transport", "/api/me/transport/**",
                            "/api/library", "/api/library/**", "/api/me/library", "/api/me/library/**",
                            "/api/inventory", "/api/inventory/**")),
            new Gate("report_cards", List.of("report_cards"),
                    List.of("/api/exams/report-cards", "/api/exams/report-cards/**", "/api/me/report-card",
                            "/api/me/children/*/report-card")));

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    private PlanFeatures() {
    }

    public static List<Gate> gates() {
        return GATES;
    }

    /** The gated feature a request path needs, if any (public paths, such as bus devices, are never gated). */
    public static Optional<Gate> gateFor(String path) {
        if (path == null || path.startsWith("/api/public/")) {
            return Optional.empty();
        }
        return GATES.stream().filter(g -> g.paths().stream().anyMatch(p -> MATCHER.match(p, path))).findFirst();
    }

    /** Whether the plan's limits apply at all to a school in this status (not during the trial). */
    public static boolean enforced(TenantStatus status) {
        return status != TenantStatus.TRIAL;
    }

    /** The gated features the plan leaves out. */
    public static List<Gate> locked(Plan plan) {
        List<String> excluded = PlanCatalog.of(plan).notIncluded();
        return GATES.stream().filter(g -> excluded.contains(g.feature())).toList();
    }

    /** The modules (menu keys) the plan leaves out. */
    public static Set<String> lockedModules(Plan plan) {
        Set<String> modules = new LinkedHashSet<>();
        locked(plan).forEach(g -> modules.addAll(g.modules()));
        return modules;
    }

    /** Whether a school on this plan and in this status may use the feature. */
    public static boolean allows(Plan plan, TenantStatus status, String feature) {
        return !enforced(status) || !PlanCatalog.of(plan).notIncluded().contains(feature);
    }
}
