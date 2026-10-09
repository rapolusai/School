import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api";
import { ACCOUNTANT, ARJUN_FEES, PARENT, receipt } from "@/test/fees-fixtures";
import { renderAs } from "@/test/render";
import { ChildFeesSummary, ChildFeesView, ChildReceiptView, nextDue, payNowInstalments } from "./child-fees";
import { SandboxCheckoutView } from "./sandbox-checkout-view";

const { childFees, childOrder, childVerify, childReceipt, sandboxCheckout, sandboxComplete, staffVerify, push, replace } =
  vi.hoisted(() => ({
    childFees: vi.fn(),
    childOrder: vi.fn(),
    childVerify: vi.fn(),
    childReceipt: vi.fn(),
    sandboxCheckout: vi.fn(),
    sandboxComplete: vi.fn(),
    staffVerify: vi.fn(),
    push: vi.fn(),
    replace: vi.fn(),
  }));

vi.mock("@/lib/fees-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/fees-api")>();
  return {
    ...actual,
    feesApi: {
      ...actual.feesApi,
      childFees,
      childOrder,
      childVerify,
      childReceipt,
      sandboxCheckout,
      sandboxComplete,
      staffVerify,
    },
  };
});

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace, push }),
  usePathname: () => "/app/children/st1/fees",
}));

const CHECKOUT = {
  gatewayOrderId: "order_SBX1",
  orderId: "o1",
  studentId: "st1",
  amountPaise: 11_600_00,
  currency: "INR",
  schoolName: "Sunrise Public School",
  studentName: "Arjun Sharma",
  instalments: ["Quarter 2"],
  status: "CREATED" as const,
};

beforeEach(() => {
  childFees.mockResolvedValue(ARJUN_FEES);
  sandboxCheckout.mockResolvedValue(CHECKOUT);
});

describe("a parent's view of fees", () => {
  it("picks what is due now and the next instalment", () => {
    expect(payNowInstalments(ARJUN_FEES).map((i) => i.instalmentId)).toEqual(["q2"]);
    expect(nextDue(ARJUN_FEES)?.instalmentId).toBe("q3");
  });

  it("summarises a child's fees on the dashboard card", async () => {
    renderAs(PARENT, <ChildFeesSummary childId="st1" />, ["PARENT"]);
    expect(await screen.findByText("₹11,600 overdue")).toBeInTheDocument();
    expect(screen.getByText("Next: Quarter 3, ₹11,500 due 10 Dec 2026")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Pay fees" })).toHaveAttribute("href", "/app/children/st1/fees");
  });

  it("starts an online payment for the ticked instalments", async () => {
    const user = userEvent.setup();
    childOrder.mockResolvedValue({ ...CHECKOUT, id: "o1", gateway: "sandbox", status: "CREATED", receiptId: null, failureReason: null });
    renderAs(PARENT, <ChildFeesView id="st1" />, ["PARENT"]);
    expect(await screen.findByRole("heading", { name: "Arjun Sharma" })).toBeInTheDocument();
    const table = screen.getByTestId("dues-table");
    expect(within(table).getByRole("checkbox", { name: "Pay Quarter 2" })).toBeChecked();
    expect(within(table).getByRole("checkbox", { name: "Pay Quarter 3" })).not.toBeChecked();
    expect(screen.getByRole("link", { name: "RCPT/2026-27/000007" })).toHaveAttribute(
      "href",
      "/app/children/st1/fees/receipts/r1",
    );

    await user.click(within(table).getByRole("checkbox", { name: "Pay Quarter 3" }));
    expect(screen.getByRole("button", { name: "Pay ₹23,100 online" })).toBeInTheDocument();
    await user.click(within(table).getByRole("checkbox", { name: "Pay Quarter 3" }));
    await user.click(screen.getByRole("button", { name: "Pay ₹11,600 online" }));
    await waitFor(() => expect(childOrder).toHaveBeenCalledWith("st1", ["q2"]));
    expect(push).toHaveBeenCalledWith("/app/pay/sandbox/order_SBX1");
  });

  it("does not show another family's child", async () => {
    childFees.mockRejectedValue(new ApiError({ status: 404, title: "Not found" }));
    renderAs(PARENT, <ChildFeesView id="other" />, ["PARENT"]);
    expect(await screen.findByText("This student could not be found.")).toBeInTheDocument();
  });

  it("shows the receipt after paying", async () => {
    childReceipt.mockResolvedValue(receipt({ mode: "ONLINE", source: "ONLINE", gatewayPaymentId: "pay_SBX1" }));
    renderAs(PARENT, <ChildReceiptView studentId="st1" receiptId="r42" justPaid />, ["PARENT"]);
    expect(await screen.findByTestId("payment-success")).toHaveTextContent(
      "Payment of ₹11,500 received. Receipt RCPT/2026-27/000042 is below.",
    );
    expect(screen.getByRole("button", { name: "Print or save as PDF" })).toBeInTheDocument();
    expect(childReceipt).toHaveBeenCalledWith("st1", "r42");
  });
});

describe("SandboxCheckoutView", () => {
  it("verifies a successful sandbox payment and opens the parent's receipt", async () => {
    const user = userEvent.setup();
    sandboxComplete.mockResolvedValue({
      orderId: "o1",
      gatewayOrderId: "order_SBX1",
      status: "SUCCESS",
      gatewayPaymentId: "pay_SBX9",
      signature: "abc123",
    });
    childVerify.mockResolvedValue(receipt({ id: "r77" }));
    renderAs(PARENT, <SandboxCheckoutView gatewayOrderId="order_SBX1" />, ["PARENT"]);
    expect(await screen.findByTestId("sandbox-amount")).toHaveTextContent("₹11,600");
    expect(screen.getByText("Sunrise Public School")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Payment succeeds" }));
    await waitFor(() =>
      expect(childVerify).toHaveBeenCalledWith("st1", "o1", { gatewayPaymentId: "pay_SBX9", signature: "abc123" }),
    );
    expect(sandboxComplete).toHaveBeenCalledWith("order_SBX1", "SUCCESS");
    expect(replace).toHaveBeenCalledWith("/app/children/st1/fees/receipts/r77?paid=1");
  });

  it("verifies through the staff route for fee staff", async () => {
    const user = userEvent.setup();
    sandboxComplete.mockResolvedValue({
      orderId: "o1",
      gatewayOrderId: "order_SBX1",
      status: "SUCCESS",
      gatewayPaymentId: "pay_SBX9",
      signature: "abc123",
    });
    staffVerify.mockResolvedValue(receipt({ id: "r78" }));
    renderAs(ACCOUNTANT, <SandboxCheckoutView gatewayOrderId="order_SBX1" />, ["ACCOUNTANT"]);
    await user.click(await screen.findByRole("button", { name: "Payment succeeds" }));
    await waitFor(() => expect(staffVerify).toHaveBeenCalledWith("o1", { gatewayPaymentId: "pay_SBX9", signature: "abc123" }));
    expect(childVerify).not.toHaveBeenCalled();
    expect(replace).toHaveBeenCalledWith("/app/fees/receipts/r78");
  });

  it("reports a failed payment without recording anything", async () => {
    const user = userEvent.setup();
    sandboxComplete.mockResolvedValue({
      orderId: "o1",
      gatewayOrderId: "order_SBX1",
      status: "FAILED",
      gatewayPaymentId: null,
      signature: null,
    });
    renderAs(PARENT, <SandboxCheckoutView gatewayOrderId="order_SBX1" />, ["PARENT"]);
    await user.click(await screen.findByRole("button", { name: "Payment fails" }));
    expect(await screen.findByText("The payment failed. Nothing was charged.")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Back to fees" })).toHaveAttribute("href", "/app/children/st1/fees");
    expect(childVerify).not.toHaveBeenCalled();
  });

  it("is closed to people who are neither parents nor fee staff", () => {
    renderAs(["dashboard.view", "students.read"], <SandboxCheckoutView gatewayOrderId="order_SBX1" />, ["TEACHER"]);
    expect(screen.getByTestId("access-denied")).toBeInTheDocument();
    expect(sandboxCheckout).not.toHaveBeenCalled();
  });
});
