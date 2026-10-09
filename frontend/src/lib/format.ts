/**
 * Indian formatting helpers. Dates are shown in India Standard Time so that every viewer
 * (and the server render) sees the same calendar day for a given instant.
 */

export const DISPLAY_TIME_ZONE = "Asia/Kolkata";

const inrNumber = new Intl.NumberFormat("en-IN", { maximumFractionDigits: 0 });

/** 184300 → "₹1,84,300" (lakh/crore grouping, rounded to whole rupees). */
export function formatINR(amount: number): string {
  const rounded = Math.round(amount);
  const sign = rounded < 0 ? "-" : "";
  return `${sign}₹${inrNumber.format(Math.abs(rounded))}`;
}

function toDate(value: string | Date): Date | null {
  const date = value instanceof Date ? value : new Date(value);
  return Number.isNaN(date.getTime()) ? null : date;
}

/** "2026-10-09T…" → "9 Oct 2026". Returns "" for missing or invalid input. */
export function formatDate(iso: string | Date | null | undefined, locale = "en-IN"): string {
  if (!iso) return "";
  const date = toDate(iso);
  if (!date) return "";
  return new Intl.DateTimeFormat(locale, {
    day: "numeric",
    month: "short",
    year: "numeric",
    timeZone: DISPLAY_TIME_ZONE,
  }).format(date);
}

/** "9 Oct 2026, 11:32 am" */
export function formatDateTime(iso: string | Date | null | undefined, locale = "en-IN"): string {
  if (!iso) return "";
  const date = toDate(iso);
  if (!date) return "";
  return new Intl.DateTimeFormat(locale, {
    day: "numeric",
    month: "short",
    year: "numeric",
    hour: "numeric",
    minute: "2-digit",
    timeZone: DISPLAY_TIME_ZONE,
  }).format(date);
}

/** "Friday, 9 October 2026" */
export function formatLongDate(value: string | Date, locale = "en-IN"): string {
  const date = toDate(value);
  if (!date) return "";
  return new Intl.DateTimeFormat(locale, {
    weekday: "long",
    day: "numeric",
    month: "long",
    year: "numeric",
    timeZone: DISPLAY_TIME_ZONE,
  }).format(date);
}

/** Whole days from `now` until `iso` (rounded up; negative once it has passed). */
export function daysUntil(iso: string, now: Date = new Date()): number {
  const target = toDate(iso);
  if (!target) return 0;
  return Math.ceil((target.getTime() - now.getTime()) / 86_400_000);
}

/** Hour of day (0–23) in IST, used for the dashboard greeting. */
export function hourInIndia(now: Date = new Date()): number {
  const hour = new Intl.DateTimeFormat("en-GB", {
    hour: "numeric",
    hourCycle: "h23",
    timeZone: DISPLAY_TIME_ZONE,
  }).format(now);
  return Number.parseInt(hour, 10);
}

/** "Asha Rao Menon" → "AR" */
export function initials(name: string): string {
  return (
    name
      .trim()
      .split(/\s+/)
      .filter(Boolean)
      .slice(0, 2)
      .map((part) => Array.from(part)[0]?.toUpperCase() ?? "")
      .join("") || "?"
  );
}

/** "Asha Rao" → "Asha" */
export function firstName(name: string): string {
  return name.trim().split(/\s+/)[0] ?? name;
}

/** "SCHOOL_ADMIN" → "School admin" (fallback when a role's display name is unknown). */
export function humanizeCode(code: string): string {
  const words = code.replace(/[._-]+/g, " ").trim().toLowerCase();
  return words.charAt(0).toUpperCase() + words.slice(1);
}
