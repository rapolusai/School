import { apiFetch } from "./api";
import { sendForm } from "./files-api";
import type {
  FileRef,
  HomeworkDetail,
  HomeworkOptions,
  HomeworkPage,
  HomeworkQuery,
  HomeworkRequest,
  HomeworkSettings,
  HomeworkTracker,
  ReviewRequest,
  StudentHomework,
  StudentHomeworkDetail,
  TrackerRow,
} from "./types";

/* Endpoint helpers for docs/api/phase-1-timetable-homework.md (homework). */

const id = (value: string) => encodeURIComponent(value);

/** "?when=open&page=0…" from the set filters only. Exported for tests. */
export function homeworkQueryString(query: HomeworkQuery): string {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (value === undefined || value === null || value === "") continue;
    params.set(key, String(value));
  }
  const text = params.toString();
  return text ? `?${text}` : "";
}

export type SubmissionInput = { text: string; files: File[]; keepFileIds: string[] };

export const homeworkApi = {
  options: () => apiFetch<HomeworkOptions>("/api/homework/options"),
  list: (query: HomeworkQuery = {}) => apiFetch<HomeworkPage>(`/api/homework${homeworkQueryString(query)}`),
  get: (homeworkId: string) => apiFetch<HomeworkDetail>(`/api/homework/${id(homeworkId)}`),
  create: (body: HomeworkRequest) => apiFetch<HomeworkDetail>("/api/homework", { method: "POST", body }),
  update: (homeworkId: string, body: HomeworkRequest) =>
    apiFetch<HomeworkDetail>(`/api/homework/${id(homeworkId)}`, { method: "PUT", body }),
  remove: (homeworkId: string) => apiFetch<void>(`/api/homework/${id(homeworkId)}`, { method: "DELETE" }),
  addAttachment: (homeworkId: string, file: File) => {
    const form = new FormData();
    form.append("file", file, file.name);
    return sendForm<FileRef>(`/api/homework/${id(homeworkId)}/attachments`, form);
  },
  removeAttachment: (homeworkId: string, fileId: string) =>
    apiFetch<void>(`/api/homework/${id(homeworkId)}/attachments/${id(fileId)}`, { method: "DELETE" }),
  tracker: (homeworkId: string) => apiFetch<HomeworkTracker>(`/api/homework/${id(homeworkId)}/submissions`),
  review: (homeworkId: string, submissionId: string, body: ReviewRequest) =>
    apiFetch<TrackerRow>(`/api/homework/${id(homeworkId)}/submissions/${id(submissionId)}/review`, {
      method: "PUT",
      body,
    }),
  settings: () => apiFetch<HomeworkSettings>("/api/homework/settings"),
  saveSettings: (body: HomeworkSettings) =>
    apiFetch<HomeworkSettings>("/api/homework/settings", { method: "PUT", body }),

  mine: () => apiFetch<StudentHomework>("/api/me/homework"),
  myDetail: (homeworkId: string) => apiFetch<StudentHomeworkDetail>(`/api/me/homework/${id(homeworkId)}`),
  submit: (homeworkId: string, input: SubmissionInput) => {
    const form = new FormData();
    if (input.text.trim()) form.append("text", input.text);
    for (const file of input.files) form.append("files", file, file.name);
    for (const keep of input.keepFileIds) form.append("keepFileIds", keep);
    return sendForm<StudentHomeworkDetail>(`/api/me/homework/${id(homeworkId)}/submission`, form);
  },
  child: (studentId: string) => apiFetch<StudentHomework>(`/api/me/children/${id(studentId)}/homework`),
  childDetail: (studentId: string, homeworkId: string) =>
    apiFetch<StudentHomeworkDetail>(`/api/me/children/${id(studentId)}/homework/${id(homeworkId)}`),
};
