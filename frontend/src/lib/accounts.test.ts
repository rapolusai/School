import { describe, expect, it } from "vitest";
import {
  financialYearMonths,
  financialYearOfDate,
  financialYearRange,
  gstAt,
  tdsOn,
  validateExpense,
  validateIncome,
  validatePaid,
  validateVendor,
  type ExpenseValues,
  type VendorValues,
} from "./accounts";

const TODAY = "2026-10-12";

function expense(overrides: Partial<ExpenseValues> = {}): ExpenseValues {
  return {
    headId: "h-1",
    costCentreId: "",
    vendorId: "v-1",
    vendorName: "",
    expenseDate: "2026-10-05",
    amount: "1000",
    gstRate: "18",
    gst: "180",
    deductTds: false,
    description: "Lab wiring",
    billNumber: "",
    ...overrides,
  };
}

describe("financial years", () => {
  it("starts on 1 April", () => {
    expect(financialYearOfDate("2026-03-31")).toBe("2025-26");
    expect(financialYearOfDate("2026-04-01")).toBe("2026-27");
    expect(financialYearRange("2026-27")).toEqual({ from: "2026-04-01", to: "2027-03-31" });
    expect(financialYearMonths("2026-27")[0]).toBe("2026-04");
    expect(financialYearMonths("2026-27")[11]).toBe("2027-03");
  });
});

describe("GST and TDS", () => {
  it("rounds to the nearest paisa", () => {
    expect(gstAt(1_000_00, 18)).toBe(180_00);
    expect(gstAt(333_33, 5)).toBe(16_67);
    expect(tdsOn(10_000_00, 2)).toBe(200_00);
    expect(tdsOn(10_000_00, null)).toBe(0);
    expect(tdsOn(10_000_00, 0)).toBe(0);
  });
});

describe("validateExpense", () => {
  it("accepts a complete bill", () => {
    expect(validateExpense(expense(), TODAY, true)).toEqual({});
  });

  it("refuses GST on a head without it, GST above the amount, a future date and TDS without a vendor", () => {
    expect(validateExpense(expense(), TODAY, false).gst).toBe("accounts.v.gstHead");
    expect(validateExpense(expense({ gst: "2000" }), TODAY, true).gst).toBe("accounts.v.gstTooMuch");
    expect(validateExpense(expense({ expenseDate: "2026-10-13" }), TODAY, true).expenseDate).toBe("accounts.v.futureDate");
    expect(validateExpense(expense({ vendorId: "", deductTds: true }), TODAY, true).deductTds).toBe("accounts.v.tdsVendor");
    expect(validateExpense(expense({ amount: "0" }), TODAY, true).amount).toBe("accounts.v.amount");
    expect(validateExpense(expense({ description: "x".repeat(501) }), TODAY, true).description).toBe("accounts.v.descriptionLong");
  });
});

describe("validatePaid", () => {
  it("needs a bank account unless cash, a cheque number for cheques, and a date on or after the bill", () => {
    expect(validatePaid({ paidOn: "2026-10-10", mode: "CASH", bankAccountId: "", reference: "" }, "2026-10-05", TODAY)).toEqual({});
    expect(validatePaid({ paidOn: "2026-10-10", mode: "UPI", bankAccountId: "", reference: "" }, "2026-10-05", TODAY).bankAccountId).toBe("accounts.v.bankAccount");
    expect(validatePaid({ paidOn: "2026-10-10", mode: "CHEQUE", bankAccountId: "b-1", reference: "" }, "2026-10-05", TODAY).reference).toBe("accounts.v.chequeNumber");
    expect(validatePaid({ paidOn: "2026-10-01", mode: "CASH", bankAccountId: "", reference: "" }, "2026-10-05", TODAY).paidOn).toBe("accounts.v.paidBeforeBill");
  });
});

describe("validateVendor", () => {
  const vendor = (overrides: Partial<VendorValues> = {}): VendorValues => ({
    name: "Sparks Electricals",
    gstin: "36AABCU9603R1ZX",
    pan: "AABCU9603R",
    phone: "9876543210",
    email: "",
    address: "",
    bankAccountNumber: "000012345678",
    bankIfsc: "SBIN0001234",
    tdsSection: "194C",
    tdsRatePercent: "2",
    active: true,
    ...overrides,
  });

  it("checks the GSTIN, PAN, phone, bank pair and TDS rate", () => {
    expect(validateVendor(vendor())).toEqual({});
    expect(validateVendor(vendor({ gstin: "36AABCU9603R1Z" })).gstin).toBe("accounts.v.gstin");
    expect(validateVendor(vendor({ pan: "ABC123" })).pan).toBe("accounts.v.pan");
    expect(validateVendor(vendor({ phone: "12" })).phone).toBe("accounts.v.phone");
    expect(validateVendor(vendor({ bankIfsc: "" })).bankIfsc).toBe("accounts.v.accountAndIfsc");
    expect(validateVendor(vendor({ tdsRatePercent: "" })).tdsRatePercent).toBe("accounts.v.tdsRate");
    expect(validateVendor(vendor({ tdsSection: "", tdsRatePercent: "10" })).tdsSection).toBe("accounts.v.tdsSection");
    expect(validateVendor(vendor({ tdsRatePercent: "45" })).tdsRatePercent).toBe("accounts.v.tdsRate");
  });
});

describe("validateIncome", () => {
  const income = {
    kind: "DONATION",
    headId: "h-don",
    receivedOn: "2026-10-10",
    amount: "5000",
    payerName: "Ramesh Gupta",
    payerPan: "",
    payerAddress: "",
    mode: "CASH" as const,
    bankAccountId: "",
    reference: "",
    description: "",
  };

  it("asks for the donor's PAN only when the school issues 80G receipts", () => {
    expect(validateIncome(income, TODAY, false)).toEqual({});
    expect(validateIncome(income, TODAY, true).payerPan).toBe("accounts.v.panFor80g");
    expect(validateIncome({ ...income, kind: "GRANT" }, TODAY, true)).toEqual({});
    expect(validateIncome({ ...income, mode: "BANK" }, TODAY, false).bankAccountId).toBe("accounts.v.bankAccount");
  });
});
