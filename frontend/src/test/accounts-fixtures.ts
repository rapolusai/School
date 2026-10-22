import type { CostCentreView, ExpenseDetail, ExpenseRow, HeadView, IncomeVsExpense, MonthLine, VendorView } from "@/lib/types";

/** Test-only accounts data: Sunrise Public School's chart, a vendor with TDS, and a few bills. */

export const ACCOUNTS_ACCOUNTANT = ["dashboard.view", "fees.read", "accounts.read", "accounts.manage", "leave.request"];
export const ACCOUNTS_PRINCIPAL = ["dashboard.view", "accounts.read", "accounts.approve", "leave.request"];
export const ACCOUNTS_READER = ["dashboard.view", "accounts.read"];

export function head(overrides: Partial<HeadView> = {}): HeadView {
  return {
    id: "h-electricity",
    code: "ELEC",
    name: "Electricity",
    kind: "EXPENSE",
    parentId: null,
    parentName: null,
    gstApplicable: true,
    feeHeadId: null,
    systemCode: "ELECTRICITY",
    active: true,
    displayOrder: 10,
    inUse: false,
    ...overrides,
  };
}

export const HEADS: HeadView[] = [
  head(),
  head({ id: "h-repairs", code: "REPAIR", name: "Repairs", systemCode: "REPAIRS", gstApplicable: true }),
  head({ id: "h-rent", code: "RENT", name: "Rent", systemCode: "RENT", gstApplicable: false }),
  head({ id: "h-fees", code: "FEES", name: "Fees", kind: "INCOME", systemCode: "FEES", gstApplicable: false }),
];

export const CENTRES: CostCentreView[] = [{ id: "cc-main", code: "MAIN", name: "Main campus", active: true, inUse: false }];

export function vendor(overrides: Partial<VendorView> = {}): VendorView {
  return {
    id: "v-sparks",
    name: "Sparks Electricals",
    gstin: "36AABCU9603R1ZX",
    panMasked: "AABXXXXX3R",
    phoneMasked: "98XXXXXX10",
    email: null,
    address: null,
    bankAccountMasked: null,
    bankIfsc: null,
    tdsSection: "194C",
    tdsRatePercent: 2,
    active: true,
    inUse: true,
    ...overrides,
  };
}

export const VENDORS: VendorView[] = [vendor(), vendor({ id: "v-stationers", name: "City Stationers", tdsSection: null, tdsRatePercent: null })];

export function expenseRow(overrides: Partial<ExpenseRow> = {}): ExpenseRow {
  return {
    id: "e-1",
    number: "EXP-2026-27-0001",
    status: "SUBMITTED",
    expenseDate: "2026-10-05",
    headId: "h-electricity",
    headCode: "ELEC",
    headName: "Electricity",
    costCentreId: null,
    costCentreName: null,
    vendorId: "v-sparks",
    vendorName: "Sparks Electricals",
    amountPaise: 10_000_00,
    gstPaise: 1_800_00,
    totalPaise: 11_800_00,
    tdsPaise: 200_00,
    paidPaise: 11_600_00,
    description: "Lab wiring",
    requestedByName: "Meena Reddy",
    budgetWarning: false,
    escalatedAbovePaise: null,
    paidOn: null,
    paymentMode: null,
    attachmentCount: 1,
    submittedAt: "2026-10-05T09:00:00Z",
    ...overrides,
  };
}

export function expenseDetail(overrides: Partial<ExpenseDetail> = {}): ExpenseDetail {
  return {
    ...expenseRow(),
    financialYear: "2026-27",
    tdsSection: "194C",
    tdsRatePercent: 2,
    billNumber: "SE/1042",
    recurringId: null,
    createdAt: "2026-10-05T08:30:00Z",
    approvedByName: null,
    approvedAt: null,
    rejectedByName: null,
    rejectedAt: null,
    rejectReason: null,
    bankAccountId: null,
    bankAccountName: null,
    paymentReference: null,
    paidByName: null,
    paidAt: null,
    clearedOn: null,
    files: [],
    approvals: [],
    budget: { budgetPaise: 0, committedPaise: 0, afterPaise: 11_800_00, percentAfter: 0, status: "NONE", mode: "WARN" },
    myDecision: null,
    canEdit: false,
    version: 1,
    ...overrides,
  };
}

export function monthLine(month: string, overrides: Partial<MonthLine> = {}): MonthLine {
  const line: MonthLine = {
    month,
    feeIncomePaise: 0,
    otherIncomePaise: 0,
    incomePaise: 0,
    expensePaise: 0,
    payrollPaise: 0,
    pettyCashPaise: 0,
    totalExpensePaise: 0,
    netPaise: 0,
    ...overrides,
  };
  line.incomePaise = line.feeIncomePaise + line.otherIncomePaise;
  line.totalExpensePaise = line.expensePaise + line.payrollPaise + line.pettyCashPaise;
  line.netPaise = line.incomePaise - line.totalExpensePaise;
  return line;
}

/** 2026-27 with fee income and a bill in October, payroll in September. */
export function incomeVsExpense(): IncomeVsExpense {
  const months = ["2026-04", "2026-05", "2026-06", "2026-07", "2026-08", "2026-09", "2026-10", "2026-11", "2026-12", "2027-01", "2027-02", "2027-03"].map((m) =>
    m === "2026-09"
      ? monthLine(m, { feeIncomePaise: 4_00_000_00, payrollPaise: 1_50_000_00 })
      : m === "2026-10"
        ? monthLine(m, { feeIncomePaise: 2_50_000_00, otherIncomePaise: 25_000_00, expensePaise: 11_800_00, pettyCashPaise: 1_200_00 })
        : monthLine(m),
  );
  const totals = monthLine("TOTAL", {
    feeIncomePaise: months.reduce((a, m) => a + m.feeIncomePaise, 0),
    otherIncomePaise: months.reduce((a, m) => a + m.otherIncomePaise, 0),
    expensePaise: months.reduce((a, m) => a + m.expensePaise, 0),
    payrollPaise: months.reduce((a, m) => a + m.payrollPaise, 0),
    pettyCashPaise: months.reduce((a, m) => a + m.pettyCashPaise, 0),
  });
  return { financialYear: "2026-27", months, totals };
}
