import { describe, expect, it } from "vitest";
import { hasPermission, isParentUser, landingPath, navFor, navItemForPath, safeNextPath } from "./permissions";
import type { Me } from "./types";

const ALL_SCHOOL_PERMISSIONS =
  "dashboard.view users.read users.manage roles.read audit.read settings.manage students.read students.manage attendance.mark attendance.read fees.read fees.collect exams.manage notices.send child.view academics.read attendance.manage messages.read fees.manage".split(
    " ",
  );

function user(roles: string[], permissions: string[], platformAdmin = false): Me {
  return {
    id: "u1",
    name: "Test User",
    email: "test@example.com",
    roles,
    permissions,
    platformAdmin,
    tenant: platformAdmin
      ? null
      : {
          id: "t1",
          name: "Sunrise Public School",
          code: "sunrise-public",
          status: "TRIAL",
          plan: "STARTER",
          board: "CBSE",
          city: "Pune",
          trialEndsAt: "2026-10-23T00:00:00Z",
        },
  };
}

// Seeded roles from docs/api/phase-0.md, with academics.read added in docs/api/phase-1.md and
// attendance.manage and messages.read in docs/api/phase-1-attendance.md
const SCHOOL_ADMIN = user(["SCHOOL_ADMIN"], ALL_SCHOOL_PERMISSIONS);
const PRINCIPAL = user(
  ["PRINCIPAL"],
  "dashboard.view users.read roles.read audit.read students.read attendance.read fees.read exams.manage notices.send academics.read attendance.manage messages.read".split(
    " ",
  ),
);
const TEACHER = user(
  ["TEACHER"],
  "dashboard.view students.read attendance.mark attendance.read exams.manage notices.send academics.read".split(" "),
);
const PARENT = user(["PARENT"], ["dashboard.view", "child.view"]);
const PLATFORM_ADMIN = user([], ["platform.admin"], true);

const keys = (me: Me | null) => navFor(me).map((item) => item.key);

describe("navFor", () => {
  it("gives a school admin every school item and no platform items", () => {
    expect(keys(SCHOOL_ADMIN)).toEqual([
      "dashboard",
      "attendance",
      "students",
      "setup",
      "fees",
      "users",
      "roles",
      "audit",
      "messages",
      "notices",
    ]);
  });

  it("gives a principal attendance, read access to students, setup, fees, users, roles and audit, messages and circulars", () => {
    expect(keys(PRINCIPAL)).toEqual([
      "dashboard",
      "attendance",
      "students",
      "setup",
      "fees",
      "users",
      "roles",
      "audit",
      "messages",
      "notices",
    ]);
  });

  it("gives an accountant students, setup and fees", () => {
    const accountant = user(
      ["ACCOUNTANT"],
      ["dashboard.view", "students.read", "fees.read", "fees.collect", "fees.manage", "academics.read"],
    );
    expect(keys(accountant)).toEqual(["dashboard", "students", "setup", "fees"]);
    expect(navFor(accountant).find((item) => item.key === "fees")?.group).toBe("finance");
  });

  it("gives a teacher the dashboard, attendance, students, school setup and circulars", () => {
    expect(keys(TEACHER)).toEqual(["dashboard", "attendance", "students", "setup", "notices"]);
  });

  it("gives front office students and setup without administration", () => {
    const frontOffice = user(["FRONT_OFFICE"], ["dashboard.view", "students.read", "students.manage", "academics.read"]);
    expect(keys(frontOffice)).toEqual(["dashboard", "students", "setup"]);
  });

  it("groups attendance, students and setup under the School group", () => {
    expect(navFor(SCHOOL_ADMIN).filter((item) => item.group === "academics").map((item) => item.key)).toEqual([
      "attendance",
      "students",
      "setup",
    ]);
  });

  it("gives an accountant no attendance and no message log", () => {
    const accountant = user(["ACCOUNTANT"], ["dashboard.view", "students.read", "fees.read", "fees.collect", "academics.read"]);
    expect(keys(accountant)).toEqual(["dashboard", "students", "setup", "fees"]);
  });

  it("gives a parent the family menu instead of the staff one", () => {
    expect(keys(PARENT)).toEqual([
      "dashboard",
      "family-attendance",
      "family-homework",
      "family-fees",
      "family-leave",
      "myPrivacy",
    ]);
  });

  it("gives parents and students the family menu, with fees for parents only", () => {
    const parent = user(["PARENT"], ["dashboard.view", "child.view", "notices.read"]);
    const student = user(["STUDENT"], ["dashboard.view", "notices.read"]);
    expect(keys(parent)).toEqual([
      "dashboard",
      "family-attendance",
      "family-homework",
      "family-fees",
      "board",
      "calendar",
      "family-leave",
      "myPrivacy",
    ]);
    expect(keys(student)).toEqual(["dashboard", "family-attendance", "family-homework", "board", "calendar", "family-leave"]);
    expect(navFor(parent)[0]).toMatchObject({ href: "/app/dashboard", labelKey: "portal.nav.home" });
    // The phone bottom bar shows the first four: Home, Attendance, Homework and Fees (Notices for a student).
    expect(navFor(student).slice(0, 4).map((item) => item.href)).toEqual([
      "/app/dashboard",
      "/app/family/attendance",
      "/app/homework",
      "/app/board",
    ]);
  });

  it("keeps the staff menu for a teacher who is also a parent", () => {
    const both = user(["TEACHER", "PARENT"], [...TEACHER.permissions, "child.view"]);
    expect(keys(both)).toEqual(["dashboard", "attendance", "students", "setup", "notices", "myPrivacy"]);
  });

  it("gives a parent their privacy page in the family menu", () => {
    expect(navFor(PARENT).find((item) => item.key === "myPrivacy")?.href).toBe("/app/my-privacy");
  });

  it("gives data protection to holders of privacy.manage, under Administration", () => {
    const admin = user(["SCHOOL_ADMIN"], [...ALL_SCHOOL_PERMISSIONS, "privacy.manage"]);
    expect(keys(admin)).toEqual([
      "dashboard",
      "attendance",
      "students",
      "setup",
      "fees",
      "users",
      "roles",
      "audit",
      "messages",
      "notices",
      "privacy",
    ]);
    expect(navFor(admin).find((item) => item.key === "privacy")?.group).toBe("administration");
    expect(keys(TEACHER)).not.toContain("privacy");
  });

  it("keeps the parent privacy page away from staff who can also view a child", () => {
    expect(keys(SCHOOL_ADMIN)).not.toContain("myPrivacy");
    const staffParent = user(["TEACHER", "PARENT"], [...TEACHER.permissions, "child.view"]);
    expect(keys(staffParent)).toContain("myPrivacy");
  });

  it("gives a platform admin only the platform items: Schools, Billing and Platform health", () => {
    expect(keys(PLATFORM_ADMIN)).toEqual(["schools", "platformBilling", "platformHealth"]);
  });

  it("returns nothing when signed out", () => {
    expect(keys(null)).toEqual([]);
  });

  it("exposes key, href, labelKey, icon and permission for every item", () => {
    for (const item of navFor(SCHOOL_ADMIN)) {
      expect(item.href.startsWith("/app/")).toBe(true);
      expect(item.labelKey).toMatch(/^nav\./);
      expect(item.icon).toBeTruthy();
      expect(hasPermission(SCHOOL_ADMIN, item.permission)).toBe(true);
    }
  });

  it("does not mutate its input", () => {
    const me = user(["TEACHER"], ["dashboard.view"]);
    const before = JSON.stringify(me);
    navFor(me);
    expect(JSON.stringify(me)).toBe(before);
  });
});

describe("hasPermission", () => {
  it("treats platformAdmin as platform.admin even without the permission string", () => {
    expect(hasPermission(user([], [], true), "platform.admin")).toBe(true);
  });
  it("is false for missing permissions", () => {
    expect(hasPermission(TEACHER, "users.read")).toBe(false);
    expect(hasPermission(null, "dashboard.view")).toBe(false);
  });
});

describe("isParentUser", () => {
  it("is true for parents and for anyone with child.view but no student list", () => {
    expect(isParentUser(PARENT)).toBe(true);
    expect(isParentUser(user(["CUSTOM"], ["child.view"]))).toBe(true);
    expect(isParentUser(SCHOOL_ADMIN)).toBe(false);
    expect(isParentUser(TEACHER)).toBe(false);
    expect(isParentUser(PLATFORM_ADMIN)).toBe(false);
    expect(isParentUser(null)).toBe(false);
  });
});

describe("landingPath", () => {
  it("sends platform admins to Schools and school users to the dashboard", () => {
    expect(landingPath(PLATFORM_ADMIN)).toBe("/app/platform/schools");
    expect(landingPath(TEACHER)).toBe("/app/dashboard");
    expect(landingPath(null)).toBe("/login");
  });
});

describe("navItemForPath", () => {
  it("matches nested paths to their section", () => {
    expect(navItemForPath("/app/users")?.key).toBe("users");
    expect(navItemForPath("/app/users/123")?.key).toBe("users");
    expect(navItemForPath("/app/students/abc")?.key).toBe("students");
    expect(navItemForPath("/app/students/import")?.key).toBe("students");
    expect(navItemForPath("/app/setup")?.key).toBe("setup");
    expect(navItemForPath("/app/attendance/reports")?.key).toBe("attendance");
    expect(navItemForPath("/app/messages")?.key).toBe("messages");
    expect(navItemForPath("/app/fees")?.key).toBe("fees");
    expect(navItemForPath("/app/fees/receipts/abc")?.key).toBe("fees");
    expect(navItemForPath("/app/platform/schools")?.key).toBe("schools");
    expect(navItemForPath("/app/privacy/requests/abc")?.key).toBe("privacy");
    expect(navItemForPath("/app/privacy/notice")?.key).toBe("privacy");
    expect(navItemForPath("/app/my-privacy/requests/abc")?.key).toBe("myPrivacy");
    expect(navItemForPath("/app/family/leave")?.key).toBe("family-leave");
    expect(navItemForPath("/app/family/attendance")?.key).toBe("family-attendance");
    expect(navItemForPath("/app/unknown")).toBeUndefined();
  });
});

describe("safeNextPath", () => {
  it("only allows paths inside the app", () => {
    expect(safeNextPath("/app/users")).toBe("/app/users");
    expect(safeNextPath("/app")).toBe("/app");
    expect(safeNextPath("/apple")).toBeNull();
    expect(safeNextPath("https://evil.example/app")).toBeNull();
    expect(safeNextPath("//evil.example")).toBeNull();
    expect(safeNextPath("/app\\..\\evil")).toBeNull();
    expect(safeNextPath(null)).toBeNull();
  });
});
