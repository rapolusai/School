package com.akshara.billing;

import java.util.List;

import com.akshara.platform.Plan;

/**
 * The plans Akshara sells, kept in code (one place to change). Names, student and branch limits and features follow
 * the prototype's plan catalogue (docs/prototype/index.html). Prices are per student per year in paise, before GST:
 * the prototype shows flat list prices of ₹2,499, ₹5,999 and ₹14,999 a month (a year = ten months), and a school of
 * 300 students billed yearly pays about that list price on each plan (₹83, ₹200 and ₹500 a student). They are
 * placeholders until the owner sets real prices. Feature codes are translated by the web app.
 */
public final class PlanCatalog {

    /**
     * One plan. {@code maxStudents} and {@code maxBranches} are null for "unlimited". {@code features} are included,
     * {@code notIncluded} are shown crossed out on the school's Billing page.
     */
    public record PlanInfo(Plan plan, long pricePerStudentPerYearPaise, Integer maxStudents, Integer maxBranches,
            List<String> features, List<String> notIncluded, boolean popular) {

        /** Whether this plan covers that many students. */
        public boolean covers(int students) {
            return maxStudents == null || students <= maxStudents;
        }

        /** The price per student for one period of the cycle. */
        public long unitPricePaise(BillingCycle cycle) {
            return cycle.unitPricePaise(pricePerStudentPerYearPaise);
        }
    }

    private static final List<PlanInfo> PLANS = List.of(
            new PlanInfo(Plan.STARTER, 83_00, 300, 1,
                    List.of("core", "fees_online", "parent_app", "email_support"),
                    List.of("transport_library", "report_cards", "api_access"), false),
            new PlanInfo(Plan.GROWTH, 200_00, 1_500, 3,
                    List.of("everything_starter", "exams_timetable", "transport_library", "sms_credits",
                            "priority_support"),
                    List.of("api_access"), true),
            new PlanInfo(Plan.ENTERPRISE, 500_00, null, null,
                    List.of("everything_growth", "custom_domain", "api_access", "success_manager",
                            "dedicated_database"),
                    List.of(), false));

    private PlanCatalog() {
    }

    public static List<PlanInfo> all() {
        return PLANS;
    }

    public static PlanInfo of(Plan plan) {
        return PLANS.stream().filter(p -> p.plan() == plan).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown plan " + plan));
    }
}
