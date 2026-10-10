import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api";
import { todayInIndia } from "@/lib/format";
import { translate } from "@/lib/i18n";
import { account, invoice, invoiceSummary, PLANS, renderAsSuperAdmin, SUNRISE_SUMMARY } from "@/test/billing-fixtures";
import { paymentProblems, PlatformInvoiceView } from "./platform-invoice-view";
import { parseStudents, periodPricePaise, SchoolAccountView, subscriptionProblems } from "./school-account-view";

const api = vi.hoisted(() => ({
  account: vi.fn(),
  plans: vi.fn(),
  start: vi.fn(),
  change: vi.fn(),
  issueNext: vi.fn(),
  invoice: vi.fn(),
  recordPayment: vi.fn(),
  cancelInvoice: vi.fn(),
  setStatus: vi.fn(),
  suspend: vi.fn(),
  reactivate: vi.fn(),
  updateDetails: vi.fn(),
}));

vi.mock("@/lib/billing-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/billing-api")>();
  return { ...actual, platformBillingApi: { ...actual.platformBillingApi, ...api } };
});

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  usePathname: () => "/app/platform/schools/t1",
}));

const PAYING = {
  billingCycle: "YEARLY" as const,
  billedStudents: 250,
  periodStart: "2026-10-09",
  periodEnd: "2027-10-08",
  nextRenewalOn: "2027-10-09",
  paidSince: "2026-10-09",
  unitPricePaise: 200_00,
  nextInvoiceTaxablePaise: 50_000_00,
};

beforeEach(() => {
  api.account.mockResolvedValue(account());
  api.plans.mockResolvedValue(PLANS);
});

describe("subscription form rules", () => {
  it("takes whole numbers of students within the plan and prices a period like the API", () => {
    expect(parseStudents("1,250")).toBe(1250);
    expect(parseStudents("0")).toBeNull();
    expect(parseStudents("12.5")).toBeNull();
    expect(parseStudents("100001")).toBeNull();
    const values = { plan: "STARTER" as const, billingCycle: "YEARLY" as const, billedStudents: "301", periodStart: "" };
    expect(subscriptionProblems(values, PLANS, true)).toEqual({ billedStudents: "billing.start.overPlan" });
    expect(subscriptionProblems({ ...values, plan: "GROWTH" }, PLANS, true)).toEqual({});
    expect(subscriptionProblems({ ...values, billedStudents: "" }, PLANS, true)).toEqual({
      billedStudents: "billing.start.studentsInvalid",
    });
    expect(periodPricePaise(PLANS[1], "YEARLY", 250)).toBe(50_000_00);
    expect(periodPricePaise(PLANS[0], "MONTHLY", 120)).toBe(996_00);
  });

  it("checks a payment against the balance and today", () => {
    const ok = { amount: "23,600", mode: "UPI" as const, reference: "UTR1", paidOn: "2026-10-09" };
    expect(paymentProblems(ok, 23_600_00, "2026-10-09")).toEqual({});
    expect(paymentProblems({ ...ok, amount: "23600.01" }, 23_600_00, "2026-10-09")).toEqual({
      amount: "billing.payment.amountTooHigh",
    });
    expect(paymentProblems({ ...ok, amount: "0" }, 23_600_00, "2026-10-09")).toEqual({
      amount: "billing.payment.amountInvalid",
    });
    expect(paymentProblems({ ...ok, reference: " " }, 23_600_00, "2026-10-09")).toEqual({
      reference: "billing.payment.referenceRequired",
    });
    expect(paymentProblems({ ...ok, paidOn: "2026-10-10" }, 23_600_00, "2026-10-09")).toEqual({
      paidOn: "billing.payment.future",
    });
  });
});

describe("SchoolAccountView", () => {
  it("converts a trial to a paid subscription and shows the first invoice", async () => {
    const user = userEvent.setup();
    api.start.mockResolvedValue(account({ subscription: PAYING, invoices: [invoiceSummary()] }));
    renderAsSuperAdmin(<SchoolAccountView id="t1" />);
    expect(await screen.findByRole("heading", { name: "Sunrise Public School" })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Start paid subscription" }));
    const dialog = await screen.findByRole("dialog", { name: "Start a paid subscription" });
    // 240 students on roll are suggested; Starter covers 300.
    expect(within(dialog).getByLabelText("Billed students")).toHaveValue("240");
    await user.clear(within(dialog).getByLabelText("Billed students"));
    await user.type(within(dialog).getByLabelText("Billed students"), "400");
    await user.click(within(dialog).getByRole("button", { name: "Start and issue invoice" }));
    expect(await within(dialog).findByText(/does not cover that many students/)).toBeInTheDocument();
    expect(api.start).not.toHaveBeenCalled();

    await user.selectOptions(within(dialog).getByLabelText("Plan"), "GROWTH");
    expect(within(dialog).getByTestId("subscription-price")).toHaveTextContent("₹80,000 + GST 18%, yearly");
    api.account.mockResolvedValue(account({ school: { ...SUNRISE_SUMMARY, status: "ACTIVE", plan: "GROWTH" }, subscription: PAYING, invoices: [invoiceSummary()] }));
    await user.click(within(dialog).getByRole("button", { name: "Start and issue invoice" }));
    await waitFor(() =>
      expect(api.start).toHaveBeenCalledWith("t1", {
        plan: "GROWTH",
        billingCycle: "YEARLY",
        billedStudents: 400,
        periodStart: todayInIndia(),
      }),
    );
    expect(await screen.findByText("Subscription started. Invoice AKS/26-27/000007 issued.")).toBeInTheDocument();
    expect(await screen.findByTestId("account-renewal")).toHaveTextContent("9 Oct 2027");
    expect(screen.getByRole("link", { name: "AKS/26-27/000007" })).toHaveAttribute(
      "href",
      "/app/platform/schools/t1/invoices/inv1",
    );
  });

  it("needs the school's state before a subscription can start", async () => {
    api.account.mockResolvedValue(account({ details: { legalName: null, address: null, stateCode: null, gstin: null } }));
    renderAsSuperAdmin(<SchoolAccountView id="t1" />);
    expect(await screen.findByRole("button", { name: "Start paid subscription" })).toBeDisabled();
    expect(screen.getByText(/before starting its subscription/)).toBeInTheDocument();
  });

  it("suspends only with a reason, and reactivates", async () => {
    const user = userEvent.setup();
    const suspended = account({
      school: { ...SUNRISE_SUMMARY, status: "SUSPENDED" },
      suspension: { suspendedAt: "2026-10-09T06:30:00Z", reason: "Unpaid for 60 days" },
    });
    api.suspend.mockResolvedValue(suspended);
    renderAsSuperAdmin(<SchoolAccountView id="t1" />);
    await user.click(await screen.findByRole("button", { name: "Suspend school" }));
    const dialog = await screen.findByRole("dialog", { name: "Suspend Sunrise Public School?" });
    expect(dialog).toHaveTextContent("Nobody of this school can sign in");
    await user.click(within(dialog).getByRole("button", { name: "Suspend school" }));
    expect(await within(dialog).findByText("Fill in this field.")).toBeInTheDocument();
    expect(api.suspend).not.toHaveBeenCalled();

    api.account.mockResolvedValue(suspended);
    await user.type(within(dialog).getByLabelText("Reason"), "Unpaid for 60 days");
    await user.click(within(dialog).getByRole("button", { name: "Suspend school" }));
    await waitFor(() => expect(api.suspend).toHaveBeenCalledWith("t1", "Unpaid for 60 days"));
    expect(await screen.findByTestId("account-suspended")).toHaveTextContent("Reason: Unpaid for 60 days");
    expect(screen.queryByRole("button", { name: "Start paid subscription" })).not.toBeInTheDocument();

    api.reactivate.mockResolvedValue(account());
    api.account.mockResolvedValue(account());
    await user.click(screen.getByRole("button", { name: "Reactivate" }));
    const confirm = await screen.findByRole("dialog", { name: "Reactivate Sunrise Public School?" });
    await user.click(within(confirm).getByRole("button", { name: "Reactivate" }));
    await waitFor(() => expect(api.reactivate).toHaveBeenCalledWith("t1"));
    expect(await screen.findByText("Sunrise Public School is active again.")).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByTestId("account-suspended")).not.toBeInTheDocument());
  });

  it("issues the next invoice and shows the API's refusal in place", async () => {
    const user = userEvent.setup();
    api.account.mockResolvedValue(
      account({ school: { ...SUNRISE_SUMMARY, status: "ACTIVE", plan: "GROWTH" }, subscription: PAYING }),
    );
    api.issueNext.mockRejectedValue(
      new ApiError({
        status: 409,
        title: "Too early",
        detail: "The next period starts on 9 Oct 2027. Its invoice can be issued from 9 Sept 2027.",
      }),
    );
    renderAsSuperAdmin(<SchoolAccountView id="t1" />);
    await user.click(await screen.findByRole("button", { name: "Issue next invoice" }));
    const dialog = await screen.findByRole("dialog", { name: "Issue the next invoice?" });
    await user.click(within(dialog).getByRole("button", { name: "Issue invoice" }));
    expect(await within(dialog).findByText(/Its invoice can be issued from 9 Sept 2027/)).toBeInTheDocument();
    // A paying school can be marked past due by hand.
    expect(screen.getByRole("button", { name: "Mark past due" })).toBeInTheDocument();
  });

  it("says when the school does not exist", async () => {
    api.account.mockRejectedValue(new ApiError({ status: 404, title: "Not found" }));
    renderAsSuperAdmin(<SchoolAccountView id="nope" />);
    expect(await screen.findByText("This school could not be found.")).toBeInTheDocument();
  });
});

describe("PlatformInvoiceView", () => {
  it("records a part payment by hand, then the rest", async () => {
    const user = userEvent.setup();
    api.invoice.mockResolvedValue(invoice({ sample: false }));
    api.recordPayment.mockResolvedValue(invoice({ paidPaise: 9_000_00, balancePaise: 50_000_00 }));
    renderAsSuperAdmin(<PlatformInvoiceView tenantId="t1" invoiceId="inv1" />);
    await user.click(await screen.findByRole("button", { name: "Record payment" }));
    const dialog = await screen.findByRole("dialog", { name: "Record a payment for AKS/26-27/000007" });
    expect(within(dialog).getByLabelText("Amount (₹)")).toHaveValue("59000");
    await user.clear(within(dialog).getByLabelText("Amount (₹)"));
    await user.type(within(dialog).getByLabelText("Amount (₹)"), "60000");
    await user.click(within(dialog).getByRole("button", { name: "Record payment" }));
    expect(await within(dialog).findByText("This is more than is still due.")).toBeInTheDocument();
    expect(await within(dialog).findByText("Enter the bank or UPI reference.")).toBeInTheDocument();

    await user.clear(within(dialog).getByLabelText("Amount (₹)"));
    await user.type(within(dialog).getByLabelText("Amount (₹)"), "9,000");
    await user.selectOptions(within(dialog).getByLabelText("Paid by"), "UPI");
    await user.type(within(dialog).getByLabelText("Reference"), "UPI-998877");
    await user.click(within(dialog).getByRole("button", { name: "Record payment" }));
    await waitFor(() =>
      expect(api.recordPayment).toHaveBeenCalledWith("t1", "inv1", {
        amountPaise: 9_000_00,
        mode: "UPI",
        reference: "UPI-998877",
        paidOn: todayInIndia(),
      }),
    );
    expect(await screen.findByText("Payment recorded. ₹50,000 is still due.")).toBeInTheDocument();
  });

  it("cancels an unpaid invoice with a reason; a paid one has neither action", async () => {
    const user = userEvent.setup();
    api.invoice.mockResolvedValue(invoice());
    api.cancelInvoice.mockResolvedValue(invoice({ status: "CANCELLED" }));
    renderAsSuperAdmin(<PlatformInvoiceView tenantId="t1" invoiceId="inv1" />);
    await user.click(await screen.findByRole("button", { name: "Cancel invoice" }));
    const dialog = await screen.findByRole("dialog", { name: "Cancel invoice AKS/26-27/000007?" });
    await user.type(within(dialog).getByLabelText("Reason"), "Wrong number of students");
    api.invoice.mockResolvedValue(
      invoice({ status: "PAID", paidPaise: 59_000_00, balancePaise: 0, paidOn: "2026-10-09" }),
    );
    await user.click(within(dialog).getByRole("button", { name: "Cancel invoice" }));
    await waitFor(() => expect(api.cancelInvoice).toHaveBeenCalledWith("t1", "inv1", "Wrong number of students"));
    await waitFor(() => expect(screen.queryByRole("button", { name: "Record payment" })).not.toBeInTheDocument());
    expect(screen.queryByRole("button", { name: "Cancel invoice" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: translate("en", "billing.invoice.print") })).toBeInTheDocument();
  });
});
