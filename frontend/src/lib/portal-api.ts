import { apiFetch } from "./api";
import type { ChildAttendance, ChildLeave, ChildLeaveApplication, FamilyLeave, LeaveInbox } from "./types";

/* Endpoint helpers for docs/api/phase-1-portal.md (the parent and student app). */

const id = (value: string) => encodeURIComponent(value);

export const portalApi = {
  /** A parent's child's leave requests (child.view). */
  childLeave: (studentId: string) => apiFetch<FamilyLeave>(`/api/me/children/${id(studentId)}/leave-requests`),
  applyLeave: (studentId: string, body: ChildLeaveApplication) =>
    apiFetch<ChildLeave>(`/api/me/children/${id(studentId)}/leave-requests`, { method: "POST", body }),
  cancelLeave: (studentId: string, requestId: string) =>
    apiFetch<ChildLeave>(`/api/me/children/${id(studentId)}/leave-requests/${id(requestId)}/cancel`, {
      method: "POST",
    }),
  /** The signed-in student's own requests, read only. */
  myLeave: () => apiFetch<FamilyLeave>("/api/me/leave-requests"),
  /** The signed-in student's own attendance month. */
  myAttendance: (month?: string) =>
    apiFetch<ChildAttendance>(`/api/me/attendance${month ? `?month=${encodeURIComponent(month)}` : ""}`),
  /** Class teachers (their sections) and attendance.manage (every section). */
  inbox: () => apiFetch<LeaveInbox>("/api/attendance/leave-requests"),
  approve: (requestId: string, comment?: string) =>
    apiFetch<ChildLeave>(`/api/attendance/leave-requests/${id(requestId)}/approve`, {
      method: "POST",
      body: { comment: comment || null },
    }),
  reject: (requestId: string, comment: string) =>
    apiFetch<ChildLeave>(`/api/attendance/leave-requests/${id(requestId)}/reject`, {
      method: "POST",
      body: { comment },
    }),
};
