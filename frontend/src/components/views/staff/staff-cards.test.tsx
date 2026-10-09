import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api";
import type { MyStaffDay } from "@/lib/types";
import { renderAs } from "@/test/render";
import { CheckInCard, StaffDashboardCards } from "./staff-cards";

const { myToday, checkIn, checkOut, today, inbox } = vi.hoisted(() => ({
  myToday: vi.fn(),
  checkIn: vi.fn(),
  checkOut: vi.fn(),
  today: vi.fn(),
  inbox: vi.fn(),
}));

vi.mock("@/lib/staff-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/staff-api")>();
  return {
    ...actual,
    staffAttendanceApi: { ...actual.staffAttendanceApi, myToday, checkIn, checkOut, today },
    leaveApi: { ...actual.leaveApi, inbox },
  };
});

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  usePathname: () => "/app/dashboard",
}));

function myDay(overrides: Partial<MyStaffDay> = {}): MyStaffDay {
  return {
    date: "2026-10-09",
    workingDay: true,
    status: null,
    checkInAt: null,
    checkOutAt: null,
    checkInNote: null,
    checkOutNote: null,
    onLeave: false,
    canCheckIn: true,
    canCheckOut: false,
    ...overrides,
  };
}

// 03:01Z is 8:31 am in India; 11:15Z is 4:45 pm.
const IN = "2026-10-09T03:01:00Z";
const OUT = "2026-10-09T11:15:00Z";

beforeEach(() => {
  myToday.mockResolvedValue(myDay());
  inbox.mockResolvedValue([]);
  today.mockResolvedValue({
    date: "2026-10-09",
    workingDay: true,
    activeStaff: 12,
    present: 8,
    halfDay: 1,
    onLeave: 2,
    absent: 0,
    notMarked: 1,
    checkedIn: 9,
  });
});

describe("CheckInCard", () => {
  it("checks in with a note, then checks out", async () => {
    checkIn.mockResolvedValue(
      myDay({ status: "PRESENT", checkInAt: IN, checkInNote: "Bus was late", canCheckIn: false, canCheckOut: true }),
    );
    checkOut.mockResolvedValue(
      myDay({ status: "PRESENT", checkInAt: IN, checkOutAt: OUT, checkInNote: "Bus was late", canCheckIn: false }),
    );
    const user = userEvent.setup();
    renderAs(["leave.request"], <CheckInCard />, ["TEACHER"]);
    const card = await screen.findByTestId("check-in-card");
    expect(await within(card).findByTestId("check-in-state")).toHaveTextContent("Not checked in yet");

    await user.type(within(card).getByLabelText("Note (optional)"), "Bus was late");
    await user.click(within(card).getByRole("button", { name: "Check in" }));
    await waitFor(() => expect(checkIn).toHaveBeenCalledWith("Bus was late"));
    expect(within(card).getByTestId("check-in-state")).toHaveTextContent("Checked in at 8:31 am");
    expect(within(card).getByTestId("check-in-times")).toHaveTextContent("Bus was late");
    expect(within(card).getByLabelText("Note (optional)")).toHaveValue("");

    await user.click(within(card).getByRole("button", { name: "Check out" }));
    await waitFor(() => expect(checkOut).toHaveBeenCalledWith(""));
    expect(within(card).getByTestId("check-in-state")).toHaveTextContent("Checked in and out");
    expect(within(card).getByTestId("check-in-times")).toHaveTextContent("4:45 pm");
    expect(within(card).queryByRole("button", { name: /Check (in|out)/ })).not.toBeInTheDocument();
  });

  it("shows leave instead of the buttons", async () => {
    myToday.mockResolvedValue(myDay({ status: "ON_LEAVE", onLeave: true, canCheckIn: false }));
    renderAs(["leave.request"], <CheckInCard />, ["TEACHER"]);
    expect(await screen.findByTestId("check-in-state")).toHaveTextContent("On leave today");
    expect(screen.queryByRole("button", { name: "Check in" })).not.toBeInTheDocument();
  });

  it("shows the API's refusal", async () => {
    checkIn.mockRejectedValue(
      new ApiError({ status: 409, title: "Already checked in", detail: "You checked in at 08:31 today." }),
    );
    const user = userEvent.setup();
    renderAs(["leave.request"], <CheckInCard />, ["TEACHER"]);
    await user.click(await screen.findByRole("button", { name: "Check in" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("You checked in at 08:31 today.");
  });
});

describe("StaffDashboardCards", () => {
  it("gives a teacher only the check-in card when nothing waits for them", async () => {
    renderAs(["dashboard.view", "leave.request"], <StaffDashboardCards />, ["TEACHER"]);
    expect(await screen.findByTestId("check-in-card")).toBeInTheDocument();
    await waitFor(() => expect(inbox).toHaveBeenCalled());
    expect(screen.queryByTestId("leave-inbox-card")).not.toBeInTheDocument();
    expect(screen.queryByTestId("staff-today-card")).not.toBeInTheDocument();
    expect(today).not.toHaveBeenCalled();
  });

  it("gives approvers the waiting requests and staff present today", async () => {
    inbox.mockResolvedValue([
      {
        id: "r1",
        userName: "Ravi Kumar",
        leaveTypeName: "Casual leave",
        fromDate: "2026-10-14",
        toDate: "2026-10-14",
        halfDay: false,
        days: 1,
      },
    ]);
    renderAs(["dashboard.view", "staff.read", "leave.request", "leave.approve"], <StaffDashboardCards />, ["PRINCIPAL"]);
    const waiting = await screen.findByTestId("leave-inbox-card");
    expect(await within(waiting).findByTestId("leave-inbox-count")).toHaveTextContent("1");
    expect(waiting).toHaveTextContent("Ravi Kumar · Casual leave · 14 Oct 2026 · 1 day");
    expect(within(waiting).getByRole("link", { name: /Review requests/ })).toHaveAttribute("href", "/app/leave?tab=inbox");
    const present = await screen.findByTestId("staff-today-card");
    expect(await within(present).findByTestId("staff-present")).toHaveTextContent("9");
    expect(present).toHaveTextContent("of 12 staff");
    expect(present).toHaveTextContent("On leave 2 · Absent 0 · Not marked 1");
  });

  it("renders nothing for parents", () => {
    renderAs(["dashboard.view", "child.view"], <StaffDashboardCards />, ["PARENT"]);
    expect(screen.queryByTestId("staff-dashboard-cards")).not.toBeInTheDocument();
    expect(myToday).not.toHaveBeenCalled();
  });
});
