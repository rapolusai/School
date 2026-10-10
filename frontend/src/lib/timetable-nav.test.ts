import { describe, expect, it } from "vitest";
import { en } from "./i18n/en";
import { hi } from "./i18n/hi";
import { NAV_ITEMS, navFor, navItemForPath, PERMISSIONS } from "./permissions";
import type { Me } from "./types";

function user(roles: string[], permissions: string[]): Me {
  return { id: "u1", name: "Test User", email: "test@example.com", roles, permissions, platformAdmin: false, tenant: null };
}

// Seeded roles from docs/api/phase-1-timetable-homework.md.
const PRINCIPAL = user(
  ["PRINCIPAL"],
  ["dashboard.view", "students.read", "attendance.read", "timetable.read", "timetable.manage", "homework.manage"],
);
const TEACHER = user(
  ["TEACHER"],
  ["dashboard.view", "students.read", "attendance.mark", "attendance.read", "timetable.read", "homework.manage"],
);
const ACCOUNTANT = user(["ACCOUNTANT"], ["dashboard.view", "fees.read", "timetable.read"]);
const STUDENT = user(["STUDENT"], ["dashboard.view"]);
const PARENT = user(["PARENT"], ["dashboard.view", "child.view"]);

const keys = (me: Me) => navFor(me).map((item) => item.key);

describe("timetable and homework in the navigation", () => {
  it("shows Timetable to staff who read it and Homework to those who set it, after Attendance", () => {
    expect(PERMISSIONS.timetableRead).toBe("timetable.read");
    expect(PERMISSIONS.timetableManage).toBe("timetable.manage");
    expect(PERMISSIONS.homeworkManage).toBe("homework.manage");
    for (const me of [PRINCIPAL, TEACHER]) {
      const list = keys(me);
      expect(list.indexOf("timetable")).toBe(list.indexOf("attendance") + 1);
      expect(list.indexOf("homework")).toBe(list.indexOf("timetable") + 1);
    }
    expect(NAV_ITEMS.find((i) => i.key === "timetable")).toMatchObject({
      href: "/app/timetable",
      permission: "timetable.read",
      group: "academics",
    });
    expect(NAV_ITEMS.find((i) => i.key === "homework")).toMatchObject({
      href: "/app/homework",
      permission: "homework.manage",
      group: "academics",
    });
  });

  it("gives an accountant the timetable but not homework, and students and parents neither", () => {
    expect(keys(ACCOUNTANT)).toContain("timetable");
    expect(keys(ACCOUNTANT)).not.toContain("homework");
    // Students and parents get the family menu (docs/api/phase-1-portal.md), whose Homework is their own view.
    for (const family of [STUDENT, PARENT]) {
      expect(keys(family)).not.toContain("timetable");
      expect(keys(family)).not.toContain("homework");
    }
  });

  it("owns the substitution sheet and homework detail pages", () => {
    expect(navItemForPath("/app/timetable/substitutions/print")?.key).toBe("timetable");
    expect(navItemForPath("/app/homework/h1")?.key).toBe("homework");
  });

  it("has labels for the navigation, the permissions and the audit trail in both languages", () => {
    const labels = [
      "nav.timetable",
      "nav.timetable.short",
      "nav.homework",
      "nav.homework.short",
      "perm.timetable.read",
      "perm.timetable.manage",
      "perm.homework.manage",
      "audit.entity.bell_schedule",
      "audit.entity.teacher_assignment",
      "audit.entity.teacher_absence",
      "audit.entity.substitution",
      "audit.entity.homework",
      "audit.entity.submission",
      "audit.entity.homework_settings",
      "audit.action.bell_schedule.updated",
      "audit.action.teacher_assignment.created",
      "audit.action.teacher_assignment.updated",
      "audit.action.teacher_assignment.deleted",
      "audit.action.timetable.section_saved",
      "audit.action.teacher_absence.recorded",
      "audit.action.teacher_absence.removed",
      "audit.action.substitution.assigned",
      "audit.action.substitution.removed",
      "audit.action.homework.created",
      "audit.action.homework.updated",
      "audit.action.homework.deleted",
      "audit.action.homework.attachment_added",
      "audit.action.homework.attachment_removed",
      "audit.action.homework.submitted",
      "audit.action.homework.reviewed",
      "audit.action.homework_settings.updated",
    ] as const;
    for (const key of labels) {
      expect(en[key], key).toBeTruthy();
      expect(hi[key], key).toBeTruthy();
    }
  });
});
