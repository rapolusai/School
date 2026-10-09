import {
  WEEK_DAYS,
  type BellSchedule,
  type Booking,
  type CellRequest,
  type PeriodView,
  type SlotView,
  type SubjectLoad,
  type WeekDay,
} from "@/lib/types";

/* Pure helpers for the timetable screens (exported for tests). */

/** One cell of a section's week in the editor. */
export type GridCell = { subjectId: string; teacherId: string | null; room: string };

/** Cells by "MONDAY-3". */
export type Grid = Record<string, GridCell>;

export function cellKey(day: WeekDay, period: number): string {
  return `${day}-${period}`;
}

export function gridFromSlots(slots: SlotView[]): Grid {
  const grid: Grid = {};
  for (const slot of slots) {
    grid[cellKey(slot.day, slot.period)] = { subjectId: slot.subjectId, teacherId: slot.teacherId, room: slot.room ?? "" };
  }
  return grid;
}

/** The working days in week order. */
export function workingDays(bells: BellSchedule): WeekDay[] {
  return WEEK_DAYS.filter((day) => bells.workingDays.includes(day));
}

/** The periods and breaks of a day: Saturday's own schedule when the school has one. */
export function periodsOf(bells: BellSchedule, day: WeekDay): PeriodView[] {
  return day === "SATURDAY" && bells.saturdaySchedule ? bells.saturday : bells.weekday;
}

export type GridRow =
  | { kind: "period"; number: number; label: string; startsAt: string; endsAt: string }
  | { kind: "break"; label: string; startsAt: string; endsAt: string };

/**
 * Rows of the week table: the weekday schedule (periods and breaks), plus any extra numbered periods
 * a separate Saturday schedule has beyond it.
 */
export function gridRows(bells: BellSchedule): GridRow[] {
  const rows: GridRow[] = bells.weekday.map((p) =>
    p.breakTime || p.number === null
      ? { kind: "break", label: p.label, startsAt: p.startsAt, endsAt: p.endsAt }
      : { kind: "period", number: p.number, label: p.label, startsAt: p.startsAt, endsAt: p.endsAt },
  );
  if (bells.saturdaySchedule && bells.workingDays.includes("SATURDAY")) {
    for (const p of bells.saturday) {
      if (p.number !== null && p.number > bells.weekdayPeriods) {
        rows.push({ kind: "period", number: p.number, label: p.label, startsAt: p.startsAt, endsAt: p.endsAt });
      }
    }
  }
  return rows;
}

/** Whether the day has a teaching period with this number. */
export function hasPeriod(bells: BellSchedule, day: WeekDay, period: number): boolean {
  return periodsOf(bells, day).some((p) => p.number === period);
}

/** The teacher a subject's periods go to: its assigned teacher, if any. */
export function teacherFor(subjects: SubjectLoad[], subjectId: string): string | null {
  return subjects.find((s) => s.subjectId === subjectId)?.teacherId ?? null;
}

/** Cells whose teacher is already in another section in that period, by cell key. */
export function teacherClashes(grid: Grid, busy: Booking[]): Record<string, Booking> {
  const found: Record<string, Booking> = {};
  for (const booking of busy) {
    const cell = grid[cellKey(booking.day, booking.period)];
    if (cell && cell.teacherId && cell.teacherId === booking.teacherId) {
      found[cellKey(booking.day, booking.period)] ??= booking;
    }
  }
  return found;
}

/** Periods placed per subject. */
export function weeklyCounts(grid: Grid): Record<string, number> {
  const counts: Record<string, number> = {};
  for (const cell of Object.values(grid)) counts[cell.subjectId] = (counts[cell.subjectId] ?? 0) + 1;
  return counts;
}

export type OverLimit = { subjectId: string; subjectName: string; scheduled: number; allowed: number };

/** Subjects placed more often than their assignment allows (a warning, not an error). */
export function overLimit(grid: Grid, subjects: SubjectLoad[]): OverLimit[] {
  const counts = weeklyCounts(grid);
  return subjects
    .filter((s) => s.periodsPerWeek !== null && (counts[s.subjectId] ?? 0) > s.periodsPerWeek)
    .map((s) => ({
      subjectId: s.subjectId,
      subjectName: s.subjectName,
      scheduled: counts[s.subjectId] ?? 0,
      allowed: s.periodsPerWeek ?? 0,
    }));
}

/** The cells to send, in week order. */
export function toCells(grid: Grid): CellRequest[] {
  return Object.entries(grid)
    .map(([key, cell]) => {
      const dash = key.lastIndexOf("-");
      return {
        day: key.slice(0, dash) as WeekDay,
        period: Number(key.slice(dash + 1)),
        subjectId: cell.subjectId,
        teacherId: cell.teacherId,
        room: cell.room.trim() || null,
      };
    })
    .sort((a, b) => WEEK_DAYS.indexOf(a.day) - WEEK_DAYS.indexOf(b.day) || a.period - b.period);
}

export function sameGrid(a: Grid, b: Grid): boolean {
  const keys = Object.keys(a);
  if (keys.length !== Object.keys(b).length) return false;
  return keys.every((key) => {
    const x = a[key];
    const y = b[key];
    return Boolean(y) && x.subjectId === y.subjectId && x.teacherId === y.teacherId && x.room.trim() === y.room.trim();
  });
}

/** "HH:mm" → minutes since midnight, or NaN. */
export function minutes(time: string): number {
  const match = /^(\d{2}):(\d{2})$/.exec(time);
  if (!match) return Number.NaN;
  const h = Number(match[1]);
  const m = Number(match[2]);
  return h < 24 && m < 60 ? h * 60 + m : Number.NaN;
}

export type RowProblem = "label" | "time" | "order" | "overlap";

/** Client-side checks of a bell schedule's rows, by row index. Pure. */
export function bellRowProblems(rows: { label: string; startsAt: string; endsAt: string }[]): Record<number, RowProblem> {
  const problems: Record<number, RowProblem> = {};
  let previousEnd = -1;
  rows.forEach((row, i) => {
    const start = minutes(row.startsAt);
    const end = minutes(row.endsAt);
    if (!row.label.trim()) problems[i] = "label";
    else if (Number.isNaN(start) || Number.isNaN(end)) problems[i] = "time";
    else if (end <= start) problems[i] = "order";
    else if (start < previousEnd) problems[i] = "overlap";
    if (!Number.isNaN(end)) previousEnd = Math.max(previousEnd, end);
  });
  return problems;
}
