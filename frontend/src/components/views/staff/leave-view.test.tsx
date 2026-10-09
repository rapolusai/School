import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { LeaveBalance, LeaveType, MyLeave, StaffLeaveRequest } from "@/lib/types";
import { renderAs } from "@/test/render";
import { LeaveView } from "./leave-view";

const { mine, types, inbox, approve, reject, cancel } = vi.hoisted(() => ({
  mine: vi.fn(),
  types: vi.fn(),
  inbox: vi.fn(),
  approve: vi.fn(),
  reject: vi.fn(),
  cancel: vi.fn(),
}));

vi.mock("@/lib/staff-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/staff-api")>();
  return { ...actual, leaveApi: { ...actual.leaveApi, mine, types, inbox, approve, reject, cancel } };
});

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  usePathname: () => "/app/leave",
}));

const PRINCIPAL = ["dashboard.view", "staff.read", "leave.request", "leave.approve"];
const TEACHER = ["dashboard.view", "students.read", "leave.request"];

const YEAR = { id: "y1", name: "2026-27", startsOn: "2026-04-01", endsOn: "2027-03-31", current: true };

const CL: LeaveBalance = {
  leaveTypeId: "cl",
  leaveTypeName: "Casual leave",
  code: "CL",
  lossOfPay: false,
  halfDayAllowed: true,
  active: true,
  opening: 0,
  accrued: 12,
  taken: 2,
  pending: 2,
  available: 10,
  setByHand: false,
};

function request(overrides: Partial<StaffLeaveRequest> = {}): StaffLeaveRequest {
  return {
    id: "r1",
    userId: "u1",
    userName: "Ravi Kumar",
    employeeCode: "AKS-101",
    departmentName: "Primary",
    leaveTypeId: "cl",
    leaveTypeName: "Casual leave",
    leaveTypeCode: "CL",
    lossOfPay: false,
    academicYearId: "y1",
    academicYearName: "2026-27",
    fromDate: "2026-10-14",
    toDate: "2026-10-15",
    halfDay: false,
    days: 2,
    reason: "Family function",
    status: "PENDING",
    routing: "SCHOOL",
    approverName: null,
    decidedByName: null,
    decidedAt: null,
    decisionComment: null,
    cancelledByName: null,
    cancelledAt: null,
    cancelComment: null,
    createdAt: "2026-10-08T04:30:00Z",
    canCancel: false,
    canDecide: true,
    available: 10,
    ...overrides,
  };
}

function myLeave(overrides: Partial<MyLeave> = {}): MyLeave {
  return {
    year: YEAR,
    years: [YEAR],
    approver: { routing: "SCHOOL", departmentHead: null },
    balances: [CL],
    requests: [],
    waitingForMe: 1,
    ...overrides,
  };
}

beforeEach(() => {
  mine.mockResolvedValue(myLeave());
  types.mockResolvedValue([]);
  inbox.mockResolvedValue([request()]);
});

describe("approver inbox", () => {
  it("lists requests waiting for the approver with the requester's balance", async () => {
    renderAs(PRINCIPAL, <LeaveView initialTab="inbox" />, ["PRINCIPAL"]);
    const list = await screen.findByTestId("leave-inbox");
    const item = within(list).getByTestId("leave-request");
    expect(item).toHaveTextContent("Ravi Kumar");
    expect(item).toHaveTextContent("AKS-101 · Primary");
    expect(item).toHaveTextContent("Casual leave · 14 Oct 2026 – 15 Oct 2026 · 2 days");
    expect(item).toHaveTextContent("Family function");
    expect(item).toHaveTextContent("10 days left before this");
    expect(screen.getByRole("tab", { name: "To approve (1)" })).toHaveAttribute("aria-selected", "true");
    expect(inbox).toHaveBeenCalledWith(false);
  });

  it("approves with an optional comment and refreshes the list", async () => {
    approve.mockResolvedValue(request({ status: "APPROVED", canDecide: false }));
    const user = userEvent.setup();
    renderAs(PRINCIPAL, <LeaveView initialTab="inbox" />, ["PRINCIPAL"]);
    await user.click(await screen.findByRole("button", { name: "Approve leave of Ravi Kumar" }));
    const dialog = await screen.findByRole("dialog", { name: "Approve leave" });
    inbox.mockResolvedValue([]);
    await user.click(within(dialog).getByRole("button", { name: "Approve" }));
    await waitFor(() => expect(approve).toHaveBeenCalledWith("r1", ""));
    expect(await screen.findByText("Leave of Ravi Kumar approved.")).toBeInTheDocument();
    expect(await screen.findByText("Nothing is waiting for you.")).toBeInTheDocument();
  });

  it("needs a comment to reject", async () => {
    reject.mockResolvedValue(request({ status: "REJECTED", canDecide: false }));
    const user = userEvent.setup();
    renderAs(PRINCIPAL, <LeaveView initialTab="inbox" />, ["PRINCIPAL"]);
    await user.click(await screen.findByRole("button", { name: "Reject leave of Ravi Kumar" }));
    const dialog = await screen.findByRole("dialog", { name: "Reject leave" });
    await user.click(within(dialog).getByRole("button", { name: "Reject" }));
    expect(reject).not.toHaveBeenCalled();
    expect(within(dialog).getByText("Say why, so they know.")).toBeInTheDocument();
    await user.type(within(dialog).getByLabelText("Comment for them"), "Exams that week");
    await user.click(within(dialog).getByRole("button", { name: "Reject" }));
    await waitFor(() => expect(reject).toHaveBeenCalledWith("r1", "Exams that week"));
  });

  it("lets approvers see every pending request", async () => {
    const user = userEvent.setup();
    renderAs(PRINCIPAL, <LeaveView initialTab="inbox" />, ["PRINCIPAL"]);
    await screen.findByTestId("leave-inbox");
    await user.click(screen.getByRole("checkbox", { name: "Show every pending request" }));
    await waitFor(() => expect(inbox).toHaveBeenLastCalledWith(true));
  });
});

describe("my leave", () => {
  it("shows balances, where requests go and cancels a pending request", async () => {
    mine.mockResolvedValue(
      myLeave({
        waitingForMe: 0,
        approver: { routing: "DEPARTMENT_HEAD", departmentHead: { id: "u2", name: "Anjali Deshmukh" } },
        requests: [request({ canCancel: true, canDecide: false, approverName: "Anjali Deshmukh", available: null })],
      }),
    );
    cancel.mockResolvedValue(request({ status: "CANCELLED" }));
    const user = userEvent.setup();
    renderAs(TEACHER, <LeaveView />, ["TEACHER"]);
    expect(await screen.findByTestId("leave-approver")).toHaveTextContent(
      "Your requests go to Anjali Deshmukh, your department head.",
    );
    expect(within(screen.getByTestId("balance-CL")).getByTestId("balance-available")).toHaveTextContent("10");
    // A teacher with nothing to decide sees no inbox tab and no leave types.
    expect(screen.queryByRole("tab", { name: /To approve/ })).not.toBeInTheDocument();
    expect(screen.queryByRole("tab", { name: "Leave types" })).not.toBeInTheDocument();
    const mineList = screen.getByTestId("my-requests");
    expect(within(mineList).getByText(/Waiting for Anjali Deshmukh/)).toBeInTheDocument();
    await user.click(within(mineList).getByRole("button", { name: "Cancel leave" }));
    const dialog = await screen.findByRole("dialog", { name: "Cancel leave" });
    await user.click(within(dialog).getByRole("button", { name: "Cancel leave" }));
    await waitFor(() => expect(cancel).toHaveBeenCalledWith("r1", ""));
  });

  it("explains that leave needs an academic year", async () => {
    mine.mockResolvedValue(myLeave({ year: null, years: [], balances: [], waitingForMe: 0 }));
    renderAs(TEACHER, <LeaveView />, ["TEACHER"]);
    expect(await screen.findByTestId("leave-no-year")).toBeInTheDocument();
    expect(screen.queryByTestId("apply-leave")).not.toBeInTheDocument();
  });
});

describe("leave types", () => {
  const ADMIN = ["dashboard.view", "staff.read", "staff.manage", "leave.request", "leave.approve"];
  const type = (overrides: Partial<LeaveType>): LeaveType => ({
    id: "cl",
    name: "Casual leave",
    code: "CL",
    yearlyQuota: 12,
    carryForwardCap: 0,
    halfDayAllowed: true,
    lossOfPay: false,
    active: true,
    inUse: true,
    ...overrides,
  });
  const TYPES = [type({}), type({ id: "lop", name: "Loss of pay", code: "LOP", yearlyQuota: 0, lossOfPay: true, inUse: false })];

  it("shows the school's types as a table and as phone cards, with delete only for unused types", async () => {
    types.mockResolvedValue(TYPES);
    renderAs(ADMIN, <LeaveView initialTab="types" />, ["SCHOOL_ADMIN"]);
    const table = await screen.findByTestId("leave-types");
    expect(within(table).getAllByRole("row")).toHaveLength(3);
    const cards = within(screen.getByTestId("leave-types-cards")).getAllByRole("listitem");
    expect(cards[0]).toHaveTextContent("Casual leave CL");
    expect(cards[0]).toHaveTextContent("12 days a year · carry forward up to 0");
    expect(cards[0]).toHaveTextContent("Half days");
    expect(cards[1]).toHaveTextContent("Unpaid");
    expect(cards[1]).not.toHaveTextContent("days a year");
    expect(within(cards[0]).queryByRole("button", { name: "Delete Casual leave?" })).not.toBeInTheDocument();
    expect(within(cards[1]).getByRole("button", { name: "Delete Loss of pay?" })).toBeInTheDocument();
    expect(within(cards[1]).getByRole("button", { name: "Edit Loss of pay" })).toBeInTheDocument();
  });
});
