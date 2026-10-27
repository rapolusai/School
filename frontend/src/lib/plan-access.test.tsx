import { render, renderHook, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { Sidebar } from "@/components/shell/sidebar";
import { SchoolBillingView } from "@/components/views/billing/school-billing-view";
import { meWith } from "@/test/render";
import { renderInSchool, SCHOOL_ADMIN_BILLING, schoolBilling } from "@/test/billing-fixtures";
import { ApiError, parseErrorResponse, PLAN_FEATURE_TYPE, PLAN_STUDENT_LIMIT_TYPE } from "./api";
import { errorMessage } from "./error-message";
import { translate, type Lang, type Translate } from "./i18n";
import { navFor } from "./permissions";
import { applyPlanLocks, notifyPlanChanged, planModule, usePlanLocks } from "./plan-access";
import type { Me, PlanAccess } from "./types";

const { mine, planAccess } = vi.hoisted(() => ({ mine: vi.fn(), planAccess: vi.fn() }));

vi.mock("@/lib/billing-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/billing-api")>();
  return { ...actual, billingApi: { ...actual.billingApi, mine, planAccess } };
});

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  usePathname: () => "/app/billing",
}));

const tr = (lang: Lang): Translate => (key, vars) => translate(lang, key, vars);

const TENANT = {
  id: "t1",
  name: "Sunrise Public School",
  code: "sunrise-public",
  status: "ACTIVE" as const,
  plan: "STARTER" as const,
  board: "CBSE" as const,
  city: "Hyderabad",
  trialEndsAt: null,
};

const STAFF_MODULES = ["dashboard.view", "library.read", "transport.read", "inventory.read", "students.read"];
const ADMIN: Me = { ...meWith([...STAFF_MODULES, "billing.read", "settings.manage"]), tenant: TENANT };
const LIBRARIAN: Me = { ...meWith(STAFF_MODULES, ["LIBRARIAN"]), tenant: TENANT };

function access(overrides: Partial<PlanAccess> = {}): PlanAccess {
  return {
    plan: "STARTER",
    status: "ACTIVE",
    enforced: true,
    excludedFeatures: ["transport_library", "report_cards"],
    lockedFeatures: ["transport_library", "report_cards"],
    lockedModules: ["transport", "library", "inventory", "report_cards"],
    maxStudents: 300,
    activeStudents: 280,
    ...overrides,
  };
}

beforeEach(() => {
  mine.mockReset();
  planAccess.mockReset();
});

describe("applyPlanLocks", () => {
  const locked = new Set(["transport", "library", "inventory"] as const);

  it("turns a locked module into an Upgrade link to Billing for someone who can see Billing", () => {
    const items = applyPlanLocks(navFor(ADMIN), locked, ADMIN);
    const library = items.find((item) => item.key === "library");
    expect(library).toMatchObject({ locked: true, href: "/app/billing?upgrade=library" });
    expect(items.find((item) => item.key === "transport")).toMatchObject({ locked: true });
    expect(items.find((item) => item.key === "students")?.locked).toBeUndefined();
    // Same items, same order: nothing disappears for the School Admin.
    expect(items.map((item) => item.key)).toEqual(navFor(ADMIN).map((item) => item.key));
  });

  it("hides locked modules from staff who cannot upgrade", () => {
    const keys = applyPlanLocks(navFor(LIBRARIAN), locked, LIBRARIAN).map((item) => item.key);
    expect(keys).not.toContain("library");
    expect(keys).not.toContain("transport");
    expect(keys).toContain("students");
  });

  it("leaves the menu alone when nothing is locked", () => {
    expect(applyPlanLocks(navFor(ADMIN), new Set(), ADMIN)).toEqual(navFor(ADMIN));
  });
});

describe("usePlanLocks", () => {
  it("locks what the API says once the school pays, and checks again when the plan changes", async () => {
    planAccess.mockResolvedValueOnce(access()).mockResolvedValueOnce(access({ plan: "GROWTH", lockedModules: [] }));
    const { result } = renderHook(() => usePlanLocks(ADMIN));
    await waitFor(() => expect([...result.current]).toEqual(["transport", "library", "inventory", "report_cards"]));
    notifyPlanChanged();
    await waitFor(() => expect(result.current.size).toBe(0));
    expect(planAccess).toHaveBeenCalledTimes(2);
  });

  it("locks nothing during the trial, for the platform console, or when the API cannot be reached", async () => {
    planAccess.mockResolvedValue(access({ status: "TRIAL", enforced: false }));
    const trial = renderHook(() => usePlanLocks(ADMIN));
    await waitFor(() => expect(planAccess).toHaveBeenCalled());
    expect(trial.result.current.size).toBe(0);

    planAccess.mockClear();
    const platform = renderHook(() => usePlanLocks({ ...meWith(["platform.admin"]), platformAdmin: true }));
    expect(platform.result.current.size).toBe(0);
    expect(planAccess).not.toHaveBeenCalled();

    planAccess.mockRejectedValue(new ApiError({ status: 500, title: "Boom" }));
    const failed = renderHook(() => usePlanLocks(ADMIN));
    await waitFor(() => expect(planAccess).toHaveBeenCalled());
    expect(failed.result.current.size).toBe(0);
  });
});

describe("plan problems", () => {
  it("keeps the problem's own members", async () => {
    const response = new Response(
      JSON.stringify({ type: PLAN_FEATURE_TYPE, title: "Not in your plan", status: 403, detail: "No.", feature: "report_cards" }),
      { status: 403, headers: { "content-type": "application/problem+json" } },
    );
    const error = await parseErrorResponse(response);
    expect(error.type).toBe(PLAN_FEATURE_TYPE);
    expect(error.properties).toEqual({ feature: "report_cards" });
  });

  it("says which feature the plan leaves out instead of 'no permission', in every language", () => {
    const error = new ApiError({
      status: 403,
      title: "Not in your plan",
      detail: "The school's STARTER plan does not include this.",
      type: PLAN_FEATURE_TYPE,
      properties: { feature: "transport_library", plan: "STARTER" },
    });
    expect(errorMessage(error, tr("en"))).toBe(
      "Transport, library and inventory is not included in your school's plan. A School Admin can upgrade the plan on the Billing page.",
    );
    expect(errorMessage(error, tr("hi"))).toContain(translate("hi", "billing.feature.transport_library"));
    expect(errorMessage(error, tr("te"))).not.toBe(translate("te", "common.error.forbidden"));
    const unknown = new ApiError({ status: 403, title: "Not in your plan", type: PLAN_FEATURE_TYPE });
    expect(errorMessage(unknown, tr("en"))).toMatch(/^This feature is not included/);
  });

  it("gives the student limit with its numbers", () => {
    const error = new ApiError({
      status: 409,
      title: "Student limit reached",
      type: PLAN_STUDENT_LIMIT_TYPE,
      properties: { maxStudents: 300, activeStudents: 300, adding: 2 },
    });
    expect(errorMessage(error, tr("en"))).toBe(
      "Your plan covers up to 300 students on roll and the school has 300. Upgrade the plan on the Billing page to add more.",
    );
    expect(errorMessage(error, tr("te"))).toContain("300");
  });
});

describe("locked menu and Billing page", () => {
  it("shows a locked item with a lock and Upgrade in the sidebar", () => {
    const items = applyPlanLocks(navFor(ADMIN), new Set(["library"] as const), ADMIN);
    render(<Sidebar me={ADMIN} items={items} pathname="/app/billing" onSignOut={() => {}} />);
    const library = screen.getByTestId("nav-locked-library");
    expect(library).toHaveAttribute("href", "/app/billing?upgrade=library");
    expect(library).toHaveAccessibleName("Library: not in your plan. Upgrade to use it");
    expect(library).toHaveTextContent("Upgrade");
    expect(library).not.toHaveAttribute("aria-current");
  });

  it("reads only known modules from the Billing link", () => {
    expect(planModule("library")).toBe("library");
    expect(planModule("report_cards")).toBe("report_cards");
    expect(planModule("students")).toBeNull();
    expect(planModule(["library"])).toBeNull();
    expect(planModule(undefined)).toBeNull();
  });

  it("explains on Billing why the item was locked and shows students against the limit", async () => {
    mine.mockResolvedValue(schoolBilling({ status: "ACTIVE", activeStudents: 280 }));
    renderInSchool(SCHOOL_ADMIN_BILLING, <SchoolBillingView upgrade="library" />);
    expect(await screen.findByTestId("billing-upgrade-notice")).toHaveTextContent(
      "Library is not in your current plan. Upgrade below to use it.",
    );
    expect(screen.getByTestId("billing-active-students")).toHaveTextContent("280 of 300");
  });
});
