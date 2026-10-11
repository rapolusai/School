import { screen, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api";
import { PAYROLL_ADMIN, payslip, TEACHER_STAFF } from "@/test/payroll-fixtures";
import { renderAs } from "@/test/render";
import { BankAdviceView } from "./bank-advice-view";
import {
  bankLine,
  MyPayslipsView,
  MyPayslipView,
  PayslipDocument,
  RunPayslipsView,
} from "./payslip-document";

const api = vi.hoisted(() => ({
  myPayslips: vi.fn(),
  myPayslip: vi.fn(),
  runPayslips: vi.fn(),
  bankAdvice: vi.fn(),
}));

vi.mock("@/lib/payroll-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/payroll-api")>();
  return {
    ...actual,
    payrollApi: {
      ...actual.payrollApi,
      myPayslips: api.myPayslips,
      myPayslip: api.myPayslip,
      runPayslips: api.runPayslips,
      bankAdvice: api.bankAdvice,
    },
  };
});

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  usePathname: () => "/app/my-payslips",
}));

beforeEach(() => {
  api.myPayslip.mockResolvedValue(payslip());
});

describe("PayslipDocument", () => {
  it("prints the school, the month, the person with PAN masked and the bank by its last four digits", () => {
    renderAs(TEACHER_STAFF, <PayslipDocument payslip={payslip()} />);
    const doc = screen.getByRole("article", { name: "Payslip of Ravi Kumar for September 2026" });
    expect(doc).toHaveClass("print-area");
    expect(within(doc).getByText("Sunrise Public School")).toBeInTheDocument();
    expect(within(doc).getByText("12 MG Road, Vijayawada")).toBeInTheDocument();
    expect(within(doc).getByTestId("payslip-month")).toHaveTextContent("September 2026");
    expect(within(doc).getByTestId("payslip-name")).toHaveTextContent("Ravi Kumar");
    expect(within(doc).getByText("Teacher · Primary")).toBeInTheDocument();
    expect(within(doc).getByText("1 Jun 2024")).toBeInTheDocument();
    expect(within(doc).getByTestId("payslip-pan")).toHaveTextContent("XXXXXX234F");
    expect(within(doc).getByTestId("payslip-bank")).toHaveTextContent("XXXX 9012 · SBIN0001234");
    expect(doc).not.toHaveTextContent("000000129012");
    expect(within(doc).getByText("100200300400")).toBeInTheDocument();
    expect(within(doc).getByText("Paid")).toBeInTheDocument();
    expect(within(doc).getByText(/PF code APHYD0012345000 · TAN HYDA12345B/)).toBeInTheDocument();
  });

  it("shows the days, loss of pay, every line and the net pay in figures and words", () => {
    renderAs(TEACHER_STAFF, <PayslipDocument payslip={payslip()} />);
    expect(screen.getByTestId("payslip-days")).toHaveTextContent("28.5 of 30");
    expect(screen.getByTestId("payslip-lop")).toHaveTextContent("1.5");
    const earnings = screen.getByTestId("payslip-earnings");
    expect(within(earnings).getByRole("row", { name: /Basic ₹30,000 ₹28,500/ })).toBeInTheDocument();
    expect(within(earnings).getByRole("row", { name: /Gross pay ₹47,500/ })).toBeInTheDocument();
    const deductions = screen.getByTestId("payslip-deductions");
    expect(within(deductions).getByRole("row", { name: /Provident fund ₹1,800/ })).toBeInTheDocument();
    expect(within(deductions).getByRole("row", { name: /Income tax \(TDS\) ₹1,225/ })).toBeInTheDocument();
    expect(within(deductions).getByRole("row", { name: /Total deductions ₹4,225/ })).toBeInTheDocument();
    expect(screen.getByTestId("payslip-net")).toHaveTextContent("Net pay₹43,275");
    expect(screen.getByTestId("payslip-words")).toHaveTextContent(
      "In words: Rupees Forty Three Thousand Two Hundred Seventy Five Only",
    );
    expect(screen.getByTestId("payslip-employer")).toHaveTextContent("Pension (EPS)₹1,250");
    expect(screen.getByText(/projected taxable income for the year ₹5,40,000, tax for the year ₹14,700/)).toBeInTheDocument();
  });

  it("marks a draft and says when nothing is recorded", () => {
    renderAs(
      PAYROLL_ADMIN,
      <PayslipDocument
        payslip={payslip({
          status: "DRAFT",
          paidOn: null,
          person: { ...payslip().person, panMasked: null, bankLast4: null, bankIfsc: null, uan: null },
          employer: [],
        })}
      />,
    );
    const doc = screen.getByTestId("payslip");
    expect(doc).toHaveAttribute("data-stamp", "Draft");
    expect(within(doc).getByTestId("payslip-pan")).toHaveTextContent("Not given");
    expect(within(doc).getByTestId("payslip-bank")).toHaveTextContent("Not given");
    expect(within(doc).getByText("Not paid yet")).toBeInTheDocument();
    expect(within(doc).queryByTestId("payslip-employer")).not.toBeInTheDocument();
  });

  it("shows only the last four digits of an account", () => {
    expect(bankLine("9012", "SBIN0001234")).toBe("XXXX 9012 · SBIN0001234");
    expect(bankLine("9012", null)).toBe("XXXX 9012");
    expect(bankLine(null, "SBIN0001234")).toBeNull();
  });
});

describe("My payslips", () => {
  it("lists the teacher's own payslips, newest first, each linking to its page", async () => {
    api.myPayslips.mockResolvedValue([
      { id: "ps-9", month: "2026-09", status: "APPROVED", paidOn: null, grossPaise: 47_500_00, deductionsPaise: 4_225_00, netPaise: 43_275_00 },
      { id: "ps-8", month: "2026-08", status: "PAID", paidOn: "2026-09-01", grossPaise: 50_000_00, deductionsPaise: 4_000_00, netPaise: 46_000_00 },
    ]);
    renderAs(TEACHER_STAFF, <MyPayslipsView />, ["TEACHER"]);
    expect(await screen.findByRole("heading", { level: 1, name: "My payslips" })).toBeInTheDocument();
    const links = await screen.findAllByTestId("my-payslip-link");
    expect(links.map((l) => l.getAttribute("href"))).toEqual(["/app/my-payslips/ps-9", "/app/my-payslips/ps-8"]);
    expect(links[0]).toHaveTextContent("September 2026");
    expect(links[0]).toHaveTextContent("Approved, payment pending");
    expect(links[0]).toHaveTextContent("₹43,275");
    expect(links[1]).toHaveTextContent("Paid on 1 Sept 2026");
  });

  it("says when there is nothing yet", async () => {
    api.myPayslips.mockResolvedValue([]);
    renderAs(TEACHER_STAFF, <MyPayslipsView />, ["TEACHER"]);
    expect(await screen.findByTestId("my-payslips-empty")).toHaveTextContent("No payslips yet.");
  });

  it("turns away a parent", async () => {
    api.myPayslips.mockRejectedValue(new ApiError({ status: 403, title: "Forbidden" }));
    renderAs(["dashboard.view", "child.view"], <MyPayslipsView />, ["PARENT"]);
    expect(await screen.findByTestId("access-denied")).toBeInTheDocument();
  });

  it("opens one payslip with Print, and says when it is not the reader's", async () => {
    renderAs(TEACHER_STAFF, <MyPayslipView id="ps-ravi" />, ["TEACHER"]);
    expect(await screen.findByRole("heading", { level: 1, name: "Payslip for September 2026" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Print" })).toBeInTheDocument();
    expect(screen.getByTestId("payslip")).toBeInTheDocument();
    expect(api.myPayslip).toHaveBeenCalledWith("ps-ravi");
  });

  it("shows not found for someone else's payslip", async () => {
    api.myPayslip.mockRejectedValue(new ApiError({ status: 404, title: "Not found" }));
    renderAs(TEACHER_STAFF, <MyPayslipView id="ps-other" />, ["TEACHER"]);
    expect(await screen.findByTestId("payslip-not-found")).toBeInTheDocument();
    expect(screen.queryByTestId("payslip")).not.toBeInTheDocument();
  });
});

describe("Printing a month's payslips", () => {
  it("puts every payslip in one printable batch", async () => {
    api.runPayslips.mockResolvedValue([
      payslip(),
      payslip({ id: "ps-sita", person: { ...payslip().person, userId: "u-sita", name: "Sita Devi" } }),
    ]);
    renderAs(PAYROLL_ADMIN, <RunPayslipsView runId="run-9" />);
    const batch = await screen.findByTestId("payslip-batch");
    expect(batch).toHaveClass("print-area");
    const docs = within(batch).getAllByTestId("payslip");
    expect(docs).toHaveLength(2);
    // The batch is the printed area; the payslips inside it are not separate print areas.
    expect(docs[0]).not.toHaveClass("print-area");
    expect(screen.getByRole("button", { name: "Print all" })).toBeInTheDocument();
    expect(screen.getByText("2 payslips, one to a page when printed.")).toBeInTheDocument();
  });
});

describe("Bank advice letter", () => {
  it("lists the transfers with accounts masked, the total in words and who is left out", async () => {
    api.bankAdvice.mockResolvedValue({
      month: "2026-09",
      status: "APPROVED",
      paidOn: null,
      school: payslip().school,
      bankName: "State Bank of India",
      bankBranch: "MG Road",
      debitAccountNumber: "000000555555",
      debitIfsc: "SBIN0001234",
      count: 1,
      totalPaise: 43_275_00,
      totalInWords: "Rupees Forty Three Thousand Two Hundred Seventy Five Only",
      rows: [{ name: "Ravi Kumar", employeeCode: "T-1", accountMasked: "XXXXXXXX9012", ifsc: "SBIN0001234", amountPaise: 43_275_00 }],
      withoutBank: [{ userId: "u-sita", name: "Sita Devi", employeeCode: "T-2" }],
    });
    renderAs(PAYROLL_ADMIN, <BankAdviceView runId="run-9" />);
    const letter = await screen.findByTestId("bank-advice");
    expect(letter).toHaveTextContent("Subject: Salary transfer for September 2026");
    expect(letter).toHaveTextContent("State Bank of India, MG Road");
    expect(letter).toHaveTextContent("Please debit our account 000000555555 (IFSC SBIN0001234) by ₹43,275");
    expect(within(screen.getByTestId("bank-advice-table")).getByText("XXXXXXXX9012")).toBeInTheDocument();
    expect(letter).toHaveTextContent("Rupees Forty Three Thousand Two Hundred Seventy Five Only");
    expect(screen.getByTestId("bank-advice-missing")).toHaveTextContent("Sita Devi (T-2)");
  });
});
