import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { todayInIndia } from "@/lib/format";
import type { Child, ChildAttendance } from "@/lib/types";
import { renderAs } from "@/test/render";
import { FamilyAttendanceView, monthCells } from "./family-attendance";
import { calendarDaysBetween, shiftMonth } from "./family-shared";

const m = vi.hoisted(() => ({ myChildren: vi.fn(), child: vi.fn(), myAttendance: vi.fn() }));

vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return { ...actual, api: { ...actual.api, myChildren: m.myChildren } };
});
vi.mock("@/lib/attendance-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/attendance-api")>();
  return { ...actual, attendanceApi: { ...actual.attendanceApi, child: m.child } };
});
vi.mock("@/lib/portal-api", () => ({ portalApi: { myAttendance: m.myAttendance } }));
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  usePathname: () => "/app/family/attendance",
}));

const NONE = { present: 0, absent: 0, late: 0, halfDay: 0, leave: 0 };
const ARJUN: Child = {
  id: "st1",
  fullName: "Arjun Sharma",
  admissionNo: "AKS/2026/001",
  status: "ACTIVE",
  className: "Class 5",
  sectionName: "A",
  rollNo: 1,
  classTeacherName: "Ravi Kumar",
  academicYearName: "2026-27",
};
const DIYA: Child = { ...ARJUN, id: "st2", fullName: "Diya Sharma", className: "Class 2" };

function month(value: string, studentId = "st1"): ChildAttendance {
  return {
    studentId,
    fullName: "Arjun Sharma",
    month: value,
    daysMarked: 2,
    counts: { ...NONE, present: 1, absent: 1 },
    presentPercent: 50,
    days: [
      { date: `${value}-05`, status: "PRESENT" },
      { date: `${value}-06`, status: "ABSENT" },
    ],
    recentAbsences: [],
    today: todayInIndia(),
    todayStatus: null,
    todayHoliday: null,
    todayLeave: null,
    holidays: [{ date: `${value}-02`, title: "Gandhi Jayanti" }],
    leaveDays: [{ date: `${value}-08`, halfDay: true }],
  };
}

beforeEach(() => {
  window.localStorage.clear();
  m.myChildren.mockResolvedValue([ARJUN, DIYA]);
  m.child.mockImplementation((id: string, value?: string) => Promise.resolve(month(value ?? todayInIndia().slice(0, 7), id)));
  m.myAttendance.mockImplementation((value?: string) => Promise.resolve(month(value ?? todayInIndia().slice(0, 7))));
});

afterEach(() => window.localStorage.clear());

describe("monthCells", () => {
  it("starts on Monday and carries marks, holidays and leave", () => {
    const { blanks, cells } = monthCells(month("2026-10"), "2026-10-09");
    // 1 October 2026 is a Thursday: three empty cells before it.
    expect(blanks).toBe(3);
    expect(cells).toHaveLength(31);
    expect(cells[1]).toMatchObject({ date: "2026-10-02", holiday: "Gandhi Jayanti", status: null });
    expect(cells[5]).toMatchObject({ date: "2026-10-06", status: "ABSENT" });
    expect(cells[7]).toMatchObject({ date: "2026-10-08", leave: "half" });
    expect(cells[8].isToday).toBe(true);
  });

  it("moves between months and counts days", () => {
    expect(shiftMonth("2026-01", -1)).toBe("2025-12");
    expect(shiftMonth("2026-12", 1)).toBe("2027-01");
    expect(calendarDaysBetween("2026-10-12", "2026-10-12")).toBe(1);
    expect(calendarDaysBetween("2026-10-30", "2026-11-02")).toBe(4);
  });
});

describe("FamilyAttendanceView", () => {
  it("shows a parent the chosen child's month and goes back a month", async () => {
    const user = userEvent.setup();
    const current = todayInIndia().slice(0, 7);
    renderAs(["dashboard.view", "child.view"], <FamilyAttendanceView />, ["PARENT"]);
    expect(await screen.findByTestId("family-month-percent")).toHaveTextContent("50%");
    expect(screen.getByTestId("family-month-grid")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Next month" })).toBeDisabled();
    expect(screen.getByTestId("family-month-absences")).not.toHaveTextContent("None");
    expect(m.child).toHaveBeenCalledWith("st1", current);

    await user.click(screen.getByRole("button", { name: "Previous month" }));
    await waitFor(() => expect(m.child).toHaveBeenCalledWith("st1", shiftMonth(current, -1)));
    expect(screen.getByRole("button", { name: "Next month" })).toBeEnabled();

    await user.click(screen.getByRole("radio", { name: "Diya Sharma" }));
    await waitFor(() => expect(m.child).toHaveBeenCalledWith("st2", current));
  });

  it("labels each day for screen readers", async () => {
    renderAs(["dashboard.view", "child.view"], <FamilyAttendanceView />, ["PARENT"]);
    const grid = await screen.findByTestId("family-month-grid");
    const current = todayInIndia().slice(0, 7);
    const sixth = within(grid).getAllByRole("listitem").find((li) => li.getAttribute("data-date") === `${current}-06`);
    expect(sixth).toHaveAttribute("aria-label", expect.stringContaining("Absent"));
  });

  it("shows a student their own month", async () => {
    renderAs(["dashboard.view"], <FamilyAttendanceView />, ["STUDENT"]);
    expect(await screen.findByTestId("family-month-percent")).toHaveTextContent("50%");
    expect(m.myAttendance).toHaveBeenCalled();
    expect(m.myChildren).not.toHaveBeenCalled();
  });
});
