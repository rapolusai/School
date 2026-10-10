import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api";
import { translate, type Translate } from "@/lib/i18n";
import { NAV_ITEMS, navItemForPath, PERMISSIONS } from "@/lib/permissions";
import { HEALTH, PLANS, RENEWALS, renderAsSuperAdmin } from "@/test/billing-fixtures";
import { HealthView, uptimeLabel } from "./health-view";
import { daysLabel, RenewalsView } from "./renewals-view";

const api = vi.hoisted(() => ({ renewals: vi.fn(), plans: vi.fn(), health: vi.fn() }));

vi.mock("@/lib/billing-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/billing-api")>();
  return { ...actual, platformBillingApi: { ...actual.platformBillingApi, ...api } };
});

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  usePathname: () => "/app/platform/billing",
}));

const t: Translate = (key, vars) => translate("en", key, vars);

beforeEach(() => {
  api.renewals.mockResolvedValue(RENEWALS);
  api.plans.mockResolvedValue(PLANS.map((plan, i) => ({ ...plan, schools: [3, 1, 0][i] })));
  api.health.mockResolvedValue(HEALTH);
});

describe("billing in the navigation", () => {
  it("gives School Admins Billing under Accounts and the Super Admin two platform pages", () => {
    expect(NAV_ITEMS.find((i) => i.key === "billing")).toMatchObject({
      href: "/app/billing",
      permission: PERMISSIONS.billingRead,
      group: "finance",
    });
    expect(NAV_ITEMS.find((i) => i.key === "platformBilling")).toMatchObject({
      href: "/app/platform/billing",
      permission: "platform.admin",
    });
    expect(navItemForPath("/app/billing/invoices/inv1")?.key).toBe("billing");
    expect(navItemForPath("/app/platform/health")?.key).toBe("platformHealth");
    expect(navItemForPath("/app/platform/schools/t1/invoices/inv1")?.key).toBe("schools");
  });
});

describe("RenewalsView", () => {
  it("lists overdue, due and trial-ending schools with links, and filters them", async () => {
    const user = userEvent.setup();
    renderAsSuperAdmin(<RenewalsView />);
    const table = await screen.findByTestId("renewals-table");
    expect(within(table).getAllByRole("row")).toHaveLength(4);
    expect(within(table).getByRole("link", { name: "Bright Minds Academy" })).toHaveAttribute(
      "href",
      "/app/platform/schools/t2",
    );
    expect(within(table).getByText("₹2,36,000")).toBeInTheDocument();
    expect(within(table).getByText("2 invoices")).toBeInTheDocument();
    expect(within(table).getAllByText("25 days ago").length).toBeGreaterThan(0);
    expect(within(table).getAllByText("in 12 days").length).toBeGreaterThan(0);

    await user.click(screen.getByLabelText("Overdue (1)"));
    expect(within(screen.getByTestId("renewals-table")).getAllByRole("row")).toHaveLength(2);
    await user.click(screen.getByLabelText("Trial ending (1)"));
    expect(screen.getByRole("link", { name: "Sunrise Public School" })).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Bright Minds Academy" })).not.toBeInTheDocument();

    // The catalogue counts schools per plan.
    expect(await screen.findByText("3 schools on this plan")).toBeInTheDocument();
    expect(screen.getByText("1 school on this plan")).toBeInTheDocument();
  });

  it("says when nothing needs attention", async () => {
    api.renewals.mockResolvedValue([]);
    renderAsSuperAdmin(<RenewalsView />);
    expect(await screen.findByTestId("renewals-empty")).toHaveTextContent("Nothing needs attention.");
  });

  it("words days from today", () => {
    expect(daysLabel(t, 0)).toBe("today");
    expect(daysLabel(t, 1)).toBe("in 1 day");
    expect(daysLabel(t, -3)).toBe("3 days ago");
  });
});

describe("HealthView", () => {
  it("shows schools by status, people, students, the outbox, the database and the app", async () => {
    renderAsSuperAdmin(<HealthView />);
    const schools = await screen.findByTestId("health-schools");
    expect(schools).toHaveTextContent("42");
    expect(within(schools).getByText("Active: 30")).toBeInTheDocument();
    expect(within(schools).getByText("Suspended: 1")).toBeInTheDocument();
    expect(screen.getByTestId("health-users")).toHaveTextContent("1,840");
    expect(screen.getByTestId("health-students")).toHaveTextContent("25,310");
    expect(screen.getByTestId("health-outbox")).toHaveTextContent("12 queued · 3 failed");
    expect(screen.getByTestId("health-database")).toHaveTextContent("Reachable");
    expect(screen.getByTestId("health-database")).toHaveTextContent("Answered in 4 ms");
    expect(screen.getByTestId("health-app")).toHaveTextContent("0.1.0-SNAPSHOT");
    expect(screen.getByTestId("health-app")).toHaveTextContent("Up 1 d 2 h 30 min");
  });

  it("says when the database cannot be reached", async () => {
    api.health.mockResolvedValue({
      ...HEALTH,
      schools: null,
      users: null,
      activeStudents: null,
      outbox: null,
      database: { reachable: false, latencyMs: null },
    });
    renderAsSuperAdmin(<HealthView />);
    expect(await screen.findByTestId("health-db-down")).toBeInTheDocument();
    expect(screen.getByTestId("health-database")).toHaveTextContent("Unreachable");
    expect(screen.getByTestId("health-users")).toHaveTextContent("—");
  });

  it("offers a retry when the check fails", async () => {
    api.health.mockRejectedValue(new ApiError({ status: 500, title: "Server error" }));
    renderAsSuperAdmin(<HealthView />);
    expect(await screen.findByRole("button", { name: "Try again" })).toBeInTheDocument();
  });

  it("words the uptime", () => {
    expect(uptimeLabel(t, 30)).toBe("less than a minute");
    expect(uptimeLabel(t, 5 * 60)).toBe("5 min");
    expect(uptimeLabel(t, 3 * 3600 + 60)).toBe("3 h 1 min");
    expect(uptimeLabel(t, 95_400)).toBe("1 d 2 h 30 min");
  });
});
