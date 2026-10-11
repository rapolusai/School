import { apiFetch } from "./api";
import { sendForm } from "./files-api";
import type {
  ChatAway,
  ChatAwayRequest,
  ChatContacts,
  ChatMessage,
  ChatReport,
  ChatSent,
  ChatSettings,
  ChatSettingsRequest,
  ChatUnread,
  ConversationChanges,
  ConversationDetail,
  ConversationRow,
  OversightDetail,
  OversightFilter,
  OversightPage,
  PtmAttendanceSummary,
  PtmBooking,
  PtmEventDetail,
  PtmEventRequest,
  PtmEventSummary,
  PtmFamily,
  PtmFamilyBooking,
  PtmMarkRequest,
  PtmMyEvent,
  PtmSettings,
  PtmSlot,
  PtmTeacherSchedule,
} from "./types";

/* Endpoint helpers for docs/api/phase-2-chat.md: messages (staff and parents), oversight and parent-teacher meetings. */

const id = (value: string) => encodeURIComponent(value);

/** What a conversation screen needs, for staff (/api/chat) and for parents (/api/me/chat) alike. */
export type ChatClient = {
  list: () => Promise<ConversationRow[]>;
  unread: () => Promise<ChatUnread>;
  contacts: (sectionId?: string | null) => Promise<ChatContacts>;
  start: (studentId: string, personId: string) => Promise<ConversationDetail>;
  get: (conversationId: string, before?: string | null) => Promise<ConversationDetail>;
  changes: (conversationId: string, since: string) => Promise<ConversationChanges>;
  send: (conversationId: string, body: string, files: File[]) => Promise<ChatSent>;
  remove: (conversationId: string, messageId: string) => Promise<ChatMessage>;
  report: (conversationId: string, reason: string) => Promise<ChatReport>;
};

function client(base: string, personField: "parentUserId" | "teacherUserId"): ChatClient {
  return {
    list: () => apiFetch<ConversationRow[]>(`${base}/conversations`),
    unread: () => apiFetch<ChatUnread>(`${base}/unread`),
    contacts: (sectionId) =>
      apiFetch<ChatContacts>(`${base}/contacts${sectionId ? `?sectionId=${id(sectionId)}` : ""}`),
    start: (studentId, personId) =>
      apiFetch<ConversationDetail>(`${base}/conversations`, {
        method: "POST",
        body: { studentId, [personField]: personId },
      }),
    get: (conversationId, before) =>
      apiFetch<ConversationDetail>(
        `${base}/conversations/${id(conversationId)}${before ? `?before=${encodeURIComponent(before)}` : ""}`,
      ),
    changes: (conversationId, since) =>
      apiFetch<ConversationChanges>(
        `${base}/conversations/${id(conversationId)}/changes?since=${encodeURIComponent(since)}`,
      ),
    send: (conversationId, body, files) => {
      const form = new FormData();
      if (body.trim()) form.append("body", body);
      for (const file of files) form.append("files", file, file.name);
      return sendForm<ChatSent>(`${base}/conversations/${id(conversationId)}/messages`, form);
    },
    remove: (conversationId, messageId) =>
      apiFetch<ChatMessage>(`${base}/conversations/${id(conversationId)}/messages/${id(messageId)}`, {
        method: "DELETE",
      }),
    report: (conversationId, reason) =>
      apiFetch<ChatReport>(`${base}/conversations/${id(conversationId)}/reports`, {
        method: "POST",
        body: { reason },
      }),
  };
}

/** Staff with chat.use: their conversations with parents. */
export const staffChatApi: ChatClient = client("/api/chat", "parentUserId");
/** Parents (child.view): their conversations with their children's teachers. */
export const familyChatApi: ChatClient = client("/api/me/chat", "teacherUserId");

export const chatApi = {
  away: () => apiFetch<ChatAway>("/api/chat/away"),
  setAway: (body: ChatAwayRequest) => apiFetch<ChatAway>("/api/chat/away", { method: "PUT", body }),
  settings: () => apiFetch<ChatSettings>("/api/chat/settings"),
  saveSettings: (body: ChatSettingsRequest) => apiFetch<ChatSettings>("/api/chat/settings", { method: "PUT", body }),

  oversight: (filter: OversightFilter, q: string, page = 0, size = 20) => {
    const params = new URLSearchParams({ filter, page: String(page), size: String(size) });
    if (q.trim()) params.set("q", q.trim());
    return apiFetch<OversightPage>(`/api/chat/oversight/conversations?${params.toString()}`);
  },
  oversightRead: (conversationId: string) =>
    apiFetch<OversightDetail>(`/api/chat/oversight/conversations/${id(conversationId)}`),
  close: (conversationId: string, reason: string) =>
    apiFetch<OversightDetail>(`/api/chat/oversight/conversations/${id(conversationId)}/close`, {
      method: "POST",
      body: { reason },
    }),
  reopen: (conversationId: string) =>
    apiFetch<OversightDetail>(`/api/chat/oversight/conversations/${id(conversationId)}/reopen`, { method: "POST" }),
  reports: (status: "open" | "all" = "open") =>
    apiFetch<ChatReport[]>(`/api/chat/oversight/reports?status=${status}`),
  review: (reportId: string, note: string | null) =>
    apiFetch<ChatReport>(`/api/chat/oversight/reports/${id(reportId)}/review`, { method: "POST", body: { note } }),
};

export const ptmPaths = {
  attendanceCsv: (eventId: string) => `/api/ptm/reports/attendance.csv?eventId=${id(eventId)}`,
};

export const ptmApi = {
  events: () => apiFetch<PtmEventSummary[]>("/api/ptm/events"),
  event: (eventId: string) => apiFetch<PtmEventDetail>(`/api/ptm/events/${id(eventId)}`),
  create: (body: PtmEventRequest) => apiFetch<PtmEventDetail>("/api/ptm/events", { method: "POST", body }),
  update: (eventId: string, body: PtmEventRequest) =>
    apiFetch<PtmEventDetail>(`/api/ptm/events/${id(eventId)}`, { method: "PUT", body }),
  cancel: (eventId: string, reason: string) =>
    apiFetch<PtmEventDetail>(`/api/ptm/events/${id(eventId)}/cancel`, { method: "POST", body: { reason } }),
  hours: (eventId: string, teacherId: string, availableFrom: string, availableTo: string) =>
    apiFetch<PtmTeacherSchedule>(`/api/ptm/events/${id(eventId)}/teachers/${id(teacherId)}/hours`, {
      method: "PUT",
      body: { availableFrom, availableTo },
    }),
  block: (slotId: string, blocked: boolean) =>
    apiFetch<PtmSlot>(`/api/ptm/slots/${id(slotId)}/block`, { method: "PUT", body: { blocked } }),
  book: (eventId: string, slotId: string, studentId: string) =>
    apiFetch<PtmBooking>(`/api/ptm/events/${id(eventId)}/bookings`, { method: "POST", body: { slotId, studentId } }),
  cancelBooking: (bookingId: string) => apiFetch<void>(`/api/ptm/bookings/${id(bookingId)}`, { method: "DELETE" }),
  mark: (bookingId: string, body: PtmMarkRequest) =>
    apiFetch<PtmBooking>(`/api/ptm/bookings/${id(bookingId)}`, { method: "PUT", body }),
  settings: () => apiFetch<PtmSettings>("/api/ptm/settings"),
  saveSettings: (remindersEnabled: boolean) =>
    apiFetch<PtmSettings>("/api/ptm/settings", { method: "PUT", body: { remindersEnabled } }),
  attendance: (eventId: string) =>
    apiFetch<PtmAttendanceSummary>(`/api/ptm/reports/attendance?eventId=${id(eventId)}`),

  /* A teacher's own schedule (chat.use or ptm.manage). */
  mine: () => apiFetch<PtmMyEvent[]>("/api/ptm/my"),
  myEvent: (eventId: string) => apiFetch<PtmMyEvent>(`/api/ptm/my/events/${id(eventId)}`),
  myHours: (eventId: string, availableFrom: string, availableTo: string) =>
    apiFetch<PtmTeacherSchedule>(`/api/ptm/my/events/${id(eventId)}/hours`, {
      method: "PUT",
      body: { availableFrom, availableTo },
    }),
  myBlock: (slotId: string, blocked: boolean) =>
    apiFetch<PtmSlot>(`/api/ptm/my/slots/${id(slotId)}/block`, { method: "PUT", body: { blocked } }),
  myMark: (bookingId: string, body: PtmMarkRequest) =>
    apiFetch<PtmBooking>(`/api/ptm/my/bookings/${id(bookingId)}`, { method: "PUT", body }),

  /* Parents (child.view). */
  family: () => apiFetch<PtmFamily>("/api/me/ptm"),
  familyBook: (slotId: string, studentId: string) =>
    apiFetch<PtmFamilyBooking>("/api/me/ptm/bookings", { method: "POST", body: { slotId, studentId } }),
  familyMove: (bookingId: string, slotId: string) =>
    apiFetch<PtmFamilyBooking>(`/api/me/ptm/bookings/${id(bookingId)}`, { method: "PUT", body: { slotId } }),
  familyCancel: (bookingId: string) => apiFetch<void>(`/api/me/ptm/bookings/${id(bookingId)}`, { method: "DELETE" }),
};
