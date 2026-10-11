import { describe, expect, it } from "vitest";
import { run } from "@/test/payroll-fixtures";
import { en } from "./i18n/en";
import { hi } from "./i18n/hi";
import { parseRupees } from "./format";
import {
  financialYearOf,
  financialYearsUpTo,
  formatMonth,
  formatPayDays,
  groupWarnings,
  parseLopDays,
  previousMonth,
  runActions,
  structureTotals,
  validateAdjustment,
  validateProfile,
  warningKey,
  type ProfileValues,
} from "./payroll";
import { NAV_ITEMS, navFor, navItemForPath, PERMISSIONS, REPORT_PERMISSIONS } from "./permissions";
import { RUN_WARNING_CODES, type Me } from "./types";

const subject = (permissions: string[]) => ({ permissions, platformAdmin: false });

describe("months and financial years", () => {
  it("names a month in either language", () => {
    expect(formatMonth("2026-09")).toBe("September 2026");
    expect(formatMonth("2027-01", "hi-IN")).toContain("2027");
    expect(formatMonth("2026-13")).toBe("2026-13");
    expect(formatMonth(null)).toBe("");
  });

  it("puts April to March in one financial year", () => {
    expect(financialYearOf("2026-04")).toBe("2026-27");
    expect(financialYearOf("2027-03")).toBe("2026-27");
    expect(financialYearOf("2099-12")).toBe("2099-00");
    expect(financialYearsUpTo("2026-02", 3)).toEqual(["2025-26", "2024-25", "2023-24"]);
    expect(previousMonth("2026-01")).toBe("2025-12");
    expect(previousMonth("2026-10")).toBe("2026-09");
  });

  it("shows whole and half days", () => {
    expect(formatPayDays(28)).toBe("28");
    expect(formatPayDays(28.5)).toBe("28.5");
  });
});

describe("runActions", () => {
  const admin = subject(["payroll.read", "payroll.manage", "payroll.approve"]);
  const accountant = subject(["payroll.read", "payroll.manage"]);
  const reader = subject(["payroll.read"]);

  it("lets a manager change a draft and an approver approve it", () => {
    expect(runActions(run(), accountant)).toEqual({
      recalculate: true,
      adjust: true,
      remove: true,
      approve: false,
      markPaid: false,
      files: false,
    });
    expect(runActions(run(), admin).approve).toBe(true);
    expect(runActions(run(), reader)).toEqual({
      recalculate: false,
      adjust: false,
      remove: false,
      approve: false,
      markPaid: false,
      files: false,
    });
  });

  it("locks an approved month: only paying it and the files remain", () => {
    expect(runActions(run({ status: "APPROVED" }), admin)).toEqual({
      recalculate: false,
      adjust: false,
      remove: false,
      approve: false,
      markPaid: true,
      files: true,
    });
    expect(runActions(run({ status: "PAID" }), accountant)).toMatchObject({ markPaid: false, files: true });
    expect(runActions(run({ status: "PAID" }), reader).files).toBe(false);
  });

  it("does not offer approval when nobody is in the month", () => {
    const empty = run({ payslips: [], totals: { ...run().totals, staffCount: 0 } });
    expect(runActions(empty, admin).approve).toBe(false);
  });
});

describe("warnings", () => {
  it("groups the per-person warnings by code, with a message for every code in both languages", () => {
    const groups = groupWarnings([
      { code: "NO_BANK", userId: "a", name: "Ravi Kumar", detail: null },
      { code: "TAX_RULES_EARLIER_YEAR", userId: null, name: null, detail: "2025-26" },
      { code: "NO_BANK", userId: "b", name: "Sita Devi", detail: null },
    ]);
    expect(groups).toEqual([
      { code: "NO_BANK", names: ["Ravi Kumar", "Sita Devi"], detail: null },
      { code: "TAX_RULES_EARLIER_YEAR", names: [], detail: "2025-26" },
    ]);
    for (const code of RUN_WARNING_CODES) {
      expect(en[warningKey(code)], code).toBeTruthy();
      expect(hi[warningKey(code)], code).toBeTruthy();
    }
  });
});

describe("adjustments", () => {
  it("accepts whole or half loss-of-pay days, positive or negative", () => {
    expect(parseLopDays("1")).toBe(1);
    expect(parseLopDays("-0.5")).toBe(-0.5);
    expect(parseLopDays(" 2.5 ")).toBe(2.5);
    expect(parseLopDays("0")).toBeNull();
    expect(parseLopDays("1.25")).toBeNull();
    expect(parseLopDays("0.3")).toBeNull();
    expect(parseLopDays("32")).toBeNull();
    expect(parseLopDays("x")).toBeNull();
  });

  it("checks what the API checks", () => {
    const base = { userId: "u1", kind: "BONUS" as const, amount: "5,000", days: "", reason: "Diwali bonus" };
    expect(validateAdjustment(base, parseRupees)).toEqual({});
    expect(validateAdjustment({ ...base, userId: "", amount: "0", reason: " " }, parseRupees)).toEqual({
      userId: "validation.choose",
      amount: "payroll.v.amount",
      reason: "validation.required",
    });
    expect(validateAdjustment({ ...base, kind: "LOP_CORRECTION", amount: "", days: "-1" }, parseRupees)).toEqual({});
    expect(validateAdjustment({ ...base, kind: "LOP_CORRECTION", days: "0.2" }, parseRupees)).toEqual({
      days: "payroll.v.days",
    });
    expect(validateAdjustment({ ...base, reason: "x".repeat(501) }, parseRupees)).toEqual({
      reason: "validation.tooLong",
    });
  });
});

describe("pay details", () => {
  const blank: ProfileValues = {
    bankAccountNumber: "",
    bankIfsc: "",
    accountHolderName: "",
    pan: "",
    uan: "",
    esiNumber: "",
    pfMember: true,
    ptWomenSlab: false,
  };

  it("allows nothing at all, and checks each shape given", () => {
    expect(validateProfile(blank)).toEqual({});
    expect(
      validateProfile({ ...blank, bankAccountNumber: "000000123456", bankIfsc: "SBIN0001234", pan: "ABCDE1234F" }),
    ).toEqual({});
    expect(
      validateProfile({ ...blank, bankAccountNumber: "12-34", bankIfsc: "SBIN1234", pan: "1234", uan: "12", esiNumber: "x" }),
    ).toEqual({
      bankAccountNumber: "payroll.v.account",
      bankIfsc: "payroll.v.ifsc",
      pan: "payroll.v.pan",
      uan: "payroll.v.uan",
      esiNumber: "payroll.v.esi",
    });
  });

  it("wants the account number and IFSC together", () => {
    expect(validateProfile({ ...blank, bankAccountNumber: "000000123456" })).toEqual({ bankIfsc: "payroll.v.together" });
    expect(validateProfile({ ...blank, bankIfsc: "SBIN0001234" })).toEqual({ bankAccountNumber: "payroll.v.together" });
  });
});

describe("structureTotals", () => {
  it("adds earnings and fixed deductions as typed, flagging bad amounts", () => {
    expect(
      structureTotals(
        [
          { kind: "EARNING", amount: "30,000" },
          { kind: "EARNING", amount: "20000.50" },
          { kind: "DEDUCTION", amount: "1000" },
          { kind: "EARNING", amount: "" },
        ],
        parseRupees,
      ),
    ).toEqual({ grossPaise: 50_000_50, deductionsPaise: 1_000_00, invalid: false });
    expect(structureTotals([{ kind: "EARNING", amount: "abc" }], parseRupees).invalid).toBe(true);
  });
});

describe("payroll in the navigation", () => {
  const user = (roles: string[], permissions: string[]): Me => ({
    id: "u1",
    name: "Test",
    email: "t@example.com",
    roles,
    permissions,
    platformAdmin: false,
    tenant: null,
  });

  it("defines the permission codes the API uses", () => {
    expect(PERMISSIONS.payrollRead).toBe("payroll.read");
    expect(PERMISSIONS.payrollManage).toBe("payroll.manage");
    expect(PERMISSIONS.payrollApprove).toBe("payroll.approve");
    expect(REPORT_PERMISSIONS).toContain("payroll.read");
  });

  it("puts Payroll under Finance for payroll readers and My payslips under Staff for every staff member", () => {
    const accountant = user(["ACCOUNTANT"], ["dashboard.view", "fees.read", "payroll.read", "payroll.manage", "leave.request"]);
    expect(navFor(accountant).map((i) => i.key)).toEqual(["dashboard", "fees", "payroll", "leave", "myPayslips", "reports"]);
    expect(NAV_ITEMS.find((i) => i.key === "payroll")).toMatchObject({
      href: "/app/payroll",
      group: "finance",
      permission: "payroll.read",
    });
    expect(NAV_ITEMS.find((i) => i.key === "myPayslips")).toMatchObject({ href: "/app/my-payslips", group: "staff" });
    const teacher = user(["TEACHER"], ["dashboard.view", "attendance.read", "leave.request"]);
    expect(navFor(teacher).map((i) => i.key)).toContain("myPayslips");
    expect(navFor(teacher).map((i) => i.key)).not.toContain("payroll");
  });

  it("keeps payroll away from parents and students", () => {
    for (const roles of [["PARENT"], ["STUDENT"]]) {
      const keys = navFor(user(roles, ["dashboard.view", "child.view", "notices.read"])).map((i) => i.key);
      expect(keys).not.toContain("myPayslips");
      expect(keys).not.toContain("payroll");
    }
  });

  it("owns the payroll pages", () => {
    expect(navItemForPath("/app/payroll/runs/r1")?.key).toBe("payroll");
    expect(navItemForPath("/app/payroll/staff/u1")?.key).toBe("payroll");
    expect(navItemForPath("/app/my-payslips/p1")?.key).toBe("myPayslips");
  });

  it("has labels for the menu, permissions and audit trail in both languages", () => {
    const keys = [
      "nav.payroll",
      "nav.myPayslips.short",
      "perm.payroll.read",
      "perm.payroll.manage",
      "perm.payroll.approve",
      "audit.action.payroll_run.approved",
      "audit.action.payroll_pay_profile.viewed",
      "audit.action.payroll.exported",
      "audit.action.payroll_tds.overridden",
      "audit.entity.payroll_run",
      "audit.entity.staff_pay_profile",
      "audit.entity.payroll_report",
    ] as const;
    for (const key of keys) {
      expect(en[key], key).toBeTruthy();
      expect(hi[key], key).toBeTruthy();
    }
  });
});
