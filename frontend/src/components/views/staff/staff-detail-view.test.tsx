import { screen, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api";
import type { StaffDetail, StaffLeave, StaffPersonMonth } from "@/lib/types";
import { renderAs } from "@/test/render";
import { calendarWeeks, StaffDetailView } from "./staff-detail-view";

const { get, attendanceOf, leaveOf } = vi.hoisted(() => ({ get: vi.fn(), attendanceOf: vi.fn(), leaveOf: vi.fn() }));

vi.mock("@/lib/staff-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/staff-api")>();
  return { ...actual, staffApi: { ...actual.staffApi, get, attendanceOf, leaveOf } };
});

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  usePathname: () => "/app/staff/u1",
}));

const READ = ["dashboard.view", "staff.read", "leave.request", "leave.approve"];
const MANAGE = [...READ, "staff.manage", "staff_attendance.manage"];

const YEAR = { id: "y1", name: "2026-27", startsOn: "2026-04-01", endsOn: "2027-03-31", current: true };

const RAVI: StaffDetail = {
  userId: "u1",
  name: "Ravi Kumar",
  email: "teacher@demo.akshara.test",
  roles: ["TEACHER"],
  status: "ACTIVE",
  accountActive: true,
  lastLoginAt: null,
  profileComplete: true,
  profile: {
    employeeCode: "AKS-101",
    designation: "Primary teacher",
    department: { id: "d0", name: "Primary" },
    employmentType: "PERMANENT",
    dateOfJoining: "2020-06-01",
    dateOfLeaving: null,
    leavingReason: null,
    mobile: "9848000002",
    qualifications: "B.Ed.",
    emergencyContactName: "Sunita Kumar",
    emergencyContactMobile: "9848000003",
  },
  leaveApprover: { routing: "SCHOOL", departmentHead: null },
};

const MONTH: StaffPersonMonth = {
  userId: "u1",
  month: "2026-10",
  counts: { present: 1, halfDay: 1, absent: 0, onLeave: 0 },
  daysWorked: 1.5,
  days: [
    { date: "2026-10-01", workingDay: true, status: "PRESENT", source: "SELF", checkInAt: "2026-10-01T03:01:00Z", checkOutAt: null },
    { date: "2026-10-02", workingDay: true, status: "HALF_DAY", source: "ADMIN", checkInAt: null, checkOutAt: null },
    { date: "2026-10-03", workingDay: true, status: null, source: null, checkInAt: null, checkOutAt: null },
    { date: "2026-10-04", workingDay: false, status: null, source: null, checkInAt: null, checkOutAt: null },
  ],
};

const LEAVE: StaffLeave = {
  userId: "u1",
  year: YEAR,
  years: [YEAR],
  balances: [
    {
      leaveTypeId: "cl",
      leaveTypeName: "Casual leave",
      code: "CL",
      lossOfPay: false,
      halfDayAllowed: true,
      active: true,
      opening: 0,
      accrued: 12,
      taken: 1,
      pending: 0,
      available: 11,
      setByHand: false,
    },
  ],
  requests: [],
};

beforeEach(() => {
  get.mockResolvedValue(RAVI);
  attendanceOf.mockImplementation(async (_id: string, month: string) => ({ ...MONTH, month }));
  leaveOf.mockResolvedValue(LEAVE);
});

describe("calendarWeeks", () => {
  it("starts weeks on Monday", () => {
    // 1 Oct 2026 is a Thursday.
    const weeks = calendarWeeks(MONTH.days);
    expect(weeks).toHaveLength(1);
    expect(weeks[0].map((d) => d?.date ?? null)).toEqual([null, null, null, "2026-10-01", "2026-10-02", "2026-10-03", "2026-10-04"]);
  });
});

describe("StaffDetailView", () => {
  it("shows the profile with the full mobile, attendance and leave", async () => {
    renderAs(READ, <StaffDetailView id="u1" />, ["PRINCIPAL"]);
    expect(await screen.findByTestId("staff-name")).toHaveTextContent("Ravi Kumar");
    const profile = screen.getByTestId("staff-profile");
    expect(within(profile).getByRole("link", { name: "98480 00002" })).toHaveAttribute("href", "tel:+919848000002");
    expect(profile).toHaveTextContent("Sunita Kumar · 98480 00003");
    expect(profile).toHaveTextContent("School Admin or Principal");
    expect(within(await screen.findByTestId("staff-month")).getByText("1.5")).toBeInTheDocument();
    expect(within(await screen.findByTestId("balance-CL")).getByTestId("balance-available")).toHaveTextContent("11");
    // Reading is not managing.
    expect(screen.queryByRole("button", { name: "Edit profile" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Set balance" })).not.toBeInTheDocument();
  });

  it("offers editing, leaving and balances to staff.manage", async () => {
    renderAs(MANAGE, <StaffDetailView id="u1" />);
    expect(await screen.findByRole("button", { name: "Edit profile" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Record leaving" })).toBeInTheDocument();
    expect(await screen.findByRole("button", { name: "Set balance" })).toBeInTheDocument();
  });

  it("asks to complete a missing profile", async () => {
    get.mockResolvedValue({ ...RAVI, profileComplete: false, profile: null });
    renderAs(MANAGE, <StaffDetailView id="u1" />);
    expect(await screen.findByTestId("staff-incomplete")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Complete profile" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Record leaving" })).not.toBeInTheDocument();
  });

  it("says when someone has left", async () => {
    get.mockResolvedValue({
      ...RAVI,
      status: "LEFT",
      accountActive: false,
      profile: { ...RAVI.profile!, dateOfLeaving: "2026-10-01", leavingReason: "Moved to Pune" },
    });
    renderAs(MANAGE, <StaffDetailView id="u1" />);
    expect(await screen.findByTestId("staff-left")).toHaveTextContent("Left on 1 Oct 2026. Reason: Moved to Pune");
    expect(screen.queryByRole("button", { name: "Edit profile" })).not.toBeInTheDocument();
  });

  it("shows not found for another school's id", async () => {
    get.mockRejectedValue(new ApiError({ status: 404, title: "Not found" }));
    renderAs(READ, <StaffDetailView id="other" />);
    expect(await screen.findByTestId("staff-not-found")).toHaveTextContent("This staff member was not found.");
  });
});
