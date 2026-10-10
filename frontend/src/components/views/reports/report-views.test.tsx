import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type {
  AbsenteesReport,
  ClassView,
  FunnelReport,
  HomeworkCompletionReport,
  LeaveTakenReport,
  SectionAttendanceReport,
} from "@/lib/types";
import { renderAs } from "@/test/render";
import { AbsenteesReportView } from "./absentees-report";
import { FunnelReportView } from "./funnel-report";
import { HomeworkReportView } from "./homework-report";
import { formatDays, LeaveReportView } from "./leave-report";
import { addDays, monthStart, rangeProblem } from "./report-frame";
import { SectionsReportView } from "./sections-report";

const { absentees, sections, homework, funnel, leave, downloadXlsx, listClasses, listSubjects, listYears, departments, types } =
  vi.hoisted(() => ({
    absentees: vi.fn(),
    sections: vi.fn(),
    homework: vi.fn(),
    funnel: vi.fn(),
    leave: vi.fn(),
    downloadXlsx: vi.fn(),
    listClasses: vi.fn(),
    listSubjects: vi.fn(),
    listYears: vi.fn(),
    departments: vi.fn(),
    types: vi.fn(),
  }));

vi.mock("@/lib/reports-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/reports-api")>();
  return {
    ...actual,
    downloadXlsx,
    reportsApi: { ...actual.reportsApi, absentees, sections, homework, funnel, leave },
  };
});

vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return { ...actual, api: { ...actual.api, listClasses, listSubjects, listYears } };
});

vi.mock("@/lib/staff-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/staff-api")>();
  return {
    ...actual,
    staffApi: { ...actual.staffApi, departments },
    leaveApi: { ...actual.leaveApi, types },
  };
});

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  usePathname: () => "/app/reports",
}));


const CLASSES: ClassView[] = [
  { id: "c5", name: "Class 5", displayOrder: 5, sections: [], subjects: [] },
  { id: "c6", name: "Class 6", displayOrder: 6, sections: [], subjects: [] },
];

function sectionsReport(from: string, to: string, classId: string | null = null): SectionAttendanceReport {
  return {
    from,
    to,
    classId,
    schoolDays: 7,
    holidays: 1,
    students: 3,
    daysMarked: 7,
    counts: { present: 18, absent: 2, late: 1, halfDay: 0, leave: 0 },
    presentPercent: 90.5,
    classes: [
      {
        classId: "c5",
        className: "Class 5",
        students: 3,
        counts: { present: 18, absent: 2, late: 1, halfDay: 0, leave: 0 },
        presentPercent: 90.5,
        sections: [
          {
            sectionId: "s5a",
            classId: "c5",
            className: "Class 5",
            sectionName: "A",
            label: "Class 5 A",
            students: 2,
            daysMarked: 7,
            counts: { present: 12, absent: 1, late: 1, halfDay: 0, leave: 0 },
            presentPercent: 92.9,
          },
          {
            sectionId: "s5b",
            classId: "c5",
            className: "Class 5",
            sectionName: "B",
            label: "Class 5 B",
            students: 1,
            daysMarked: 7,
            counts: { present: 6, absent: 1, late: 0, halfDay: 0, leave: 0 },
            presentPercent: 85.7,
          },
        ],
      },
    ],
  };
}

const PRINCIPAL = [
  "dashboard.view",
  "academics.read",
  "attendance.read",
  "admissions.read",
  "staff.read",
  "homework.manage",
];

beforeEach(() => {
  vi.useFakeTimers({ toFake: ["Date"] });
  vi.setSystemTime(new Date("2026-10-09T06:00:00Z"));
  for (const mock of [absentees, sections, homework, funnel, leave, downloadXlsx, listClasses, listSubjects, listYears, departments, types]) {
    mock.mockReset();
  }
  listClasses.mockResolvedValue(CLASSES);
  listSubjects.mockResolvedValue([
    { id: "m", name: "Mathematics", code: null, classCount: 2 },
    { id: "e", name: "English", code: null, classCount: 2 },
  ]);
  listYears.mockResolvedValue([
    { id: "y1", name: "2026-27", startsOn: "2026-06-01", endsOn: "2027-03-31", current: true },
    { id: "y0", name: "2025-26", startsOn: "2025-06-01", endsOn: "2026-03-31", current: false },
  ]);
  departments.mockResolvedValue([{ id: "d1", name: "Primary", head: null, staffCount: 2 }]);
  types.mockResolvedValue([]);
});

afterEach(() => {
  vi.useRealTimers();
});

describe("date helpers", () => {
  it("checks a date range the way the API does", () => {
    expect(rangeProblem("2026-10-01", "2026-10-09")).toBeNull();
    expect(rangeProblem("2026-10-09", "2026-10-09")).toBeNull();
    expect(rangeProblem("2026-10-09", "2026-10-01")).toBe("reports.v.order");
    expect(rangeProblem("", "2026-10-01")).toBe("reports.v.dates");
    expect(rangeProblem("2025-09-03", "2026-10-08")).toBe("reports.v.long");
    expect(rangeProblem("2025-09-04", "2026-10-08")).toBeNull();
  });

  it("moves dates and finds the month's first day", () => {
    expect(addDays("2026-10-09", -30)).toBe("2026-09-09");
    expect(addDays("2026-12-31", 1)).toBe("2027-01-01");
    expect(monthStart("2026-10-09")).toBe("2026-10-01");
    expect(formatDays(1.5)).toBe("1.5");
    expect(formatDays(2)).toBe("2");
  });
});

describe("Attendance by class and section", () => {
  it("shows this month by default with a row per section and the school total", async () => {
    sections.mockImplementation(async (p: { from: string; to: string }) => sectionsReport(p.from, p.to));
    renderAs(PRINCIPAL, <SectionsReportView />, ["PRINCIPAL"]);
    const table = await screen.findByTestId("sections-table");
    expect(sections).toHaveBeenCalledWith({ from: "2026-10-01", to: "2026-10-09", classId: undefined });
    expect(within(table).getAllByTestId("section-row").map((row) => row.textContent)).toEqual([
      "Class 5 A27121010" + "92.9%",
      "Class 5 B1760010" + "85.7%",
    ]);
    expect(within(table).getAllByRole("row").at(-1)).toHaveTextContent("Total37181020" + "90.5%");
    expect(screen.getByTestId("overall-percent")).toHaveTextContent("90.5%");
    // The print header names the report, the filters and who printed it.
    const head = screen.getByTestId("report-print-head");
    expect(head).toHaveTextContent("Attendance by class and section");
    expect(head).toHaveTextContent("1 Oct 2026 to 9 Oct 2026 · All classes");
    expect(head).toHaveTextContent("by Priya Nair");
  });

  it("filters by class and refuses an end date before the start", async () => {
    sections.mockImplementation(async (p: { from: string; to: string; classId?: string }) =>
      sectionsReport(p.from, p.to, p.classId ?? null),
    );
    renderAs(PRINCIPAL, <SectionsReportView />, ["PRINCIPAL"]);
    await screen.findByTestId("sections-table");
    await screen.findByRole("option", { name: "Class 6" });
    fireEvent.change(screen.getByLabelText("Class"), { target: { value: "c6" } });
    await waitFor(() =>
      expect(sections).toHaveBeenLastCalledWith({ from: "2026-10-01", to: "2026-10-09", classId: "c6" }),
    );
    expect(await screen.findByTestId("report-print-head")).toHaveTextContent("Class: Class 6");

    const calls = sections.mock.calls.length;
    fireEvent.change(screen.getByLabelText("From"), { target: { value: "2026-10-12" } });
    expect(screen.getByRole("alert")).toHaveTextContent("The end date must not be before the start date.");
    expect(screen.getByRole("button", { name: "Download Excel" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Print or save as PDF" })).toBeDisabled();
    expect(sections).toHaveBeenCalledTimes(calls);
  });

  it("downloads the Excel file for the chosen filters and prints", async () => {
    sections.mockImplementation(async (p: { from: string; to: string }) => sectionsReport(p.from, p.to));
    downloadXlsx.mockResolvedValue("attendance-by-section-2026-10-01-to-2026-10-09.xlsx");
    const print = vi.spyOn(window, "print").mockImplementation(() => {});
    const user = userEvent.setup();
    renderAs(PRINCIPAL, <SectionsReportView />, ["PRINCIPAL"]);
    await screen.findByTestId("sections-table");

    await user.click(screen.getByRole("button", { name: "Download Excel" }));
    expect(downloadXlsx).toHaveBeenCalledWith(
      "/api/reports/attendance/sections.xlsx?from=2026-10-01&to=2026-10-09",
      "attendance-by-section-2026-10-01-to-2026-10-09.xlsx",
    );
    expect(await screen.findByText("Downloaded attendance-by-section-2026-10-01-to-2026-10-09.xlsx")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Print or save as PDF" }));
    expect(print).toHaveBeenCalledTimes(1);
  });

  it("shows why a download failed", async () => {
    sections.mockImplementation(async (p: { from: string; to: string }) => sectionsReport(p.from, p.to));
    const { ApiError } = await import("@/lib/api");
    downloadXlsx.mockRejectedValue(new ApiError({ status: 403, title: "Forbidden" }));
    const user = userEvent.setup();
    renderAs(PRINCIPAL, <SectionsReportView />, ["PRINCIPAL"]);
    await screen.findByTestId("sections-table");
    await user.click(screen.getByRole("button", { name: "Download Excel" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("You don't have permission");
  });
});

describe("Daily absentees", () => {
  const REPORT: AbsenteesReport = {
    date: "2026-10-09",
    holiday: null,
    classId: null,
    includeLeave: false,
    sectionCount: 3,
    sectionsMarked: 2,
    absent: 1,
    onLeave: 0,
    rows: [
      {
        studentId: "st2",
        fullName: "Bala Krishnan",
        admissionNo: "A-2",
        rollNo: 2,
        classId: "c5",
        className: "Class 5",
        sectionId: "s5a",
        sectionName: "A",
        sectionLabel: "Class 5 A",
        status: "ABSENT",
        daysInARow: 3,
        markedByName: "Ravi Kumar",
      },
    ],
  };

  it("lists today's absentees with how many days in a row", async () => {
    absentees.mockResolvedValue(REPORT);
    renderAs(PRINCIPAL, <AbsenteesReportView />, ["PRINCIPAL"]);
    const table = await screen.findByTestId("absentees-table");
    expect(absentees).toHaveBeenCalledWith({ date: "2026-10-09", classId: undefined, includeLeave: false });
    expect(within(table).getAllByRole("row")[1]).toHaveTextContent("Bala KrishnanA-2Class 5 A2Absent3Ravi Kumar");
    expect(screen.getByTestId("absentees-cards")).toHaveTextContent("3 days in a row");
    expect(screen.getByTestId("absent-count")).toHaveTextContent("1");
  });

  it("does not ask for a future day", async () => {
    absentees.mockResolvedValue(REPORT);
    renderAs(PRINCIPAL, <AbsenteesReportView />, ["PRINCIPAL"]);
    await screen.findByTestId("absentees-table");
    fireEvent.change(screen.getByLabelText("Date"), { target: { value: "2026-10-10" } });
    expect(screen.getByRole("alert")).toHaveTextContent("Choose today or an earlier day.");
    expect(absentees).toHaveBeenCalledTimes(1);
  });

  it("says when no one was absent", async () => {
    absentees.mockResolvedValue({ ...REPORT, absent: 0, rows: [] });
    renderAs(PRINCIPAL, <AbsenteesReportView />, ["PRINCIPAL"]);
    expect(await screen.findByText("No one was absent on 9 Oct 2026.")).toBeInTheDocument();
  });
});

describe("Homework completion", () => {
  it("shows each section and subject with the total", async () => {
    const line = {
      sectionId: "s5a",
      sectionLabel: "Class 5 A",
      classId: "c5",
      className: "Class 5",
      subjectId: "m",
      subjectName: "Mathematics",
      homework: 2,
      online: 1,
      expected: 3,
      submitted: 2,
      late: 1,
      reviewed: 1,
      needsRedo: 0,
      waiting: 1,
      completionPercent: 66.7,
    };
    homework.mockImplementation(
      async (p: { from: string; to: string }): Promise<HomeworkCompletionReport> => ({
        from: p.from,
        to: p.to,
        classId: null,
        subjectId: null,
        rows: [line],
        total: { ...line, sectionId: null, sectionLabel: null, classId: null, className: null, subjectId: null, subjectName: null },
      }),
    );
    renderAs(PRINCIPAL, <HomeworkReportView />, ["PRINCIPAL"]);
    const table = await screen.findByTestId("homework-table");
    expect(homework).toHaveBeenCalledWith({ from: "2026-09-09", to: "2026-10-09", classId: undefined, subjectId: undefined });
    expect(within(table).getAllByRole("row")[1]).toHaveTextContent("Class 5 AMathematics2321101" + "66.7%");
    expect(screen.getByTestId("completion-percent")).toHaveTextContent("66.7%");
    expect(screen.getByTestId("report-print-head")).toHaveTextContent("Due 9 Sept 2026 to 9 Oct 2026 · All classes · All subjects");
  });
});

describe("Admissions funnel", () => {
  it("shows the stages and conversion by class and source", async () => {
    funnel.mockImplementation(
      async (): Promise<FunnelReport> => ({
        academicYearId: null,
        academicYearName: null,
        classId: null,
        source: null,
        from: null,
        to: null,
        funnel: {
          total: 4,
          open: 2,
          rejected: 1,
          withdrawn: 0,
          conversionPercent: 25,
          stages: [
            { stage: "ENQUIRY", reached: 4, current: 1, fromPrevious: null, fromEnquiry: 100 },
            { stage: "APPLICATION", reached: 3, current: 1, fromPrevious: 75, fromEnquiry: 75 },
            { stage: "ADMITTED", reached: 1, current: 1, fromPrevious: 33.3, fromEnquiry: 25 },
          ],
          byClass: [
            {
              classId: "c5",
              className: "Class 5",
              source: null,
              total: 4,
              applied: 3,
              assessed: 2,
              offered: 1,
              admitted: 1,
              rejected: 1,
              withdrawn: 0,
              conversionPercent: 25,
            },
          ],
          bySource: [
            {
              classId: null,
              className: null,
              source: "WALK_IN",
              total: 4,
              applied: 3,
              assessed: 2,
              offered: 1,
              admitted: 1,
              rejected: 1,
              withdrawn: 0,
              conversionPercent: 25,
            },
          ],
        },
      }),
    );
    renderAs(PRINCIPAL, <FunnelReportView />, ["PRINCIPAL"]);
    expect(await screen.findByTestId("funnel-total")).toHaveTextContent("4");
    expect(funnel).toHaveBeenCalledWith({ yearId: undefined, classId: undefined, source: undefined, from: undefined, to: undefined });
    expect(screen.getByTestId("funnel-chart")).toHaveTextContent("75% of the stage before");
    expect(within(screen.getByTestId("funnel-by-class")).getAllByRole("row")[1]).toHaveTextContent("Class 54321110" + "25%");
    expect(within(screen.getByTestId("funnel-by-source")).getAllByRole("row")[1]).toHaveTextContent("Walk-in");
    expect(screen.getByTestId("report-print-head")).toHaveTextContent("All years · All classes · All sources");
  });
});

describe("Leave taken", () => {
  it("shows a column per leave type with half days", async () => {
    leave.mockImplementation(
      async (): Promise<LeaveTakenReport> => ({
        academicYearId: "y1",
        academicYearName: "2026-27",
        departmentId: null,
        leaveTypeId: null,
        types: [
          { id: "cl", name: "Casual leave", code: "CL", lossOfPay: false, active: true },
          { id: "lwp", name: "Leave without pay", code: "LWP", lossOfPay: true, active: true },
        ],
        staff: [
          {
            userId: "u1",
            name: "Ravi Kumar",
            employeeCode: "T-01",
            departmentId: "d1",
            departmentName: "Primary",
            active: true,
            days: [1.5, 1],
            total: 2.5,
            lossOfPay: 1,
            pending: 2,
          },
        ],
        typeTotals: [1.5, 1],
        total: 2.5,
        lossOfPay: 1,
        pending: 2,
      }),
    );
    renderAs(PRINCIPAL, <LeaveReportView />, ["PRINCIPAL"]);
    const table = await screen.findByTestId("leave-table");
    expect(within(table).getAllByRole("columnheader").map((th) => th.textContent)).toEqual([
      "Staff member",
      "Department",
      "CL",
      "LWP",
      "Total",
      "Loss of pay",
      "Pending",
    ]);
    expect(within(table).getAllByRole("row")[1]).toHaveTextContent("Ravi KumarT-01Primary1.512.512");
    expect(screen.getByTestId("leave-total")).toHaveTextContent("2.5");
    expect(screen.getByTestId("report-print-head")).toHaveTextContent("Year: 2026-27 · All departments · All leave types");
  });
});
