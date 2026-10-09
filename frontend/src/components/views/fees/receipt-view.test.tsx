import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api";
import { translate, type Translate } from "@/lib/i18n";
import { ACCOUNTANT, PRINCIPAL_FEES, receipt } from "@/test/fees-fixtures";
import { renderAs } from "@/test/render";
import { lineLabel, ReceiptDocument } from "./receipt-document";
import { StaffReceiptView } from "./receipts-view";

const { getReceipt, cancelReceipt } = vi.hoisted(() => ({ getReceipt: vi.fn(), cancelReceipt: vi.fn() }));

vi.mock("@/lib/fees-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/fees-api")>();
  return { ...actual, feesApi: { ...actual.feesApi, getReceipt, cancelReceipt } };
});

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  usePathname: () => "/app/fees/receipts/r42",
}));

const t: Translate = (key, vars) => translate("en", key, vars);

beforeEach(() => {
  getReceipt.mockResolvedValue(receipt());
});

describe("ReceiptDocument", () => {
  it("prints the school header, the student, every line, the total in Indian format and in words", () => {
    renderAs(
      ACCOUNTANT,
      <ReceiptDocument
        receipt={receipt({
          amountPaise: 1_84_300_00,
          amountInWords: "Rupees One Lakh Eighty Four Thousand Three Hundred Only",
          lines: [
            { kind: "DUE", instalmentLabel: "Quarter 2", headName: "Tuition fee", amountPaise: 1_84_000_00 },
            { kind: "LATE_FEE", instalmentLabel: "Quarter 2", headName: null, amountPaise: 300_00 },
          ],
        })}
      />,
    );
    const doc = screen.getByRole("article", { name: "Receipt RCPT/2026-27/000042" });
    expect(within(doc).getByText("Sunrise Public School")).toBeInTheDocument();
    expect(within(doc).getByText("12 MG Road, Vijayawada")).toBeInTheDocument();
    expect(within(doc).getByText(/UDISE 28161234567/)).toBeInTheDocument();
    expect(within(doc).getByText("Arjun Sharma")).toBeInTheDocument();
    expect(within(doc).getByText("Class 5 A")).toBeInTheDocument();
    expect(within(doc).getByText("9 Oct 2026")).toBeInTheDocument();
    expect(within(doc).getByText("Quarter 2 · Tuition fee")).toBeInTheDocument();
    expect(within(doc).getByText("Late fee · Quarter 2")).toBeInTheDocument();
    expect(within(doc).getByText("₹1,84,000")).toBeInTheDocument();
    expect(within(doc).getByText("₹1,84,300")).toBeInTheDocument();
    expect(within(doc).getByText("Rupees One Lakh Eighty Four Thousand Three Hundred Only")).toBeInTheDocument();
    expect(within(doc).getByText("Cheque · cheque 123456, State Bank of India")).toBeInTheDocument();
    expect(within(doc).getByText("Meena Reddy")).toBeInTheDocument();
    expect(within(doc).getByText("Issued")).toBeInTheDocument();
  });

  it("marks a cancelled receipt with who cancelled it and why", () => {
    renderAs(
      ACCOUNTANT,
      <ReceiptDocument
        receipt={receipt({
          status: "CANCELLED",
          cancelledAt: "2026-10-09T08:00:00Z",
          cancelledByName: "Meena Reddy",
          cancelReason: "Cheque returned unpaid",
        })}
      />,
    );
    expect(screen.getByTestId("receipt")).toHaveAttribute("data-stamp", "Cancelled");
    expect(screen.getByTestId("receipt-cancelled")).toHaveTextContent(
      /Cancelled on 9 Oct 2026, 1:30 pm by Meena Reddy\. Reason: Cheque returned unpaid/i,
    );
  });

  it("labels online receipts and advance lines", () => {
    expect(lineLabel({ kind: "ADVANCE", instalmentLabel: null, headName: null, amountPaise: 1 }, t)).toBe("Advance");
    renderAs(
      ACCOUNTANT,
      <ReceiptDocument
        receipt={receipt({ mode: "ONLINE", source: "ONLINE", chequeNo: null, bankName: null, gatewayPaymentId: "pay_SBX1", collectedByName: null })}
      />,
    );
    expect(screen.getByText("Online · payment pay_SBX1")).toBeInTheDocument();
    expect(screen.getByText("Online payment")).toBeInTheDocument();
  });
});

describe("StaffReceiptView", () => {
  it("lets fees.manage cancel a receipt with a reason", async () => {
    const user = userEvent.setup();
    cancelReceipt.mockResolvedValue(receipt({ status: "CANCELLED" }));
    renderAs(ACCOUNTANT, <StaffReceiptView id="r42" />, ["ACCOUNTANT"]);
    expect(await screen.findByRole("heading", { name: "Receipt RCPT/2026-27/000042" })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Cancel receipt" }));
    const dialog = await screen.findByRole("dialog", { name: "Cancel receipt RCPT/2026-27/000042?" });
    await user.click(within(dialog).getByRole("button", { name: "Cancel receipt" }));
    expect(await within(dialog).findByText("Fill in this field.")).toBeInTheDocument();
    expect(cancelReceipt).not.toHaveBeenCalled();

    getReceipt.mockResolvedValue(
      receipt({ status: "CANCELLED", cancelledAt: "2026-10-09T08:00:00Z", cancelledByName: "Meena Reddy", cancelReason: "Cheque bounced" }),
    );
    await user.type(within(dialog).getByLabelText("Reason"), "Cheque bounced");
    await user.click(within(dialog).getByRole("button", { name: "Cancel receipt" }));
    await waitFor(() => expect(cancelReceipt).toHaveBeenCalledWith("r42", "Cheque bounced"));
    expect(await screen.findByTestId("receipt-cancelled")).toHaveTextContent("Cheque bounced");
    expect(screen.queryByRole("button", { name: "Cancel receipt" })).not.toBeInTheDocument();
  });

  it("shows read-only staff the receipt without Cancel", async () => {
    renderAs(PRINCIPAL_FEES, <StaffReceiptView id="r42" />, ["PRINCIPAL"]);
    expect(await screen.findByTestId("receipt")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Print" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Cancel receipt" })).not.toBeInTheDocument();
  });

  it("explains a receipt that is not there", async () => {
    getReceipt.mockRejectedValue(new ApiError({ status: 404, title: "Not found" }));
    renderAs(ACCOUNTANT, <StaffReceiptView id="other" />, ["ACCOUNTANT"]);
    expect(await screen.findByText("This receipt could not be found.")).toBeInTheDocument();
  });
});
