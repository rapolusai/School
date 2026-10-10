import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api";
import { CHILD_STORAGE_KEY } from "@/lib/family";
import type {
  BoardItem,
  BoardPage,
  Child,
  ChildAttendance,
  ChildLeave,
  FamilyLeave,
  FamilyTimetable,
  StudentHomework,
  StudentHomeworkRow,
} from "@/lib/types";
import { ARJUN_FEES } from "@/test/fees-fixtures";
import { renderAs } from "@/test/render";
import { DashboardView } from "../dashboard-view";
import { FamilyHome } from "./family-home";
import { todayState } from "./family-shared";

const m = vi.hoisted(() => ({
  myChildren: vi.fn(),
  myStudentRecord: vi.fn(),
  child: vi.fn(),
  myAttendance: vi.fn(),
  childLeave: vi.fn(),
  myLeave: vi.fn(),
  childTimetable: vi.fn(),
  myTimetable: vi.fn(),
  homeworkChild: vi.fn(),
  homeworkMine: vi.fn(),
  childFees: vi.fn(),
  boardPage: vi.fn(),
  upcoming: vi.fn(),
  markRead: vi.fn(),
}));

vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return { ...actual, api: { ...actual.api, myChildren: m.myChildren, myStudentRecord: m.myStudentRecord } };
});
vi.mock("@/lib/attendance-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/attendance-api")>();
  return { ...actual, attendanceApi: { ...actual.attendanceApi, child: m.child } };
});
vi.mock("@/lib/portal-api", () => ({
  portalApi: { myAttendance: m.myAttendance, childLeave: m.childLeave, myLeave: m.myLeave },
}));
vi.mock("@/lib/timetable-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/timetable-api")>();
  return {
    ...actual,
    timetableApi: { ...actual.timetableApi, childTimetable: m.childTimetable, myTimetable: m.myTimetable },
  };
});
vi.mock("@/lib/homework-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/homework-api")>();
  return { ...actual, homeworkApi: { ...actual.homeworkApi, child: m.homeworkChild, mine: m.homeworkMine } };
});
vi.mock("@/lib/fees-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/fees-api")>();
  return { ...actual, feesApi: { ...actual.feesApi, childFees: m.childFees } };
});
vi.mock("@/lib/communication-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/communication-api")>();
  return {
    ...actual,
    boardApi: { ...actual.boardApi, page: m.boardPage, markRead: m.markRead },
    calendarApi: { ...actual.calendarApi, upcoming: m.upcoming },
  };
});
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  usePathname: () => "/app/dashboard",
}));

const PARENT = ["dashboard.view", "child.view", "notices.read"];
const STUDENT = ["dashboard.view", "notices.read"];
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
const DIYA: Child = { ...ARJUN, id: "st2", fullName: "Diya Sharma", className: "Class 2", rollNo: 4, classTeacherName: "Sita Devi" };

function attendance(studentId: string, overrides: Partial<ChildAttendance> = {}): ChildAttendance {
  return {
    studentId,
    fullName: studentId === "st1" ? "Arjun Sharma" : "Diya Sharma",
    month: "2026-10",
    daysMarked: 7,
    counts: { ...NONE, present: 5, absent: 1, halfDay: 1 },
    presentPercent: 78.6,
    days: [
      { date: "2026-10-07", status: "ABSENT" },
      { date: "2026-10-08", status: "PRESENT" },
    ],
    recentAbsences: [{ date: "2026-10-07", status: "ABSENT" }],
    today: "2026-10-09",
    todayStatus: studentId === "st1" ? "PRESENT" : null,
    todayHoliday: null,
    todayLeave: studentId === "st2" ? { requestId: "l9", halfDay: false, prefill: "LEAVE" } : null,
    holidays: [],
    leaveDays: [],
    ...overrides,
  };
}

function row(overrides: Partial<StudentHomeworkRow>): StudentHomeworkRow {
  return {
    id: "h1",
    title: "Fractions worksheet",
    subjectId: "sub1",
    subjectName: "Mathematics",
    assignedOn: "2026-10-05",
    dueOn: "2026-10-12",
    onlineSubmission: true,
    attachments: 0,
    status: "PENDING",
    late: false,
    overdue: false,
    submittedAt: null,
    grade: null,
    ...overrides,
  };
}

function homework(studentId: string): StudentHomework {
  return {
    studentId,
    studentName: "Arjun Sharma",
    sectionId: "s5a",
    sectionLabel: "Class 5 A",
    today: "2026-10-09",
    items: [
      row({}),
      row({ id: "h2", title: "Leaf collection", subjectName: "EVS", status: "REVIEWED", grade: "A", submittedAt: "2026-10-03T05:00:00Z", dueOn: "2026-10-04" }),
    ],
  };
}

function timetable(studentId: string): FamilyTimetable {
  return {
    studentId,
    studentName: "Arjun Sharma",
    sectionId: "s5a",
    sectionLabel: "Class 5 A",
    academicYear: { id: "y1", name: "2026-27", startsOn: "2026-06-01", endsOn: "2027-03-31" },
    bells: { workingDays: ["MONDAY"], saturdaySchedule: false, weekday: [], saturday: [], weekdayPeriods: 1, saturdayPeriods: 0 },
    slots: [],
    today: "2026-10-09",
    todayDay: "FRIDAY",
    workingDay: true,
    todayPeriods: [
      {
        number: 1,
        label: "Period 1",
        startsAt: "08:30",
        endsAt: "09:10",
        breakTime: false,
        entries: [
          {
            kind: "CLASS",
            sectionId: "s5a",
            sectionLabel: "Class 5 A",
            subjectId: "sub1",
            subjectName: "Mathematics",
            teacherName: "Ravi Kumar",
            room: null,
            substituteName: null,
            absentTeacherName: null,
          },
        ],
      },
    ],
  };
}

function leaveRequest(overrides: Partial<ChildLeave> = {}): ChildLeave {
  return {
    id: "l1",
    studentId: "st1",
    studentName: "Arjun Sharma",
    admissionNo: "AKS/2026/001",
    rollNo: 1,
    sectionId: "s5a",
    sectionLabel: "Class 5 A",
    fromDate: "2026-10-14",
    toDate: "2026-10-14",
    halfDay: true,
    schoolDays: 1,
    reason: "Dentist appointment",
    status: "PENDING",
    requestedByName: "Anitha Sharma",
    createdAt: "2026-10-09T04:00:00Z",
    decidedByName: null,
    decidedAt: null,
    decisionComment: null,
    cancelledByName: null,
    cancelledAt: null,
    canCancel: true,
    canDecide: false,
    ...overrides,
  };
}

function family(studentId: string, requests: ChildLeave[], canApply = true): FamilyLeave {
  return {
    studentId,
    studentName: studentId === "st1" ? "Arjun Sharma" : "Diya Sharma",
    sectionLabel: "Class 5 A",
    classTeacherName: "Ravi Kumar",
    today: "2026-10-09",
    earliest: "2026-09-09",
    latest: "2027-03-31",
    canApply,
    requests,
  };
}

function notice(id: string, title: string, read: boolean): BoardItem {
  return {
    id,
    title,
    body: "Body",
    category: "GENERAL",
    sentAt: "2026-10-08T04:00:00Z",
    sentByName: "Lakshmi Iyer",
    calendarReminder: false,
    pinned: false,
    read,
    readAt: read ? "2026-10-08T05:00:00Z" : null,
  };
}

const BOARD: BoardPage = {
  items: [notice("n1", "Sports day kit", true), notice("n2", "Fee reminder", true), notice("n3", "PTM on Saturday", false)],
  page: 0,
  size: 10,
  total: 3,
  unread: 1,
};

beforeEach(() => {
  window.localStorage.clear();
  m.myChildren.mockResolvedValue([ARJUN, DIYA]);
  m.myStudentRecord.mockResolvedValue(ARJUN);
  m.child.mockImplementation((id: string) => Promise.resolve(attendance(id)));
  m.myAttendance.mockResolvedValue(attendance("st1"));
  m.childLeave.mockImplementation((id: string) => Promise.resolve(family(id, id === "st1" ? [leaveRequest()] : [])));
  m.myLeave.mockResolvedValue(family("st1", [leaveRequest({ canCancel: false })], false));
  m.childTimetable.mockImplementation((id: string) => Promise.resolve(timetable(id)));
  m.myTimetable.mockResolvedValue(timetable("st1"));
  m.homeworkChild.mockImplementation((id: string) => Promise.resolve(homework(id)));
  m.homeworkMine.mockResolvedValue(homework("st1"));
  m.childFees.mockImplementation((id: string) =>
    Promise.resolve(id === "st1" ? ARJUN_FEES : { ...ARJUN_FEES, instalments: [], receipts: [] }),
  );
  m.boardPage.mockResolvedValue(BOARD);
  m.upcoming.mockResolvedValue([]);
});

afterEach(() => {
  window.localStorage.clear();
});

describe("FamilyHome for a parent", () => {
  it("shows the first child with today, fees and a way to pay", async () => {
    renderAs(PARENT, <FamilyHome />, ["PARENT"]);
    const card = await screen.findByRole("article", { name: "Arjun Sharma" });
    expect(card).toHaveAttribute("data-testid", "child-card");
    expect(within(card).getByText("Class 5 A")).toBeInTheDocument();
    expect(await within(card).findByText("Present today")).toBeInTheDocument();
    const fees = within(card).getByTestId("child-fees");
    expect(await within(fees).findByText("₹11,600 overdue")).toBeInTheDocument();
    expect(within(fees).getByRole("link", { name: "Pay fees" })).toHaveAttribute("href", "/app/children/st1/fees");
    expect(screen.getByRole("heading", { name: "My children" })).toBeInTheDocument();
  });

  it("shows today's timetable, this month, homework due and reviewed, and leave", async () => {
    renderAs(PARENT, <FamilyHome />, ["PARENT"]);
    const today = await screen.findByTestId("family-today");
    expect(await within(today).findByText("Mathematics")).toBeInTheDocument();
    const month = screen.getByTestId("child-attendance");
    expect(await within(month).findByText("78.6%")).toBeInTheDocument();
    expect(month).toHaveTextContent("Absent this month: 7 Oct 2026");
    expect(within(month).getByRole("link", { name: /Attendance by day/ })).toHaveAttribute(
      "href",
      "/app/family/attendance?child=st1",
    );
    const homeworkCard = screen.getByTestId("child-homework-due");
    expect(await within(homeworkCard).findByText("1 to hand in")).toBeInTheDocument();
    expect(within(homeworkCard).getByRole("link", { name: /Fractions worksheet/ })).toHaveAttribute(
      "href",
      "/app/homework/h1?child=st1",
    );
    expect(within(within(homeworkCard).getByTestId("homework-reviewed")).getByText("Leaf collection")).toBeInTheDocument();
    const leave = screen.getByTestId("family-leave-card");
    expect(await within(leave).findByText("14 Oct 2026 (half day)")).toBeInTheDocument();
    expect(within(leave).getByText("Waiting")).toBeInTheDocument();
    expect(within(leave).getByRole("link", { name: /Apply for leave/ })).toHaveAttribute(
      "href",
      "/app/family/leave?child=st1",
    );
  });

  it("switches child and remembers the choice on this device", async () => {
    const user = userEvent.setup();
    renderAs(PARENT, <FamilyHome />, ["PARENT"]);
    await screen.findByRole("article", { name: "Arjun Sharma" });
    await user.click(screen.getByRole("radio", { name: "Diya Sharma" }));
    const diya = await screen.findByRole("article", { name: "Diya Sharma" });
    expect(screen.queryByRole("article", { name: "Arjun Sharma" })).not.toBeInTheDocument();
    expect(await within(diya).findByText("On approved leave today")).toBeInTheDocument();
    expect(await within(diya).findByText("No fees have been set yet.")).toBeInTheDocument();
    expect(window.localStorage.getItem(CHILD_STORAGE_KEY)).toBe("st2");
    expect(m.child).toHaveBeenCalledWith("st2");
    expect(m.homeworkChild).toHaveBeenCalledWith("st2");
  });

  it("opens on the child chosen last time", async () => {
    window.localStorage.setItem(CHILD_STORAGE_KEY, "st2");
    renderAs(PARENT, <FamilyHome />, ["PARENT"]);
    expect(await screen.findByRole("article", { name: "Diya Sharma" })).toBeInTheDocument();
    expect(screen.getByRole("radio", { name: "Diya Sharma" })).toBeChecked();
  });

  it("lists unread notices first", async () => {
    renderAs(PARENT, <FamilyHome />, ["PARENT"]);
    const card = await screen.findByTestId("notices-card");
    await waitFor(() => expect(within(card).getAllByRole("listitem")).toHaveLength(3));
    const items = within(card).getAllByRole("listitem");
    expect(items[0]).toHaveTextContent("PTM on Saturday");
    expect(items[0]).toHaveAttribute("data-unread", "true");
    expect(within(card).getByText("1 unread")).toBeInTheDocument();
    expect(await screen.findByTestId("upcoming-card")).toBeInTheDocument();
  });

  it("explains when no child is linked yet", async () => {
    m.myChildren.mockResolvedValue([]);
    renderAs(PARENT, <FamilyHome />, ["PARENT"]);
    expect(await screen.findByText(/No children are linked to your sign-in yet/)).toBeInTheDocument();
    expect(screen.queryByTestId("child-switcher")).not.toBeInTheDocument();
  });

  it("is what the dashboard shows a parent", async () => {
    renderAs(PARENT, <DashboardView />, ["PARENT"]);
    expect(await screen.findByTestId("child-switcher")).toBeInTheDocument();
    expect(screen.queryByText("Your access")).not.toBeInTheDocument();
  });
});

describe("FamilyHome for a student", () => {
  it("shows their own class, today, homework and leave without fees", async () => {
    renderAs(STUDENT, <FamilyHome />, ["STUDENT"]);
    expect(await screen.findByRole("heading", { name: "My class" })).toBeInTheDocument();
    const card = await screen.findByRole("article", { name: "Arjun Sharma" });
    expect(within(card).queryByTestId("child-fees")).not.toBeInTheDocument();
    const due = screen.getByTestId("student-homework-due");
    expect(await within(due).findByText("1 to hand in")).toBeInTheDocument();
    expect(within(due).getByRole("link", { name: /Fractions worksheet/ })).toHaveAttribute("href", "/app/homework/h1");
    const leave = screen.getByTestId("family-leave-card");
    expect(within(leave).getByRole("link", { name: /Leave requests/ })).toHaveAttribute("href", "/app/family/leave");
    expect(m.myAttendance).toHaveBeenCalled();
    expect(m.childFees).not.toHaveBeenCalled();
    expect(m.myChildren).not.toHaveBeenCalled();
  });

  it("explains a sign-in that is not linked to a record", async () => {
    m.myStudentRecord.mockRejectedValue(new ApiError({ status: 404, title: "Not found" }));
    renderAs(STUDENT, <FamilyHome />, ["STUDENT"]);
    expect(await screen.findByText(/not linked to a student record yet/)).toBeInTheDocument();
    expect(await screen.findByTestId("notices-card")).toBeInTheDocument();
  });
});

describe("todayState", () => {
  it("puts a holiday first, then the register, then approved leave, then Sunday", () => {
    const base = attendance("st1", { todayStatus: null, todayLeave: null });
    expect(todayState({ ...base, todayHoliday: "Diwali", todayStatus: "PRESENT" })).toEqual({ kind: "holiday", name: "Diwali" });
    expect(todayState({ ...base, todayStatus: "ABSENT", todayLeave: { requestId: "l1", halfDay: false, prefill: "LEAVE" } })).toEqual({
      kind: "marked",
      status: "ABSENT",
      onLeave: true,
    });
    expect(todayState({ ...base, todayLeave: { requestId: "l1", halfDay: true, prefill: "HALF_DAY" } })).toEqual({
      kind: "leave",
      halfDay: true,
    });
    expect(todayState({ ...base, today: "2026-10-11" })).toEqual({ kind: "sunday" });
    expect(todayState(base)).toEqual({ kind: "unmarked" });
  });
});
