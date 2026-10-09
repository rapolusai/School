"use client";

import { useI18n, type MessageKey } from "@/lib/i18n";
import { MARK_OF, type AttendanceCounts, type AttendanceMark, type AttendanceStatus } from "@/lib/types";

export const STATUS_LABEL: Record<AttendanceStatus, MessageKey> = {
  PRESENT: "attendance.status.PRESENT",
  ABSENT: "attendance.status.ABSENT",
  LATE: "attendance.status.LATE",
  HALF_DAY: "attendance.status.HALF_DAY",
  LEAVE: "attendance.status.LEAVE",
};

export const MARK_LABEL: Record<AttendanceMark, MessageKey> = {
  P: "attendance.status.PRESENT",
  A: "attendance.status.ABSENT",
  L: "attendance.status.LATE",
  H: "attendance.status.HALF_DAY",
  E: "attendance.status.LEAVE",
};

/** 93.456 → "93.5%"; null → "—". */
export function formatPercent(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) return "—";
  return `${Number.isInteger(value) ? value : value.toFixed(1)}%`;
}

/** Counts of each mark (pure, exported for tests). */
export function countStatuses(statuses: (AttendanceStatus | null | undefined)[]): AttendanceCounts {
  const counts: AttendanceCounts = { present: 0, absent: 0, late: 0, halfDay: 0, leave: 0 };
  for (const status of statuses) {
    if (status === "PRESENT") counts.present++;
    else if (status === "ABSENT") counts.absent++;
    else if (status === "LATE") counts.late++;
    else if (status === "HALF_DAY") counts.halfDay++;
    else if (status === "LEAVE") counts.leave++;
  }
  return counts;
}

/** Present and late count as a day, a half day as half, absent and leave as none (docs/api/phase-1-attendance.md). */
export function presentPercent(counts: AttendanceCounts): number | null {
  const total = counts.present + counts.absent + counts.late + counts.halfDay + counts.leave;
  if (total === 0) return null;
  const halves = 2 * (counts.present + counts.late) + counts.halfDay;
  return Math.round((halves * 1000) / (2 * total)) / 10;
}

/** A coloured P / A / L / H / E square. */
export function MarkBadge({ mark }: { mark: AttendanceMark }) {
  const { t } = useI18n();
  return (
    <span className={`mark mark-${mark}`} title={t(MARK_LABEL[mark])}>
      {mark}
    </span>
  );
}

export function statusMark(status: AttendanceStatus): AttendanceMark {
  return MARK_OF[status];
}

/** "Present 26 · Absent 2 · …" for a register or a day. */
export function CountsBar({
  counts,
  unmarked,
  testId,
}: {
  counts: AttendanceCounts;
  unmarked?: number;
  testId?: string;
}) {
  const { t } = useI18n();
  const items: { key: string; label: string; value: number; mark?: AttendanceMark }[] = [
    { key: "P", label: t("attendance.status.PRESENT"), value: counts.present, mark: "P" },
    { key: "A", label: t("attendance.status.ABSENT"), value: counts.absent, mark: "A" },
    { key: "L", label: t("attendance.status.LATE"), value: counts.late, mark: "L" },
    { key: "H", label: t("attendance.status.HALF_DAY"), value: counts.halfDay, mark: "H" },
    { key: "E", label: t("attendance.status.LEAVE"), value: counts.leave, mark: "E" },
  ];
  if (unmarked !== undefined) items.push({ key: "U", label: t("attendance.notMarkedYet"), value: unmarked });
  return (
    <dl className="att-counts" aria-live="polite" data-testid={testId}>
      {items.map((item) => (
        <div key={item.key} className={item.key === "U" && item.value > 0 ? "att-count-todo" : undefined}>
          <dt>{item.label}</dt>
          <dd className="num">{item.value}</dd>
        </div>
      ))}
    </dl>
  );
}

/** The P / A / L / H / E legend under registers and reports. */
export function MarkLegend() {
  const { t } = useI18n();
  return (
    <ul className="legend" aria-label={t("attendance.legend")}>
      {(["P", "A", "L", "H", "E"] as const).map((mark) => (
        <li key={mark}>
          <span className={`mark mark-${mark}`} aria-hidden="true">
            {mark}
          </span>
          {t(MARK_LABEL[mark])}
        </li>
      ))}
    </ul>
  );
}
