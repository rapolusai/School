import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { StaffDayRow, StaffDaySheet, StaffMonthReport } from "@/lib/types";
import { renderAs } from "@/test/render";
import {
  changedEntries,
  draftOf,
  StaffAttendanceView,
  staffCsvFileName,
  validateEntries,
} from "./staff-attendance-view";

const { day, saveDay, month, monthCsv, departments, saveTextFile } = vi.hoisted(() => ({
  day: vi.fn(),
  saveDay: vi.fn(),
  month: vi.fn(),
  monthCsv: vi.fn(),
  departments: vi.fn(),
  saveTextFile: vi.fn(),
}));

vi.mock("@/lib/staff-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/staff-api")>();
  return {
    ...actual,
    staffApi: { ...actual.staffApi, departments },
    staffAttendanceApi: { ...actual.staffAttendanceApi, day, saveDay, month, monthCsv },
  };
});

vi.mock("@/lib/attendance-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/attendance-api")>();
  return { ...actual, saveTextFile };
});

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  usePathname: () => "/app/staff-attendance",
}));

const ADMIN = ["dashboard.view", "staff.read", "staff.manage", "staff_attendance.manage", "leave.request", "leave.approve"];
const PRINCIPAL = ["dashboard.view", "staff.read", "leave.request", "leave.approve"];

function row(overrides: Partial<StaffDayRow>): StaffDayRow {
  return {
    userId: "u1",
    name: "Ravi Kumar",
    employeeCode: "AKS-101",
    designation: "Primary teacher",
    departmentName: "Primary",
    onRoll: true,
    status: null,
    source: null,
    checkIn: null,
    checkOut: null,
    checkInAt: null,
    checkOutAt: null,
    checkInNote: null,
    checkOutNote: null,
    onLeaveRequest: false,
    markedByName: null,
    updatedByName: null,
    editedAt: null,
    ...overrides,
  };
}

function sheet(rows: StaffDayRow[], overrides: Partial<StaffDaySheet> = {}): StaffDaySheet {
  return {
    date: "2026-10-08",
    today: "2026-10-09",
    workingDay: true,
    canEdit: true,
    counts: { present: 1, halfDay: 0, absent: 0, onLeave: 1 },
    notMarked: 1,
    rows,
    ...overrides,
  };
}

const SHEET = sheet([
  row({}),
  row({
    userId: "u2",
    name: "Anjali Deshmukh",
    employeeCode: "AKS-102",
    status: "PRESENT",
    source: "SELF",
    checkIn: "08:31",
    checkInAt: "2026-10-08T03:01:00Z",
  }),
  row({ userId: "u3", name: "Meena Iyer", employeeCode: "AKS-002", status: "ON_LEAVE", source: "LEAVE", onLeaveRequest: true }),
]);

beforeEach(() => {
  day.mockResolvedValue(SHEET);
  departments.mockResolvedValue([{ id: "d1", name: "Science", head: null, staffCount: 3 }]);
});

describe("sheet helpers", () => {
  it("edits everyone except people on approved leave and sends only changes", () => {
    const base = draftOf(SHEET);
    expect(Object.keys(base)).toEqual(["u1", "u2"]);
    expect(base.u2).toEqual({ status: "PRESENT", checkIn: "08:31", checkOut: "" });
    expect(changedEntries(base, base)).toEqual([]);
    const draft = { ...base, u1: { status: "ABSENT" as const, checkIn: "", checkOut: "" }, u2: { ...base.u2, checkOut: "16:00" } };
    expect(changedEntries(base, draft)).toEqual([
      { userId: "u1", status: "ABSENT", checkIn: null, checkOut: null },
      { userId: "u2", status: "PRESENT", checkIn: "08:31", checkOut: "16:00" },
    ]);
  });

  it("checks times like the API", () => {
    const at = (checkIn: string | null, checkOut: string | null) => [{ userId: "u1", status: "PRESENT" as const, checkIn, checkOut }];
    expect(validateEntries(at("08:30", "16:00"), "2026-10-08", "2026-10-09", "10:00")).toEqual({});
    expect(validateEntries(at(null, "16:00"), "2026-10-08", "2026-10-09", "10:00")).toEqual({ u1: "staffAttendance.v.outWithoutIn" });
    expect(validateEntries(at("16:00", "08:00"), "2026-10-08", "2026-10-09", "10:00")).toEqual({ u1: "staffAttendance.v.outBeforeIn" });
    expect(validateEntries(at("08:30", "16:00"), "2026-10-09", "2026-10-09", "10:00")).toEqual({ u1: "staffAttendance.v.future" });
  });

  it("names the CSV after the month and department", () => {
    expect(staffCsvFileName("2026-10")).toBe("staff-attendance-2026-10.csv");
    expect(staffCsvFileName("2026-10", "Science & Maths")).toBe("staff-attendance-science-maths-2026-10.csv");
  });
});

describe("daily sheet", () => {
  it("marks and corrects, keeping approved leave locked", async () => {
    saveDay.mockResolvedValue({ sheet: SHEET, marked: 1, corrected: 1 });
    const user = userEvent.setup();
    renderAs(ADMIN, <StaffAttendanceView initialDate="2026-10-08" />);
    const list = await screen.findByTestId("staff-sheet");
    const rows = within(list).getAllByTestId("staff-sheet-row");
    expect(rows).toHaveLength(3);
    expect(rows[1]).toHaveTextContent("In 8:31 am · self check-in");
    expect(rows[2]).toHaveTextContent("On approved leave");
    expect(within(rows[2]).queryByRole("button", { name: "Present" })).not.toBeInTheDocument();
    expect(screen.getByTestId("staff-sheet-save")).toBeDisabled();

    await user.click(screen.getByRole("button", { name: "Mark the rest present" }));
    expect(within(rows[0]).getByRole("button", { name: "Present" })).toHaveAttribute("aria-pressed", "true");
    await user.type(within(rows[1]).getByLabelText("Check-out time of Anjali Deshmukh"), "16:00");
    await user.click(screen.getByTestId("staff-sheet-save"));

    await waitFor(() => expect(saveDay).toHaveBeenCalledTimes(1));
    expect(saveDay).toHaveBeenCalledWith("2026-10-08", {
      entries: [
        { userId: "u1", status: "PRESENT", checkIn: null, checkOut: null },
        { userId: "u2", status: "PRESENT", checkIn: "08:31", checkOut: "16:00" },
      ],
    });
    expect(await screen.findByText("1 person marked. 1 correction saved.")).toBeInTheDocument();
  });

  it("refuses a check-out without a check-in before sending", async () => {
    const user = userEvent.setup();
    renderAs(ADMIN, <StaffAttendanceView initialDate="2026-10-08" />);
    const rows = within(await screen.findByTestId("staff-sheet")).getAllByTestId("staff-sheet-row");
    await user.click(within(rows[0]).getByRole("button", { name: "Half day" }));
    await user.type(within(rows[0]).getByLabelText("Check-out time of Ravi Kumar"), "13:00");
    await user.click(screen.getByTestId("staff-sheet-save"));
    expect(saveDay).not.toHaveBeenCalled();
    expect(within(rows[0]).getByRole("alert")).toHaveTextContent("Enter the check-in time as well.");
  });

  it("is read-only without staff_attendance.manage", async () => {
    renderAs(PRINCIPAL, <StaffAttendanceView initialDate="2026-10-08" />, ["PRINCIPAL"]);
    const rows = within(await screen.findByTestId("staff-sheet")).getAllByTestId("staff-sheet-row");
    expect(within(rows[0]).queryByRole("button", { name: "Present" })).not.toBeInTheDocument();
    expect(rows[0]).toHaveTextContent("Not marked");
    expect(screen.queryByTestId("staff-sheet-save")).not.toBeInTheDocument();
    expect(screen.getByText("Only the School Admin can mark or correct staff attendance.")).toBeInTheDocument();
  });
});

describe("monthly report", () => {
  const REPORT: StaffMonthReport = {
    month: "2026-10",
    departmentId: null,
    workingDays: 2,
    days: [
      { date: "2026-10-03", workingDay: true, counts: { present: 1, halfDay: 0, absent: 0, onLeave: 1 } },
      { date: "2026-10-04", workingDay: false, counts: { present: 0, halfDay: 0, absent: 0, onLeave: 0 } },
    ],
    staff: [
      {
        userId: "u1",
        name: "Ravi Kumar",
        employeeCode: "AKS-101",
        departmentName: "Primary",
        active: true,
        marks: ["P", null],
        counts: { present: 1, halfDay: 0, absent: 0, onLeave: 0 },
        notMarked: 0,
        daysWorked: 1,
      },
      {
        userId: "u3",
        name: "Meena Iyer",
        employeeCode: "AKS-002",
        departmentName: null,
        active: true,
        marks: ["L", null],
        counts: { present: 0, halfDay: 0, absent: 0, onLeave: 1 },
        notMarked: 0,
        daysWorked: 0,
      },
    ],
    totals: { present: 1, halfDay: 0, absent: 0, onLeave: 1 },
  };

  it("shows staff by day and downloads the CSV", async () => {
    month.mockImplementation(async (m: string, departmentId?: string) => ({ ...REPORT, month: m, departmentId: departmentId ?? null }));
    monthCsv.mockResolvedValue("Employee code,Name\r\n");
    const user = userEvent.setup();
    renderAs(ADMIN, <StaffAttendanceView initialTab="month" />);
    const grid = await screen.findByTestId("staff-month-grid");
    const ravi = within(grid).getByText("Ravi Kumar").closest("tr") as HTMLElement;
    expect(within(ravi).getByTitle("Present")).toHaveTextContent("P");
    const meena = within(grid).getByText("Meena Iyer").closest("tr") as HTMLElement;
    expect(within(meena).getByTitle("On leave")).toHaveTextContent("L");

    await user.click(screen.getByTestId("staff-csv"));
    await waitFor(() => expect(saveTextFile).toHaveBeenCalledTimes(1));
    expect(saveTextFile.mock.calls[0][0]).toMatch(/^staff-attendance-\d{4}-\d{2}\.csv$/);
    expect(saveTextFile.mock.calls[0][1]).toBe("Employee code,Name\r\n");

    await user.selectOptions(await screen.findByRole("combobox", { name: "Department" }), "d1");
    await waitFor(() => expect(month).toHaveBeenLastCalledWith(expect.any(String), "d1"));
  });
});
