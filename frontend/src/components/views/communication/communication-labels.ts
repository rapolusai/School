import type { PillTone } from "@/components/ui/pill";
import { formatPlainDate } from "@/lib/format";
import { translateOr, type MessageKey, type Translate } from "@/lib/i18n";
import type {
  AudienceOptions,
  AudienceRequest,
  CalendarEntry,
  CircularCategory,
  CircularStatus,
  EntryKind,
  PlainDate,
} from "@/lib/types";
import { isPlainDate } from "@/lib/validation";

/** Limits the API applies (docs/api/phase-1-communication.md). */
export const MAX_TITLE = 200;
export const MAX_BODY = 5000;
export const MAX_DESCRIPTION = 2000;
export const MAX_NOTE = 1000;
export const MAX_REASON = 500;
export const MAX_SPAN_DAYS = 120;

const CATEGORY_TONES: Record<CircularCategory, PillTone> = {
  GENERAL: "neutral",
  ACADEMIC: "accent",
  EVENT: "info",
  HOLIDAY: "good",
  FEES: "warn",
  URGENT: "bad",
};

const STATUS_TONES: Record<CircularStatus, PillTone> = {
  DRAFT: "neutral",
  PENDING_APPROVAL: "warn",
  SCHEDULED: "info",
  SENT: "good",
  WITHDRAWN: "bad",
};

export function categoryTone(category: CircularCategory): PillTone {
  return CATEGORY_TONES[category] ?? "neutral";
}

export function statusTone(status: CircularStatus): PillTone {
  return STATUS_TONES[status] ?? "neutral";
}

export function categoryLabel(t: Translate, category: string): string {
  return translateOr(t, `notices.category.${category}`, category);
}

export function statusLabel(t: Translate, status: string): string {
  return translateOr(t, `notices.status.${status}`, status);
}

export function channelLabel(t: Translate, channel: string): string {
  return translateOr(t, `messages.channel.${channel}`, channel);
}

export function kindLabel(t: Translate, kind: string): string {
  return translateOr(t, `calendar.kind.${kind}`, kind);
}

/** 80 → "₹0.80"; 125000 → "₹1,250.00" (indicative prices are small, so paise are shown). */
export function formatPaise(paise: number): string {
  const rupees = paise / 100;
  return `₹${rupees.toLocaleString("en-IN", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}

const LOCAL_DATE_TIME = /^(\d{4}-\d{2}-\d{2})T(\d{2}):(\d{2})$/;

/** "2026-10-12T09:30" typed as India time → the ISO instant; null when it is not a full date and time. */
export function indiaLocalToInstant(value: string): string | null {
  const match = LOCAL_DATE_TIME.exec(value);
  if (!match || !isPlainDate(match[1]) || Number(match[2]) > 23 || Number(match[3]) > 59) return null;
  const date = new Date(`${value}:00+05:30`);
  return Number.isNaN(date.getTime()) ? null : date.toISOString();
}

/** An ISO instant → "2026-10-12T09:30" in India time, for a datetime-local input. */
export function instantToIndiaLocal(iso: string | null | undefined): string {
  if (!iso) return "";
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return "";
  const india = new Date(date.getTime() + 330 * 60_000);
  return india.toISOString().slice(0, 16);
}

export const EMPTY_AUDIENCE: AudienceRequest = { wholeSchool: false, classIds: [], sectionIds: [], roles: [] };

export function isFamilyRole(code: string): boolean {
  return code === "PARENT" || code === "STUDENT";
}

/**
 * The same audience rules as the API: someone must be chosen; chosen classes or sections need parents or students;
 * without notices.approve only parents and students of one's own sections. Null when the audience is fine.
 */
export function audienceProblem(audience: AudienceRequest, options: AudienceOptions | undefined): MessageKey | null {
  if (audience.wholeSchool) {
    return options && !options.canAddressWholeSchool ? "notices.v.teacherLimit" : null;
  }
  const scoped = audience.classIds.length > 0 || audience.sectionIds.length > 0;
  if (!scoped && audience.roles.length === 0) return "notices.v.audience";
  if (scoped && !audience.roles.some(isFamilyRole)) return "notices.v.families";
  if (options && !options.canApprove) {
    const own = new Set(options.classes.flatMap((c) => c.sections.filter((s) => s.own).map((s) => s.id)));
    const ok =
      audience.classIds.length === 0 &&
      audience.sectionIds.length > 0 &&
      audience.sectionIds.every((s) => own.has(s)) &&
      audience.roles.every(isFamilyRole);
    if (!ok) return "notices.v.teacherLimit";
  }
  return null;
}

/** True when anything at all is chosen (worth asking the API for an estimate). */
export function audienceChosen(audience: AudienceRequest): boolean {
  return (
    audience.wholeSchool ||
    audience.classIds.length > 0 ||
    audience.sectionIds.length > 0 ||
    audience.roles.length > 0
  );
}

/* ---------------------------------------------------------------- calendar dates */

function utc(date: PlainDate): Date {
  return new Date(`${date}T00:00:00Z`);
}

/** "2026-10-30" + 3 → "2026-11-02". */
export function addDays(date: PlainDate, days: number): PlainDate {
  const d = utc(date);
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10);
}

/** Whole days from `from` to `to` (negative when `to` is earlier). */
export function daysBetween(from: PlainDate, to: PlainDate): number {
  return Math.round((utc(to).getTime() - utc(from).getTime()) / 86_400_000);
}

/** "2026-10" + 1 → "2026-11". */
export function addMonths(month: string, count: number): string {
  const [year, m] = month.split("-").map(Number);
  const d = new Date(Date.UTC(year, m - 1 + count, 1));
  return d.toISOString().slice(0, 7);
}

/** 0 = Monday … 6 = Sunday. */
export function weekdayIndex(date: PlainDate): number {
  return (utc(date).getUTCDay() + 6) % 7;
}

/** The days shown for a month: whole weeks from the Monday on or before the 1st to the Sunday after the last day. */
export function monthGridDays(month: string): PlainDate[] {
  const first = `${month}-01`;
  const last = addDays(`${addMonths(month, 1)}-01`, -1);
  const start = addDays(first, -weekdayIndex(first));
  const end = addDays(last, 6 - weekdayIndex(last));
  const days: PlainDate[] = [];
  for (let d = start; d <= end; d = addDays(d, 1)) days.push(d);
  return days;
}

/** Entries on each day they cover, in start order (all-day entries first, then by time). */
export function entriesByDay(entries: CalendarEntry[], days: PlainDate[]): Map<PlainDate, CalendarEntry[]> {
  const map = new Map<PlainDate, CalendarEntry[]>(days.map((d) => [d, []]));
  const sorted = [...entries].sort(
    (a, b) =>
      a.startsOn.localeCompare(b.startsOn) ||
      (a.startTime ?? "").localeCompare(b.startTime ?? "") ||
      a.title.localeCompare(b.title),
  );
  for (const entry of sorted) {
    for (const day of days) {
      if (day >= entry.startsOn && day <= entry.endsOn) map.get(day)?.push(entry);
    }
  }
  return map;
}

/** Whole-school holidays close the school (the same rule attendance uses). */
export function isSchoolHoliday(entry: CalendarEntry): boolean {
  return entry.kind === "HOLIDAY" && entry.audience === "SCHOOL";
}

const KIND_ORDER: EntryKind[] = ["HOLIDAY", "EXAM", "PTM", "EVENT", "OTHER"];

export function kindOrder(kind: EntryKind): number {
  return KIND_ORDER.indexOf(kind);
}

/** "09:00:00" or "09:00" → "09:00". */
export function shortTime(value: string | null | undefined): string {
  return value ? value.slice(0, 5) : "";
}

/** "13:30" → "1:30 pm" in the viewer's language. */
export function formatClock(value: string | null | undefined, locale = "en-IN"): string {
  const time = shortTime(value);
  if (!/^\d{2}:\d{2}$/.test(time)) return "";
  return new Intl.DateTimeFormat(locale, { hour: "numeric", minute: "2-digit", timeZone: "UTC" }).format(
    new Date(`1970-01-01T${time}:00Z`),
  );
}

/** When an entry happens, in words: "24 Oct 2026, 9:00 am – 12:30 pm" or "7 Dec 2026 to 12 Dec 2026". */
export function entryWhen(entry: CalendarEntry, t: Translate, locale: string): string {
  const day = (date: PlainDate) => formatPlainDate(date, locale);
  const dates =
    entry.endsOn && entry.endsOn !== entry.startsOn
      ? t("calendar.range", { from: day(entry.startsOn), to: day(entry.endsOn) })
      : day(entry.startsOn);
  if (!entry.startTime) return dates;
  const times = entry.endTime
    ? `${formatClock(entry.startTime, locale)} – ${formatClock(entry.endTime, locale)}`
    : formatClock(entry.startTime, locale);
  return `${dates}, ${times}`;
}

/** Who an entry is for, in words. */
export function entryAudience(entry: CalendarEntry, t: Translate): string {
  if (entry.audience === "CLASSES") {
    return t("calendar.audience.classesList", { classes: entry.classes.map((c) => c.name).join(", ") });
  }
  return translateOr(t, `calendar.audience.${entry.audience}`, entry.audience);
}
