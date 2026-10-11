import type { PayslipRow, PayslipView, RunDetail, RunTotals } from "@/lib/types";

/** Test-only payroll data: September 2026 at Sunrise Public School, two teachers. */

export const PAYROLL_ADMIN = ["dashboard.view", "payroll.read", "payroll.manage", "payroll.approve", "leave.request"];
export const PAYROLL_ACCOUNTANT = ["dashboard.view", "fees.read", "payroll.read", "payroll.manage", "leave.request"];
export const PAYROLL_READER = ["dashboard.view", "payroll.read", "leave.request"];
export const TEACHER_STAFF = ["dashboard.view", "students.read", "leave.request"];

export function totals(overrides: Partial<RunTotals> = {}): RunTotals {
  return {
    staffCount: 2,
    grossPaise: 85_000_00,
    deductionsPaise: 6_225_00,
    netPaise: 78_775_00,
    employerPaise: 4_545_00,
    pfEmployeePaise: 3_600_00,
    pfEmployerPaise: 3_600_00,
    pfAdminPaise: 500_00,
    edliPaise: 150_00,
    esiEmployeePaise: 0,
    esiEmployerPaise: 0,
    ptPaise: 400_00,
    tdsPaise: 1_225_00,
    ...overrides,
  };
}

export function slipRow(overrides: Partial<PayslipRow> = {}): PayslipRow {
  return {
    id: "ps-ravi",
    userId: "u-ravi",
    name: "Ravi Kumar",
    employeeCode: "T-1",
    departmentName: "Primary",
    payableDays: 28.5,
    lopDays: 1.5,
    grossPaise: 47_500_00,
    pfEmployeePaise: 1_800_00,
    esiEmployeePaise: 0,
    ptPaise: 200_00,
    tdsPaise: 1_225_00,
    tdsOverridden: false,
    tdsOverrideReason: null,
    tdsCalculatedPaise: 1_225_00,
    otherDeductionsPaise: 1_000_00,
    deductionsPaise: 4_225_00,
    netPaise: 43_275_00,
    employerPaise: 2_400_00,
    hasBank: true,
    ...overrides,
  };
}

export function run(overrides: Partial<RunDetail> = {}): RunDetail {
  return {
    id: "run-9",
    month: "2026-09",
    status: "DRAFT",
    prorationBasis: "CALENDAR_DAYS",
    daysInMonth: 30,
    workingDays: 26,
    totals: totals(),
    calculatedAt: "2026-10-01T04:30:00Z",
    createdByName: "Meena Reddy",
    approvedByName: null,
    approvedAt: null,
    paidOn: null,
    paymentReference: null,
    paidByName: null,
    taxFinancialYear: "2026-27",
    payslips: [
      slipRow(),
      slipRow({
        id: "ps-sita",
        userId: "u-sita",
        name: "Sita Devi",
        employeeCode: "T-2",
        payableDays: 30,
        lopDays: 0,
        grossPaise: 37_500_00,
        pfEmployeePaise: 1_800_00,
        ptPaise: 200_00,
        tdsPaise: 0,
        tdsCalculatedPaise: 0,
        otherDeductionsPaise: 0,
        deductionsPaise: 2_000_00,
        netPaise: 35_500_00,
        hasBank: false,
      }),
    ],
    adjustments: [],
    warnings: [],
    version: 3,
    ...overrides,
  };
}

export function payslip(overrides: Partial<PayslipView> = {}): PayslipView {
  return {
    id: "ps-ravi",
    runId: "run-9",
    month: "2026-09",
    status: "PAID",
    paidOn: "2026-10-01",
    school: {
      name: "Sunrise Public School",
      address: "12 MG Road",
      city: "Vijayawada",
      phone: "9876500001",
      email: "office@sunrise.test",
      pfEstablishmentCode: "APHYD0012345000",
      esiEmployerCode: null,
      tan: "HYDA12345B",
    },
    person: {
      userId: "u-ravi",
      name: "Ravi Kumar",
      employeeCode: "T-1",
      designation: "Teacher",
      departmentName: "Primary",
      dateOfJoining: "2024-06-01",
      dateOfLeaving: null,
      panMasked: "XXXXXX234F",
      bankLast4: "9012",
      bankIfsc: "SBIN0001234",
      uan: "100200300400",
      esiNumber: null,
    },
    prorationBasis: "CALENDAR_DAYS",
    basisDays: 30,
    daysOnRoll: 30,
    lopDays: 1.5,
    payableDays: 28.5,
    earnings: [
      { code: "BASIC", name: "Basic", fullPaise: 30_000_00, amountPaise: 28_500_00 },
      { code: "HRA", name: "House rent allowance", fullPaise: 20_000_00, amountPaise: 19_000_00 },
    ],
    deductions: [
      { code: "PF", name: "Provident fund", fullPaise: null, amountPaise: 1_800_00 },
      { code: "PT", name: "Professional tax", fullPaise: null, amountPaise: 200_00 },
      { code: "TDS", name: "Income tax (TDS)", fullPaise: null, amountPaise: 1_225_00 },
      { code: "LOAN", name: "Loan recovery", fullPaise: 1_000_00, amountPaise: 1_000_00 },
    ],
    employer: [
      { code: "EPF_ER", name: "Employer PF (EPF)", fullPaise: null, amountPaise: 550_00 },
      { code: "EPS", name: "Pension (EPS)", fullPaise: null, amountPaise: 1_250_00 },
    ],
    grossPaise: 47_500_00,
    deductionsPaise: 4_225_00,
    netPaise: 43_275_00,
    employerPaise: 1_800_00,
    netInWords: "Rupees Forty Three Thousand Two Hundred Seventy Five Only",
    taxableYearProjectedPaise: 5_40_000_00,
    taxYearPaise: 14_700_00,
    ...overrides,
  };
}
