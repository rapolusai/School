import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api";
import type { InvoiceOrder } from "@/lib/types";
import {
  BILLING_READ_ONLY,
  invoice,
  invoiceSummary,
  NO_NOTICE,
  renderInSchool,
  SCHOOL_ADMIN_BILLING,
  schoolBilling,
} from "@/test/billing-fixtures";
import { BillingSandboxCheckoutView } from "./pay-online";
import { SchoolBillingView, SchoolInvoiceView } from "./school-billing-view";

const { mine, getInvoice, payOnline, verify, sandboxCheckout, sandboxComplete, push, replace, openRazorpayCheckout } =
  vi.hoisted(() => ({
    mine: vi.fn(),
    getInvoice: vi.fn(),
    payOnline: vi.fn(),
    verify: vi.fn(),
    sandboxCheckout: vi.fn(),
    sandboxComplete: vi.fn(),
    push: vi.fn(),
    replace: vi.fn(),
    openRazorpayCheckout: vi.fn(),
  }));

vi.mock("@/lib/billing-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/billing-api")>();
  return {
    ...actual,
    billingApi: {
      ...actual.billingApi,
      mine,
      invoice: getInvoice,
      payOnline,
      verify,
      sandboxCheckout,
      sandboxComplete,
    },
  };
});

vi.mock("@/lib/razorpay-checkout", () => ({ openRazorpayCheckout }));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace, push }),
  usePathname: () => "/app/billing",
}));

const unpaid = invoiceSummary({ id: "inv1", invoiceNo: "AKS/26-27/000007" });
const paid = invoiceSummary({ id: "inv0", invoiceNo: "AKS/25-26/000099", status: "PAID", paidPaise: 59_000_00, balancePaise: 0 });

function order(overrides: Partial<InvoiceOrder> = {}): InvoiceOrder {
  return {
    id: "ord1",
    invoiceId: "inv1",
    invoiceNo: "AKS/26-27/000007",
    gateway: "sandbox",
    gatewayOrderId: "order_SBXabc",
    amountPaise: 59_000_00,
    currency: "INR",
    status: "CREATED",
    failureReason: null,
    checkoutKey: null,
    payeeName: "Akshara School Cloud (sample seller)",
    ...overrides,
  };
}

beforeEach(() => {
  mine.mockResolvedValue(
    schoolBilling({ status: "ACTIVE", plan: "GROWTH", trialEndsAt: null, trialDaysLeft: null, notice: NO_NOTICE, invoices: [unpaid, paid] }),
  );
});

describe("paying an invoice online", () => {
  it("offers no online payment while Akshara takes none, and says to pay by bank transfer", async () => {
    mine.mockResolvedValue(
      schoolBilling({
        status: "ACTIVE",
        plan: "GROWTH",
        trialEndsAt: null,
        trialDaysLeft: null,
        notice: NO_NOTICE,
        invoices: [unpaid, paid],
        onlinePayments: false,
      }),
    );
    renderInSchool(SCHOOL_ADMIN_BILLING, <SchoolBillingView />);
    expect(await screen.findByTestId("billing-pay-offline")).toHaveTextContent("Pay by bank transfer");
    expect(screen.queryByRole("button", { name: /Pay online/ })).not.toBeInTheDocument();
  });

  it("offers Pay online only on open invoices, and opens the sandbox checkout", async () => {
    const user = userEvent.setup();
    payOnline.mockResolvedValue(order());
    renderInSchool(SCHOOL_ADMIN_BILLING, <SchoolBillingView />);
    const table = await screen.findByTestId("invoices-table");
    const buttons = within(table).getAllByRole("button", { name: /Pay invoice .* online/ });
    expect(buttons).toHaveLength(1);
    await user.click(buttons[0]);
    await waitFor(() => expect(push).toHaveBeenCalledWith("/app/billing/pay/sandbox/order_SBXabc"));
    expect(payOnline).toHaveBeenCalledWith("inv1");
  });

  it("is not offered without settings.manage", async () => {
    renderInSchool(BILLING_READ_ONLY, <SchoolBillingView />);
    await screen.findByTestId("invoices-table");
    expect(screen.queryByRole("button", { name: /online/ })).not.toBeInTheDocument();
  });

  it("pays through Razorpay's checkout and verifies the result", async () => {
    const user = userEvent.setup();
    payOnline.mockResolvedValue(order({ gateway: "razorpay", checkoutKey: "rzp_test_key", gatewayOrderId: "order_R1" }));
    openRazorpayCheckout.mockResolvedValue({ gatewayPaymentId: "pay_R1", signature: "sig" });
    verify.mockResolvedValue(invoice({ status: "PAID", paidPaise: 59_000_00, balancePaise: 0 }));
    renderInSchool(SCHOOL_ADMIN_BILLING, <SchoolBillingView />);
    await user.click(await screen.findByRole("button", { name: "Pay invoice AKS/26-27/000007 online" }));
    await waitFor(() => expect(verify).toHaveBeenCalledWith("ord1", { gatewayPaymentId: "pay_R1", signature: "sig" }));
    expect(openRazorpayCheckout).toHaveBeenCalledWith(
      expect.objectContaining({ key: "rzp_test_key", gatewayOrderId: "order_R1", amountPaise: 59_000_00 }),
    );
    expect(await screen.findByText("Payment received. Invoice AKS/26-27/000007 is paid.")).toBeInTheDocument();
  });

  it("says so when the payer closes Razorpay's window, and shows API errors", async () => {
    const user = userEvent.setup();
    payOnline.mockResolvedValueOnce(order({ gateway: "razorpay", checkoutKey: "rzp_test_key" }));
    openRazorpayCheckout.mockResolvedValueOnce(null);
    renderInSchool(SCHOOL_ADMIN_BILLING, <SchoolBillingView />);
    await user.click(await screen.findByRole("button", { name: "Pay invoice AKS/26-27/000007 online" }));
    expect(await screen.findByText("The payment window was closed. Nothing was charged.")).toBeInTheDocument();
    expect(verify).not.toHaveBeenCalled();

    payOnline.mockRejectedValueOnce(
      new ApiError({ status: 409, title: "Already paid", detail: "Invoice AKS/26-27/000007 is already paid." }),
    );
    await user.click(screen.getByRole("button", { name: "Pay invoice AKS/26-27/000007 online" }));
    expect(await screen.findByText("Invoice AKS/26-27/000007 is already paid.")).toBeInTheDocument();
  });

  it("offers Pay online on an open invoice's page and thanks the payer after paying", async () => {
    getInvoice.mockResolvedValue(invoice());
    const { unmount } = renderInSchool(SCHOOL_ADMIN_BILLING, <SchoolInvoiceView id="inv1" />);
    expect(await screen.findByRole("button", { name: "Pay invoice AKS/26-27/000007 online" })).toBeInTheDocument();
    unmount();
    getInvoice.mockResolvedValue(invoice({ status: "PAID", paidPaise: 59_000_00, balancePaise: 0 }));
    renderInSchool(SCHOOL_ADMIN_BILLING, <SchoolInvoiceView id="inv1" paid />);
    expect(await screen.findByTestId("invoice-paid-banner")).toHaveTextContent("This invoice is paid");
    expect(screen.queryByRole("button", { name: /online/ })).not.toBeInTheDocument();
  });
});

describe("BillingSandboxCheckoutView", () => {
  const checkout = {
    gatewayOrderId: "order_SBXabc",
    orderId: "ord1",
    invoiceId: "inv1",
    invoiceNo: "AKS/26-27/000007",
    amountPaise: 59_000_00,
    currency: "INR",
    payeeName: "Akshara School Cloud (sample seller)",
    schoolName: "Sunrise Public School",
    status: "CREATED" as const,
  };

  it("verifies a successful sandbox payment and opens the paid invoice", async () => {
    const user = userEvent.setup();
    sandboxCheckout.mockResolvedValue(checkout);
    sandboxComplete.mockResolvedValue({
      orderId: "ord1",
      gatewayOrderId: "order_SBXabc",
      status: "SUCCESS",
      gatewayPaymentId: "pay_SBX1",
      signature: "sig",
    });
    verify.mockResolvedValue(invoice({ status: "PAID" }));
    renderInSchool(SCHOOL_ADMIN_BILLING, <BillingSandboxCheckoutView gatewayOrderId="order_SBXabc" />);
    expect(await screen.findByTestId("billing-sandbox-amount")).toHaveTextContent("₹59,000");
    await user.click(screen.getByRole("button", { name: "Payment succeeds" }));
    await waitFor(() => expect(replace).toHaveBeenCalledWith("/app/billing/invoices/inv1?paid=1"));
    expect(verify).toHaveBeenCalledWith("ord1", { gatewayPaymentId: "pay_SBX1", signature: "sig" });
  });

  it("shows a failed payment", async () => {
    const user = userEvent.setup();
    sandboxCheckout.mockResolvedValue(checkout);
    sandboxComplete.mockResolvedValue({
      orderId: "ord1",
      gatewayOrderId: "order_SBXabc",
      status: "FAILED",
      gatewayPaymentId: null,
      signature: null,
    });
    renderInSchool(SCHOOL_ADMIN_BILLING, <BillingSandboxCheckoutView gatewayOrderId="order_SBXabc" />);
    await user.click(await screen.findByRole("button", { name: "Payment fails" }));
    expect(await screen.findByText("The payment failed. Nothing was charged.")).toBeInTheDocument();
  });

  it("is refused without settings.manage", () => {
    renderInSchool(BILLING_READ_ONLY, <BillingSandboxCheckoutView gatewayOrderId="order_SBXabc" />);
    expect(sandboxCheckout).not.toHaveBeenCalled();
  });
});
