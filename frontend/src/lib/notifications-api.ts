import { apiFetch } from "./api";
import type { MessageDetail, MessagePage, MessageQuery, NotificationSettings } from "./types";

/* Endpoint helpers for docs/api/phase-1-attendance.md (message log and alert settings). */

/** "?from=…&status=…" from the set filters only. Exported for tests. */
export function messageQueryString(query: MessageQuery): string {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (value === undefined || value === null || value === "") continue;
    params.set(key, String(value));
  }
  const text = params.toString();
  return text ? `?${text}` : "";
}

export const notificationsApi = {
  listMessages: (query: MessageQuery = {}) => apiFetch<MessagePage>(`/api/messages${messageQueryString(query)}`),
  getMessage: (messageId: string) => apiFetch<MessageDetail>(`/api/messages/${encodeURIComponent(messageId)}`),
  getSettings: () => apiFetch<NotificationSettings>("/api/notifications/settings"),
  updateSettings: (body: NotificationSettings) =>
    apiFetch<NotificationSettings>("/api/notifications/settings", { method: "PUT", body }),
};
