import { describe, expect, it } from "vitest";
import { communicationQuery } from "./communication-api";
import { en } from "./i18n/en";
import { hi } from "./i18n/hi";
import { NAV_ITEMS, navFor, navItemForPath, PERMISSIONS } from "./permissions";
import type { Me } from "./types";

function user(roles: string[], permissions: string[]): Me {
  return { id: "u1", name: "Test User", email: "test@example.com", roles, permissions, platformAdmin: false, tenant: null };
}

// notices.read for everyone, notices.approve and calendar.manage for School Admin and Principal
// (docs/api/phase-1-communication.md).
const PARENT = user(["PARENT"], ["dashboard.view", "child.view", "notices.read"]);
const TEACHER = user(
  ["TEACHER"],
  ["dashboard.view", "students.read", "attendance.mark", "attendance.read", "notices.send", "academics.read", "notices.read"],
);
const PRINCIPAL = user(
  ["PRINCIPAL"],
  ["dashboard.view", "notices.send", "notices.read", "notices.approve", "calendar.manage", "messages.read"],
);
const keys = (me: Me) => navFor(me).map((item) => item.key);

describe("circulars, the notice board and the calendar in the navigation", () => {
  it("gives parents and students the notice board and the calendar, next to their dashboard", () => {
    expect(PERMISSIONS.noticesRead).toBe("notices.read");
    expect(keys(PARENT)).toEqual(["dashboard", "board", "calendar"]);
  });

  it("gives teachers and principals the circulars too, in the Notices group", () => {
    expect(keys(TEACHER)).toEqual(expect.arrayContaining(["board", "notices", "calendar"]));
    const group = navFor(PRINCIPAL).filter((item) => item.group === "communication").map((item) => item.key);
    expect(group).toEqual(["board", "notices", "calendar"]);
    expect(NAV_ITEMS.find((i) => i.key === "notices")).toMatchObject({ href: "/app/notices", permission: "notices.send" });
  });

  it("owns the compose, detail and edit pages", () => {
    expect(navItemForPath("/app/notices/new")?.key).toBe("notices");
    expect(navItemForPath("/app/notices/n1/edit")?.key).toBe("notices");
    expect(navItemForPath("/app/board")?.key).toBe("board");
    expect(navItemForPath("/app/calendar")?.key).toBe("calendar");
  });

  it("has labels for the navigation, permissions, audit trail and message log in both languages", () => {
    const labels = [
      "nav.group.communication",
      "nav.board",
      "nav.notices",
      "nav.calendar",
      "perm.notices.read",
      "perm.notices.approve",
      "perm.calendar.manage",
      "audit.entity.circular",
      "audit.entity.calendar_entry",
      "audit.action.circular.sent",
      "audit.action.circular.withdrawn",
      "audit.action.calendar_entry.reminder_sent",
      "audit.action.communication_settings.updated",
      "messages.template.communication.circular",
      "messages.template.calendar.reminder",
      "messages.template.admissions.enquiry_ack",
    ] as const;
    for (const key of labels) {
      expect(en[key], key).toBeTruthy();
      expect(hi[key], key).toBeTruthy();
    }
  });

  it("builds query strings from the set values only", () => {
    expect(communicationQuery({ page: 0, size: 20, unreadOnly: false })).toBe("?page=0&size=20");
    expect(communicationQuery({ unreadOnly: true, status: undefined })).toBe("?unreadOnly=true");
    expect(communicationQuery({})).toBe("");
  });
});
