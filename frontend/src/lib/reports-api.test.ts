import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { setAccessToken } from "./api";
import { attachmentName, downloadXlsx, reportPaths, reportQuery, XLSX_TYPE } from "./reports-api";

describe("reportQuery", () => {
  it("keeps only the filters that are set", () => {
    expect(reportQuery({ from: "2026-10-01", to: "2026-10-09", classId: undefined, includeLeave: false })).toBe(
      "?from=2026-10-01&to=2026-10-09",
    );
    expect(reportQuery({ classId: "", yearId: null })).toBe("");
    expect(reportQuery({ includeLeave: true, source: "WALK_IN" })).toBe("?includeLeave=true&source=WALK_IN");
  });

  it("encodes values", () => {
    expect(reportQuery({ q: "a&b c" })).toBe("?q=a%26b+c");
  });
});

describe("reportPaths", () => {
  it("builds the JSON and the Excel path of each report", () => {
    expect(reportPaths.sections({ from: "2026-10-01", to: "2026-10-09" })).toBe(
      "/api/reports/attendance/sections?from=2026-10-01&to=2026-10-09",
    );
    expect(reportPaths.sectionsXlsx({ from: "2026-10-01", to: "2026-10-09", classId: "c5" })).toBe(
      "/api/reports/attendance/sections.xlsx?from=2026-10-01&to=2026-10-09&classId=c5",
    );
    expect(reportPaths.absenteesXlsx({ date: "2026-10-09", includeLeave: true })).toBe(
      "/api/reports/attendance/absentees.xlsx?date=2026-10-09&includeLeave=true",
    );
    expect(reportPaths.homework({})).toBe("/api/reports/homework/completion");
    expect(reportPaths.funnelXlsx({ yearId: "y1" })).toBe("/api/reports/admissions/funnel.xlsx?yearId=y1");
    expect(reportPaths.leave({ departmentId: "d1" })).toBe("/api/reports/staff/leave?departmentId=d1");
  });
});

describe("attachmentName", () => {
  it("reads the plain and the UTF-8 file name, else falls back", () => {
    expect(attachmentName('attachment; filename="absentees-2026-10-09.xlsx"', "x.xlsx")).toBe(
      "absentees-2026-10-09.xlsx",
    );
    expect(attachmentName("attachment; filename*=UTF-8''leave%20taken.xlsx", "x.xlsx")).toBe("leave taken.xlsx");
    expect(attachmentName("attachment; filename=plain.xlsx", "x.xlsx")).toBe("plain.xlsx");
    expect(attachmentName(null, "x.xlsx")).toBe("x.xlsx");
    expect(attachmentName("attachment", "x.xlsx")).toBe("x.xlsx");
  });
});

describe("downloadXlsx", () => {
  const fetchMock = vi.fn<typeof fetch>();
  const createObjectURL = vi.fn(() => "blob:report");
  const revokeObjectURL = vi.fn();
  let clicked: { href: string; download: string }[] = [];

  beforeEach(() => {
    setAccessToken("t1");
    fetchMock.mockReset();
    clicked = [];
    vi.stubGlobal("fetch", fetchMock);
    Object.assign(URL, { createObjectURL, revokeObjectURL });
    vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(function (this: HTMLAnchorElement) {
      clicked.push({ href: this.href, download: this.download });
    });
  });

  afterEach(() => {
    setAccessToken(null);
    vi.unstubAllGlobals();
  });

  it("downloads with the bearer token and saves under the server's file name", async () => {
    fetchMock.mockResolvedValueOnce(
      new Response(new Uint8Array([80, 75, 3, 4]), {
        status: 200,
        headers: {
          "Content-Type": XLSX_TYPE,
          "Content-Disposition": 'attachment; filename="attendance-by-section-2026-10-01-to-2026-10-09.xlsx"',
        },
      }),
    );
    const name = await downloadXlsx("/api/reports/attendance/sections.xlsx?from=2026-10-01", "fallback.xlsx");
    expect(name).toBe("attendance-by-section-2026-10-01-to-2026-10-09.xlsx");
    const [path, init] = fetchMock.mock.calls[0];
    expect(path).toBe("/api/reports/attendance/sections.xlsx?from=2026-10-01");
    expect((init?.headers as Record<string, string>).Authorization).toBe("Bearer t1");
    expect(clicked).toEqual([{ href: "blob:report", download: "attendance-by-section-2026-10-01-to-2026-10-09.xlsx" }]);
    expect(revokeObjectURL).toHaveBeenCalledWith("blob:report");
    expect(document.querySelector("a[download]")).toBeNull();
  });

  it("throws the API's error and saves nothing", async () => {
    fetchMock.mockResolvedValueOnce(
      new Response(JSON.stringify({ status: 403, title: "Forbidden" }), {
        status: 403,
        headers: { "Content-Type": "application/problem+json" },
      }),
    );
    await expect(downloadXlsx("/api/reports/staff/leave.xlsx", "leave.xlsx")).rejects.toMatchObject({ status: 403 });
    expect(clicked).toEqual([]);
  });
});
