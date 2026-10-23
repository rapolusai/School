import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { HostelView, RollCallRow, RollCallSheet } from "@/lib/types";
import { renderAs } from "@/test/render";
import { initialMarks, RollCallView, tally, unmarked } from "./roll-call-view";

const m = vi.hoisted(() => ({
  hostels: vi.fn(),
  rollCall: vi.fn(),
  takeRollCall: vi.fn(),
  rollCallRegister: vi.fn(),
}));

vi.mock("@/lib/hostel-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/hostel-api")>();
  return { ...actual, hostelApi: { ...actual.hostelApi, ...m } };
});
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  usePathname: () => "/app/hostel/roll-call",
  useSearchParams: () => new URLSearchParams(),
}));

const WARDEN = ["dashboard.view", "hostel.warden"];
const PRINCIPAL = ["dashboard.view", "hostel.read"];

const HOSTEL: HostelView = {
  id: "h1",
  name: "Boys' Hostel",
  type: "BOYS",
  wardenUserId: "w1",
  wardenName: "Vikram Pillai",
  address: null,
  active: true,
  blocks: 1,
  rooms: 1,
  beds: 3,
  occupied: 3,
  occupancyPercent: 100,
  canManage: false,
  canWarden: true,
};

function row(id: string, name: string, overrides: Partial<RollCallRow> = {}): RollCallRow {
  return { studentId: id, studentName: name, admissionNo: `AKS/${id}`, className: "Class 5 A", roomNumber: "101", bedLabel: "A", status: null, note: null, passId: null, suggestedOnLeave: false, ...overrides };
}

const ROWS = [row("st1", "Arjun Sharma"), row("st2", "Bala Iyer", { suggestedOnLeave: true, passId: "p1" }), row("st3", "Chetan Rao")];

function sheet(overrides: Partial<RollCallSheet> = {}): RollCallSheet {
  return { hostelId: "h1", hostelName: "Boys' Hostel", night: "2026-10-22", taken: false, takenByName: null, takenAt: null, present: 0, onLeave: 0, absent: 0, rows: ROWS, canMark: true, ...overrides };
}

describe("initialMarks, unmarked and tally", () => {
  it("suggests on-leave for a boarder away on a pass and counts what is still unmarked", () => {
    const marks = initialMarks(ROWS);
    expect(marks.st2.status).toBe("ON_LEAVE");
    expect(marks.st1.status).toBeNull();
    expect(unmarked(marks)).toBe(2);
    expect(tally(marks)).toEqual({ PRESENT: 0, ON_LEAVE: 1, ABSENT: 0 });
  });

  it("keeps saved marks and notes", () => {
    const marks = initialMarks([row("st1", "Arjun", { status: "ABSENT", note: "Not in room" })]);
    expect(marks.st1).toEqual({ status: "ABSENT", note: "Not in room" });
    expect(unmarked(marks)).toBe(0);
  });
});

describe("RollCallView", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    m.hostels.mockResolvedValue([HOSTEL]);
    m.rollCall.mockResolvedValue(sheet());
    m.takeRollCall.mockResolvedValue({});
    m.rollCallRegister.mockResolvedValue({ hostelId: "h1", hostelName: "Boys' Hostel", from: "2026-09-22", to: "2026-10-22", nights: [] });
  });

  it("refuses to save while a boarder is unmarked, then saves every mark with the absentee's note", async () => {
    const user = userEvent.setup();
    renderAs(WARDEN, <RollCallView />);
    const form = await screen.findByTestId("roll-call-form");
    const rows = within(form).getAllByTestId("roll-call-row");
    expect(rows).toHaveLength(3);
    expect(rows[1]).toHaveAttribute("data-status", "ON_LEAVE");
    await user.click(within(form).getByTestId("roll-call-save"));
    expect(m.takeRollCall).not.toHaveBeenCalled();
    expect(within(form).getAllByText("2 boarders still unmarked").length).toBeGreaterThan(0);

    await user.click(within(rows[2]).getByLabelText("Absent"));
    await user.type(within(rows[2]).getByPlaceholderText("Where are they? (optional)"), "Not in room");
    await user.click(within(form).getByTestId("roll-call-all-present"));
    expect(rows[0]).toHaveAttribute("data-status", "PRESENT");
    expect(rows[2]).toHaveAttribute("data-status", "ABSENT");
    await user.click(within(form).getByTestId("roll-call-save"));
    await waitFor(() =>
      expect(m.takeRollCall).toHaveBeenCalledWith("h1", "2026-10-22", {
        entries: [
          { studentId: "st1", status: "PRESENT", note: null },
          { studentId: "st2", status: "ON_LEAVE", note: null },
          { studentId: "st3", status: "ABSENT", note: "Not in room" },
        ],
      }),
    );
    expect(await screen.findByText("Roll call saved; 1 absentee's parent has been messaged.")).toBeInTheDocument();
  });

  it("shows a taken roll call read-only to someone who may only read", async () => {
    m.rollCall.mockResolvedValue(
      sheet({ taken: true, takenByName: "Vikram Pillai", takenAt: "2026-10-22T16:30:00Z", canMark: false, rows: [row("st1", "Arjun Sharma", { status: "PRESENT" }), row("st3", "Chetan Rao", { status: "ABSENT", note: "Not in room" })] }),
    );
    renderAs(PRINCIPAL, <RollCallView />);
    await screen.findByTestId("roll-call-form");
    expect(screen.getByTestId("roll-call-state")).toHaveTextContent(/Taken by Vikram Pillai/);
    expect(screen.queryByTestId("roll-call-save")).not.toBeInTheDocument();
    expect(screen.queryByRole("radio")).not.toBeInTheDocument();
    expect(screen.getByText("Not in room")).toBeInTheDocument();
  });

  it("lists the register's nights with their absentees", async () => {
    m.rollCallRegister.mockResolvedValue({
      hostelId: "h1",
      hostelName: "Boys' Hostel",
      from: "2026-09-22",
      to: "2026-10-22",
      nights: [{ id: "rc1", night: "2026-10-21", takenByName: "Vikram Pillai", takenAt: "2026-10-21T16:30:00Z", present: 2, onLeave: 0, absent: 1, absentees: ["Chetan Rao"] }],
    });
    renderAs(WARDEN, <RollCallView />);
    const night = await screen.findByTestId("register-night");
    expect(night).toHaveTextContent("21 Oct 2026");
    expect(night).toHaveTextContent("Chetan Rao");
  });

  it("says so when the hostel has no boarders", async () => {
    m.rollCall.mockResolvedValue(sheet({ rows: [] }));
    renderAs(WARDEN, <RollCallView />);
    expect(await screen.findByTestId("roll-call-empty")).toBeInTheDocument();
  });
});
