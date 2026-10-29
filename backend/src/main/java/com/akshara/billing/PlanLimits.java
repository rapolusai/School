package com.akshara.billing;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.akshara.billing.BillingViews.PlanAccessView;
import com.akshara.billing.PlanCatalog.PlanInfo;
import com.akshara.billing.PlanFeatures.Gate;
import com.akshara.platform.TenantDirectory;
import com.akshara.platform.TenantView;
import com.akshara.shared.ApiException;
import com.akshara.shared.CurrentUser;
import com.akshara.shared.TenantContext;
import com.akshara.students.EnrolmentLimit;
import com.akshara.students.StudentRoster;

/**
 * Enforces the school's plan: the student limit when students are added (for the students module, through
 * {@link EnrolmentLimit}), and the features the plan leaves out on every API request (see {@link PlanFeatures}).
 */
@Service
class PlanLimits implements EnrolmentLimit, HandlerInterceptor {

    /** Problem type of an addition that would take the school past its plan's student limit. */
    static final String STUDENT_LIMIT_TYPE = "urn:akshara:problem:plan-student-limit";

    private final TenantDirectory tenants;
    private final StudentRoster roster;
    private final JdbcTemplate jdbc;

    PlanLimits(TenantDirectory tenants, StudentRoster roster, JdbcTemplate jdbc) {
        this.tenants = tenants;
        this.roster = roster;
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ students

    /**
     * Counts the school's students on roll and refuses the addition when it would pass the plan's limit. Like the
     * features, the limit applies once the school pays: a school in its trial can load its whole roll before choosing
     * a plan (and the Super Admin bills the students it has). One school's additions are checked one at a time (an advisory
     * lock held to the end of the caller's transaction), so two admissions at once cannot both take the last place.
     */
    @Override
    public void checkRoomFor(int adding) {
        UUID tenantId = TenantContext.require();
        TenantView school = tenants.findById(tenantId).orElseThrow(() -> ApiException.notFound("School"));
        PlanInfo plan = PlanCatalog.of(school.plan());
        if (plan.maxStudents() == null || adding <= 0 || !PlanFeatures.enforced(school.status())) {
            return;
        }
        jdbc.queryForObject("select 1 from (select pg_advisory_xact_lock(hashtextextended(?, 0))) l", Integer.class,
                "billing.students:" + tenantId);
        long active = roster.activeCount();
        if (active + adding > plan.maxStudents()) {
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("plan", plan.plan().name());
            properties.put("maxStudents", plan.maxStudents());
            properties.put("activeStudents", active);
            properties.put("adding", adding);
            throw new ApiException(HttpStatus.CONFLICT, "Student limit reached",
                    "The " + plan.plan().name() + " plan covers up to " + plan.maxStudents()
                            + " students on roll. The school has " + active + ", so " + adding
                            + (adding == 1 ? " more does" : " more do") + " not fit. Upgrade the plan to add more.",
                    Map.of(), STUDENT_LIMIT_TYPE, properties);
        }
    }

    // ------------------------------------------------------------------ features

    /**
     * Refuses a request for a feature the school's plan leaves out with {@code 403} and problem type
     * {@value PlanFeatures#TYPE} (with the {@code feature} code), before the endpoint's own permission check. Platform
     * admins, public paths and schools in their trial are never checked.
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (TenantContext.current().isEmpty() || CurrentUser.isPlatformAdmin()) {
            return true;
        }
        Gate gate = PlanFeatures.gateFor(request).orElse(null);
        if (gate == null) {
            return true;
        }
        TenantView school = tenants.findById(TenantContext.require()).orElse(null);
        if (school == null || PlanFeatures.allows(school.plan(), school.status(), gate.feature())) {
            return true;
        }
        throw new ApiException(HttpStatus.FORBIDDEN, "Not in your plan",
                "The school's " + school.plan().name() + " plan does not include this. Upgrade the plan to use it.",
                Map.of(), PlanFeatures.TYPE, Map.of("feature", gate.feature(), "plan", school.plan().name()));
    }

    // ------------------------------------------------------------------ what the web app shows

    /** The signed-in school's plan, what it locks now, and its students against the limit. */
    @Transactional(readOnly = true)
    public PlanAccessView current() {
        UUID tenantId = TenantContext.current().orElseThrow(() -> ApiException.notFound("School"));
        TenantView school = tenants.findById(tenantId).orElseThrow(() -> ApiException.notFound("School"));
        PlanInfo plan = PlanCatalog.of(school.plan());
        boolean enforced = PlanFeatures.enforced(school.status());
        List<Gate> excluded = PlanFeatures.locked(school.plan());
        List<String> excludedFeatures = excluded.stream().map(Gate::feature).toList();
        return new PlanAccessView(school.plan(), school.status(), enforced, excludedFeatures,
                enforced ? excludedFeatures : List.of(),
                enforced ? List.copyOf(PlanFeatures.lockedModules(school.plan())) : List.of(),
                plan.maxStudents(), roster.activeCount());
    }

    /** Puts the feature check in front of every API endpoint. */
    @Configuration(proxyBeanMethods = false)
    static class Registration implements WebMvcConfigurer {

        private final PlanLimits limits;

        Registration(PlanLimits limits) {
            this.limits = limits;
        }

        @Override
        public void addInterceptors(InterceptorRegistry registry) {
            registry.addInterceptor(limits).addPathPatterns("/api/**");
        }
    }
}
