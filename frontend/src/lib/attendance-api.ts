import { apiFetch, getAccessToken, parseErrorResponse, refreshSession, toApiError } from "./api";
import type {
  AttendanceToday,
  ChildAttendance,
  MonthRegister,
  RegisterView,
  SaveRegisterRequest,
  SaveRegisterResult,
  SectionsForDay,
  StudentAttendanceSummary,
} from "./types";

/* Endpoint helpers for docs/api/phase-1-attendance.md (attendance). */

const id = (value: string) => encodeURIComponent(value);

function query(params: Record<string, string | undefined>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value) search.set(key, value);
  }
  const text = search.toString();
  return text ? `?${text}` : "";
}

/**
 * GET a text body (a CSV download) with the bearer token, retrying once through the refresh
 * cookie on 401, like apiFetch does for JSON.
 */
export async function fetchText(path: string): Promise<string> {
  const send = (token: string | null) =>
    fetch(path, {
      headers: { Accept: "text/csv, application/json", ...(token ? { Authorization: `Bearer ${token}` } : {}) },
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
  return res.text();
}

/** Offers text to the browser as a file download. */
export function saveTextFile(filename: string, text: string, type = "text/csv;charset=utf-8"): void {
  const blob = new Blob([text], { type });
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}

export const attendanceApi = {
  sections: (date?: string) => apiFetch<SectionsForDay>(`/api/attendance/sections${query({ date })}`),
  register: (sectionId: string, date: string) =>
    apiFetch<RegisterView>(`/api/attendance/registers/${id(sectionId)}/${id(date)}`),
  saveRegister: (sectionId: string, date: string, body: SaveRegisterRequest) =>
    apiFetch<SaveRegisterResult>(`/api/attendance/registers/${id(sectionId)}/${id(date)}`, { method: "PUT", body }),
  month: (sectionId: string, month: string) =>
    apiFetch<MonthRegister>(`/api/attendance/sections/${id(sectionId)}/month${query({ month })}`),
  monthCsv: (sectionId: string, month: string) =>
    fetchText(`/api/attendance/sections/${id(sectionId)}/month.csv${query({ month })}`),
  studentSummary: (studentId: string, from?: string, to?: string) =>
    apiFetch<StudentAttendanceSummary>(`/api/attendance/students/${id(studentId)}/summary${query({ from, to })}`),
  today: () => apiFetch<AttendanceToday>("/api/attendance/today"),
  child: (studentId: string, month?: string) =>
    apiFetch<ChildAttendance>(`/api/me/children/${id(studentId)}/attendance${query({ month })}`),
};
