import { screen, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { PayrollRegister, RegisterRow, RegisterTotals } from "@/lib/types";
import { PAYROLL_READER } from "@/test/payroll-fixtures";
import { renderAs } from "@/test/render";
import { PayrollRegisterReport } from "./payroll-reports";

const api = vi.hoisted(() => ({ register: vi.fn(), departments: vi.fn() }));

vi.mock("@/lib/payroll-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/payroll-api")>();
  return {
    ...actual,
    payrollApi: { ...actual.payrollApi, register: api.register, departments: api.departments },
  };
});

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  usePathname: () => "/app/reports/payroll-register",
}));

function row(overrides: Partial<RegisterRow>): RegisterRow {
  return {
    userId: "u-ravi",
    name: "Ravi Kumar",
    employeeCode: "T-1",
    designation: "Teacher",
    payableDays: 30,
    lopDays: 0,
    grossPaise: 50_000_00,
    pfEmployeePaise: 1_800_00,
    esiEmployeePaise: 0,
    ptPaise: 200_00,
    tdsPaise: 0,
    otherDeductionsPaise: 0,
    deductionsPaise: 2_000_00,
    netPaise: 48_000_00,
    employerPaise: 1_950_00,
    ...overrides,
  };
}

const totals = (overrides: Partial<RegisterTotals> = {}): RegisterTotals => ({
  count: 1,
  grossPaise: 50_000_00,
  pfEmployeePaise: 1_800_00,
  esiEmployeePaise: 0,
  ptPaise: 200_00,
  tdsPaise: 0,
  otherDeductionsPaise: 0,
  deductionsPaise: 2_000_00,
  netPaise: 48_000_00,
  employerPaise: 1_950_00,
  ...overrides,
});

beforeEach(() => {
  api.departments.mockResolvedValue([{ id: "d-primary", name: "Primary" }]);
});

describe("PayrollRegisterReport", () => {
  it("opens on the latest month with a run, shows it in the filter and lists staff by department", async () => {
    const register: PayrollRegister = {
      month: "2026-09",
      runId: "run-9",
      status: "APPROVED",
      departmentId: null,
      departments: [
        { departmentId: "d-primary", departmentName: "Primary", rows: [row({})], totals: totals() },
        {
          departmentId: null,
          departmentName: null,
          rows: [row({ userId: "u-farah", name: "Farah Khan", employeeCode: "A-1", designation: "Accountant", netPaise: 22_000_00 })],
          totals: totals({ netPaise: 22_000_00 }),
        },
      ],
      totals: totals({ count: 2, netPaise: 70_000_00 }),
    };
    api.register.mockResolvedValue(register);
    renderAs(PAYROLL_READER, <PayrollRegisterReport />);
    const report = await screen.findByTestId("report-payroll-register");
    expect(await within(report).findByTestId("register-net")).toHaveTextContent("₹70,000");
    expect(api.register).toHaveBeenCalledWith({ month: undefined, departmentId: undefined });
    expect(within(report).getByLabelText("Month")).toHaveValue("2026-09");
    const table = within(report).getByTestId("register-table");
    expect(table).toHaveTextContent("Primary");
    expect(table).toHaveTextContent("Ravi Kumar");
    expect(table).toHaveTextContent("Farah Khan");
    expect(screen.getByRole("button", { name: "Download Excel" })).toBeEnabled();
  });

  it("says when the month has no payroll", async () => {
    api.register.mockResolvedValue({
      month: "2026-10",
      runId: null,
      status: null,
      departmentId: null,
      departments: [],
      totals: totals({ count: 0, grossPaise: 0, netPaise: 0, employerPaise: 0, deductionsPaise: 0 }),
    } satisfies PayrollRegister);
    renderAs(PAYROLL_READER, <PayrollRegisterReport />);
    expect(await screen.findByText("There is no payroll for October 2026.")).toBeInTheDocument();
    expect(screen.queryByTestId("register-table")).not.toBeInTheDocument();
  });
});
