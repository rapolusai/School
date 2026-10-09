"use client";

import { Pill, type PillTone } from "@/components/ui/pill";
import { formatPlainDate } from "@/lib/format";
import { useI18n, type MessageKey, type Translate } from "@/lib/i18n";
import type { HomeworkCounts, StudentHomeworkRow, TrackerRow } from "@/lib/types";

type AnyStatus = StudentHomeworkRow["status"] | TrackerRow["status"];

export const HOMEWORK_STATUS_LABEL: Record<AnyStatus, MessageKey> = {
  PENDING: "homework.status.PENDING",
  MISSING: "homework.status.MISSING",
  SUBMITTED: "homework.status.SUBMITTED",
  REVIEWED: "homework.status.REVIEWED",
  NEEDS_REDO: "homework.status.NEEDS_REDO",
};

const TONES: Record<AnyStatus, PillTone> = {
  PENDING: "neutral",
  MISSING: "warn",
  SUBMITTED: "info",
  REVIEWED: "good",
  NEEDS_REDO: "bad",
};

export function StatusPill({ status, late = false }: { status: AnyStatus; late?: boolean }) {
  const { t } = useI18n();
  return (
    <span className="inline-flex flex-wrap gap-1">
      <Pill tone={TONES[status]} dot>
        {t(HOMEWORK_STATUS_LABEL[status])}
      </Pill>
      {late ? <Pill tone="warn">{t("homework.late")}</Pill> : null}
    </span>
  );
}

/** Whole days from `today` to `date` (both "YYYY-MM-DD"). Pure. */
export function daysBetween(today: string, date: string): number {
  const a = Date.UTC(Number(today.slice(0, 4)), Number(today.slice(5, 7)) - 1, Number(today.slice(8, 10)));
  const b = Date.UTC(Number(date.slice(0, 4)), Number(date.slice(5, 7)) - 1, Number(date.slice(8, 10)));
  return Math.round((b - a) / 86_400_000);
}

/** "Due today", "Due tomorrow", "Due 12 Oct 2026" or "Was due 3 Oct 2026". Pure. */
export function dueLabel(t: Translate, dueOn: string, today: string, locale: string): string {
  const days = daysBetween(today, dueOn);
  if (days === 0) return t("homework.due.today");
  if (days === 1) return t("homework.due.tomorrow");
  const date = formatPlainDate(dueOn, locale);
  return days < 0 ? t("homework.due.past", { date }) : t("homework.due.on", { date });
}

export function dueTone(dueOn: string, today: string): PillTone {
  const days = daysBetween(today, dueOn);
  if (days < 0) return "neutral";
  if (days <= 1) return "warn";
  return "info";
}

/** "12 of 30 submitted · 2 late · 18 not yet". */
export function countsText(t: Translate, counts: HomeworkCounts): string {
  const parts = [t("homework.counts.submitted", { submitted: counts.submitted, students: counts.students })];
  if (counts.late > 0) parts.push(t("homework.counts.late", { count: counts.late }));
  if (counts.missing > 0) parts.push(t("homework.counts.missing", { count: counts.missing }));
  if (counts.waiting > 0) parts.push(t("homework.counts.waiting", { count: counts.waiting }));
  return parts.join(" · ");
}

/** Instructions are plain text: line breaks kept, nothing rendered as HTML. */
export function Instructions({ text }: { text: string | null }) {
  const { t } = useI18n();
  if (!text) return <p className="text-sm text-ink-3">{t("homework.noInstructions")}</p>;
  return <p className="msg-body">{text}</p>;
}
