import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError, PLAN_DOWNGRADE_TYPE } from "@/lib/api";
import { PLAN_CHANGED_EVENT } from "@/lib/plan-access";
import type { PlanChangeQuote, SchoolBilling, SubscriptionView } from "@/lib/types";
import {
  BILLING_READ_ONLY,
  invoice,
  NO_NOTICE,
  PLANS,
  renderInSchool,
  SCHOOL_ADMIN_BILLING,
  schoolBilling,
} from "@/test/billing-fixtures";
import { InvoiceDocument } from "./invoice-document";
import { isUpgrade } from "./plan-change";
import { SchoolBillingView } from "./school-billing-view";

const api = vi.hoisted(() => ({
  mine: vi.fn(),
  planQuote: vi.fn(),
  changePlan: vi.fn(),
  withdrawDowngrade: vi.fn(),
  cancelSubscription: vi.fn(),
  resumeSubscription: vi.fn(),
}));

vi.mock("@/lib/billing-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/billing-api")>();
  return { ...actual, billingApi: { ...actual.billingApi, ...api } };
});

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  usePathname: () => "/app/billing",
}));

const SUB: SubscriptionView = {
  billingCycle: "YEARLY",
  billedStudents: 250,
  periodStart: "2026-04-01",
  periodEnd: "2027-03-31",
  nextRenewalOn: "2027-04-01",
  paidSince: "2026-04-01",
  unitPricePaise: 83_00,
  nextInvoiceTaxablePaise: 20_750_00,
  pendingPlan: null,
  pendingPlanRequestedAt: null,
  cancelAtPeriodEnd: false,
  cancelRequestedAt: null,
  nextUnitPricePaise: 83_00,
  nextBilledStudents: 250,
};

function paying(overrides: Partial<SubscriptionView> = {}, billing: Partial<SchoolBilling> = {}): SchoolBilling {
  return schoolBilling({
    status: "ACTIVE",
    plan: "STARTER",
    trialEndsAt: null,
    trialDaysLeft: null,
    notice: NO_NOTICE,
    subscription: { ...SUB, ...overrides },
    ...billing,
  });
}

function quote(overrides: Partial<PlanChangeQuote> = {}): PlanChangeQuote {
  return {
    plan: "GROWTH",
    kind: "UPGRADE",
    effectiveOn: "2026-10-27",
    remainingDays: 156,
    periodDays: 365,
    unitPricePaise: 50_01,
    billedStudents: 250,
    taxablePaise: 12_502_50,
    totalPaise: 14_752_95,
    allowed: true,
    maxStudents: 1500,
    activeStudents: 240,
    ...overrides,
  };
}

beforeEach(() => {
  Object.values(api).forEach((fn) => fn.mockReset());
});

describe("changing the plan from the Billing page", () => {
  it("upgrades at once after showing the prorated invoice, and tells the menu", async () => {
    const user = userEvent.setup();
    api.mine.mockResolvedValueOnce(paying()).mockResolvedValue(paying({}, { plan: "GROWTH" }));
    api.planQuote.mockResolvedValue(quote());
    api.changePlan.mockResolvedValue(paying({}, { plan: "GROWTH" }));
    const changed = vi.fn();
    window.addEventListener(PLAN_CHANGED_EVENT, changed);
    renderInSchool(SCHOOL_ADMIN_BILLING, <SchoolBillingView />);

    expect(await screen.findByTestId("plan-change-ENTERPRISE")).toHaveTextContent("Upgrade");
    expect(screen.queryByTestId("plan-change-STARTER")).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Upgrade: Growth plan" }));
    const dialog = await screen.findByRole("dialog", { name: "Change to Growth" });
    expect(await within(dialog).findByTestId("plan-change-quote")).toHaveTextContent(
      "An invoice for the rest of this period (156 of 365 days) is issued now: 250 students × ₹50.01 = ₹12,502.50 + GST, ₹14,752.95 in all.",
    );
    expect(api.planQuote).toHaveBeenCalledWith("GROWTH");
    await user.click(within(dialog).getByRole("button", { name: "Change plan" }));
    await waitFor(() => expect(api.changePlan).toHaveBeenCalledWith("GROWTH"));
    expect(await screen.findByText("The school is now on Growth.")).toBeInTheDocument();
    expect(changed).toHaveBeenCalled();
    window.removeEventListener(PLAN_CHANGED_EVENT, changed);
  });

  it("explains a refused downgrade and does not let it be confirmed", async () => {
    const user = userEvent.setup();
    api.mine.mockResolvedValue(paying({ unitPricePaise: 200_00 }, { plan: "GROWTH" }));
    api.planQuote.mockResolvedValue(
      quote({ plan: "STARTER", kind: "DOWNGRADE", allowed: false, maxStudents: 300, activeStudents: 420 }),
    );
    renderInSchool(SCHOOL_ADMIN_BILLING, <SchoolBillingView />);
    await user.click(await screen.findByRole("button", { name: "Downgrade: Starter plan" }));
    const dialog = await screen.findByRole("dialog", { name: "Change to Starter" });
    expect(await within(dialog).findByTestId("plan-change-refused")).toHaveTextContent(
      "The Starter plan covers up to 300 students on roll and the school has 420.",
    );
    expect(within(dialog).getByRole("button", { name: "Change plan" })).toBeDisabled();
  });

  it("shows the API's refusal in the user's words when the students grew meanwhile", async () => {
    const user = userEvent.setup();
    api.mine.mockResolvedValue(paying({}, { plan: "GROWTH" }));
    api.planQuote.mockResolvedValue(quote({ plan: "STARTER", kind: "DOWNGRADE", effectiveOn: "2027-04-01" }));
    api.changePlan.mockRejectedValue(
      new ApiError({
        status: 409,
        title: "Too many students for this plan",
        type: PLAN_DOWNGRADE_TYPE,
        properties: { plan: "STARTER", maxStudents: 300, activeStudents: 301 },
      }),
    );
    renderInSchool(SCHOOL_ADMIN_BILLING, <SchoolBillingView />);
    await user.click(await screen.findByRole("button", { name: "Downgrade: Starter plan" }));
    const dialog = await screen.findByRole("dialog");
    expect(await within(dialog).findByTestId("plan-change-quote")).toHaveTextContent(
      "The school moves to Starter on 1 Apr 2027",
    );
    await user.click(within(dialog).getByRole("button", { name: "Change plan" }));
    expect(await within(dialog).findByText(/covers up to 300 students on roll and the school has 301/)).toBeVisible();
  });

  it("shows a scheduled downgrade with a way to keep the plan", async () => {
    const user = userEvent.setup();
    const pending = paying(
      { pendingPlan: "STARTER", nextUnitPricePaise: 83_00, nextBilledStudents: 250 },
      { plan: "GROWTH" },
    );
    api.mine.mockResolvedValue(pending);
    api.withdrawDowngrade.mockResolvedValue(paying({}, { plan: "GROWTH" }));
    renderInSchool(SCHOOL_ADMIN_BILLING, <SchoolBillingView />);
    expect(await screen.findByTestId("billing-pending-plan")).toHaveTextContent("Moving to Starter on 1 Apr 2027.");
    // No second button for the plan already chosen.
    expect(screen.queryByTestId("plan-change-STARTER")).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Keep Growth" }));
    await waitFor(() => expect(api.withdrawDowngrade).toHaveBeenCalled());
    expect(await screen.findByText("The school stays on Growth.")).toBeInTheDocument();
  });

  it("cancels at the period's end with an optional reason, and takes it back", async () => {
    const user = userEvent.setup();
    api.mine.mockResolvedValueOnce(paying()).mockResolvedValue(paying({ cancelAtPeriodEnd: true }));
    api.cancelSubscription.mockResolvedValue(paying({ cancelAtPeriodEnd: true }));
    api.resumeSubscription.mockResolvedValue(paying());
    renderInSchool(SCHOOL_ADMIN_BILLING, <SchoolBillingView />);
    await user.click(await screen.findByTestId("billing-cancel"));
    const dialog = await screen.findByRole("dialog", { name: "Cancel the subscription" });
    expect(dialog).toHaveTextContent("keep using Akshara until 31 Mar 2027");
    await user.type(within(dialog).getByLabelText("Why are you cancelling? (optional)"), "Budget");
    await user.click(within(dialog).getByRole("button", { name: "Cancel at period end" }));
    await waitFor(() => expect(api.cancelSubscription).toHaveBeenCalledWith("Budget"));
    expect(await screen.findByTestId("billing-cancelled")).toHaveTextContent(
      "Cancelled: the subscription ends on 31 Mar 2027 and will not renew.",
    );
    await user.click(screen.getByRole("button", { name: "Keep subscription" }));
    await waitFor(() => expect(api.resumeSubscription).toHaveBeenCalled());
  });

  it("offers nothing to change to someone who can only read Billing", async () => {
    api.mine.mockResolvedValue(paying({ pendingPlan: "STARTER" }, { plan: "GROWTH" }));
    renderInSchool(BILLING_READ_ONLY, <SchoolBillingView />);
    expect(await screen.findByTestId("billing-pending-plan")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Upgrade|Downgrade|Keep|Cancel subscription/ })).not.toBeInTheDocument();
  });

  it("lets a trial switch plan for nothing", async () => {
    const user = userEvent.setup();
    api.mine.mockResolvedValue(schoolBilling());
    api.planQuote.mockResolvedValue(quote({ plan: "ENTERPRISE", kind: "TRIAL" }));
    renderInSchool(SCHOOL_ADMIN_BILLING, <SchoolBillingView />);
    await user.click(await screen.findByRole("button", { name: "Switch to this plan: Enterprise plan" }));
    expect(await screen.findByTestId("plan-change-quote")).toHaveTextContent(
      "During the trial the plan changes at once, at no charge.",
    );
    // A trial has nothing to cancel.
    expect(screen.queryByTestId("billing-cancel")).not.toBeInTheDocument();
  });

  it("ranks plans by price", () => {
    expect(isUpgrade(PLANS, "STARTER", "GROWTH")).toBe(true);
    expect(isUpgrade(PLANS, "ENTERPRISE", "GROWTH")).toBe(false);
  });
});

describe("an upgrade invoice", () => {
  it("says what it bills", () => {
    renderInSchool(
      BILLING_READ_ONLY,
      <InvoiceDocument invoice={invoice({ kind: "UPGRADE", previousPlan: "STARTER", periodStart: "2026-10-27" })} />,
    );
    expect(
      screen.getByText(
        "Akshara School Cloud, upgrade from the Starter to the Growth plan for the rest of the yearly period (prorated)",
      ),
    ).toBeInTheDocument();
  });
});
