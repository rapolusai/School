import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api";
import { ACCOUNTANT, ARJUN_FEES, ARJUN_HIT, PRINCIPAL_FEES, receipt } from "@/test/fees-fixtures";
import { renderAs } from "@/test/render";
import { CollectView, EMPTY_PAYMENT, payableFor, toPaymentRequest, validatePayment } from "./collect-view";

const { searchStudents, studentFees, collect, waiveLateFee } = vi.hoisted(() => ({
  searchStudents: vi.fn(),
  studentFees: vi.fn(),
  collect: vi.fn(),
  waiveLateFee: vi.fn(),
}));

vi.mock("@/lib/fees-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/fees-api")>();
  return { ...actual, feesApi: { ...actual.feesApi, searchStudents, studentFees, collect, waiveLateFee } };
});

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  usePathname: () => "/app/fees/collect",
}));

beforeEach(() => {
  searchStudents.mockResolvedValue([ARJUN_HIT]);
  studentFees.mockResolvedValue(ARJUN_FEES);
  collect.mockResolvedValue(receipt());
});

describe("validatePayment", () => {
  it("mirrors the API's rules for amounts and mode details", () => {
    expect(validatePayment({ ...EMPTY_PAYMENT }, 100_00)).toEqual({ amount: "validation.required" });
    expect(validatePayment({ ...EMPTY_PAYMENT, amount: "12.345" }, 100_00).amount).toBe("fees.v.amount");
    expect(validatePayment({ ...EMPTY_PAYMENT, amount: "0" }, 100_00).amount).toBe("fees.v.amount");
    expect(validatePayment({ ...EMPTY_PAYMENT, amount: "100.01" }, 100_00).amount).toBe("fees.v.moreThanDue");
    expect(validatePayment({ ...EMPTY_PAYMENT, amount: "100" }, 100_00)).toEqual({});
    expect(validatePayment({ ...EMPTY_PAYMENT, amount: "100", mode: "CHEQUE" }, 100_00)).toEqual({
      chequeNo: "validation.required",
      bankName: "validation.required",
    });
    expect(validatePayment({ ...EMPTY_PAYMENT, amount: "100", mode: "UPI" }, 100_00)).toEqual({
      reference: "validation.required",
    });
    expect(validatePayment({ ...EMPTY_PAYMENT, amount: "100", mode: "CARD" }, 100_00)).toEqual({});
  });

  it("sends only the fields of the chosen mode, in paise", () => {
    expect(
      toPaymentRequest(
        { ...EMPTY_PAYMENT, amount: "1,150.50", mode: "UPI", reference: " UTR123 ", chequeNo: "9" },
        ["q2"],
      ),
    ).toEqual({
      amountPaise: 1_150_50,
      mode: "UPI",
      chequeNo: null,
      bankName: null,
      reference: "UTR123",
      instalmentIds: ["q2"],
      includeLateFee: true,
      remarks: null,
    });
  });

  it("allows up to the open balance plus late fees, or the ticked instalments only", () => {
    expect(payableFor(ARJUN_FEES.instalments, new Set(), true)).toBe(34_600_00);
    expect(payableFor(ARJUN_FEES.instalments, new Set(), false)).toBe(34_500_00);
    expect(payableFor(ARJUN_FEES.instalments, new Set(["q2", "q3"]), true)).toBe(23_100_00);
  });
});

describe("CollectView", () => {
  it("finds a student, takes a cheque for one instalment and shows the printable receipt", async () => {
    const user = userEvent.setup();
    const print = vi.spyOn(window, "print").mockImplementation(() => {});
    renderAs(ACCOUNTANT, <CollectView />, ["ACCOUNTANT"]);

    await user.type(screen.getByPlaceholderText("Search by student, admission number or parent"), "Arj");
    await user.click(await screen.findByRole("button", { name: /Arjun Sharma/ }));
    expect(await screen.findByRole("heading", { name: "Arjun Sharma" })).toBeInTheDocument();
    expect(searchStudents).toHaveBeenCalledWith("Arj");
    expect(studentFees).toHaveBeenCalledWith("st1");

    // What is payable today (Q2 and its late fee) is suggested.
    const amount = screen.getByLabelText("Amount received (₹)");
    expect(amount).toHaveValue("11600");

    // Pick Q2 only and leave the late fee out.
    const table = screen.getByTestId("dues-table");
    expect(within(table).getAllByRole("checkbox")).toHaveLength(3);
    await user.click(within(table).getByRole("checkbox", { name: "Pay Quarter 2" }));
    expect(screen.getByText("The ticked instalments come to ₹11,600.")).toBeInTheDocument();
    await user.click(screen.getByRole("checkbox", { name: "Collect the late fee too" }));
    expect(amount).toHaveValue("11500");

    // A cheque needs its number and bank.
    await user.click(screen.getByRole("radio", { name: "Cheque" }));
    await user.click(screen.getByRole("button", { name: "Collect ₹11,500" }));
    expect(await screen.findAllByText("Fill in this field.")).toHaveLength(2);
    expect(collect).not.toHaveBeenCalled();

    // More than the ticked instalment is refused before it reaches the API.
    await user.clear(amount);
    await user.type(amount, "50000");
    await user.type(screen.getByLabelText("Cheque number"), "123456");
    await user.type(screen.getByLabelText("Bank"), "State Bank of India");
    await user.click(screen.getByRole("button", { name: "Collect ₹50,000" }));
    expect(await screen.findByText("This is more than what is due.")).toBeInTheDocument();
    expect(collect).not.toHaveBeenCalled();

    await user.clear(amount);
    await user.type(amount, "11500");
    await user.click(screen.getByRole("button", { name: "Collect ₹11,500" }));
    await waitFor(() =>
      expect(collect).toHaveBeenCalledWith("st1", {
        amountPaise: 11_500_00,
        mode: "CHEQUE",
        chequeNo: "123456",
        bankName: "State Bank of India",
        reference: null,
        instalmentIds: ["q2"],
        includeLateFee: false,
        remarks: null,
      }),
    );

    const doc = await screen.findByTestId("receipt");
    expect(within(doc).getByTestId("receipt-no")).toHaveTextContent("RCPT/2026-27/000042");
    expect(within(doc).getByText("Rupees Eleven Thousand Five Hundred Only")).toBeInTheDocument();
    expect(within(doc).getByText("Sunrise Public School")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Print" }));
    expect(print).toHaveBeenCalledTimes(1);
  });

  it("shows the API's field error when the amount is refused", async () => {
    const user = userEvent.setup();
    collect.mockRejectedValue(
      new ApiError({ status: 400, title: "Bad request", errors: { amountPaise: "This is more than the ₹100 due." } }),
    );
    renderAs(ACCOUNTANT, <CollectView initialStudentId="st1" />, ["ACCOUNTANT"]);
    await user.click(await screen.findByRole("button", { name: "Collect ₹11,600" }));
    expect(await screen.findByText("This is more than the ₹100 due.")).toBeInTheDocument();
  });

  it("lets fees.read only look at a student's dues", async () => {
    renderAs(PRINCIPAL_FEES, <CollectView initialStudentId="st1" />, ["PRINCIPAL"]);
    expect(await screen.findByRole("heading", { name: "Arjun Sharma" })).toBeInTheDocument();
    expect(within(screen.getByTestId("dues-table")).queryAllByRole("checkbox")).toHaveLength(0);
    expect(screen.queryByRole("button", { name: /^Collect/ })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Waive" })).not.toBeInTheDocument();
  });

  it("waives a late fee with a reason", async () => {
    const user = userEvent.setup();
    waiveLateFee.mockResolvedValue(ARJUN_FEES);
    renderAs(ACCOUNTANT, <CollectView initialStudentId="st1" />, ["ACCOUNTANT"]);
    const table = await screen.findByTestId("dues-table");
    await user.click(within(table).getByRole("button", { name: "Waive" }));
    const dialog = await screen.findByRole("dialog", { name: "Waive the late fee on Quarter 2?" });
    await user.click(within(dialog).getByRole("button", { name: "Waive late fee" }));
    expect(await within(dialog).findByText("Fill in this field.")).toBeInTheDocument();
    await user.type(within(dialog).getByLabelText("Reason"), "Parent was in hospital");
    await user.click(within(dialog).getByRole("button", { name: "Waive late fee" }));
    await waitFor(() => expect(waiveLateFee).toHaveBeenCalledWith("st1", "q2", "Parent was in hospital"));
  });
});
