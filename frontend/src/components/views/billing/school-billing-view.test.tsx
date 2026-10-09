import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api";
import {
  BILLING_READ_ONLY,
  invoice,
  invoiceSummary,
  NO_NOTICE,
  renderInSchool,
  SCHOOL_ADMIN_BILLING,
  schoolBilling,
} from "@/test/billing-fixtures";
import { BillingBanner, showBannerOn } from "./billing-banner";
import { gstinCheckCharacter } from "./billing-ui";
import { SchoolBillingView, SchoolInvoiceView } from "./school-billing-view";

const { mine, notice, getInvoice, updateDetails } = vi.hoisted(() => ({
  mine: vi.fn(),
  notice: vi.fn(),
  getInvoice: vi.fn(),
  updateDetails: vi.fn(),
}));

vi.mock("@/lib/billing-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/billing-api")>();
  return { ...actual, billingApi: { ...actual.billingApi, mine, notice, invoice: getInvoice, updateDetails } };
});

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  usePathname: () => "/app/billing",
}));

beforeEach(() => {
  mine.mockResolvedValue(schoolBilling());
});

describe("SchoolBillingView", () => {
  it("shows a trial school its plan, days left, the plans and an empty invoice list", async () => {
    renderInSchool(SCHOOL_ADMIN_BILLING, <SchoolBillingView />);
    expect(await screen.findByTestId("billing-plan-name")).toHaveTextContent("Starter");
    expect(screen.getByTestId("billing-trial")).toHaveTextContent("12 Oct 2026 · 3 days left");
    expect(screen.getByTestId("billing-notice")).toHaveTextContent("3 days left");
    expect(screen.getByTestId("invoices-empty")).toHaveTextContent("No invoices yet.");
    expect(screen.getByRole("region", { name: "Starter" })).toHaveTextContent("Current plan");
    expect(screen.getByTestId("billing-details")).toHaveTextContent("Sunrise Public School");
    expect(screen.getByTestId("billing-details")).toHaveTextContent("Not set");
    // Schools never pay from here.
    expect(screen.queryByRole("button", { name: /pay/i })).not.toBeInTheDocument();
  });

  it("lists a paying school's invoices with links to the printable page and its renewal date", async () => {
    mine.mockResolvedValue(
      schoolBilling({
        status: "ACTIVE",
        plan: "GROWTH",
        trialEndsAt: null,
        trialDaysLeft: null,
        notice: NO_NOTICE,
        subscription: {
          billingCycle: "YEARLY",
          billedStudents: 250,
          periodStart: "2026-10-09",
          periodEnd: "2027-10-08",
          nextRenewalOn: "2027-10-09",
          paidSince: "2026-10-09",
          unitPricePaise: 200_00,
          nextInvoiceTaxablePaise: 50_000_00,
        },
        invoices: [invoiceSummary({ status: "PAID", paidPaise: 59_000_00, balancePaise: 0 })],
      }),
    );
    renderInSchool(BILLING_READ_ONLY, <SchoolBillingView />);
    expect(await screen.findByTestId("billing-renewal")).toHaveTextContent("9 Oct 2027");
    expect(screen.getByText("₹50,000 + GST (250 students × ₹200)")).toBeInTheDocument();
    const table = screen.getByTestId("invoices-table");
    expect(within(table).getByRole("link", { name: "AKS/26-27/000007" })).toHaveAttribute(
      "href",
      "/app/billing/invoices/inv1",
    );
    expect(within(table).getByText("Paid")).toBeInTheDocument();
    expect(screen.queryByTestId("billing-notice")).not.toBeInTheDocument();
    // Without settings.manage the details are read-only.
    expect(screen.queryByRole("button", { name: "Edit" })).not.toBeInTheDocument();
  });

  it("lets a School Admin set the billing details, checking the state and GSTIN first", async () => {
    const user = userEvent.setup();
    updateDetails.mockResolvedValue(schoolBilling());
    renderInSchool(SCHOOL_ADMIN_BILLING, <SchoolBillingView />);
    await user.click(await screen.findByRole("button", { name: "Edit" }));
    const dialog = await screen.findByRole("dialog", { name: "Billing details" });
    await user.type(within(dialog).getByLabelText("GSTIN (optional)"), "36ABCDE1234F1Z0X");
    await user.click(within(dialog).getByRole("button", { name: "Save" }));
    expect(await within(dialog).findByText("Choose the state.")).toBeInTheDocument();
    expect(updateDetails).not.toHaveBeenCalled();

    const gstin = `29ABCDE1234F1Z${gstinCheckCharacter("29ABCDE1234F1Z")}`;
    await user.selectOptions(within(dialog).getByLabelText("State"), "36");
    await user.clear(within(dialog).getByLabelText("GSTIN (optional)"));
    await user.type(within(dialog).getByLabelText("GSTIN (optional)"), gstin.toLowerCase());
    await user.click(within(dialog).getByRole("button", { name: "Save" }));
    expect(await within(dialog).findByText(/registered in another state/)).toBeInTheDocument();

    await user.selectOptions(within(dialog).getByLabelText("State"), "29");
    await user.type(within(dialog).getByLabelText("Billing address"), "4 Residency Road, Bengaluru");
    await user.click(within(dialog).getByRole("button", { name: "Save" }));
    await waitFor(() =>
      expect(updateDetails).toHaveBeenCalledWith({
        legalName: null,
        address: "4 Residency Road, Bengaluru",
        stateCode: "29",
        gstin,
      }),
    );
    expect(await screen.findByText("Billing details saved.")).toBeInTheDocument();
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("shows the API's field errors in the dialog", async () => {
    const user = userEvent.setup();
    updateDetails.mockRejectedValue(
      new ApiError({ status: 400, title: "Bad request", errors: { gstin: "This GSTIN is not valid." } }),
    );
    renderInSchool(SCHOOL_ADMIN_BILLING, <SchoolBillingView />);
    await user.click(await screen.findByRole("button", { name: "Edit" }));
    const dialog = await screen.findByRole("dialog", { name: "Billing details" });
    await user.selectOptions(within(dialog).getByLabelText("State"), "36");
    await user.click(within(dialog).getByRole("button", { name: "Save" }));
    expect(await within(dialog).findByText("This GSTIN is not valid.")).toBeInTheDocument();
  });

  it("offers a retry when the page cannot load", async () => {
    mine.mockRejectedValue(new ApiError({ status: 500, title: "Server error" }));
    renderInSchool(SCHOOL_ADMIN_BILLING, <SchoolBillingView />);
    expect(await screen.findByRole("button", { name: "Try again" })).toBeInTheDocument();
  });
});

describe("SchoolInvoiceView", () => {
  it("shows the invoice with a print button", async () => {
    getInvoice.mockResolvedValue(invoice());
    renderInSchool(BILLING_READ_ONLY, <SchoolInvoiceView id="inv1" />);
    expect(await screen.findByRole("heading", { name: "Invoice AKS/26-27/000007" })).toBeInTheDocument();
    expect(screen.getByTestId("invoice")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Print or save as PDF" })).toBeInTheDocument();
    expect(getInvoice).toHaveBeenCalledWith("inv1");
  });

  it("says when an invoice is not the school's", async () => {
    getInvoice.mockRejectedValue(new ApiError({ status: 404, title: "Not found" }));
    renderInSchool(BILLING_READ_ONLY, <SchoolInvoiceView id="other" />);
    expect(await screen.findByText("This invoice could not be found.")).toBeInTheDocument();
  });
});

describe("BillingBanner", () => {
  const overdue = {
    ...NO_NOTICE,
    kind: "PAYMENT_OVERDUE" as const,
    overduePaise: 23_600_00,
    overdueInvoices: 1,
    oldestDueDate: "2026-09-14",
  };
  const trial = { ...NO_NOTICE, kind: "TRIAL_ENDING" as const, trialEndsAt: "2026-10-12T06:30:00Z", trialDaysLeft: 3 };

  it("is shown on every page but Billing, and leaves the dashboard's own trial banner alone", () => {
    expect(showBannerOn("/app/students", trial)).toBe(true);
    expect(showBannerOn("/app/dashboard", trial)).toBe(false);
    expect(showBannerOn("/app/dashboard", overdue)).toBe(true);
    expect(showBannerOn("/app/billing", overdue)).toBe(false);
    expect(showBannerOn("/app/billing/invoices/inv1", overdue)).toBe(false);
    expect(showBannerOn("/app/students", NO_NOTICE)).toBe(false);
    expect(showBannerOn("/app/students", undefined)).toBe(false);
  });

  it("tells a School Admin that a payment is overdue, with a link to Billing", async () => {
    notice.mockResolvedValue(overdue);
    renderInSchool(SCHOOL_ADMIN_BILLING, <BillingBanner pathname="/app/students" />);
    const banner = await screen.findByTestId("billing-banner");
    expect(banner).toHaveTextContent("A payment of ₹23,600 to Akshara is overdue");
    expect(within(banner).getByRole("link", { name: "View billing" })).toHaveAttribute("href", "/app/billing");
  });

  it("is never fetched for people without billing.read", () => {
    renderInSchool(["dashboard.view", "students.read"], <BillingBanner pathname="/app/students" />);
    expect(notice).not.toHaveBeenCalled();
    expect(screen.queryByTestId("billing-banner")).not.toBeInTheDocument();
  });
});
