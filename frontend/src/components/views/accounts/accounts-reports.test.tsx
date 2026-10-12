import { screen, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ACCOUNTS_READER, incomeVsExpense, monthLine } from "@/test/accounts-fixtures";
import { renderAs } from "@/test/render";
import { IncomeVsExpenseReport } from "./accounts-reports";

const api = vi.hoisted(() => ({ incomeVsExpense: vi.fn() }));

vi.mock("@/lib/accounts-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/accounts-api")>();
  return { ...actual, accountsApi: { ...actual.accountsApi, ...api } };
});

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  usePathname: () => "/app/reports/accounts-income-vs-expense",
}));

beforeEach(() => {
  vi.clearAllMocks();
  vi.useFakeTimers({ now: new Date("2026-10-12T04:00:00Z"), toFake: ["Date"] });
});

describe("IncomeVsExpenseReport", () => {
  it("shows the year's totals and each month's fees, payroll and net", async () => {
    api.incomeVsExpense.mockResolvedValue(incomeVsExpense());
    renderAs(ACCOUNTS_READER, <IncomeVsExpenseReport />);
    const report = await screen.findByTestId("report-income-vs-expense");
    expect(api.incomeVsExpense).toHaveBeenCalledWith({ financialYear: "2026-27" });
    expect(await within(report).findByTestId("ive-income")).toHaveTextContent("₹6,75,000");
    expect(within(report).getByTestId("ive-fee-income")).toHaveTextContent("₹6,50,000");
    expect(within(report).getByTestId("ive-expense")).toHaveTextContent("₹1,63,000");
    expect(within(report).getByTestId("ive-net")).toHaveTextContent("₹5,12,000");
    const october = within(report).getByTestId("ive-month-2026-10");
    expect(october).toHaveTextContent("October 2026");
    expect(october).toHaveTextContent("₹2,75,000");
    expect(october).toHaveTextContent("₹13,000");
    const september = within(report).getByTestId("ive-month-2026-09");
    expect(september).toHaveTextContent("₹1,50,000");
    expect(within(report).getByLabelText("Financial year")).toHaveValue("2026-27");
    expect(screen.getByRole("button", { name: "CSV" })).toBeEnabled();
  });

  it("says when the year has nothing yet", async () => {
    const empty = incomeVsExpense();
    api.incomeVsExpense.mockResolvedValue({ ...empty, months: empty.months.map((m) => monthLine(m.month)), totals: monthLine("TOTAL") });
    renderAs(ACCOUNTS_READER, <IncomeVsExpenseReport />);
    expect(await screen.findByTestId("ive-empty")).toHaveTextContent("Nothing in this period.");
    expect(screen.queryByTestId("ive-table")).not.toBeInTheDocument();
  });
});
