import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { auditQuery, type PlatformAuditEvent } from "@/lib/audit-api";
import { translate, type Translate } from "@/lib/i18n";
import type { AuditEvent } from "@/lib/types";
import { renderAsSuperAdmin, renderInSchool } from "@/test/billing-fixtures";
import { AUDIT_PAGE_SIZE, AuditView, badRange } from "./audit-view";
import { ActivityView, activityNote } from "./billing/activity-view";

const { audit, platform, downloadCsv } = vi.hoisted(() => ({
  audit: { list: vi.fn(), facets: vi.fn() },
  platform: { list: vi.fn(), facets: vi.fn() },
  downloadCsv: vi.fn(),
}));

vi.mock("@/lib/audit-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/audit-api")>();
  return {
    ...actual,
    auditApi: { ...actual.auditApi, ...audit },
    platformAuditApi: { ...actual.platformAuditApi, ...platform },
  };
});

vi.mock("@/lib/fees-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/fees-api")>();
  return { ...actual, downloadCsv };
});

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  usePathname: () => "/app/audit",
}));

const t: Translate = (key, vars) => translate("en", key, vars);

function event(i: number, overrides: Partial<AuditEvent> = {}): AuditEvent {
  return {
    id: `e${i}`,
    at: new Date(Date.UTC(2026, 9, 20, 10, 0) - i * 60_000).toISOString(),
    actorName: "Priya Nair",
    action: "user.created",
    entityType: "user",
    entityId: `u${i}`,
    details: {},
    ...overrides,
  };
}

beforeEach(() => {
  Object.values(audit).forEach((fn) => fn.mockReset());
  Object.values(platform).forEach((fn) => fn.mockReset());
  downloadCsv.mockReset();
  audit.facets.mockResolvedValue({
    actions: ["user.created", "auth.login"],
    people: [{ id: "u1", name: "Priya Nair" }],
  });
});

describe("audit filters", () => {
  it("sends only the filters that are set", () => {
    expect(auditQuery({}, 50)).toBe("?limit=50");
    expect(auditQuery({ action: "user.created", actorId: "", from: "2026-10-01" })).toBe(
      "?action=user.created&from=2026-10-01",
    );
    expect(badRange({ from: "2026-10-02", to: "2026-10-01" })).toBe(true);
    expect(badRange({ from: "2026-10-01", to: "2026-10-01" })).toBe(false);
  });
});

describe("AuditView", () => {
  it("filters by action and person, loads older entries and downloads what is shown", async () => {
    const user = userEvent.setup();
    const firstPage = Array.from({ length: AUDIT_PAGE_SIZE }, (_, i) => event(i));
    audit.list.mockImplementation((filters: { before?: string }) =>
      Promise.resolve(filters.before ? [event(AUDIT_PAGE_SIZE)] : firstPage),
    );
    renderInSchool(["audit.read"], <AuditView />);
    const table = await screen.findByTestId("audit-table");
    expect(within(table).getAllByRole("row")).toHaveLength(AUDIT_PAGE_SIZE + 1);
    expect(screen.getByText(`${AUDIT_PAGE_SIZE} entries shown, newest first`)).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Load older entries" }));
    const last = firstPage[firstPage.length - 1];
    await waitFor(() =>
      expect(audit.list).toHaveBeenLastCalledWith({ before: last.at, beforeId: last.id }, AUDIT_PAGE_SIZE),
    );
    expect(await screen.findByText(`${AUDIT_PAGE_SIZE + 1} entries shown, newest first`)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Load older entries" })).not.toBeInTheDocument();

    audit.list.mockResolvedValue([]);
    await user.selectOptions(screen.getByLabelText("Action"), "user.created");
    await user.selectOptions(screen.getByLabelText("Person"), "u1");
    await waitFor(() =>
      expect(audit.list).toHaveBeenLastCalledWith({ action: "user.created", actorId: "u1" }, AUDIT_PAGE_SIZE),
    );
    expect(await screen.findByText("Nothing matches these filters.")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Download for Excel" }));
    expect(downloadCsv).toHaveBeenCalledWith(
      "/api/audit-events/export.csv?action=user.created&actorId=u1",
      "audit-trail.csv",
    );
  });

  it("refuses a backwards range without asking the API", async () => {
    const user = userEvent.setup();
    audit.list.mockResolvedValue([event(1)]);
    renderInSchool(["audit.read"], <AuditView />);
    await screen.findByTestId("audit-table");
    audit.list.mockClear();
    await user.type(screen.getByLabelText("From"), "2026-10-10");
    await user.type(screen.getByLabelText("To"), "2026-10-01");
    expect(await screen.findByText("The “to” day is before the “from” day.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Download for Excel" })).toBeDisabled();
  });
});

describe("ActivityView", () => {
  const row = (overrides: Partial<PlatformAuditEvent> = {}): PlatformAuditEvent => ({
    ...event(1),
    actorName: "Ravi",
    action: "subscription.cancel_scheduled",
    entityType: "school",
    platformAdmin: true,
    schoolId: "t1",
    schoolName: "Sunrise Public School",
    schoolCode: "sunrise-public",
    ip: null,
    details: { reason: "Asked by phone", onBehalf: true },
    ...overrides,
  });

  it("words an entry's details", () => {
    expect(activityNote(t, { details: { invoiceNo: "AKS/26-27/000007", automatic: true } })).toBe(
      "AKS/26-27/000007 · automatic",
    );
    expect(activityNote(t, { details: { plan: "STARTER", previousPlan: "GROWTH" } })).toBe("GROWTH → STARTER");
    expect(activityNote(t, { details: {} })).toBeNull();
  });

  it("lists Super Admin and school actions across schools, filtered by school", async () => {
    const user = userEvent.setup();
    platform.facets.mockResolvedValue({
      actions: ["subscription.cancel_scheduled", "platform.login"],
      schools: [{ id: "t1", name: "Sunrise Public School", code: "sunrise-public" }],
    });
    platform.list.mockResolvedValue([
      row(),
      row({
        id: "e2",
        action: "platform.login_failed",
        actorName: null,
        platformAdmin: false,
        schoolId: null,
        schoolName: null,
        schoolCode: null,
        details: { email: "ravi@akshara.test" },
      }),
    ]);
    renderAsSuperAdmin(<ActivityView />);
    const table = await screen.findByTestId("activity-table");
    expect(within(table).getByRole("link", { name: "Sunrise Public School" })).toHaveAttribute(
      "href",
      "/app/platform/schools/t1",
    );
    expect(within(table).getByText("Asked by phone · for the school")).toBeInTheDocument();
    expect(within(table).getByText("Super Admin sign-in failed")).toBeInTheDocument();
    expect(within(table).getByText("ravi@akshara.test")).toBeInTheDocument();

    await user.selectOptions(screen.getByLabelText("School"), "t1");
    await waitFor(() => expect(platform.list).toHaveBeenLastCalledWith({ schoolId: "t1" }, AUDIT_PAGE_SIZE));
  });
});
