import { apiFetch, getAccessToken, parseErrorResponse, refreshSession, toApiError } from "./api";
import type {
  AudienceOptions,
  BoardItem,
  BoardPage,
  CalendarEntry,
  CalendarEntryList,
  CircularDetail,
  CircularEstimate,
  CircularList,
  CircularRequest,
  CircularStatus,
  CommunicationSettings,
  EntryRequest,
  EstimateRequest,
  HolidaySuggestions,
} from "./types";

/* Endpoint helpers for docs/api/phase-1-communication.md (circulars, notice board, calendar). */

const id = (value: string) => encodeURIComponent(value);

/** "?a=…&b=…" from the set values only. Exported for tests. */
export function communicationQuery(params: Record<string, string | number | boolean | undefined | null>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === "" || value === false) continue;
    search.set(key, String(value));
  }
  const text = search.toString();
  return text ? `?${text}` : "";
}

/** GET the school calendar as iCalendar text, with the bearer token (and one refresh on 401). */
async function fetchCalendarText(path: string): Promise<string> {
  const send = (token: string | null) =>
    fetch(path, {
      headers: { Accept: "text/calendar, application/json", ...(token ? { Authorization: `Bearer ${token}` } : {}) },
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

export const noticesApi = {
  list: (status?: CircularStatus) => apiFetch<CircularList>(`/api/notices${communicationQuery({ status })}`),
  get: (circularId: string) => apiFetch<CircularDetail>(`/api/notices/${id(circularId)}`),
  audienceOptions: () => apiFetch<AudienceOptions>("/api/notices/audience-options"),
  estimate: (body: EstimateRequest) => apiFetch<CircularEstimate>("/api/notices/estimate", { method: "POST", body }),
  create: (body: CircularRequest) => apiFetch<CircularDetail>("/api/notices", { method: "POST", body }),
  update: (circularId: string, body: CircularRequest) =>
    apiFetch<CircularDetail>(`/api/notices/${id(circularId)}`, { method: "PUT", body }),
  remove: (circularId: string) => apiFetch<void>(`/api/notices/${id(circularId)}`, { method: "DELETE" }),
  submit: (circularId: string) =>
    apiFetch<CircularDetail>(`/api/notices/${id(circularId)}/submit`, { method: "POST" }),
  approve: (circularId: string, note?: string) =>
    apiFetch<CircularDetail>(`/api/notices/${id(circularId)}/approve`, {
      method: "POST",
      body: { note: note?.trim() || null },
    }),
  reject: (circularId: string, note: string) =>
    apiFetch<CircularDetail>(`/api/notices/${id(circularId)}/reject`, { method: "POST", body: { note } }),
  cancel: (circularId: string) =>
    apiFetch<CircularDetail>(`/api/notices/${id(circularId)}/cancel`, { method: "POST" }),
  withdraw: (circularId: string, reason: string) =>
    apiFetch<CircularDetail>(`/api/notices/${id(circularId)}/withdraw`, { method: "POST", body: { reason } }),
  getSettings: () => apiFetch<CommunicationSettings>("/api/notices/settings"),
  updateSettings: (body: CommunicationSettings) =>
    apiFetch<CommunicationSettings>("/api/notices/settings", { method: "PUT", body }),
};

export const boardApi = {
  page: (query: { page?: number; size?: number; unreadOnly?: boolean } = {}) =>
    apiFetch<BoardPage>(`/api/notices/board${communicationQuery(query)}`),
  item: (circularId: string) => apiFetch<BoardItem>(`/api/notices/board/${id(circularId)}`),
  markRead: (circularId: string) =>
    apiFetch<BoardItem>(`/api/notices/board/${id(circularId)}/read`, { method: "POST" }),
  markAllRead: () => apiFetch<{ marked: number }>("/api/notices/board/read-all", { method: "POST" }),
};

export const calendarApi = {
  entries: (from?: string, to?: string) =>
    apiFetch<CalendarEntryList>(`/api/calendar/entries${communicationQuery({ from, to })}`),
  upcoming: (limit = 5) => apiFetch<CalendarEntry[]>(`/api/calendar/upcoming${communicationQuery({ limit })}`),
  create: (body: EntryRequest) => apiFetch<CalendarEntry>("/api/calendar/entries", { method: "POST", body }),
  createAll: (entries: EntryRequest[]) =>
    apiFetch<CalendarEntry[]>("/api/calendar/entries/bulk", { method: "POST", body: { entries } }),
  update: (entryId: string, body: EntryRequest) =>
    apiFetch<CalendarEntry>(`/api/calendar/entries/${id(entryId)}`, { method: "PUT", body }),
  remove: (entryId: string) => apiFetch<void>(`/api/calendar/entries/${id(entryId)}`, { method: "DELETE" }),
  holidaySuggestions: () => apiFetch<HolidaySuggestions>("/api/calendar/holiday-suggestions"),
  ics: () => fetchCalendarText("/api/calendar.ics"),
};
