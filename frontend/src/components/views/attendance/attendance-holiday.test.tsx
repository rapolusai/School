import { render, screen, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { MonthRegister, RegisterView, SectionsForDay } from "@/lib/types";
import { renderAs } from "@/test/render";
import { MonthGrid } from "./attendance-reports-view";
import { AttendanceView } from "./attendance-view";

const { sections, register } = vi.hoisted(() => ({ sections: vi.fn(), register: vi.fn() }));

vi.mock("@/lib/attendance-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/attendance-api")>();
  return { ...actual, attendanceApi: { ...actual.attendanceApi, sections, register } };
});

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  usePathname: () => "/app/attendance",
}));

const TEACHER = ["dashboard.view", "students.read", "attendance.mark", "attendance.read", "academics.read"];
const NONE = { present: 0, absent: 0, late: 0, halfDay: 0, leave: 0 };

function holidayDay(date: string): SectionsForDay {
  return {
    date,
    today: date,
    academicYear: { id: "y1", name: "2026-27", startsOn: "2026-04-01", endsOn: "2027-03-31" },
    canMark: false,
    holiday: "Founders Day",
    sections: [
      {
        sectionId: "s5a",
        classId: "c5",
        className: "Class 5",
        sectionName: "A",
        label: "Class 5 A",
        classTeacherName: "Ravi Kumar",
        students: 1,
        marked: false,
        markedByName: null,
        markedAt: null,
        counts: NONE,
        presentPercent: null,
        canMark: false,
      },
    ],
  };
}

function holidayRegister(date: string): RegisterView {
  return {
    sectionId: "s5a",
    classId: "c5",
    className: "Class 5",
    sectionName: "A",
    label: "Class 5 A",
    date,
    academicYearName: "2026-27",
    marked: false,
    markedByName: null,
    markedAt: null,
    updatedByName: null,
    updatedAt: null,
    canEdit: false,
    counts: NONE,
    unmarked: 1,
    presentPercent: null,
    holiday: "Founders Day",
    entries: [{ studentId: "st1", fullName: "Asha Rao", admissionNo: "A-1", rollNo: 1, inSection: true, status: null }],
  };
}

beforeEach(() => {
  sections.mockImplementation((date: string) => Promise.resolve(holidayDay(date)));
  register.mockImplementation((_: string, date: string) => Promise.resolve(holidayRegister(date)));
});

describe("school holidays in attendance", () => {
  it("says the school is closed and offers nothing to mark", async () => {
    renderAs(TEACHER, <AttendanceView />, ["TEACHER"]);
    expect(await screen.findByTestId("attendance-holiday")).toHaveTextContent(
      "School holiday: Founders Day. Attendance is not marked on holidays.",
    );
    const asha = await screen.findByRole("group", { name: "Asha Rao" });
    expect(within(asha).getByRole("button", { name: "Present" })).toBeDisabled();
    expect(screen.queryByTestId("attendance-save")).not.toBeInTheDocument();
  });

  it("shades holidays in the month register and counts school days", () => {
    const days = Array.from({ length: 31 }, (_, i) => ({
      date: `2026-10-${String(i + 1).padStart(2, "0")}`,
      marked: i === 1,
      counts: i === 1 ? { ...NONE, present: 1 } : NONE,
      presentPercent: i === 1 ? 100 : null,
      holiday: i === 1 ? "Gandhi Jayanti" : null,
    }));
    const month: MonthRegister = {
      sectionId: "s5a",
      className: "Class 5",
      sectionName: "A",
      label: "Class 5 A",
      month: "2026-10",
      days,
      students: [
        {
          studentId: "st1",
          fullName: "Asha Rao",
          admissionNo: "A-1",
          rollNo: 1,
          inSection: true,
          marks: days.map((d) => (d.marked ? ("P" as const) : null)),
          daysMarked: 0,
          counts: NONE,
          presentPercent: null,
        },
      ],
      daysMarked: 0,
      counts: NONE,
      presentPercent: null,
      schoolDays: 25,
      holidays: 1,
    };
    render(<MonthGrid register={month} />);
    expect(screen.getByTestId("month-summary")).toHaveTextContent("School days: 25");
    expect(screen.getByTestId("month-summary")).toHaveTextContent("Holidays: 1");
    const header = screen.getByTitle("2 Oct 2026 · Gandhi Jayanti");
    expect(header).toHaveClass("is-holiday");
    // A mark made before the holiday was declared stays visible but is not counted in the day's totals.
    const grid = screen.getByTestId("month-grid");
    const presentRow = within(grid).getByRole("rowheader", { name: "Present (P+L+H)" }).closest("tr");
    expect(presentRow?.querySelectorAll("td")[1].textContent).toBe("");
  });
});
