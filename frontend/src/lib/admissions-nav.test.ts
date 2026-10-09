import { describe, expect, it } from "vitest";
import { en } from "./i18n/en";
import { hi } from "./i18n/hi";
import { NAV_ITEMS, navFor, navItemForPath, PERMISSIONS } from "./permissions";
import type { Me } from "./types";

function user(roles: string[], permissions: string[]): Me {
  return { id: "u1", name: "Test User", email: "test@example.com", roles, permissions, platformAdmin: false, tenant: null };
}

// The roles that run admissions get admissions.read and admissions.manage (docs/api/phase-1-admissions.md).
const FRONT_OFFICE = user(
  ["FRONT_OFFICE"],
  ["dashboard.view", "students.read", "students.manage", "academics.read", "admissions.read", "admissions.manage"],
);
const TEACHER = user(["TEACHER"], ["dashboard.view", "students.read", "academics.read", "attendance.mark"]);
const ACCOUNTANT = user(["ACCOUNTANT"], ["dashboard.view", "fees.read", "fees.collect"]);

describe("admissions in the navigation", () => {
  it("is shown to the people who run admissions, after Students", () => {
    expect(PERMISSIONS.admissionsRead).toBe("admissions.read");
    expect(PERMISSIONS.admissionsManage).toBe("admissions.manage");
    const keys = navFor(FRONT_OFFICE).map((item) => item.key);
    expect(keys).toContain("admissions");
    expect(keys.indexOf("admissions")).toBe(keys.indexOf("students") + 1);
    const item = NAV_ITEMS.find((i) => i.key === "admissions");
    expect(item).toMatchObject({ href: "/app/admissions", permission: "admissions.read", group: "academics" });
  });

  it("is hidden from teachers and accountants", () => {
    expect(navFor(TEACHER).map((item) => item.key)).not.toContain("admissions");
    expect(navFor(ACCOUNTANT).map((item) => item.key)).not.toContain("admissions");
  });

  it("owns the application and offer letter pages", () => {
    expect(navItemForPath("/app/admissions")?.key).toBe("admissions");
    expect(navItemForPath("/app/admissions/a1")?.key).toBe("admissions");
    expect(navItemForPath("/app/admissions/a1/offer-letter")?.key).toBe("admissions");
  });

  it("has labels for the navigation, the permissions and the audit trail in both languages", () => {
    const keys = [
      "nav.admissions",
      "nav.admissions.short",
      "perm.admissions.read",
      "perm.admissions.manage",
      "audit.entity.application",
      "audit.entity.assessment_slot",
      "audit.action.application.enquiry_received",
      "audit.action.application.admitted",
    ] as const;
    for (const key of keys) {
      expect(en[key], key).toBeTruthy();
      expect(hi[key], key).toBeTruthy();
    }
  });
});
