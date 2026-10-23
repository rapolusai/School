import { apiFetch, pathSegment } from "./api";
import type {
  AcceptedReport,
  AssistSettings,
  AssistSettingsRequest,
  AssistUsage,
  AtRiskReport,
  DraftRemarksRequest,
  PaperDraftRequest,
  PaperRow,
  PaperSaveRequest,
  PaperView,
  RemarkDraft,
  RemarkSheet,
  RunResult,
  WatchDetail,
  WatchList,
  WatchStatus,
  WritingResult,
} from "./types";

/* Endpoint helpers for docs/api/phase-3-assist.md. */

const id = pathSegment;

/** "?a=1&b=x" from the set values only. Exported for tests. */
export function assistQuery(params: Record<string, string | null | undefined>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === "") continue;
    search.set(key, value);
  }
  const text = search.toString();
  return text ? `?${text}` : "";
}

/** The problem types that switch features off or stop at the monthly limit. */
export const ASSIST_PROBLEMS = {
  off: "urn:akshara:assist:off",
  featureOff: "urn:akshara:assist:feature-off",
  limit: "urn:akshara:assist:limit-reached",
} as const;

const watch = (entryId: string) => `/api/assist/watch-list/${id(entryId)}`;
const paper = (paperId: string) => `/api/assist/papers/${id(paperId)}`;

export const assistPaths = {
  atRiskCsv: (classId?: string | null) => `/api/assist/reports/at-risk.csv${assistQuery({ classId })}`,
  acceptedCsv: (from?: string | null, to?: string | null) => `/api/assist/reports/drafts-accepted.csv${assistQuery({ from, to })}`,
};

export const assistApi = {
  settings: () => apiFetch<AssistSettings>("/api/assist/settings"),
  saveSettings: (body: AssistSettingsRequest) => apiFetch<AssistSettings>("/api/assist/settings", { method: "PUT", body }),
  usage: (month?: string | null) => apiFetch<AssistUsage>(`/api/assist/usage${assistQuery({ month })}`),

  remarks: (termId: string, sectionId: string) => apiFetch<RemarkSheet>(`/api/assist/remarks${assistQuery({ termId, sectionId })}`),
  draftRemarks: (body: DraftRemarksRequest) => apiFetch<RemarkDraft[]>("/api/assist/remarks/drafts", { method: "POST", body }),
  editRemark: (draftId: string, text: string) => apiFetch<RemarkDraft>(`/api/assist/remarks/${id(draftId)}`, { method: "PUT", body: { text } }),
  acceptRemark: (draftId: string, text: string) =>
    apiFetch<RemarkDraft>(`/api/assist/remarks/${id(draftId)}/accept`, { method: "POST", body: { text } }),

  watchList: (classId?: string | null, status?: WatchStatus | null) =>
    apiFetch<WatchList>(`/api/assist/watch-list${assistQuery({ classId, status })}`),
  runWatchList: () => apiFetch<RunResult>("/api/assist/watch-list/run", { method: "POST" }),
  watchEntry: (entryId: string) => apiFetch<WatchDetail>(watch(entryId)),
  assign: (entryId: string, userId: string | null) => apiFetch<WatchDetail>(`${watch(entryId)}/assignment`, { method: "PUT", body: { userId } }),
  setStatus: (entryId: string, status: WatchStatus) => apiFetch<WatchDetail>(`${watch(entryId)}/status`, { method: "PUT", body: { status } }),
  addNote: (entryId: string, body: string) => apiFetch<WatchDetail>(`${watch(entryId)}/notes`, { method: "POST", body: { body } }),
  informParent: (entryId: string) => apiFetch<WatchDetail>(`${watch(entryId)}/inform-parent`, { method: "POST" }),

  draftPaper: (body: PaperDraftRequest) => apiFetch<PaperView>("/api/assist/papers/draft", { method: "POST", body }),
  papers: () => apiFetch<PaperRow[]>("/api/assist/papers"),
  paper: (paperId: string) => apiFetch<PaperView>(paper(paperId)),
  savePaper: (body: PaperSaveRequest) => apiFetch<PaperView>("/api/assist/papers", { method: "POST", body }),
  updatePaper: (paperId: string, body: PaperSaveRequest) => apiFetch<PaperView>(paper(paperId), { method: "PUT", body }),
  deletePaper: (paperId: string) => apiFetch<void>(paper(paperId), { method: "DELETE" }),

  improve: (text: string) => apiFetch<WritingResult>("/api/assist/writing/improve", { method: "POST", body: { text } }),

  atRisk: (classId?: string | null) => apiFetch<AtRiskReport>(`/api/assist/reports/at-risk${assistQuery({ classId })}`),
  accepted: (from?: string | null, to?: string | null) =>
    apiFetch<AcceptedReport>(`/api/assist/reports/drafts-accepted${assistQuery({ from, to })}`),
};
