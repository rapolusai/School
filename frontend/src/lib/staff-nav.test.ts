import { describe, expect, it } from "vitest";
import { en } from "./i18n/en";
import { hi } from "./i18n/hi";
import { NAV_ITEMS, navFor, navItemForPath, PERMISSIONS } from "./permissions";
import { staffQueryString } from "./staff-api";
import type { Me } from "./types";

function user(roles: string[], permissions: string[]): Me {
  return { id: "u1", name: "Test User", email: "test@example.com", roles, permissions, platformAdmin: false, tenant: null };
}

// Seeded role permissions from docs/api/phase-1-staff.md.
const ADMIN = user(
  ["SCHOOL_ADMIN"],
  ["dashboard.view", "staff.read", "staff.manage", "leave.request", "leave.approve", "staff_attendance.manage"],
);
const PRINCIPAL = user(["PRINCIPAL"], ["dashboard.view", "staff.read", "leave.request", "leave.approve"]);
const TEACHER = user(["TEACHER"], ["dashboard.view", "students.read", "attendance.mark", "leave.request"]);
const PARENT = user(["PARENT"], ["dashboard.view", "child.view"]);

describe("staff in the navigation", () => {
  it("defines the permission codes the API uses", () => {
    expect(PERMISSIONS.staffRead).toBe("staff.read");
    expect(PERMISSIONS.staffManage).toBe("staff.manage");
    expect(PERMISSIONS.leaveRequest).toBe("leave.request");
    expect(PERMISSIONS.leaveApprove).toBe("leave.approve");
    expect(PERMISSIONS.staffAttendanceManage).toBe("staff_attendance.manage");
  });

  it("shows staff, staff attendance and leave to admins and principals in the staff group", () => {
    for (const me of [ADMIN, PRINCIPAL]) {
      const keys = navFor(me).map((item) => item.key);
      expect(keys).toEqual(expect.arrayContaining(["staff", "staff-attendance", "leave"]));
    }
    for (const key of ["staff", "staff-attendance", "leave"]) {
      expect(NAV_ITEMS.find((i) => i.key === key)?.group).toBe("staff");
    }
    expect(NAV_ITEMS.find((i) => i.key === "staff")).toMatchObject({ href: "/app/staff", permission: "staff.read" });
    expect(NAV_ITEMS.find((i) => i.key === "leave")).toMatchObject({ href: "/app/leave", permission: "leave.request" });
  });

  it("shows only leave to teachers and nothing to parents", () => {
    const teacher = navFor(TEACHER).map((item) => item.key);
    expect(teacher).toContain("leave");
    expect(teacher).not.toContain("staff");
    expect(teacher).not.toContain("staff-attendance");
    const parent = navFor(PARENT).map((item) => item.key);
    expect(parent).not.toContain("leave");
    expect(parent).not.toContain("staff");
  });

  it("owns the profile and departments pages", () => {
    expect(navItemForPath("/app/staff")?.key).toBe("staff");
    expect(navItemForPath("/app/staff/u1")?.key).toBe("staff");
    expect(navItemForPath("/app/staff/departments")?.key).toBe("staff");
    expect(navItemForPath("/app/staff-attendance")?.key).toBe("staff-attendance");
    expect(navItemForPath("/app/leave")?.key).toBe("leave");
  });

  it("has labels for the navigation, permissions and audit trail in both languages", () => {
    const keys = [
      "nav.group.staff",
      "nav.staff",
      "nav.staffAttendance.short",
      "nav.leave",
      "perm.staff.read",
      "perm.staff.manage",
      "perm.leave.request",
      "perm.leave.approve",
      "perm.staff_attendance.manage",
      "audit.action.user.disabled",
      "audit.action.staff.created",
      "audit.action.leave_request.approved",
      "audit.action.staff_attendance.corrected",
      "audit.entity.staff_attendance_day",
      "audit.entity.leave_request",
    ] as const;
    for (const key of keys) {
      expect(en[key], key).toBeTruthy();
      expect(hi[key], key).toBeTruthy();
    }
  });
});

describe("staffQueryString", () => {
  it("keeps only the values that are set", () => {
    expect(staffQueryString({})).toBe("");
    expect(staffQueryString({ q: "", page: 0, status: "ACTIVE", incomplete: undefined, departmentId: null })).toBe(
      "?page=0&status=ACTIVE",
    );
    expect(staffQueryString({ q: "ravi kumar", incomplete: true })).toBe("?q=ravi+kumar&incomplete=true");
  });
});
