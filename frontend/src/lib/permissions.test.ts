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
      "exams",
      "students",
      "setup",
      "fees",
      "users",
      "roles",
      "audit",
      "messages",
      "notices",
      "reports",
    ]);
  });

  it("gives a principal attendance, read access to students, setup, fees, users, roles and audit, messages and circulars", () => {
    expect(keys(PRINCIPAL)).toEqual([
      "dashboard",
      "attendance",
      "exams",
      "students",
      "setup",
      "fees",
      "users",
      "roles",
      "audit",
      "messages",
      "notices",
      "reports",
    ]);
  });

  it("gives an accountant students, setup and fees", () => {
    const accountant = user(
      ["ACCOUNTANT"],
      ["dashboard.view", "students.read", "fees.read", "fees.collect", "fees.manage", "academics.read"],
    );
    expect(keys(accountant)).toEqual(["dashboard", "students", "setup", "fees", "reports"]);
    expect(navFor(accountant).find((item) => item.key === "fees")?.group).toBe("finance");
  });

  it("gives a teacher the dashboard, attendance, students, school setup and circulars", () => {
    expect(keys(TEACHER)).toEqual(["dashboard", "attendance", "exams", "students", "setup", "notices", "reports"]);
  });

  it("gives a librarian the library under the School group, and the reports hub for its reports", () => {
    const librarian = user(["LIBRARIAN"], ["dashboard.view", "library.read", "library.manage", "notices.read"]);
    expect(keys(librarian)).toEqual(["dashboard", "library", "board", "calendar", "reports"]);
    expect(navFor(librarian).find((item) => item.key === "library")).toMatchObject({
      href: "/app/library",
      group: "academics",
      labelKey: "nav.library",
    });
    // Every member of staff may search the catalogue; only library.manage opens the reports hub.
    expect(keys(user(["TEACHER"], [...TEACHER.permissions, "library.read"]))).toEqual([
      "dashboard",
      "attendance",
      "exams",
      "students",
      "library",
      "setup",
      "notices",
      "reports",
    ]);
  });

  it("gives front office students and setup without administration", () => {
    const frontOffice = user(["FRONT_OFFICE"], ["dashboard.view", "students.read", "students.manage", "academics.read"]);
    expect(keys(frontOffice)).toEqual(["dashboard", "students", "setup"]);
  });

  it("opens the front office with frontoffice.read, the gate with gate.pass, and pickup to parents", () => {
    // Seeded in docs/api/phase-2-frontoffice.md: front office holds frontoffice.read/manage and gate.pass, security
    // only gate.pass, parents reach early pickup through child.view.
    const frontOffice = user(["FRONT_OFFICE"], ["dashboard.view", "students.read", "frontoffice.read", "frontoffice.manage", "gate.pass"]);
    expect(keys(frontOffice)).toEqual(["dashboard", "students", "frontoffice", "gate", "reports"]);
    expect(navFor(frontOffice).find((item) => item.key === "frontoffice")).toMatchObject({
      href: "/app/frontoffice",
      labelKey: "nav.frontoffice",
      group: "administration",
    });
    const security = user(["SECURITY"], ["dashboard.view", "gate.pass"]);
    expect(keys(security)).toEqual(["dashboard", "gate"]);
    expect(navFor(security).find((item) => item.key === "gate")).toMatchObject({ href: "/app/frontoffice/gate", labelKey: "nav.gate" });
    expect(navFor(PARENT).find((item) => item.key === "family-pickup")).toMatchObject({ href: "/app/family/pickup", labelKey: "nav.pickup" });
    const student = user(["STUDENT"], ["dashboard.view", "notices.read"]);
    expect(keys(student)).not.toContain("family-pickup");
    expect(navItemForPath("/app/frontoffice/passes")?.key).toBe("frontoffice");
    expect(navItemForPath("/app/frontoffice/gate")?.key).toBe("gate");
    expect(navItemForPath("/app/family/pickup")?.key).toBe("family-pickup");
  });

  it("groups attendance, students and setup under the School group", () => {
    expect(navFor(SCHOOL_ADMIN).filter((item) => item.group === "academics").map((item) => item.key)).toEqual([
      "attendance",
      "exams",
      "students",
      "setup",
    ]);
  });

  it("gives an accountant no attendance and no message log", () => {
    const accountant = user(["ACCOUNTANT"], ["dashboard.view", "students.read", "fees.read", "fees.collect", "academics.read"]);
    expect(keys(accountant)).toEqual(["dashboard", "students", "setup", "fees", "reports"]);
  });

  it("gives a parent the family menu instead of the staff one", () => {
    expect(keys(PARENT)).toEqual([
      "dashboard",
      "family-attendance",
      "family-homework",
      "family-fees",
      "family-pickup",
      "family-leave",
      "family-library",
      "family-results",
      "family-study",
      "family-bus",
      "family-hostel",
      "family-certificates",
      "myPrivacy",
      "family-chat",
      "family-ptm",
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
      "family-pickup",
      "board",
      "calendar",
      "family-leave",
      "family-library",
      "family-results",
      "family-study",
      "family-bus",
      "family-hostel",
      "family-certificates",
      "myPrivacy",
      "family-chat",
      "family-ptm",
    ]);
    expect(keys(student)).toEqual([
      "dashboard",
      "family-attendance",
      "family-homework",
      "board",
      "calendar",
      "family-leave",
      "family-library",
      "family-results",
      "family-study",
      "family-bus",
    ]);
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
    expect(keys(both)).toEqual([
      "dashboard",
      "attendance",
      "exams",
      "students",
      "setup",
      "notices",
      "myPrivacy",
      "reports",
    ]);
  });

  it("gives a parent their privacy page in the family menu", () => {
    expect(navFor(PARENT).find((item) => item.key === "myPrivacy")?.href).toBe("/app/my-privacy");
  });

  it("gives data protection to holders of privacy.manage, under Administration", () => {
    const admin = user(["SCHOOL_ADMIN"], [...ALL_SCHOOL_PERMISSIONS, "privacy.manage"]);
    expect(keys(admin)).toEqual([
      "dashboard",
      "attendance",
      "exams",
      "students",
      "setup",
      "fees",
      "users",
      "roles",
      "audit",
      "messages",
      "notices",
      "privacy",
      "reports",
    ]);
    expect(navFor(admin).find((item) => item.key === "privacy")?.group).toBe("administration");
    expect(keys(TEACHER)).not.toContain("privacy");
  });

  it("keeps the parent privacy page away from staff who can also view a child", () => {
    expect(keys(SCHOOL_ADMIN)).not.toContain("myPrivacy");
    const staffParent = user(["TEACHER", "PARENT"], [...TEACHER.permissions, "child.view"]);
    expect(keys(staffParent)).toContain("myPrivacy");
  });

  it("gives payroll under Finance to payroll readers and My payslips under Staff to every staff member", () => {
    // Seeded in docs/api/phase-2-payroll.md: School Admin holds all three payroll permissions, Accountant read and
    // manage; every staff role holds leave.request.
    const admin = user(["SCHOOL_ADMIN"], [
      ...ALL_SCHOOL_PERMISSIONS,
      "leave.request",
      "payroll.read",
      "payroll.manage",
      "payroll.approve",
    ]);
    expect(keys(admin)).toEqual([
      "dashboard",
      "attendance",
      "exams",
      "students",
      "setup",
      "fees",
      "payroll",
      "leave",
      "myPayslips",
      "users",
      "roles",
      "audit",
      "messages",
      "notices",
      "reports",
    ]);
    const accountant = user(["ACCOUNTANT"], [
      "dashboard.view",
      "students.read",
      "fees.read",
      "academics.read",
      "leave.request",
      "payroll.read",
      "payroll.manage",
    ]);
    expect(keys(accountant)).toEqual(["dashboard", "students", "setup", "fees", "payroll", "leave", "myPayslips", "reports"]);
    expect(keys(user(["TEACHER"], [...TEACHER.permissions, "leave.request"]))).toEqual([
      "dashboard",
      "attendance",
      "exams",
      "students",
      "setup",
      "leave",
      "myPayslips",
      "notices",
      "reports",
    ]);
    expect(keys(PARENT)).not.toContain("myPayslips");
    expect(navItemForPath("/app/payroll/runs/r1")?.key).toBe("payroll");
    expect(navItemForPath("/app/my-payslips/p1")?.key).toBe("myPayslips");
  });

  it("opens exams with any exams permission and the reports hub with exams.read", () => {
    const reader = user(["CUSTOM"], ["dashboard.view", "exams.read"]);
    expect(keys(reader)).toEqual(["dashboard", "exams", "reports"]);
    const setup = user(["CUSTOM"], ["dashboard.view", "exams.setup"]);
    expect(keys(setup)).toEqual(["dashboard", "exams"]);
    expect(keys(user(["CUSTOM"], ["dashboard.view"]))).toEqual(["dashboard"]);
    expect(navFor(reader).find((item) => item.key === "exams")).toMatchObject({ href: "/app/exams", group: "academics" });
    expect(navItemForPath("/app/exams/marks/p1/s1")?.key).toBe("exams");
    expect(navItemForPath("/app/family/results")?.key).toBe("family-results");
  });

  it("gives Chat and PTM under Communication by chat and PTM permissions, apart from the message log", () => {
    // Seeded in docs/api/phase-2-chat.md: Teacher holds chat.use; Principal and School Admin all three.
    const teacher = user(["TEACHER"], [...TEACHER.permissions, "chat.use"]);
    expect(keys(teacher)).toEqual(["dashboard", "attendance", "exams", "students", "setup", "notices", "chat", "ptm", "reports"]);
    const principal = user(["PRINCIPAL"], [...PRINCIPAL.permissions, "chat.use", "chat.oversee", "ptm.manage"]);
    expect(keys(principal)).toEqual([
      "dashboard",
      "attendance",
      "exams",
      "students",
      "setup",
      "fees",
      "users",
      "roles",
      "audit",
      "messages",
      "notices",
      "chat",
      "ptm",
      "reports",
    ]);
    expect(navFor(principal).find((item) => item.key === "chat")).toMatchObject({
      href: "/app/chat",
      group: "communication",
      labelKey: "nav.chat",
    });
    expect(navFor(principal).find((item) => item.key === "ptm")).toMatchObject({ href: "/app/ptm", group: "communication" });
    // Oversight alone opens Chat; meeting setup alone opens PTM and the reports hub for the attendance summary.
    expect(keys(user(["CUSTOM"], ["dashboard.view", "chat.oversee"]))).toEqual(["dashboard", "chat"]);
    expect(keys(user(["CUSTOM"], ["dashboard.view", "ptm.manage"]))).toEqual(["dashboard", "ptm", "reports"]);
    expect(keys(TEACHER)).not.toContain("chat");
    expect(navItemForPath("/app/chat/oversight/c1")?.key).toBe("chat");
    expect(navItemForPath("/app/ptm/e1/print")?.key).toBe("ptm");
    expect(navItemForPath("/app/messages")?.key).toBe("messages");
  });

  it("gives parents Messages and PTM after the bottom bar's four, and nothing of them to students", () => {
    const parent = user(["PARENT"], ["dashboard.view", "child.view", "notices.read"]);
    expect(navFor(parent).find((item) => item.key === "family-chat")).toMatchObject({
      href: "/app/family/chat",
      labelKey: "nav.familyChat",
    });
    expect(navFor(parent).find((item) => item.key === "family-ptm")).toMatchObject({ href: "/app/family/ptm" });
    expect(navFor(parent).slice(0, 4).map((item) => item.key)).toEqual([
      "dashboard",
      "family-attendance",
      "family-homework",
      "family-fees",
    ]);
    const student = user(["STUDENT"], ["dashboard.view", "notices.read"]);
    expect(keys(student)).not.toContain("family-chat");
    expect(keys(student)).not.toContain("family-ptm");
    expect(navItemForPath("/app/family/chat")?.key).toBe("family-chat");
    expect(navItemForPath("/app/family/ptm")?.key).toBe("family-ptm");
  });

  it("opens lessons with any lessons permission, adds coverage to the reports hub and study to the family menu", () => {
    // Seeded in docs/api/phase-2-lessons.md: teachers hold lessons.read and lessons.manage, principals lessons.read
    // and lessons.approve.
    const teacher = user(["TEACHER"], [...TEACHER.permissions, "lessons.read", "lessons.manage"]);
    expect(keys(teacher)).toEqual(["dashboard", "attendance", "exams", "lessons", "students", "setup", "notices", "reports"]);
    const principal = user(["PRINCIPAL"], [...PRINCIPAL.permissions, "lessons.read", "lessons.approve"]);
    expect(keys(principal).slice(0, 5)).toEqual(["dashboard", "attendance", "exams", "lessons", "students"]);
    expect(keys(user(["CUSTOM"], ["dashboard.view", "lessons.approve"]))).toEqual(["dashboard", "lessons"]);
    expect(keys(user(["CUSTOM"], ["dashboard.view", "lessons.read"]))).toEqual(["dashboard", "lessons", "reports"]);
    expect(navFor(teacher).find((item) => item.key === "lessons")).toMatchObject({ href: "/app/lessons", group: "academics" });
    expect(navItemForPath("/app/lessons/tests/t1/results")?.key).toBe("lessons");
    expect(navItemForPath("/app/family/study/tests/t1")?.key).toBe("family-study");
    expect(navFor(PARENT).find((item) => item.key === "family-study")).toMatchObject({
      href: "/app/family/study",
      labelKey: "nav.study",
    });
  });

  it("gives transport under the School group, bus trips to crew and the Bus page to families", () => {
    // Seeded in docs/api/phase-2-transport.md: Transport manager holds read, manage and trip; Bus attendant only trip.
    const manager = user(["TRANSPORT_MANAGER"], [
      "dashboard.view",
      "academics.read",
      "leave.request",
      "notices.read",
      "timetable.read",
      "transport.read",
      "transport.manage",
      "transport.trip",
    ]);
    expect(keys(manager)).toEqual([
      "dashboard",
      "timetable",
      "transport",
      "setup",
      "busTrips",
      "leave",
      "myPayslips",
      "board",
      "calendar",
      "reports",
    ]);
    expect(navFor(manager).find((item) => item.key === "transport")).toMatchObject({
      href: "/app/transport",
      group: "academics",
      labelKey: "nav.transport",
    });
    // An attendant's phone keeps Bus trips in the bottom bar (the first four items).
    const attendant = user(["BUS_ATTENDANT"], ["dashboard.view", "leave.request", "notices.read", "transport.trip"]);
    expect(keys(attendant)).toEqual(["dashboard", "busTrips", "leave", "myPayslips", "board", "calendar"]);
    expect(navFor(attendant).slice(0, 4).map((item) => item.href)).toContain("/app/transport/trip");
    // The principal reads transport, which also opens the reports hub.
    expect(keys(user(["PRINCIPAL"], [...PRINCIPAL.permissions, "transport.read"]))).toEqual([
      "dashboard",
      "attendance",
      "exams",
      "students",
      "transport",
      "setup",
      "fees",
      "users",
      "roles",
      "audit",
      "messages",
      "notices",
      "reports",
    ]);
    expect(keys(user(["CUSTOM"], ["dashboard.view", "transport.read"]))).toEqual(["dashboard", "transport", "reports"]);
    expect(navItemForPath("/app/transport/routes/r1")?.key).toBe("transport");
    expect(navItemForPath("/app/transport/trip")?.key).toBe("busTrips");
    expect(navItemForPath("/app/family/bus")?.key).toBe("family-bus");
    expect(navFor(PARENT).find((item) => item.key === "family-bus")).toMatchObject({
      href: "/app/family/bus",
      labelKey: "nav.bus",
    });
  });

  it("gives certificates and ID cards to certificates.read, under Administration, and a child's to parents", () => {
    // Seeded in docs/api/phase-2-certificates.md: the office and admins read and issue, the principal signs.
    const office = user(["FRONT_OFFICE"], ["dashboard.view", "students.read", "certificates.read", "certificates.issue"]);
    expect(keys(office)).toEqual(["dashboard", "students", "certificates", "reports"]);
    expect(navFor(office).find((item) => item.key === "certificates")).toMatchObject({
      href: "/app/certificates",
      labelKey: "nav.certificates",
      group: "administration",
    });
    expect(keys(TEACHER)).not.toContain("certificates");
    expect(keys(user(["STUDENT"], ["dashboard.view"]))).not.toContain("family-certificates");
    expect(navFor(PARENT).find((item) => item.key === "family-certificates")?.href).toBe("/app/family/certificates");
    expect(navItemForPath("/app/certificates/id-cards")?.key).toBe("certificates");
    expect(navItemForPath("/app/certificates/c1")?.key).toBe("certificates");
    expect(navItemForPath("/app/family/certificates/c1")?.key).toBe("family-certificates");
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

describe("navFor: accounts", () => {
  it("gives Accounts under Finance to readers, approvers and managers (docs/api/phase-2-accounts.md)", () => {
    // Seeded: School Admin and Accountant hold read and manage, Principal read and approve.
    const accountant = user(["ACCOUNTANT"], [
      "dashboard.view",
      "fees.read",
      "academics.read",
      "leave.request",
      "accounts.read",
      "accounts.manage",
    ]);
    expect(keys(accountant)).toEqual(["dashboard", "setup", "fees", "accounts", "leave", "myPayslips", "reports"]);
    const principal = user(["PRINCIPAL"], ["dashboard.view", "leave.request", "accounts.read", "accounts.approve"]);
    expect(keys(principal)).toEqual(["dashboard", "accounts", "leave", "myPayslips", "reports"]);
    // Someone given only the approve permission still reaches the queue.
    expect(keys(user(["PRINCIPAL"], ["dashboard.view", "accounts.approve"]))).toEqual(["dashboard", "accounts"]);
    expect(keys(user(["TEACHER"], [...TEACHER.permissions, "leave.request"]))).not.toContain("accounts");
    expect(navItemForPath("/app/accounts/expenses/e1")?.key).toBe("accounts");
    expect(navItemForPath("/app/reports/accounts-income-vs-expense")?.key).toBe("reports");
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
    expect(navItemForPath("/app/library/desk")?.key).toBe("library");
    expect(navItemForPath("/app/family/library")?.key).toBe("family-library");
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
