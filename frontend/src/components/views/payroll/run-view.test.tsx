import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api";
import { PAYROLL_ACCOUNTANT, PAYROLL_ADMIN, PAYROLL_READER, run, slipRow } from "@/test/payroll-fixtures";
import { renderAs } from "@/test/render";
import { PayrollRunView } from "./run-view";
import { PayrollRunsView, validateRunMonth } from "./runs-view";

const api = vi.hoisted(() => ({
  run: vi.fn(),
  runs: vi.fn(),
  createRun: vi.fn(),
  recalculate: vi.fn(),
  approve: vi.fn(),
  markPaid: vi.fn(),
  addAdjustment: vi.fn(),
  overrideTds: vi.fn(),
  deleteRun: vi.fn(),
  download: vi.fn(),
  push: vi.fn(),
}));

vi.mock("@/lib/payroll-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/payroll-api")>();
  return {
    ...actual,
    downloadPayrollFile: api.download,
    payrollApi: {
      ...actual.payrollApi,
      run: api.run,
      runs: api.runs,
      createRun: api.createRun,
      recalculate: api.recalculate,
      approve: api.approve,
      markPaid: api.markPaid,
      addAdjustment: api.addAdjustment,
      overrideTds: api.overrideTds,
      deleteRun: api.deleteRun,
    },
  };
});

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: api.push }),
  usePathname: () => "/app/payroll/runs/run-9",
}));

beforeEach(() => {
  api.run.mockResolvedValue(run());
});

describe("PayrollRunView: reviewing a draft", () => {
  it("shows the month's totals, each person's pay and what is worth a look", async () => {
    api.run.mockResolvedValue(
      run({
        warnings: [
          { code: "NO_BANK", userId: "u-sita", name: "Sita Devi", detail: null },
          { code: "TAX_RULES_EARLIER_YEAR", userId: null, name: null, detail: "2025-26" },
        ],
      }),
    );
    renderAs(PAYROLL_ADMIN, <PayrollRunView id="run-9" />);
    expect(await screen.findByRole("heading", { level: 1, name: "Payroll for September 2026" })).toBeInTheDocument();
    expect(screen.getByTestId("run-status")).toHaveTextContent("Draft");
    expect(screen.getByTestId("run-net")).toHaveTextContent("₹78,775");
    expect(screen.getByTestId("run-totals")).toHaveTextContent("₹85,000");
    expect(screen.getByTestId("run-statutory")).toHaveTextContent("₹3,600 staff · ₹3,600 employer");
    expect(screen.getByText(/Prorated on calendar days \(30 this month\)/)).toBeInTheDocument();

    const table = screen.getByTestId("run-payslips-table");
    const ravi = within(table).getAllByTestId("run-payslip-row")[0];
    expect(within(ravi).getByRole("link", { name: "Ravi Kumar" })).toHaveAttribute("href", "/app/payroll/payslips/ps-ravi");
    expect(ravi).toHaveTextContent("28.5");
    expect(ravi).toHaveTextContent("1.5 LOP");
    expect(ravi).toHaveTextContent("₹43,275");
    expect(within(table).getAllByTestId("run-payslip-row")[1]).toHaveTextContent("no bank account");

    const warnings = screen.getByTestId("run-warnings");
    expect(within(warnings).getByTestId("warning-NO_BANK")).toHaveTextContent(
      "No bank account, so not in the bank file: Sita Devi",
    );
    expect(within(warnings).getByTestId("warning-TAX_RULES_EARLIER_YEAR")).toHaveTextContent(
      "TDS uses the 2025-26 tax rules",
    );
    // Files only once the month is approved.
    expect(screen.queryByTestId("run-files")).not.toBeInTheDocument();
  });

  it("lets the accountant prepare the month but not approve it", async () => {
    renderAs(PAYROLL_ACCOUNTANT, <PayrollRunView id="run-9" />);
    await screen.findByTestId("run-summary");
    expect(screen.getByRole("button", { name: "Recalculate" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Add adjustment" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Delete draft" })).toBeInTheDocument();
    expect(screen.queryByTestId("approve-run")).not.toBeInTheDocument();
  });

  it("shows a payroll reader the figures without any action", async () => {
    renderAs(PAYROLL_READER, <PayrollRunView id="run-9" />);
    await screen.findByTestId("run-summary");
    for (const name of ["Recalculate", "Delete draft", "Approve", "Add adjustment"]) {
      expect(screen.queryByRole("button", { name })).not.toBeInTheDocument();
    }
    expect(screen.queryByRole("button", { name: "Change TDS for Ravi Kumar" })).not.toBeInTheDocument();
  });

  it("approves and locks the month after asking", async () => {
    const user = userEvent.setup();
    api.approve.mockResolvedValue(
      run({ status: "APPROVED", approvedByName: "Lakshmi Iyer", approvedAt: "2026-10-01T05:00:00Z", version: 4 }),
    );
    renderAs(PAYROLL_ADMIN, <PayrollRunView id="run-9" />);
    await user.click(await screen.findByTestId("approve-run"));
    const dialog = screen.getByRole("dialog", { name: "Approve payroll for September 2026?" });
    expect(dialog).toHaveTextContent("This locks the month for 2 staff with a net pay of ₹78,775.");
    api.run.mockResolvedValue(
      run({ status: "APPROVED", approvedByName: "Lakshmi Iyer", approvedAt: "2026-10-01T05:00:00Z", version: 4 }),
    );
    await user.click(within(dialog).getByRole("button", { name: "Approve and lock" }));
    expect(api.approve).toHaveBeenCalledWith("run-9");
    expect(await screen.findByTestId("run-locked")).toHaveTextContent("Approved and locked by Lakshmi Iyer");
    expect(screen.getByTestId("run-status")).toHaveTextContent("Approved");
    expect(screen.queryByTestId("approve-run")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Recalculate" })).not.toBeInTheDocument();
    expect(screen.getByTestId("run-files")).toBeInTheDocument();
  });

  it("keeps the dialog open with the reason when the figures changed since the review", async () => {
    const user = userEvent.setup();
    api.approve.mockRejectedValue(
      new ApiError({
        status: 409,
        title: "Figures changed",
        detail: "The figures changed since they were last calculated. Recalculate, review them and approve again.",
      }),
    );
    renderAs(PAYROLL_ADMIN, <PayrollRunView id="run-9" />);
    await user.click(await screen.findByTestId("approve-run"));
    const dialog = screen.getByRole("dialog");
    await user.click(within(dialog).getByRole("button", { name: "Approve and lock" }));
    expect(await within(dialog).findByRole("alert")).toHaveTextContent("The figures changed since they were last calculated");
    await waitFor(() => expect(api.run).toHaveBeenCalledTimes(2));
  });

  it("adds a loss-of-pay correction with a reason", async () => {
    const user = userEvent.setup();
    api.addAdjustment.mockResolvedValue(
      run({
        version: 4,
        adjustments: [
          {
            id: "adj1",
            userId: "u-ravi",
            name: "Ravi Kumar",
            kind: "LOP_CORRECTION",
            amountPaise: null,
            days: -0.5,
            reason: "Half day was on duty",
            createdByName: "Meena Reddy",
            createdAt: "2026-10-01T05:00:00Z",
          },
        ],
      }),
    );
    renderAs(PAYROLL_ACCOUNTANT, <PayrollRunView id="run-9" />);
    await user.click(await screen.findByRole("button", { name: "Add adjustment" }));
    const dialog = screen.getByRole("dialog", { name: "Add adjustment" });
    await user.selectOptions(within(dialog).getByLabelText("Type"), "LOP_CORRECTION");
    await user.click(within(dialog).getByRole("button", { name: "Add" }));
    expect(within(dialog).getByText("Choose one.")).toBeInTheDocument();
    expect(api.addAdjustment).not.toHaveBeenCalled();

    await user.selectOptions(within(dialog).getByLabelText("Staff member"), "u-ravi");
    await user.type(within(dialog).getByLabelText("Loss-of-pay days"), "-0.5");
    await user.type(within(dialog).getByLabelText("Reason"), "Half day was on duty");
    await user.click(within(dialog).getByRole("button", { name: "Add" }));
    expect(api.addAdjustment).toHaveBeenCalledWith("run-9", {
      userId: "u-ravi",
      kind: "LOP_CORRECTION",
      amountPaise: null,
      days: -0.5,
      reason: "Half day was on duty",
    });
    expect(await screen.findByText("Half day was on duty")).toBeInTheDocument();
    expect(screen.getByTestId("run-adjustments")).toHaveTextContent("-0.5 days");
  });

  it("sets a month's TDS by hand with a reason", async () => {
    const user = userEvent.setup();
    api.overrideTds.mockResolvedValue(
      run({ version: 4, payslips: [slipRow({ tdsPaise: 2_000_00, tdsOverridden: true, tdsOverrideReason: "Declared" })] }),
    );
    renderAs(PAYROLL_ACCOUNTANT, <PayrollRunView id="run-9" />);
    await user.click(await screen.findByRole("button", { name: "Change TDS for Ravi Kumar" }));
    const dialog = screen.getByRole("dialog", { name: "TDS for Ravi Kumar" });
    expect(dialog).toHaveTextContent("₹1,225 this month");
    const amount = within(dialog).getByLabelText("TDS this month (₹)");
    await user.clear(amount);
    await user.type(amount, "2000");
    await user.type(within(dialog).getByLabelText("Reason"), "Declared");
    await user.click(within(dialog).getByRole("button", { name: "Use this amount" }));
    expect(api.overrideTds).toHaveBeenCalledWith("run-9", "ps-ravi", { amountPaise: 2_000_00, reason: "Declared" });
  });
});

describe("PayrollRunView: an approved month", () => {
  it("gives the manager the bank file and the other files, and the bank advice letter", async () => {
    const user = userEvent.setup();
    api.run.mockResolvedValue(run({ status: "APPROVED", approvedByName: "Lakshmi Iyer", approvedAt: "2026-10-01T05:00:00Z" }));
    api.download.mockResolvedValue("bank-transfer-2026-09.csv");
    renderAs(PAYROLL_ACCOUNTANT, <PayrollRunView id="run-9" />);
    const files = await screen.findByTestId("run-files");
    await user.click(within(files).getByTestId("download-bank"));
    expect(api.download).toHaveBeenCalledWith("/api/payroll/runs/run-9/bank-file.csv", "bank-transfer-2026-09.csv");
    expect(await screen.findByText("Downloaded bank-transfer-2026-09.csv.")).toBeInTheDocument();
    expect(within(files).getByTestId("bank-advice-link")).toHaveAttribute("href", "/app/payroll/runs/run-9/bank-advice");
    expect(within(files).getByRole("button", { name: "EPFO ECR (text)" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Mark paid" })).toBeInTheDocument();
  });

  it("records the payment date", async () => {
    const user = userEvent.setup();
    api.run.mockResolvedValue(run({ status: "APPROVED", approvedByName: "Lakshmi Iyer", approvedAt: "2026-10-01T05:00:00Z" }));
    api.markPaid.mockResolvedValue(run({ status: "PAID", paidOn: "2026-10-01", paidByName: "Meena Reddy", version: 5 }));
    renderAs(PAYROLL_ACCOUNTANT, <PayrollRunView id="run-9" />);
    await user.click(await screen.findByRole("button", { name: "Mark paid" }));
    const dialog = screen.getByRole("dialog", { name: "Mark salaries paid" });
    const date = within(dialog).getByLabelText("Payment date");
    await user.clear(date);
    await user.type(date, "2026-10-01");
    await user.type(within(dialog).getByLabelText("Bank reference"), "UTR123");
    await user.click(within(dialog).getByRole("button", { name: "Mark paid" }));
    expect(api.markPaid).toHaveBeenCalledWith("run-9", { paidOn: "2026-10-01", reference: "UTR123" });
  });

  it("says when the month does not exist", async () => {
    api.run.mockRejectedValue(new ApiError({ status: 404, title: "Not found" }));
    renderAs(PAYROLL_ADMIN, <PayrollRunView id="nope" />);
    expect(await screen.findByTestId("run-not-found")).toBeInTheDocument();
  });
});

describe("PayrollRunsView", () => {
  it("lists the months and works out a new one", async () => {
    const user = userEvent.setup();
    api.runs.mockResolvedValue({
      runs: [
        {
          id: "run-8",
          month: "2026-08",
          status: "PAID",
          totals: run().totals,
          calculatedAt: "2026-09-01T04:30:00Z",
          approvedByName: "Lakshmi Iyer",
          approvedAt: "2026-09-01T05:00:00Z",
          paidOn: "2026-09-01",
        },
      ],
      suggestedMonth: "2026-09",
      needStructure: 1,
    });
    api.createRun.mockResolvedValue(run());
    renderAs(PAYROLL_ACCOUNTANT, <PayrollRunsView />);
    const table = await screen.findByTestId("runs-table");
    expect(within(table).getByRole("link", { name: "August 2026" })).toHaveAttribute("href", "/app/payroll/runs/run-8");
    expect(table).toHaveTextContent("Paid on 1 Sept 2026");
    expect(screen.getByTestId("runs-need-structure")).toHaveTextContent(
      "1 member of staff has no salary structure and will not be paid.",
    );

    await user.click(screen.getByRole("button", { name: "Work out a month" }));
    const dialog = screen.getByRole("dialog", { name: "Work out a month's payroll" });
    expect(within(dialog).getByLabelText("Month")).toHaveValue("2026-09");
    await user.click(within(dialog).getByRole("button", { name: "Work out payroll" }));
    expect(api.createRun).toHaveBeenCalledWith("2026-09");
    await waitFor(() => expect(api.push).toHaveBeenCalledWith("/app/payroll/runs/run-9"));
  });

  it("checks the month as the API does", () => {
    expect(validateRunMonth("2026-09", "2026-10-11")).toEqual({});
    expect(validateRunMonth("2026-11", "2026-10-11")).toEqual({ month: "payroll.v.futureMonth" });
    expect(validateRunMonth("Sept", "2026-10-11")).toEqual({ month: "payroll.v.month" });
  });
});
