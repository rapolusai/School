/** Types mirroring docs/api/phase-1-attendance.md (messages and alert settings). Keep in sync with the contract. */

import type { PlainDate } from "./school";

export const MESSAGE_CHANNELS = ["WHATSAPP", "SMS", "EMAIL"] as const;
export type MessageChannel = (typeof MESSAGE_CHANNELS)[number];

export const MESSAGE_STATUSES = ["QUEUED", "SENT", "SIMULATED", "FAILED", "SKIPPED"] as const;
export type MessageStatus = (typeof MESSAGE_STATUSES)[number];

export type MessageRow = {
  id: string;
  /** ISO-8601 instant */
  createdAt: string;
  channel: MessageChannel;
  /** Always masked by the API, e.g. "98765•••01". */
  recipient: string;
  recipientName: string | null;
  templateKey: string;
  status: MessageStatus;
  attempts: number;
  sentAt: string | null;
  /** Only while QUEUED. */
  nextAttemptAt: string | null;
  lastError: string | null;
  relatedType: string | null;
  relatedId: string | null;
  relatedLabel: string | null;
};

export type MessageDetail = MessageRow & {
  language: "en" | "hi" | "te";
  body: string;
  fallbackChannel: MessageChannel | null;
};

export type MessagePage = { items: MessageRow[]; page: number; size: number; total: number };

export type MessageQuery = {
  from?: PlainDate;
  to?: PlainDate;
  channel?: MessageChannel;
  status?: MessageStatus;
  q?: string;
  relatedId?: string;
  page?: number;
  size?: number;
};

export const ALERT_CHANNELS = ["WHATSAPP_SMS", "SMS"] as const;
export type AlertChannel = (typeof ALERT_CHANNELS)[number];

export type AlertLanguage = "en" | "hi" | "te";

/** A select's value as an alert language (English for anything unknown). */
export function alertLanguageOf(value: string): AlertLanguage {
  return value === "hi" || value === "te" ? value : "en";
}

export type NotificationSettings = {
  absenceAlertsEnabled: boolean;
  absenceAlertChannel: AlertChannel;
  alertLanguage: AlertLanguage;
  quietHoursEnabled: boolean;
  /** "HH:mm", India time */
  quietHoursStart: string;
  quietHoursEnd: string;
};
