import { apiFetch } from "./api";
import { fetchText } from "./attendance-api";
import type {
  CreateStaffRequest,
  LeaveApplicationRequest,
  LeaveBalance,
  LeaveBalanceRequest,
  LeavePreview,
  LeavePreviewRequest,
  LeaveType,
  LeaveTypeRequest,
  MyLeave,
  MyStaffDay,
  SaveStaffDayRequest,
  SaveStaffDayResult,
  StaffDaySheet,
  StaffDepartment,
  StaffDepartmentRequest,
  StaffDetail,
  StaffDirectoryQuery,
  StaffLeave,
  StaffLeaveRequest,
  StaffLeavingRequest,
  StaffMonthReport,
  StaffPage,
  StaffPersonMonth,
  StaffProfileRequest,
  StaffRoleOption,
  StaffTodaySummary,
} from "./types";

/* Endpoint helpers for docs/api/phase-1-staff.md (staff, leave, staff attendance). */

const id = (value: string) => encodeURIComponent(value);

/** "?a=1&b=x" from the set values only. Exported for tests. */
export function staffQueryString(params: Record<string, string | number | boolean | null | undefined>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === "") continue;
    search.set(key, String(value));
  }
  const text = search.toString();
  return text ? `?${text}` : "";
}

export const staffApi = {
  list: (query: StaffDirectoryQuery) => apiFetch<StaffPage>(`/api/staff${staffQueryString(query)}`),
  designations: () => apiFetch<string[]>("/api/staff/designations"),
  roles: () => apiFetch<StaffRoleOption[]>("/api/staff/roles"),
  get: (userId: string) => apiFetch<StaffDetail>(`/api/staff/${id(userId)}`),
  create: (body: CreateStaffRequest) => apiFetch<StaffDetail>("/api/staff", { method: "POST", body }),
  saveProfile: (userId: string, body: StaffProfileRequest) =>
    apiFetch<StaffDetail>(`/api/staff/${id(userId)}/profile`, { method: "PUT", body }),
  recordLeaving: (userId: string, body: StaffLeavingRequest) =>
    apiFetch<StaffDetail>(`/api/staff/${id(userId)}/leaving`, { method: "POST", body }),
  leaveOf: (userId: string, yearId?: string) =>
    apiFetch<StaffLeave>(`/api/staff/${id(userId)}/leave${staffQueryString({ yearId })}`),
  attendanceOf: (userId: string, month?: string) =>
    apiFetch<StaffPersonMonth>(`/api/staff/${id(userId)}/attendance${staffQueryString({ month })}`),

  departments: () => apiFetch<StaffDepartment[]>("/api/staff/departments"),
  createDepartment: (body: StaffDepartmentRequest) =>
    apiFetch<StaffDepartment>("/api/staff/departments", { method: "POST", body }),
  updateDepartment: (departmentId: string, body: StaffDepartmentRequest) =>
    apiFetch<StaffDepartment>(`/api/staff/departments/${id(departmentId)}`, { method: "PUT", body }),
  deleteDepartment: (departmentId: string) =>
    apiFetch<void>(`/api/staff/departments/${id(departmentId)}`, { method: "DELETE" }),
};

export const leaveApi = {
  types: (all = false) => apiFetch<LeaveType[]>(`/api/leave/types${staffQueryString({ all: all || undefined })}`),
  createType: (body: LeaveTypeRequest) => apiFetch<LeaveType>("/api/leave/types", { method: "POST", body }),
  updateType: (typeId: string, body: LeaveTypeRequest) =>
    apiFetch<LeaveType>(`/api/leave/types/${id(typeId)}`, { method: "PUT", body }),
  deleteType: (typeId: string) => apiFetch<void>(`/api/leave/types/${id(typeId)}`, { method: "DELETE" }),
  addStandardTypes: () => apiFetch<LeaveType[]>("/api/leave/types/standard", { method: "POST" }),
  mine: (yearId?: string) => apiFetch<MyLeave>(`/api/leave/me${staffQueryString({ yearId })}`),
  preview: (body: LeavePreviewRequest) => apiFetch<LeavePreview>("/api/leave/preview", { method: "POST", body }),
  apply: (body: LeaveApplicationRequest) =>
    apiFetch<StaffLeaveRequest>("/api/leave/requests", { method: "POST", body }),
  approve: (requestId: string, comment?: string) =>
    apiFetch<StaffLeaveRequest>(`/api/leave/requests/${id(requestId)}/approve`, {
      method: "POST",
      body: { comment: comment?.trim() || null },
    }),
  reject: (requestId: string, comment: string) =>
    apiFetch<StaffLeaveRequest>(`/api/leave/requests/${id(requestId)}/reject`, {
      method: "POST",
      body: { comment: comment.trim() },
    }),
  cancel: (requestId: string, comment?: string) =>
    apiFetch<StaffLeaveRequest>(`/api/leave/requests/${id(requestId)}/cancel`, {
      method: "POST",
      body: { comment: comment?.trim() || null },
    }),
  inbox: (all = false) => apiFetch<StaffLeaveRequest[]>(`/api/leave/inbox${staffQueryString({ all: all || undefined })}`),
  setBalance: (body: LeaveBalanceRequest) => apiFetch<LeaveBalance>("/api/leave/balances", { method: "PUT", body }),
};

export const staffAttendanceApi = {
  myToday: () => apiFetch<MyStaffDay>("/api/staff-attendance/me/today"),
  checkIn: (note?: string) =>
    apiFetch<MyStaffDay>("/api/staff-attendance/me/check-in", { method: "POST", body: { note: note?.trim() || null } }),
  checkOut: (note?: string) =>
    apiFetch<MyStaffDay>("/api/staff-attendance/me/check-out", { method: "POST", body: { note: note?.trim() || null } }),
  day: (date: string) => apiFetch<StaffDaySheet>(`/api/staff-attendance/days/${id(date)}`),
  saveDay: (date: string, body: SaveStaffDayRequest) =>
    apiFetch<SaveStaffDayResult>(`/api/staff-attendance/days/${id(date)}`, { method: "PUT", body }),
  month: (month: string, departmentId?: string) =>
    apiFetch<StaffMonthReport>(`/api/staff-attendance/month${staffQueryString({ month, departmentId })}`),
  monthCsv: (month: string, departmentId?: string) =>
    fetchText(`/api/staff-attendance/month.csv${staffQueryString({ month, departmentId })}`),
  today: () => apiFetch<StaffTodaySummary>("/api/staff-attendance/today"),
};
