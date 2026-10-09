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

const PLAIN_DATE = /^(\d{4})-(\d{2})-(\d{2})$/;

/**
 * A calendar date without time ("2026-06-01", as the API sends dates of birth and academic
 * years) → "1 Jun 2026". Never shifts the day, whatever the viewer's time zone.
 */
export function formatPlainDate(value: string | null | undefined, locale = "en-IN"): string {
  if (!value) return "";
  const match = PLAIN_DATE.exec(value);
  if (!match) return "";
  const date = new Date(Date.UTC(Number(match[1]), Number(match[2]) - 1, Number(match[3])));
  if (Number.isNaN(date.getTime())) return "";
  return new Intl.DateTimeFormat(locale, {
    day: "numeric",
    month: "short",
    year: "numeric",
    timeZone: "UTC",
  }).format(date);
}

/** Today's date in India as "YYYY-MM-DD" (for date inputs and "not in the future" checks). */
export function todayInIndia(now: Date = new Date()): string {
  const parts = new Intl.DateTimeFormat("en-CA", {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    timeZone: DISPLAY_TIME_ZONE,
  }).formatToParts(now);
  const get = (type: string) => parts.find((p) => p.type === type)?.value ?? "";
  return `${get("year")}-${get("month")}-${get("day")}`;
}

/** "9876500001" → "98765 00001" (Indian mobile grouping). Other values are returned as given. */
export function formatPhone(phone: string | null | undefined): string {
  if (!phone) return "";
  return /^\d{10}$/.test(phone) ? `${phone.slice(0, 5)} ${phone.slice(5)}` : phone;
}

/** "Class 5" + "A" → "Class 5 A"; missing parts are skipped. */
export function classLabel(className: string | null | undefined, sectionName: string | null | undefined): string {
  return [className, sectionName].filter(Boolean).join(" ");
}

/* ---------------------------------------------------------------------------------------------- */
/* Fees: the API sends and takes paise (₹1 = 100 paise); people read and type rupees.              */
/* ---------------------------------------------------------------------------------------------- */

const inrPaise = new Intl.NumberFormat("en-IN", { minimumFractionDigits: 2, maximumFractionDigits: 2 });

/** 18430000 → "₹1,84,300"; 18430050 → "₹1,84,300.50"; -45000 → "-₹450". */
export function formatPaise(paise: number): string {
  const whole = Math.round(paise);
  const sign = whole < 0 ? "-" : "";
  const abs = Math.abs(whole);
  if (abs % 100 === 0) return `${sign}₹${inrNumber.format(abs / 100)}`;
  return `${sign}₹${inrPaise.format(abs / 100)}`;
}

/**
 * Rupees as typed ("1,84,300", "₹ 4500.5", "450.50") → paise, or null when it is not a
 * non-negative amount with at most two decimals.
 */
export function parseRupees(text: string): number | null {
  const cleaned = text.replace(/[₹,\s]/g, "");
  const match = /^(\d{1,9})(?:\.(\d{0,2}))?$/.exec(cleaned);
  if (!match) return null;
  return Number(match[1]) * 100 + Number((match[2] ?? "").padEnd(2, "0"));
}

/** Paise → the plain rupee text an amount input starts with: 1150000 → "11500", 45050 → "450.50". */
export function rupeesInput(paise: number): string {
  const abs = Math.abs(Math.round(paise));
  const rupees = Math.floor(abs / 100);
  const rest = abs % 100;
  return `${paise < 0 ? "-" : ""}${rupees}${rest ? `.${String(rest).padStart(2, "0")}` : ""}`;
}
