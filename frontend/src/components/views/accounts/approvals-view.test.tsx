import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { ApprovalQueue } from "@/lib/types";
import { ACCOUNTS_PRINCIPAL, expenseDetail, expenseRow } from "@/test/accounts-fixtures";
import { renderAs } from "@/test/render";
import { ApprovalsView } from "./approvals-view";

const api = vi.hoisted(() => ({
  queue: vi.fn(),
  approveExpense: vi.fn(),
  rejectExpense: vi.fn(),
}));

vi.mock("@/lib/accounts-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/accounts-api")>();
  return { ...actual, accountsApi: { ...actual.accountsApi, ...api } };
});

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  usePathname: () => "/app/accounts/approvals",
}));

function queue(overrides: Partial<ApprovalQueue> = {}): ApprovalQueue {
  return {
    expenses: [
      expenseRow(),
      expenseRow({ id: "e-2", number: "EXP-2026-27-0002", description: "Lab renovation", totalPaise: 1_85_000_00, amountPaise: 1_85_000_00, gstPaise: 0, vendorName: "Build Right" }),
      expenseRow({ id: "e-3", number: "EXP-2026-27-0003", description: "Generator service", totalPaise: 3_50_000_00, escalatedAbovePaise: 1_00_000_00 }),
    ],
    myLimitPaise: 1_00_000_00,
    limited: true,
    actionable: ["e-1", "e-2"],
    ...overrides,
  };
}

beforeEach(() => {
  vi.clearAllMocks();
  api.queue.mockResolvedValue(queue());
});

describe("ApprovalsView", () => {
  it("lists what is waiting, shows the approver's limit and offers Pass on above it", async () => {
    renderAs(ACCOUNTS_PRINCIPAL, <ApprovalsView />);
    const table = await screen.findByTestId("queue-table");
    expect(screen.getByText("3 expenses waiting")).toBeInTheDocument();
    expect(screen.getByTestId("queue-limit")).toHaveTextContent("You can approve up to ₹1,00,000");
    const rows = within(table).getAllByTestId("queue-row");
    expect(rows).toHaveLength(3);
    expect(within(rows[0]!).getByRole("button", { name: "Approve" })).toBeEnabled();
    expect(within(rows[1]!).getByRole("button", { name: "Pass on" })).toBeEnabled();
    expect(within(rows[2]!).queryByRole("button")).not.toBeInTheDocument();
    expect(within(rows[2]!).getByText("Waiting for a higher approver")).toBeInTheDocument();
  });

  it("approves within the limit and reloads the queue", async () => {
    const user = userEvent.setup();
    api.approveExpense.mockResolvedValue(expenseDetail({ status: "APPROVED" }));
    renderAs(ACCOUNTS_PRINCIPAL, <ApprovalsView />);
    const table = await screen.findByTestId("queue-table");
    const first = within(table).getAllByTestId("queue-row")[0]!;
    api.queue.mockResolvedValue(queue({ expenses: queue().expenses.slice(1), actionable: ["e-2"] }));
    await user.click(within(first).getByRole("button", { name: "Approve" }));
    expect(api.approveExpense).toHaveBeenCalledWith("e-1", null);
    await waitFor(() => expect(screen.getByText("2 expenses waiting")).toBeInTheDocument());
  });

  it("needs a reason to reject", async () => {
    const user = userEvent.setup();
    api.rejectExpense.mockResolvedValue(expenseDetail({ status: "REJECTED" }));
    renderAs(ACCOUNTS_PRINCIPAL, <ApprovalsView />);
    const table = await screen.findByTestId("queue-table");
    const first = within(table).getAllByTestId("queue-row")[0]!;
    await user.click(within(first).getByRole("button", { name: "Reject" }));
    const dialog = await screen.findByTestId("reject-form");
    await user.click(within(dialog).getByRole("button", { name: "Reject" }));
    expect(await within(dialog).findByText("Give a reason.")).toBeInTheDocument();
    expect(api.rejectExpense).not.toHaveBeenCalled();
    await user.type(within(dialog).getByLabelText("Reason"), "Get a second quote");
    await user.click(within(dialog).getByRole("button", { name: "Reject" }));
    await waitFor(() => expect(api.rejectExpense).toHaveBeenCalledWith("e-1", "Get a second quote"));
  });

  it("says when nothing is waiting", async () => {
    api.queue.mockResolvedValue(queue({ expenses: [], actionable: [], limited: false, myLimitPaise: null }));
    renderAs(ACCOUNTS_PRINCIPAL, <ApprovalsView />);
    expect(await screen.findByTestId("queue-empty")).toHaveTextContent("Nothing waiting for approval.");
    expect(screen.getByTestId("queue-limit")).toHaveTextContent("You can approve any amount.");
  });
});
