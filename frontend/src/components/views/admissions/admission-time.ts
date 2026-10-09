import { DISPLAY_TIME_ZONE } from "@/lib/format";
import { isPlainDate } from "@/lib/validation";

/** India has no daylight saving time, so its offset is fixed. */
const IST_OFFSET = "+05:30";

/** An instant as a `datetime-local` value in India time: "2026-10-12T10:00". */
export function toIndiaInput(iso: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return "";
  const parts = new Intl.DateTimeFormat("en-CA", {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hourCycle: "h23",
    timeZone: DISPLAY_TIME_ZONE,
  }).formatToParts(date);
  const get = (type: string) => parts.find((p) => p.type === type)?.value ?? "";
  return `${get("year")}-${get("month")}-${get("day")}T${get("hour")}:${get("minute")}`;
}

/** A `datetime-local` value read as India time → ISO instant; null when it is not a valid date and time. */
export function fromIndiaInput(value: string): string | null {
  const match = /^(\d{4}-\d{2}-\d{2})T(\d{2}):(\d{2})$/.exec(value);
  if (!match || !isPlainDate(match[1])) return null;
  if (Number(match[2]) > 23 || Number(match[3]) > 59) return null;
  const date = new Date(`${value}:00${IST_OFFSET}`);
  return Number.isNaN(date.getTime()) ? null : date.toISOString();
}

/** "2026-10-09" plus `days` → "2026-10-23", on the calendar (no time zone involved). */
export function addDays(day: string, days: number): string {
  const [y, m, d] = day.split("-").map(Number);
  const date = new Date(Date.UTC(y, m - 1, d + days));
  return date.toISOString().slice(0, 10);
}

/** The same calendar day `years` years earlier (29 Feb becomes 28 Feb). */
export function yearsBefore(day: string, years: number): string {
  const [y, m, d] = day.split("-").map(Number);
  const date = new Date(Date.UTC(y - years, m - 1, d));
  if (date.getUTCMonth() !== m - 1) date.setUTCDate(0);
  return date.toISOString().slice(0, 10);
}
