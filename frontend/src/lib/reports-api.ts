import { apiFetch, getAccessToken, parseErrorResponse, refreshSession, toApiError } from "./api";
import type {
  AbsenteesReport,
  DashboardSummary,
  FunnelReport,
  HomeworkCompletionReport,
  LeaveTakenReport,
  SectionAttendanceReport,
} from "./types";

/* Endpoint helpers for docs/api/phase-1-reports.md (dashboard and reports). */

export const XLSX_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

export type ReportParams = Record<string, string | boolean | null | undefined>;

/** "?from=…&classId=…" from the set filters only (false and empty values are left out). Exported for tests. */
export function reportQuery(params: ReportParams): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === "" || value === false) continue;
    search.set(key, String(value));
  }
  const text = search.toString();
  return text ? `?${text}` : "";
}

/** The file name from a Content-Disposition header, or the fallback. Exported for tests. */
export function attachmentName(header: string | null, fallback: string): string {
  if (!header) return fallback;
  const star = /filename\*=UTF-8''([^;]+)/i.exec(header);
  if (star) {
    try {
      return decodeURIComponent(star[1].trim());
    } catch {
      // fall through to the plain name
    }
  }
  const plain = /filename="?([^";]+)"?/i.exec(header);
  return plain ? plain[1].trim() : fallback;
}

/**
 * Downloads an Excel export with the signed-in user's token (a plain link would not carry it),
 * retrying once through the refresh cookie on 401, and saves it under the server's file name.
 */
export async function downloadXlsx(path: string, fallbackName: string): Promise<string> {
  const send = (token: string | null) =>
    fetch(path, {
      headers: { Accept: `${XLSX_TYPE}, application/json`, ...(token ? { Authorization: `Bearer ${token}` } : {}) },
      credentials: "include",
    });
  let res: Response;
  try {
    res = await send(getAccessToken());
    if (res.status === 401) {
      const session = await refreshSession();
      if (session) res = await send(session.accessToken);
    }
  } catch (error) {
    throw toApiError(error);
  }
  if (!res.ok) throw await parseErrorResponse(res);
  const name = attachmentName(res.headers.get("Content-Disposition"), fallbackName);
  const blob = await res.blob();
  const url = URL.createObjectURL(blob);
  try {
    const link = document.createElement("a");
    link.href = url;
    link.download = name;
    document.body.appendChild(link);
    link.click();
    link.remove();
  } finally {
    URL.revokeObjectURL(url);
  }
  return name;
}

export type AbsenteesParams = { date?: string; classId?: string; includeLeave?: boolean };
export type SectionsParams = { from?: string; to?: string; classId?: string };
export type HomeworkParams = { from?: string; to?: string; classId?: string; subjectId?: string };
export type FunnelParams = { yearId?: string; classId?: string; source?: string; from?: string; to?: string };
export type LeaveParams = { yearId?: string; departmentId?: string; leaveTypeId?: string };

/** The JSON path and the Excel path of each report. */
export const reportPaths = {
  absentees: (p: AbsenteesParams) => `/api/reports/attendance/absentees${reportQuery(p)}`,
  absenteesXlsx: (p: AbsenteesParams) => `/api/reports/attendance/absentees.xlsx${reportQuery(p)}`,
  sections: (p: SectionsParams) => `/api/reports/attendance/sections${reportQuery(p)}`,
  sectionsXlsx: (p: SectionsParams) => `/api/reports/attendance/sections.xlsx${reportQuery(p)}`,
  homework: (p: HomeworkParams) => `/api/reports/homework/completion${reportQuery(p)}`,
  homeworkXlsx: (p: HomeworkParams) => `/api/reports/homework/completion.xlsx${reportQuery(p)}`,
  funnel: (p: FunnelParams) => `/api/reports/admissions/funnel${reportQuery(p)}`,
  funnelXlsx: (p: FunnelParams) => `/api/reports/admissions/funnel.xlsx${reportQuery(p)}`,
  leave: (p: LeaveParams) => `/api/reports/staff/leave${reportQuery(p)}`,
  leaveXlsx: (p: LeaveParams) => `/api/reports/staff/leave.xlsx${reportQuery(p)}`,
};

export const reportsApi = {
  dashboard: () => apiFetch<DashboardSummary>("/api/dashboard"),
  absentees: (p: AbsenteesParams) => apiFetch<AbsenteesReport>(reportPaths.absentees(p)),
  sections: (p: SectionsParams) => apiFetch<SectionAttendanceReport>(reportPaths.sections(p)),
  homework: (p: HomeworkParams) => apiFetch<HomeworkCompletionReport>(reportPaths.homework(p)),
  funnel: (p: FunnelParams) => apiFetch<FunnelReport>(reportPaths.funnel(p)),
  leave: (p: LeaveParams) => apiFetch<LeaveTakenReport>(reportPaths.leave(p)),
};
