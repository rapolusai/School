import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { MonthRegister, SectionsForDay } from "@/lib/types";
import { renderAs } from "@/test/render";
import { AttendanceReportsView, csvFileName } from "./attendance-reports-view";

const { sections, month, monthCsv, studentSummary, saveTextFile } = vi.hoisted(() => ({
  sections: vi.fn(),
  month: vi.fn(),
  monthCsv: vi.fn(),
  studentSummary: vi.fn(),
  saveTextFile: vi.fn(),
}));

vi.mock("@/lib/attendance-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/attendance-api")>();
  return {
    ...actual,
    saveTextFile,
    attendanceApi: { ...actual.attendanceApi, sections, month, monthCsv, studentSummary },
  };
});

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  usePathname: () => "/app/attendance/reports",
}));

const READ = ["dashboard.view", "attendance.read", "attendance.manage"];
const NONE = { present: 0, absent: 0, late: 0, halfDay: 0, leave: 0 };

const SECTIONS: SectionsForDay = {
  date: "2026-10-09",
  today: "2026-10-09",
  academicYear: { id: "y1", name: "2026-27", startsOn: "2026-06-01", endsOn: "2027-03-31" },
  canMark: true,
  sections: [
    {
      sectionId: "s5a",
      classId: "c5",
      className: "Class 5",
      sectionName: "A",
      label: "Class 5 A",
      classTeacherName: "Ravi Kumar",
      students: 2,
      marked: false,
      markedByName: null,
      markedAt: null,
      counts: NONE,
      presentPercent: null,
      canMark: true,
    },
  ],
};

function monthRegister(monthValue: string): MonthRegister {
  const days = Array.from({ length: 30 }, (_, i) => {
    const date = `${monthValue}-${String(i + 1).padStart(2, "0")}`;
    if (i === 0) return { date, marked: true, counts: { ...NONE, present: 1, absent: 1 }, presentPercent: 50 };
    if (i === 1) return { date, marked: true, counts: { ...NONE, late: 1, halfDay: 1 }, presentPercent: 75 };
    return { date, marked: false, counts: NONE, presentPercent: null };
  });
  const marks = (first: "P" | "A" | "L" | "H", second: "P" | "A" | "L" | "H") =>
    [first, second, ...Array.from({ length: 28 }, () => null)] as MonthRegister["students"][number]["marks"];
  return {
    sectionId: "s5a",
    className: "Class 5",
    sectionName: "A",
    label: "Class 5 A",
    month: monthValue,
    days,
    students: [
      {
        studentId: "st1",
        fullName: "Asha Rao",
        admissionNo: "A-1",
        rollNo: 1,
        inSection: true,
        marks: marks("P", "H"),
        daysMarked: 2,
        counts: { ...NONE, present: 1, halfDay: 1 },
        presentPercent: 75,
      },
      {
        studentId: "st2",
        fullName: "Bala Iyer",
        admissionNo: "A-2",
        rollNo: 2,
        inSection: false,
        marks: marks("A", "L"),
        daysMarked: 2,
        counts: { ...NONE, absent: 1, late: 1 },
        presentPercent: 50,
      },
    ],
    daysMarked: 2,
    counts: { present: 1, absent: 1, late: 1, halfDay: 1, leave: 0 },
    presentPercent: 62.5,
  };
}

beforeEach(() => {
  sections.mockResolvedValue(SECTIONS);
  month.mockImplementation((_sectionId: string, value: string) => Promise.resolve(monthRegister(value)));
  monthCsv.mockResolvedValue("Roll no,Admission no,Student\r\n");
  studentSummary.mockResolvedValue({
    studentId: "st1",
    fullName: "Asha Rao",
    admissionNo: "A-1",
    className: "Class 5",
    sectionName: "A",
    from: "2026-06-01",
    to: "2026-10-09",
    daysMarked: 80,
    counts: { present: 70, absent: 4, late: 3, halfDay: 2, leave: 1 },
    presentPercent: 97.5,
    absences: ["2026-07-14", "2026-09-02"],
    days: [],
  });
});

describe("AttendanceReportsView", () => {
  it("shows the month register with marks, percentages and day totals", async () => {
    renderAs(READ, <AttendanceReportsView />);
    const grid = await screen.findByTestId("month-grid");
    const rows = within(grid).getAllByRole("row");
    // Header, two students, three total rows.
    expect(rows).toHaveLength(6);
    const asha = within(grid).getByRole("rowheader", { name: /Asha Rao/ }).closest("tr") as HTMLElement;
    expect(within(asha).getByTitle("Present")).toHaveTextContent("P");
    expect(within(asha).getByTitle("Half day")).toHaveTextContent("H");
    expect(asha).toHaveTextContent("75%");
    const bala = within(grid).getByRole("rowheader", { name: /Bala Iyer/ });
    expect(bala).toHaveTextContent("Left the section");
    const present = within(grid).getByRole("rowheader", { name: "Present (P+L+H)" }).closest("tr") as HTMLElement;
    expect(within(present).getAllByRole("cell")[0]).toHaveTextContent("1");
    expect(within(present).getAllByRole("cell")[1]).toHaveTextContent("2");
    expect(screen.getByTestId("month-summary")).toHaveTextContent("62.5%");
    // The grid sits in its own horizontal scroller so the page itself never scrolls sideways.
    expect(screen.getByTestId("month-grid-scroll")).toHaveClass("table-wrap");
  });

  it("loads another month and downloads the CSV", async () => {
    const user = userEvent.setup();
    renderAs(READ, <AttendanceReportsView />);
    await screen.findByTestId("month-grid");
    // jsdom has no month picker, so set the value the way the browser reports a pick.
    fireEvent.change(screen.getByLabelText("Month"), { target: { value: "2026-08" } });
    await waitFor(() => expect(month).toHaveBeenLastCalledWith("s5a", "2026-08"));
    await screen.findByText(/Attendance register of Class 5 A for 2026-08/);

    await user.click(screen.getByRole("button", { name: "Download CSV" }));
    await waitFor(() => expect(monthCsv).toHaveBeenCalledWith("s5a", "2026-08"));
    expect(saveTextFile).toHaveBeenCalledWith("attendance-class-5-a-2026-08.csv", "Roll no,Admission no,Student\r\n");
  });

  it("summarises one student", async () => {
    const user = userEvent.setup();
    renderAs(READ, <AttendanceReportsView />);
    await user.click(await screen.findByRole("tab", { name: "Student summary" }));
    const summary = await screen.findByTestId("student-summary");
    expect(within(summary).getByRole("heading", { name: "Asha Rao" })).toBeInTheDocument();
    expect(summary).toHaveTextContent("97.5%");
    expect(summary).toHaveTextContent("14 Jul 2026");
    expect(studentSummary).toHaveBeenCalledWith("st1", undefined, undefined);
  });

  it("names CSV files after the section and month", () => {
    expect(csvFileName("Class 5 A", "2026-10")).toBe("attendance-class-5-a-2026-10.csv");
    expect(csvFileName("UKG / Rose", "2026-07")).toBe("attendance-ukg-rose-2026-07.csv");
  });
});
