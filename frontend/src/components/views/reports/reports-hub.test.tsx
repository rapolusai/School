import { screen, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { renderAs } from "@/test/render";
import { reportGroupsFor, ReportsHubView } from "./reports-hub";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  usePathname: () => "/app/reports",
}));

const subject = (permissions: string[]) => ({ permissions, platformAdmin: false });

// The seeded roles' report permissions (docs/api/phase-1*.md).
const PRINCIPAL = [
  "dashboard.view",
  "attendance.read",
  "fees.read",
  "admissions.read",
  "staff.read",
  "homework.manage",
  "timetable.manage",
];
const TEACHER = ["dashboard.view", "attendance.read", "homework.manage", "timetable.read"];
const ACCOUNTANT = ["dashboard.view", "fees.read", "fees.collect"];
const FRONT_OFFICE = ["dashboard.view", "admissions.read", "admissions.manage"];

const keys = (permissions: string[]) =>
  reportGroupsFor(subject(permissions)).map(({ group, links }) => [group, links.map((l) => l.key)]);

describe("reportGroupsFor", () => {
  it("gives the principal every group in order", () => {
    expect(keys(PRINCIPAL)).toEqual([
      ["attendance", ["absentees", "sections", "register"]],
      ["fees", ["collection", "outstanding", "overdue", "receipts"]],
      ["admissions", ["funnel", "applications"]],
      ["staff", ["leave", "staffAttendance"]],
      ["academics", ["homework", "clashes"]],
    ]);
  });

  it("gives each role only the reports its permissions open", () => {
    expect(keys(TEACHER)).toEqual([
      ["attendance", ["absentees", "sections", "register"]],
      ["academics", ["homework"]],
    ]);
    expect(keys(ACCOUNTANT)).toEqual([["fees", ["collection", "outstanding", "overdue", "receipts"]]]);
    expect(keys(FRONT_OFFICE)).toEqual([["admissions", ["funnel", "applications"]]]);
    expect(keys(["dashboard.view", "child.view"])).toEqual([]);
  });
});

describe("ReportsHubView", () => {
  it("lists the principal's reports by area, linking new and existing screens", () => {
    renderAs(PRINCIPAL, <ReportsHubView />, ["PRINCIPAL"]);
    expect(screen.getByRole("heading", { level: 1, name: "Reports" })).toBeInTheDocument();
    expect(screen.getAllByRole("heading", { level: 2 }).map((h) => h.textContent)).toEqual([
      "Attendance",
      "Fees",
      "Admissions",
      "Staff",
      "Academics",
    ]);
    const attendance = screen.getByTestId("reports-group-attendance");
    expect(within(attendance).getByRole("link", { name: /Attendance by class and section/ })).toHaveAttribute(
      "href",
      "/app/reports/attendance",
    );
    expect(within(attendance).getByRole("link", { name: /Month register/ })).toHaveAttribute(
      "href",
      "/app/attendance/reports",
    );
    expect(screen.getByTestId("report-link-overdue")).toHaveAttribute("href", "/app/fees/dues?view=overdue");
    expect(screen.getByTestId("report-link-funnel")).toHaveAttribute("href", "/app/reports/admissions");
    expect(screen.getByTestId("report-link-leave")).toHaveAttribute("href", "/app/reports/leave");
    expect(screen.getByTestId("report-link-clashes")).toHaveAttribute("href", "/app/timetable?tab=clashes");
    expect(screen.getByTestId("report-link-homework")).toHaveTextContent("Excel");
  });

  it("shows an accountant only the fee reports", () => {
    renderAs(ACCOUNTANT, <ReportsHubView />, ["ACCOUNTANT"]);
    expect(screen.getAllByRole("heading", { level: 2 }).map((h) => h.textContent)).toEqual(["Fees"]);
    expect(screen.queryByTestId("report-link-absentees")).not.toBeInTheDocument();
  });

  it("turns away someone without any report permission", () => {
    renderAs(["dashboard.view", "child.view"], <ReportsHubView />, ["PARENT"]);
    expect(screen.getByTestId("access-denied")).toBeInTheDocument();
    expect(screen.queryByTestId("reports-hub")).not.toBeInTheDocument();
  });
});
