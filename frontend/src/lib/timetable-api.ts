import { apiFetch } from "./api";
import type {
  AbsenceRequest,
  AssignmentRequest,
  AssignmentSaved,
  AssignmentUpdate,
  AssignmentView,
  BellSchedule,
  BellScheduleRequest,
  CellRequest,
  ClashReport,
  FamilyTimetable,
  FreeTeachers,
  SectionsOverview,
  SectionTimetable,
  SubstitutionDay,
  SubstitutionRequest,
  TeacherDay,
  TeacherSummary,
  TeacherTimetable,
  WeekDay,
} from "./types";

/* Endpoint helpers for docs/api/phase-1-timetable-homework.md (timetable). */

const id = (value: string) => encodeURIComponent(value);

function query(params: Record<string, string | number | undefined | null>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== null && value !== "") search.set(key, String(value));
  }
  const text = search.toString();
  return text ? `?${text}` : "";
}

export const timetableApi = {
  bellSchedule: () => apiFetch<BellSchedule>("/api/timetable/bell-schedule"),
  saveBellSchedule: (body: BellScheduleRequest) =>
    apiFetch<BellSchedule>("/api/timetable/bell-schedule", { method: "PUT", body }),

  assignments: (filter: { sectionId?: string; teacherId?: string } = {}) =>
    apiFetch<AssignmentView[]>(`/api/timetable/assignments${query(filter)}`),
  createAssignment: (body: AssignmentRequest) =>
    apiFetch<AssignmentView>("/api/timetable/assignments", { method: "POST", body }),
  updateAssignment: (assignmentId: string, body: AssignmentUpdate) =>
    apiFetch<AssignmentSaved>(`/api/timetable/assignments/${id(assignmentId)}`, { method: "PUT", body }),
  deleteAssignment: (assignmentId: string) =>
    apiFetch<void>(`/api/timetable/assignments/${id(assignmentId)}`, { method: "DELETE" }),

  sections: () => apiFetch<SectionsOverview>("/api/timetable/sections"),
  section: (sectionId: string) => apiFetch<SectionTimetable>(`/api/timetable/sections/${id(sectionId)}`),
  saveSection: (sectionId: string, slots: CellRequest[]) =>
    apiFetch<SectionTimetable>(`/api/timetable/sections/${id(sectionId)}`, { method: "PUT", body: { slots } }),
  clashes: () => apiFetch<ClashReport>("/api/timetable/clashes"),

  teachers: () => apiFetch<TeacherSummary[]>("/api/timetable/teachers"),
  teacher: (teacherId: string) => apiFetch<TeacherTimetable>(`/api/timetable/teachers/${id(teacherId)}`),
  teacherDay: (teacherId: string, date?: string) =>
    apiFetch<TeacherDay>(`/api/timetable/teachers/${id(teacherId)}/day${query({ date })}`),
  mine: () => apiFetch<TeacherTimetable>("/api/timetable/me"),
  myDay: (date?: string) => apiFetch<TeacherDay>(`/api/timetable/me/today${query({ date })}`),
  freeTeachers: (params: { date?: string; day?: WeekDay; period: number; subjectId?: string }) =>
    apiFetch<FreeTeachers>(`/api/timetable/free-teachers${query(params)}`),

  substitutions: (date?: string) => apiFetch<SubstitutionDay>(`/api/timetable/substitutions${query({ date })}`),
  recordAbsence: (body: AbsenceRequest) =>
    apiFetch<SubstitutionDay>("/api/timetable/substitutions/absences", { method: "POST", body }),
  removeAbsence: (absenceId: string) =>
    apiFetch<SubstitutionDay>(`/api/timetable/substitutions/absences/${id(absenceId)}`, { method: "DELETE" }),
  assignSubstitute: (body: SubstitutionRequest) =>
    apiFetch<SubstitutionDay>("/api/timetable/substitutions", { method: "PUT", body }),
  removeSubstitution: (substitutionId: string) =>
    apiFetch<SubstitutionDay>(`/api/timetable/substitutions/${id(substitutionId)}`, { method: "DELETE" }),

  myTimetable: () => apiFetch<FamilyTimetable>("/api/me/timetable"),
  childTimetable: (studentId: string) => apiFetch<FamilyTimetable>(`/api/me/children/${id(studentId)}/timetable`),
};
