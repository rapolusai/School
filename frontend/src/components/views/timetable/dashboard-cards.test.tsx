import { screen, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api";
import type { Child, DayPeriod, FamilyTimetable, StudentHomework, StudentHomeworkRow, TeacherDay } from "@/lib/types";
import { renderAs } from "@/test/render";
import { ChildrenHomeworkCards, StudentHomeworkCard } from "../homework/homework-cards";
import { StudentTodayCard, TodayClassesCard } from "./timetable-cards";

const { myDay, myTimetable, mine, child, myChildren } = vi.hoisted(() => ({
  myDay: vi.fn(),
  myTimetable: vi.fn(),
  mine: vi.fn(),
  child: vi.fn(),
  myChildren: vi.fn(),
}));

vi.mock("@/lib/timetable-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/timetable-api")>();
  return { ...actual, timetableApi: { ...actual.timetableApi, myDay, myTimetable } };
});

vi.mock("@/lib/homework-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/homework-api")>();
  return { ...actual, homeworkApi: { ...actual.homeworkApi, mine, child } };
});

vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return { ...actual, api: { ...actual.api, myChildren } };
});

const PERIODS: DayPeriod[] = [
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
        subjectId: "maths",
        subjectName: "Mathematics",
        teacherName: "Ravi Kumar",
        room: "Room 12",
        substituteName: null,
        absentTeacherName: null,
      },
    ],
  },
  { number: null, label: "Short break", startsAt: "09:10", endsAt: "09:25", breakTime: true, entries: [] },
  {
    number: 2,
    label: "Period 2",
    startsAt: "09:25",
    endsAt: "10:05",
    breakTime: false,
    entries: [
      {
        kind: "SUBSTITUTION",
        sectionId: "s6a",
        sectionLabel: "Class 6 A",
        subjectId: "english",
        subjectName: "English",
        teacherName: "Kavitha Menon",
        room: null,
        substituteName: "Ravi Kumar",
        absentTeacherName: "Kavitha Menon",
      },
    ],
  },
];

function row(id: string, title: string, overrides: Partial<StudentHomeworkRow> = {}): StudentHomeworkRow {
  return {
    id,
    title,
    subjectId: "maths",
    subjectName: "Mathematics",
    assignedOn: "2026-10-08",
    dueOn: "2026-10-13",
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

function homework(studentId: string, items: StudentHomeworkRow[]): StudentHomework {
  return {
    studentId,
    studentName: "Arjun Sharma",
    sectionId: "s5a",
    sectionLabel: "Class 5 A",
    today: "2026-10-09",
    items,
  };
}

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

beforeEach(() => {
  const day: TeacherDay = {
    date: "2026-10-09",
    day: "FRIDAY",
    workingDay: true,
    teacherId: "t-ravi",
    teacherName: "Ravi Kumar",
    absent: false,
    periods: PERIODS,
  };
  myDay.mockResolvedValue(day);
  const family: FamilyTimetable = {
    studentId: "st1",
    studentName: "Arjun Sharma",
    sectionId: "s5a",
    sectionLabel: "Class 5 A",
    academicYear: null,
    bells: { workingDays: [], saturdaySchedule: false, weekday: [], saturday: [], weekdayPeriods: 0, saturdayPeriods: 0 },
    slots: [],
    today: "2026-10-09",
    todayDay: "FRIDAY",
    workingDay: true,
    todayPeriods: PERIODS.slice(0, 2),
  };
  myTimetable.mockResolvedValue(family);
});

describe("dashboard cards", () => {
  it("shows a teacher today's classes, including a period they cover", async () => {
    renderAs(["dashboard.view", "timetable.read"], <TodayClassesCard />, ["TEACHER"]);
    const list = await screen.findByTestId("today-classes-list");
    expect(within(list).getByText("Mathematics")).toBeInTheDocument();
    expect(within(list).getByText("Covering for Kavitha Menon")).toBeInTheDocument();
    expect(within(list).queryByText("Short break")).not.toBeInTheDocument();
    // A teacher's own name is not repeated on each period.
    expect(within(list).queryByText("Ravi Kumar")).not.toBeInTheDocument();
  });

  it("shows a student today's timetable and the homework still to hand in", async () => {
    mine.mockResolvedValue(
      homework("st1", [
        row("h1", "Tables 12 to 15"),
        row("h2", "Water cycle diagram", { status: "NEEDS_REDO", dueOn: "2026-10-05", overdue: true }),
        row("h3", "Parts of a computer", { status: "SUBMITTED" }),
        row("h4", "Paper boat", { onlineSubmission: false, dueOn: "2026-10-07", overdue: true }),
      ]),
    );
    renderAs(
      ["dashboard.view"],
      <>
        <StudentTodayCard />
        <StudentHomeworkCard />
      </>,
      ["STUDENT"],
    );

    const today = await screen.findByTestId("student-today");
    expect(await within(today).findByText("Mathematics")).toBeInTheDocument();
    expect(within(today).queryByText(/Class 5 A/)).not.toBeInTheDocument();

    const due = screen.getByTestId("student-homework-due");
    expect(await within(due).findByText("2 to hand in")).toBeInTheDocument();
    const links = within(due).getAllByRole("link");
    expect(links.map((l) => l.getAttribute("href"))).toEqual([
      "/app/homework/h2",
      "/app/homework/h1",
      "/app/homework",
    ]);
  });

  it("hides the student cards for a sign-in without a student record", async () => {
    myTimetable.mockRejectedValue(new ApiError({ status: 404, title: "Not found" }));
    mine.mockRejectedValue(new ApiError({ status: 404, title: "Not found" }));
    const { container } = renderAs(
      ["dashboard.view"],
      <>
        <StudentTodayCard />
        <StudentHomeworkCard />
      </>,
      ["STUDENT"],
    );
    await vi.waitFor(() => expect(container.querySelector("section")).toBeNull());
  });

  it("gives a parent one homework card per child at school, with links that keep the child", async () => {
    myChildren.mockResolvedValue([
      ARJUN,
      { ...ARJUN, id: "st2", fullName: "Diya Sharma", className: "Class 2" },
      { ...ARJUN, id: "st3", fullName: "Old Pupil", status: "TRANSFERRED" },
    ]);
    child.mockImplementation((studentId: string) =>
      Promise.resolve(homework(studentId, studentId === "st1" ? [row("h1", "Tables 12 to 15")] : [])),
    );
    renderAs(["dashboard.view", "child.view"], <ChildrenHomeworkCards />, ["PARENT"]);

    const cards = await screen.findAllByTestId("child-homework-due");
    expect(cards).toHaveLength(2);
    expect(within(cards[0]).getByRole("heading", { name: "Homework due · Arjun Sharma" })).toBeInTheDocument();
    expect(await within(cards[0]).findByRole("link", { name: /Tables 12 to 15/ })).toHaveAttribute(
      "href",
      "/app/homework/h1?child=st1",
    );
    expect(within(cards[0]).getByRole("link", { name: /Open timetable/ })).toHaveAttribute(
      "href",
      "/app/timetable?child=st1",
    );
    expect(await within(cards[1]).findByText("Nothing due. All caught up.")).toBeInTheDocument();
    expect(child).not.toHaveBeenCalledWith("st3");
  });
});
