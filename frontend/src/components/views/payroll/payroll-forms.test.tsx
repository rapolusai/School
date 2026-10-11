import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { PayStaffDetail, PayStaffRow, StructureView } from "@/lib/types";
import { PAYROLL_ACCOUNTANT, PAYROLL_READER } from "@/test/payroll-fixtures";
import { renderAs } from "@/test/render";
import { validateComponent } from "./components-view";
import { PayStaffDetailView, suggestedStructureMonth, validateStructure } from "./pay-staff-detail-view";
import { filterPayStaff } from "./pay-staff-view";
import { parseMonths, validatePtSlabs, validateSettings, type SettingsValues } from "./settings-view";

const api = vi.hoisted(() => ({ person: vi.fn(), fullProfile: vi.fn() }));

vi.mock("@/lib/payroll-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/payroll-api")>();
  return { ...actual, payrollApi: { ...actual.payrollApi, person: api.person, fullProfile: api.fullProfile } };
});

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  usePathname: () => "/app/payroll/staff/u-ravi",
}));

function structure(overrides: Partial<StructureView> = {}): StructureView {
  return {
    id: "s1",
    effectiveFrom: "2026-04",
    note: null,
    createdByName: "Meena Reddy",
    createdAt: "2026-04-01T04:30:00Z",
    lines: [
      { componentId: "c-basic", code: "BASIC", name: "Basic", kind: "EARNING", monthlyPaise: 30_000_00 },
      { componentId: "c-hra", code: "HRA", name: "House rent allowance", kind: "EARNING", monthlyPaise: 20_000_00 },
      { componentId: "c-loan", code: "LOAN", name: "Loan recovery", kind: "DEDUCTION", monthlyPaise: 1_000_00 },
    ],
    grossPaise: 50_000_00,
    fixedDeductionsPaise: 1_000_00,
    employerPaise: 2_400_00,
    ctcPaise: 52_400_00,
    current: true,
    locked: true,
    ...overrides,
  };
}

function person(overrides: Partial<PayStaffDetail> = {}): PayStaffDetail {
  return {
    userId: "u-ravi",
    name: "Ravi Kumar",
    email: "ravi@school.test",
    employeeCode: "T-1",
    designation: "Teacher",
    departmentName: "Primary",
    active: true,
    profileComplete: true,
    dateOfJoining: "2024-06-01",
    dateOfLeaving: null,
    payProfile: {
      saved: true,
      bankAccountMasked: "XXXXXXXX9012",
      bankLast4: "9012",
      bankIfsc: "SBIN0001234",
      accountHolderName: null,
      panMasked: "XXXXXX234F",
      uan: "100200300400",
      esiNumber: null,
      pfMember: true,
      ptWomenSlab: false,
      updatedAt: "2026-04-01T04:30:00Z",
    },
    structures: [structure()],
    payslips: [{ id: "ps-8", month: "2026-08", status: "PAID", grossPaise: 50_000_00, netPaise: 46_000_00 }],
    ...overrides,
  };
}

beforeEach(() => {
  api.person.mockResolvedValue(person());
  api.fullProfile.mockResolvedValue({
    bankAccountNumber: "000000129012",
    bankIfsc: "SBIN0001234",
    accountHolderName: null,
    pan: "ABCDE1234F",
    uan: "100200300400",
    esiNumber: null,
    pfMember: true,
    ptWomenSlab: false,
  });
});

describe("PayStaffDetailView", () => {
  it("shows the account and PAN masked, the structure with gross and CTC, and the payslips", async () => {
    renderAs(PAYROLL_READER, <PayStaffDetailView userId="u-ravi" />);
    expect(await screen.findByRole("heading", { level: 1, name: "Ravi Kumar" })).toBeInTheDocument();
    expect(screen.getByTestId("pay-account")).toHaveTextContent("XXXXXXXX9012");
    expect(screen.getByTestId("pay-pan")).toHaveTextContent("XXXXXX234F");
    expect(screen.getByTestId("structure-lines")).toHaveTextContent("Basic₹30,000");
    expect(screen.getByTestId("structure-ctc")).toHaveTextContent("₹52,400₹6,28,800 a year");
    expect(screen.getByTestId("structure-history")).toHaveTextContent("Used in an approved month");
    expect(screen.getByRole("link", { name: /August 2026/ })).toHaveAttribute("href", "/app/payroll/payslips/ps-8");
    // A reader cannot see the full values or change anything.
    expect(screen.queryByRole("button", { name: "Show in full" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Revise salary" })).not.toBeInTheDocument();
    expect(api.fullProfile).not.toHaveBeenCalled();
  });

  it("lets a payroll manager see the full values on request, saying it is recorded", async () => {
    const user = userEvent.setup();
    renderAs(PAYROLL_ACCOUNTANT, <PayStaffDetailView userId="u-ravi" />);
    await user.click(await screen.findByRole("button", { name: "Show in full" }));
    expect(api.fullProfile).toHaveBeenCalledWith("u-ravi");
    expect(await screen.findByText("000000129012")).toBeInTheDocument();
    expect(screen.getByTestId("pay-pan")).toHaveTextContent("ABCDE1234F");
    expect(screen.getByText("Viewing the full details is recorded in the audit trail.")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Hide" }));
    expect(screen.getByTestId("pay-account")).toHaveTextContent("XXXXXXXX9012");
    // A structure an approved month used cannot be deleted.
    expect(within(screen.getByTestId("structure-history")).queryByRole("button")).not.toBeInTheDocument();
  });
});

describe("form checks mirror the API", () => {
  it("needs at least one earning and positive amounts in a structure", () => {
    const components = [
      { id: "c-basic", kind: "EARNING" as const },
      { id: "c-loan", kind: "DEDUCTION" as const },
    ];
    expect(validateStructure({ effectiveFrom: "2026-10", note: "", amounts: { "c-basic": "30000" } }, components)).toEqual({});
    expect(validateStructure({ effectiveFrom: "2026-10", note: "", amounts: { "c-loan": "500" } }, components)).toEqual({
      "amount-c-basic": "payroll.v.needEarning",
    });
    expect(validateStructure({ effectiveFrom: "10/2026", note: "", amounts: { "c-basic": "0" } }, components)).toEqual({
      effectiveFrom: "payroll.v.month",
      "amount-c-basic": "payroll.v.amount",
    });
  });

  it("starts a revision this month, or after the latest structure", () => {
    expect(suggestedStructureMonth([], "2026-10-11")).toBe("2026-10");
    expect(suggestedStructureMonth([structure({ effectiveFrom: "2026-04" })], "2026-10-11")).toBe("2026-10");
    expect(suggestedStructureMonth([structure({ effectiveFrom: "2026-12" })], "2026-10-11")).toBe("2027-01");
  });

  it("checks the settings", () => {
    const ok: SettingsValues = {
      payDay: "1",
      prorationBasis: "CALENDAR_DAYS",
      pfEnabled: true,
      pfOnFullWages: false,
      esiEnabled: true,
      ptEnabled: true,
      ptState: "TG",
      pfEstablishmentCode: "APHYD0012345000",
      esiEmployerCode: "",
      ptRegistration: "",
      tan: "HYDA12345B",
      bankName: "",
      bankBranch: "",
      bankAccountNumber: "",
      bankIfsc: "",
    };
    expect(validateSettings(ok)).toEqual({});
    expect(
      validateSettings({ ...ok, payDay: "32", ptState: "", tan: "HYD", pfEstablishmentCode: "AP#1", bankAccountNumber: "000000123456" }),
    ).toEqual({
      payDay: "payroll.v.payDay",
      ptState: "payroll.v.ptState",
      tan: "payroll.v.tan",
      pfEstablishmentCode: "payroll.v.registration",
      bankIfsc: "payroll.v.together",
    });
  });

  it("checks professional tax slabs, months included", () => {
    expect(parseMonths("")).toEqual([]);
    expect(parseMonths("3, 2 2")).toEqual([2, 3]);
    expect(parseMonths("13")).toBeNull();
    const row = { appliesTo: "ALL" as const, from: "10000", to: "14999.99", amount: "150", months: "" };
    expect(validatePtSlabs("2026-04-01", [row])).toEqual({});
    expect(validatePtSlabs("2026-04-01", [{ ...row, to: "5000", amount: "20000", months: "Feb" }])).toEqual({
      "to-0": "payroll.v.slabOrder",
      "amount-0": "payroll.v.ptAmount",
      "months-0": "payroll.v.months",
    });
    expect(validatePtSlabs("2026-04-01", [])).toEqual({ effectiveFrom: "payroll.v.slabs" });
  });

  it("keeps payroll's own codes away from components", () => {
    const values = {
      code: "transport",
      kind: "EARNING" as const,
      name: "Transport",
      pfWage: false,
      esiWage: true,
      taxable: true,
      prorated: true,
      displayOrder: "60",
      active: true,
    };
    expect(validateComponent(values, true)).toEqual({});
    expect(validateComponent({ ...values, code: "pf" }, true)).toEqual({ code: "payroll.v.reservedCode" });
    expect(validateComponent({ ...values, code: "9X", name: "", displayOrder: "1000" }, true)).toEqual({
      code: "payroll.v.code",
      name: "validation.required",
      displayOrder: "payroll.v.order",
    });
    // The code of an existing component is not checked again.
    expect(validateComponent({ ...values, code: "PF" }, false)).toEqual({});
  });

  it("filters the staff list", () => {
    const row = (overrides: Partial<PayStaffRow>): PayStaffRow => ({
      userId: "u",
      name: "Ravi Kumar",
      employeeCode: "T-1",
      designation: "Teacher",
      departmentId: null,
      departmentName: "Primary",
      active: true,
      profileComplete: true,
      dateOfJoining: null,
      dateOfLeaving: null,
      structureFrom: "2026-04-01",
      grossPaise: 1,
      ctcPaise: 1,
      hasBank: true,
      bankLast4: "9012",
      panMasked: null,
      hasPan: false,
      hasUan: false,
      hasEsiNumber: false,
      ...overrides,
    });
    const rows = [
      row({ userId: "a" }),
      row({ userId: "b", name: "Sita Devi", structureFrom: null, hasBank: false }),
      row({ userId: "c", name: "Old Hand", active: false, structureFrom: null, hasBank: false }),
    ];
    expect(filterPayStaff(rows, "all", "").map((r) => r.userId)).toEqual(["a", "b", "c"]);
    expect(filterPayStaff(rows, "structure", "").map((r) => r.userId)).toEqual(["b"]);
    expect(filterPayStaff(rows, "bank", "").map((r) => r.userId)).toEqual(["b"]);
    expect(filterPayStaff(rows, "all", "primary").map((r) => r.userId)).toEqual(["a", "b", "c"]);
    expect(filterPayStaff(rows, "all", "sita").map((r) => r.userId)).toEqual(["b"]);
  });
});
