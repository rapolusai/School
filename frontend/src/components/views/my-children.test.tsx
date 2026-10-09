import { screen, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api";
import type { AttendanceToday, Child, ChildAttendance, SectionsForDay } from "@/lib/types";
import { ARJUN_FEES } from "@/test/fees-fixtures";
import { ADMIN_PERMISSIONS, renderAs } from "@/test/render";
import { DashboardView } from "./dashboard-view";
import { MyChildren, MyClass } from "./my-children";

const { myChildren, myStudentRecord, child, today, sections, childFees } = vi.hoisted(() => ({
  myChildren: vi.fn(),
  myStudentRecord: vi.fn(),
  child: vi.fn(),
  today: vi.fn(),
  sections: vi.fn(),
  childFees: vi.fn(),
}));

vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return { ...actual, api: { ...actual.api, myChildren, myStudentRecord } };
});

vi.mock("@/lib/attendance-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/attendance-api")>();
  return { ...actual, attendanceApi: { ...actual.attendanceApi, child, today, sections } };
});

vi.mock("@/lib/fees-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/fees-api")>();
  return { ...actual, feesApi: { ...actual.feesApi, childFees } };
});

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  usePathname: () => "/app/dashboard",
}));

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

const DIYA: Child = {
  ...ARJUN,
  id: "st2",
  fullName: "Diya Sharma",
  admissionNo: "AKS/2026/002",
  className: "Class 2",
  rollNo: 4,
  classTeacherName: null,
};

const PARENT = ["dashboard.view", "child.view"];

const NONE = { present: 0, absent: 0, late: 0, halfDay: 0, leave: 0 };

function childAttendance(studentId: string): ChildAttendance {
  if (studentId === "st1") {
    return {
      studentId,
      fullName: "Arjun Sharma",
      month: "2026-10",
      daysMarked: 7,
      counts: { ...NONE, present: 5, absent: 1, halfDay: 1 },
      presentPercent: 78.6,
      days: [],
      recentAbsences: [
        { date: "2026-10-07", status: "ABSENT" },
        { date: "2026-09-15", status: "ABSENT" },
      ],
    };
  }
  return {
    studentId,
    fullName: "Diya Sharma",
    month: "2026-10",
    daysMarked: 7,
    counts: { ...NONE, present: 7 },
    presentPercent: 100,
    days: [],
    recentAbsences: [],
  };
}

const TODAY: AttendanceToday = {
  date: "2026-10-09",
  academicYearName: "2026-27",
  sectionCount: 2,
  sectionsMarked: 1,
  students: 60,
  counts: { ...NONE, present: 27, absent: 2, late: 1 },
  presentPercent: 93.3,
  classes: [
    {
      classId: "c5",
      className: "Class 5",
      sectionCount: 2,
      sectionsMarked: 1,
      counts: { ...NONE, present: 27, absent: 2, late: 1 },
      presentPercent: 93.3,
      sections: [
        {
          sectionId: "s5a",
          sectionName: "A",
          label: "Class 5 A",
          classTeacherName: "Ravi Kumar",
          students: 30,
          marked: false,
          markedByName: null,
          markedAt: null,
          counts: NONE,
          presentPercent: null,
        },
        {
          sectionId: "s5b",
          sectionName: "B",
          label: "Class 5 B",
          classTeacherName: null,
          students: 30,
          marked: true,
          markedByName: "Lakshmi Iyer",
          markedAt: "2026-10-09T03:40:00Z",
          counts: { ...NONE, present: 27, absent: 2, late: 1 },
          presentPercent: 93.3,
        },
      ],
    },
  ],
};

const TEACHER_SECTIONS: SectionsForDay = {
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
      students: 30,
      marked: false,
      markedByName: null,
      markedAt: null,
      counts: NONE,
      presentPercent: null,
      canMark: true,
    },
  ],
};

beforeEach(() => {
  myChildren.mockResolvedValue([ARJUN, DIYA]);
  myStudentRecord.mockResolvedValue(ARJUN);
  child.mockImplementation((studentId: string) => Promise.resolve(childAttendance(studentId)));
  today.mockResolvedValue(TODAY);
  sections.mockResolvedValue(TEACHER_SECTIONS);
  childFees.mockImplementation((id: string) =>
    Promise.resolve(id === "st1" ? ARJUN_FEES : { ...ARJUN_FEES, instalments: [], receipts: [] }),
  );
});

describe("MyChildren", () => {
  it("shows one card per child with class, roll number and class teacher", async () => {
    renderAs(PARENT, <MyChildren />, ["PARENT"]);
    const cards = await screen.findAllByTestId("child-card");
    expect(cards).toHaveLength(2);
    const arjun = screen.getByRole("article", { name: "Arjun Sharma" });
    expect(within(arjun).getByText("Class 5 A")).toBeInTheDocument();
    expect(within(arjun).getByText("Ravi Kumar")).toBeInTheDocument();
    expect(within(arjun).getByText("2026-27")).toBeInTheDocument();
    const diya = screen.getByRole("article", { name: "Diya Sharma" });
    expect(within(diya).getByText("Class 2 A")).toBeInTheDocument();
    expect(within(diya).getByText("—")).toBeInTheDocument();
  });

  it("shows each child's fees on their card", async () => {
    renderAs(PARENT, <MyChildren />, ["PARENT"]);
    const arjun = await screen.findByRole("article", { name: "Arjun Sharma" });
    expect(await within(arjun).findByText("₹11,600 overdue")).toBeInTheDocument();
    expect(within(arjun).getByRole("link", { name: "Pay fees" })).toHaveAttribute("href", "/app/children/st1/fees");
    const diya = screen.getByRole("article", { name: "Diya Sharma" });
    expect(await within(diya).findByText("No fees have been set yet.")).toBeInTheDocument();
  });

  it("explains when no child is linked yet", async () => {
    myChildren.mockResolvedValue([]);
    renderAs(PARENT, <MyChildren />, ["PARENT"]);
    expect(await screen.findByText(/No children are linked to your sign-in yet/)).toBeInTheDocument();
  });
});

describe("MyClass", () => {
  it("shows the student's own class", async () => {
    renderAs(["dashboard.view", "child.view"], <MyClass />, ["STUDENT"]);
    expect(await screen.findByRole("heading", { name: "My class" })).toBeInTheDocument();
    expect(screen.getByRole("article", { name: "Arjun Sharma" })).toBeInTheDocument();
  });

  it("explains a sign-in that is not linked to a record", async () => {
    myStudentRecord.mockRejectedValue(new ApiError({ status: 404, title: "Not found" }));
    renderAs(["dashboard.view", "child.view"], <MyClass />, ["STUDENT"]);
    expect(await screen.findByText(/not linked to a student record yet/)).toBeInTheDocument();
  });
});

describe("Dashboard", () => {
  it("shows a parent their children", async () => {
    renderAs(PARENT, <DashboardView />, ["PARENT"]);
    expect(await screen.findAllByTestId("child-card")).toHaveLength(2);
    expect(myStudentRecord).not.toHaveBeenCalled();
  });

  it("shows a parent each child's attendance this month and the last absences", async () => {
    renderAs(PARENT, <DashboardView />, ["PARENT"]);
    const arjun = await screen.findByRole("article", { name: "Arjun Sharma" });
    const arjunAttendance = await within(arjun).findByTestId("child-attendance");
    expect(arjunAttendance).toHaveTextContent("Attendance this month");
    expect(arjunAttendance).toHaveTextContent("78.6%");
    expect(arjunAttendance).toHaveTextContent("Present 5 of 7 days marked");
    expect(arjunAttendance).toHaveTextContent("Last absent: 7 Oct 2026, 15 Sept 2026");
    const diya = screen.getByRole("article", { name: "Diya Sharma" });
    expect(await within(diya).findByTestId("child-attendance")).toHaveTextContent("No absences this year.");
    expect(child).toHaveBeenCalledWith("st1");
    expect(child).toHaveBeenCalledWith("st2");
  });

  it("asks a class teacher to mark their own section", async () => {
    renderAs(
      ["dashboard.view", "students.read", "academics.read", "attendance.read", "attendance.mark"],
      <DashboardView />,
      ["TEACHER"],
    );
    const cards = await screen.findByTestId("mark-attendance-cards");
    const link = within(cards).getByRole("link", { name: "Mark attendance for Class 5 A" });
    expect(link).toHaveAttribute("href", "/app/attendance?section=s5a");
    expect(within(cards).getByText("30 students to mark")).toBeInTheDocument();
    expect(screen.queryByTestId("attendance-today")).not.toBeInTheDocument();
    expect(today).not.toHaveBeenCalled();
  });

  it("shows the principal today's attendance across the school", async () => {
    renderAs(
      ["dashboard.view", "students.read", "academics.read", "attendance.read", "attendance.mark", "attendance.manage"],
      <DashboardView />,
      ["PRINCIPAL"],
    );
    const card = await screen.findByTestId("attendance-today");
    expect(await within(card).findByText("93.3%")).toBeInTheDocument();
    expect(card).toHaveTextContent("1 of 2 sections marked");
    expect(card).toHaveTextContent("Still to mark: Class 5 A");
    expect(screen.queryByTestId("mark-attendance-cards")).not.toBeInTheDocument();
  });

  it("does not show school staff a My children section", () => {
    renderAs(
      ADMIN_PERMISSIONS.filter((p) => !p.startsWith("users") && !p.startsWith("roles") && !p.startsWith("audit")),
      <DashboardView />,
    );
    expect(screen.queryByRole("heading", { name: "My children" })).not.toBeInTheDocument();
    expect(myChildren).not.toHaveBeenCalled();
    expect(childFees).not.toHaveBeenCalled();
  });
});
