import { screen, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api";
import { translate, type Translate } from "@/lib/i18n";
import type { DashboardSummary } from "@/lib/types";
import { renderAs } from "@/test/render";
import { chartsFor, dashboardLayout, dayTick, isStaffMember, StaffDashboard, tilesFor } from "./staff-dashboard";

const { dashboard } = vi.hoisted(() => ({ dashboard: vi.fn() }));

vi.mock("@/lib/reports-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/reports-api")>();
  return { ...actual, reportsApi: { ...actual.reportsApi, dashboard } };
});

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  usePathname: () => "/app/dashboard",
}));

const t: Translate = (key, vars) => translate("en", key, vars);
const NONE = { present: 0, absent: 0, late: 0, halfDay: 0, leave: 0 };

const SUMMARY: DashboardSummary = {
  date: "2026-10-09",
  academicYearName: "2026-27",
  students: { onRoll: 412, admittedThisMonth: 3, admittedThisYear: 41 },
  attendance: {
    date: "2026-10-09",
    wholeSchool: true,
    holiday: null,
    sectionCount: 12,
    sectionsMarked: 10,
    students: 412,
    counts: { present: 340, absent: 14, late: 6, halfDay: 0, leave: 3 },
    presentPercent: 95.3,
    month: { from: "2026-10-01", to: "2026-10-09", schoolDays: 7, daysMarked: 7, counts: NONE, presentPercent: 94.1 },
    trend: [
      { date: "2026-10-07", sectionsMarked: 12, counts: NONE, presentPercent: 93.5 },
      { date: "2026-10-08", sectionsMarked: 0, counts: NONE, presentPercent: null },
      { date: "2026-10-09", sectionsMarked: 10, counts: NONE, presentPercent: 95.3 },
    ],
    ownSections: [],
  },
  fees: {
    today: { amountPaise: 4_500_000, receiptCount: 3 },
    thisMonth: { amountPaise: 18_430_000, receiptCount: 21 },
    outstandingPaise: 92_000_000,
    overduePaise: 12_500_000,
    overdueStudents: 9,
    byDay: [
      { date: "2026-10-01", amountPaise: 2_000_000, receiptCount: 2 },
      { date: "2026-10-05", amountPaise: 0, receiptCount: 0 },
      { date: "2026-10-09", amountPaise: 4_500_000, receiptCount: 3 },
    ],
  },
  admissions: {
    openEnquiries: 7,
    inProgress: 5,
    offersPending: 2,
    admittedThisYear: 41,
    upcomingSlots: 1,
    funnel: [
      { stage: "ENQUIRY", reached: 20, current: 7, fromPrevious: null, fromEnquiry: 100 },
      { stage: "APPLICATION", reached: 12, current: 3, fromPrevious: 60, fromEnquiry: 60 },
      { stage: "ASSESSMENT", reached: 8, current: 2, fromPrevious: 66.7, fromEnquiry: 40 },
      { stage: "OFFERED", reached: 6, current: 2, fromPrevious: 75, fromEnquiry: 30 },
      { stage: "ADMITTED", reached: 4, current: 4, fromPrevious: 66.7, fromEnquiry: 20 },
    ],
    followUps: {
      date: "2026-10-09",
      dueToday: 1,
      overdue: 2,
      upcoming: 4,
      items: [
        { id: "a1", childName: "Kabir Rao", classId: "c1", className: "Class 1", stage: "ENQUIRY", followUpOn: "2026-10-07" },
        { id: "a2", childName: "Meera Iyer", classId: "c2", className: "Class 2", stage: "APPLICATION", followUpOn: "2026-10-09" },
      ],
    },
  },
  staff: {
    date: "2026-10-09",
    workingDay: true,
    activeStaff: 30,
    present: 26,
    halfDay: 1,
    onLeave: 2,
    absent: 0,
    notMarked: 1,
    checkedIn: 20,
  },
  leave: { waitingForMe: 2 },
  circulars: {
    pendingApproval: 1,
    items: [{ id: "n1", title: "Diwali holidays", createdByName: "Ravi Kumar", submittedAt: "2026-10-08T05:30:00Z" }],
  },
  homework: { waitingForReview: 5 },
  timetable: { clashes: 1, warnings: 0, periodsToCover: 4, periodsCovered: 3 },
  myDay: { date: "2026-10-09", workingDay: true, absent: false, classes: 5, substitutions: [] },
};

const TEACHER_SUMMARY: DashboardSummary = {
  ...SUMMARY,
  students: null,
  fees: null,
  admissions: null,
  staff: null,
  circulars: null,
  timetable: null,
  leave: { waitingForMe: 0 },
  attendance: {
    ...SUMMARY.attendance!,
    wholeSchool: false,
    sectionCount: 1,
    sectionsMarked: 0,
    students: 30,
    presentPercent: null,
    ownSections: [
      {
        sectionId: "s5a",
        label: "Class 5 A",
        students: 30,
        marked: false,
        canMark: true,
        counts: NONE,
        presentPercent: null,
      },
    ],
  },
  homework: { waitingForReview: 3 },
  myDay: {
    date: "2026-10-09",
    workingDay: true,
    absent: false,
    classes: 4,
    substitutions: [
      {
        period: 3,
        label: "Period 3",
        startsAt: "10:00",
        endsAt: "10:40",
        sectionLabel: "Class 6 B",
        subjectName: "English",
        room: null,
        absentTeacherName: "Sita Devi",
      },
    ],
  },
};

const ACCOUNTANT_SUMMARY: DashboardSummary = {
  ...SUMMARY,
  attendance: null,
  admissions: null,
  staff: null,
  circulars: null,
  homework: null,
  timetable: null,
  leave: { waitingForMe: 0 },
};

const PRINCIPAL = [
  "dashboard.view",
  "students.read",
  "attendance.read",
  "attendance.manage",
  "fees.read",
  "admissions.read",
  "staff.read",
  "leave.request",
  "leave.approve",
  "notices.send",
  "notices.approve",
  "homework.manage",
  "timetable.read",
  "timetable.manage",
];

const all = (can: boolean) => () => can;

describe("who gets which dashboard", () => {
  it("treats every role but parent and student as staff", () => {
    expect(isStaffMember({ roles: ["TEACHER"], platformAdmin: false })).toBe(true);
    expect(isStaffMember({ roles: ["LIBRARIAN"], platformAdmin: false })).toBe(true);
    expect(isStaffMember({ roles: ["PARENT", "TEACHER"], platformAdmin: false })).toBe(true);
    expect(isStaffMember({ roles: ["PARENT"], platformAdmin: false })).toBe(false);
    expect(isStaffMember({ roles: ["STUDENT"], platformAdmin: false })).toBe(false);
    expect(isStaffMember({ roles: [], platformAdmin: true })).toBe(false);
    expect(isStaffMember(null)).toBe(false);
  });

  it("picks the layout of the most senior role", () => {
    expect(dashboardLayout(["SCHOOL_ADMIN"])).toBe("leadership");
    expect(dashboardLayout(["TEACHER", "PRINCIPAL"])).toBe("leadership");
    expect(dashboardLayout(["TEACHER", "ACCOUNTANT"])).toBe("teacher");
    expect(dashboardLayout(["ACCOUNTANT"])).toBe("accountant");
    expect(dashboardLayout(["FRONT_OFFICE"])).toBe("frontOffice");
    expect(dashboardLayout(["LIBRARIAN"])).toBe("staff");
  });
});

describe("tilesFor", () => {
  it("gives leadership the whole school at a glance, in order", () => {
    const tiles = tilesFor(SUMMARY, "leadership", t, all(true));
    expect(tiles.map((tile) => tile.key)).toEqual([
      "attendanceToday",
      "absentToday",
      "students",
      "feesToday",
      "outstanding",
      "staffPresent",
      "leaveWaiting",
      "enquiries",
      "circulars",
      "homework",
      "clashes",
    ]);
    const byKey = Object.fromEntries(tiles.map((tile) => [tile.key, tile]));
    expect(byKey.attendanceToday).toMatchObject({ value: "95.3%", sub: "10 of 12 sections marked", attention: true });
    expect(byKey.absentToday).toMatchObject({ value: "14", sub: "3 on leave", href: "/app/reports/absentees" });
    expect(byKey.students).toMatchObject({ value: "412", sub: "3 admitted this month" });
    expect(byKey.feesToday).toMatchObject({ value: "₹45,000", sub: "3 receipts" });
    expect(byKey.outstanding).toMatchObject({ value: "₹9,20,000", sub: "Overdue ₹1,25,000" });
    expect(byKey.staffPresent).toMatchObject({ value: "27", sub: "of 30 · 2 on leave" });
    expect(byKey.leaveWaiting).toMatchObject({ value: "2", href: "/app/leave?tab=inbox", attention: true });
    expect(byKey.clashes).toMatchObject({ value: "1", sub: "3 of 4 periods covered today", attention: true });
  });

  it("leaves out tiles whose card the person may not see", () => {
    const tiles = tilesFor({ ...SUMMARY, fees: null, admissions: null }, "leadership", t, all(true));
    expect(tiles.map((tile) => tile.key)).not.toContain("feesToday");
    expect(tiles.map((tile) => tile.key)).not.toContain("enquiries");
  });

  it("gives a teacher their sections, homework and substitutions", () => {
    const tiles = tilesFor(TEACHER_SUMMARY, "teacher", t, (p) => p !== "leave.approve");
    expect(tiles.map((tile) => [tile.key, tile.value, tile.sub])).toEqual([
      ["sectionsToMark", "1", "0 of 1 sections marked"],
      ["attendanceToday", "—", "0 of 1 sections marked"],
      ["homework", "3", "Handed in, not yet reviewed"],
      ["substitutions", "1", "4 classes today"],
    ]);
    expect(tiles[0]).toMatchObject({ label: "Sections to mark", attention: true });
    expect(tiles[1].label).toBe("My classes today");
  });

  it("shows a holiday instead of sections to mark", () => {
    const holiday = { ...TEACHER_SUMMARY, attendance: { ...TEACHER_SUMMARY.attendance!, holiday: "Dussehra" } };
    const [toMark] = tilesFor(holiday, "teacher", t, all(true));
    expect(toMark).toMatchObject({ value: "0", sub: "Holiday: Dussehra", attention: false });
  });

  it("gives an accountant collections and overdue fees", () => {
    const tiles = tilesFor(ACCOUNTANT_SUMMARY, "accountant", t, all(true));
    expect(tiles.map((tile) => [tile.key, tile.value, tile.sub])).toEqual([
      ["feesToday", "₹45,000", "3 receipts"],
      ["feesMonth", "₹1,84,300", "21 receipts"],
      ["outstanding", "₹9,20,000", "Overdue ₹1,25,000"],
      ["overdue", "₹1,25,000", "9 students"],
      ["students", "412", "3 admitted this month"],
    ]);
    expect(tiles[3].href).toBe("/app/fees/dues?view=overdue");
  });

  it("gives front office enquiries and follow-ups", () => {
    const tiles = tilesFor(SUMMARY, "frontOffice", t, all(true));
    expect(tiles.map((tile) => [tile.key, tile.value, tile.sub])).toEqual([
      ["enquiries", "7", "5 in progress"],
      ["followUps", "3", "2 overdue · 4 coming up"],
      ["offers", "2", "41 admitted this year"],
      ["students", "412", "3 admitted this month"],
    ]);
  });

  it("links a tile only to a screen the person may open", () => {
    const tiles = tilesFor(SUMMARY, "leadership", t, (p) => p !== "staff.read" && p !== "notices.send");
    const byKey = Object.fromEntries(tiles.map((tile) => [tile.key, tile]));
    expect(byKey.staffPresent.href).toBeUndefined();
    expect(byKey.circulars.href).toBeUndefined();
    expect(byKey.homework.href).toBe("/app/homework");
  });
});

describe("chartsFor", () => {
  it("shows each layout's charts when their data is there", () => {
    expect(chartsFor(SUMMARY, "leadership")).toEqual(["trend", "collections", "funnel", "circulars", "followUps"]);
    expect(chartsFor(TEACHER_SUMMARY, "teacher")).toEqual(["trend"]);
    expect(chartsFor(ACCOUNTANT_SUMMARY, "accountant")).toEqual(["collections"]);
    expect(chartsFor(SUMMARY, "frontOffice")).toEqual(["funnel", "followUps"]);
    expect(chartsFor({ ...SUMMARY, circulars: { pendingApproval: 0, items: [] } }, "leadership")).not.toContain("circulars");
  });

  it("marks the 1st and every 5th day on the collections axis", () => {
    expect(["2026-10-01", "2026-10-02", "2026-10-05", "2026-10-30", "2026-10-31"].map(dayTick)).toEqual([
      "1",
      undefined,
      "5",
      "30",
      undefined,
    ]);
  });
});

describe("StaffDashboard", () => {
  beforeEach(() => {
    dashboard.mockReset();
  });

  it("shows the principal tiles, the three charts and what waits for approval", async () => {
    dashboard.mockResolvedValue(SUMMARY);
    renderAs(PRINCIPAL, <StaffDashboard />, ["PRINCIPAL"]);
    const tiles = await screen.findByTestId("dashboard-tiles");
    expect(within(tiles).getAllByRole("listitem")).toHaveLength(11);
    expect(within(screen.getByTestId("tile-attendanceToday")).getByRole("link", { name: "Attendance today" })).toHaveAttribute(
      "href",
      "/app/attendance",
    );
    expect(screen.getByTestId("tile-feesToday")).toHaveTextContent("₹45,000");
    expect(screen.getByTestId("staff-dashboard")).toHaveAttribute("data-layout", "leadership");

    const trend = screen.getByTestId("chart-trend");
    expect(trend).toHaveTextContent("Attendance, last 30 school days");
    expect(trend).toHaveTextContent("This month: 94.1%");
    expect(within(trend).getAllByRole("row")).toHaveLength(4);
    expect(screen.getByTestId("chart-collections")).toHaveTextContent("Total ₹1,84,300");
    const funnel = screen.getByTestId("chart-funnel");
    expect(within(funnel).getAllByRole("listitem")).toHaveLength(5);
    expect(funnel).toHaveTextContent("60% of the stage before");
    expect(within(funnel).getByRole("link", { name: "Full report" })).toHaveAttribute("href", "/app/reports/admissions");
    expect(within(screen.getByTestId("circulars-waiting")).getByRole("link", { name: "Diwali holidays" })).toHaveAttribute(
      "href",
      "/app/notices/n1",
    );
    expect(screen.getByTestId("follow-ups")).toHaveTextContent("Kabir Rao");
    expect(screen.getByRole("link", { name: "All reports" })).toHaveAttribute("href", "/app/reports");
  });

  it("shows a teacher their own day and no school-wide charts", async () => {
    dashboard.mockResolvedValue(TEACHER_SUMMARY);
    renderAs(
      ["dashboard.view", "students.read", "attendance.read", "attendance.mark", "homework.manage", "timetable.read", "leave.request"],
      <StaffDashboard />,
      ["TEACHER"],
    );
    expect(await screen.findByTestId("tile-sectionsToMark")).toHaveTextContent("1");
    expect(screen.getByTestId("tile-substitutions")).toHaveTextContent("Substitutions today");
    expect(screen.queryByTestId("tile-leaveWaiting")).not.toBeInTheDocument();
    expect(screen.getByTestId("chart-trend")).toHaveTextContent("My classes, last 30 school days");
    expect(screen.queryByTestId("chart-collections")).not.toBeInTheDocument();
    expect(screen.queryByTestId("chart-funnel")).not.toBeInTheDocument();
  });

  it("says so when nothing has been collected or marked yet", async () => {
    dashboard.mockResolvedValue({
      ...SUMMARY,
      attendance: { ...SUMMARY.attendance!, trend: SUMMARY.attendance!.trend.map((p) => ({ ...p, presentPercent: null })) },
      fees: { ...SUMMARY.fees!, byDay: SUMMARY.fees!.byDay.map((d) => ({ ...d, amountPaise: 0 })) },
      admissions: { ...SUMMARY.admissions!, funnel: SUMMARY.admissions!.funnel.map((s) => ({ ...s, reached: 0 })) },
    });
    renderAs(PRINCIPAL, <StaffDashboard />, ["PRINCIPAL"]);
    expect(await screen.findByText("No attendance marked in the last 30 school days.")).toBeInTheDocument();
    expect(screen.getByText("No fees collected yet this month.")).toBeInTheDocument();
    expect(screen.getByText("No enquiries yet for this year.")).toBeInTheDocument();
  });

  it("shows an error with a retry", async () => {
    dashboard.mockRejectedValueOnce(new ApiError({ status: 500, title: "Server error" }));
    renderAs(PRINCIPAL, <StaffDashboard />, ["PRINCIPAL"]);
    expect(await screen.findByRole("alert")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Try again" })).toBeInTheDocument();
  });
});
