"use client";

import { ArrowRight, ClipboardCheck } from "lucide-react";
import Link from "next/link";
import { Pill } from "@/components/ui/pill";
import { ErrorState, LoadingRows } from "@/components/ui/states";
import { attendanceApi } from "@/lib/attendance-api";
import { formatPlainDate } from "@/lib/format";
import { localeFor, plural, useI18n } from "@/lib/i18n";
import { useApiData } from "@/lib/use-api-data";
import { CountsBar, formatPercent } from "./attendance-shared";

/** Admin and principal dashboard: today's attendance across the school. */
export function AttendanceTodayCard() {
  const { t } = useI18n();
  const today = useApiData("attendance:today", attendanceApi.today);
  const data = today.data;
  const pending = data?.classes.flatMap((c) => c.sections).filter((s) => !s.marked && s.students > 0) ?? [];
  return (
    <section className="card flex flex-col gap-3" aria-labelledby="attendance-today" data-testid="attendance-today">
      <div className="card-head" style={{ marginBottom: 0 }}>
        <h2 id="attendance-today">{t("attendance.today.title")}</h2>
        <ClipboardCheck size={18} className="text-ink-3" aria-hidden="true" />
      </div>
      {today.error && !data ? (
        <ErrorState error={today.error} onRetry={today.reload} />
      ) : !data ? (
        <LoadingRows rows={2} />
      ) : !data.academicYearName ? (
        <p className="text-sm text-ink-2">{t("attendance.noYear")}</p>
      ) : data.sectionCount === 0 ? (
        <p className="text-sm text-ink-2">{t("attendance.noSections")}</p>
      ) : (
        <>
          <div className="flex flex-wrap items-end gap-x-6 gap-y-2">
            <div>
              <span className="kpi-value">{formatPercent(data.presentPercent)}</span>
              <p className="text-[12.5px] font-semibold text-ink-3">{t("attendance.today.present")}</p>
            </div>
            <p className="text-[13.5px] text-ink-2">
              {t("attendance.sectionsMarked", { marked: data.sectionsMarked, total: data.sectionCount })}
            </p>
          </div>
          {data.sectionsMarked > 0 ? <CountsBar counts={data.counts} /> : null}
          {pending.length > 0 ? (
            <p className="text-[13px] text-ink-3">
              {t("attendance.today.pending", {
                sections: pending
                  .slice(0, 6)
                  .map((s) => s.label)
                  .join(", "),
              })}
              {pending.length > 6 ? ` ${t("attendance.today.more", { count: pending.length - 6 })}` : ""}
            </p>
          ) : null}
          <Link href="/app/attendance" className="link inline-flex items-center gap-1 text-[13.5px]">
            {t("attendance.open")}
            <ArrowRight size={16} aria-hidden="true" />
          </Link>
        </>
      )}
    </section>
  );
}

/** Teacher dashboard: one card per section they are class teacher of, to mark today's register. */
export function MarkAttendanceCards() {
  const { t } = useI18n();
  const sections = useApiData("attendance:sections:dashboard", () => attendanceApi.sections());
  const data = sections.data;
  if (sections.error && !data) {
    return (
      <section className="card">
        <ErrorState error={sections.error} onRetry={sections.reload} />
      </section>
    );
  }
  if (!data || !data.academicYear || data.sections.length === 0) return null;
  return (
    <div className="grid grid-cols-1 gap-3.5 md:grid-cols-2 xl:grid-cols-3" data-testid="mark-attendance-cards">
      {data.sections.map((s) => (
        <section key={s.sectionId} className="card flex flex-col gap-2" aria-label={s.label}>
          <div className="flex items-center justify-between gap-2">
            <h2 className="text-[13px] font-medium text-ink-2 font-body">{t("attendance.today.title")}</h2>
            <Pill tone={s.marked ? "good" : "warn"} dot>
              {s.marked ? t("attendance.marked") : t("attendance.notMarked")}
            </Pill>
          </div>
          <p className="font-display text-lg font-bold leading-tight">{s.label}</p>
          <p className="text-[13px] text-ink-3">
            {s.marked
              ? t("attendance.today.summary", {
                  present: s.counts.present + s.counts.late,
                  absent: s.counts.absent,
                  total: s.counts.present + s.counts.absent + s.counts.late + s.counts.halfDay + s.counts.leave,
                })
              : plural(t, "attendance.today.students", s.students)}
          </p>
          <Link
            href={`/app/attendance?section=${encodeURIComponent(s.sectionId)}`}
            className={s.marked ? "btn mt-1 self-start" : "btn btn-primary mt-1 self-start"}
          >
            {s.marked ? t("attendance.today.edit", { section: s.label }) : t("attendance.today.mark", { section: s.label })}
          </Link>
        </section>
      ))}
    </div>
  );
}

/** A parent's view of one child's month: attendance % and the last absences. */
export function ChildAttendanceSummary({ studentId }: { studentId: string }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const data = useApiData(`attendance:child:${studentId}`, () => attendanceApi.child(studentId));
  if (data.error && !data.data) {
    return <p className="text-[13px] text-ink-3">{t("attendance.child.unavailable")}</p>;
  }
  if (!data.data) return <div className="skeleton h-12" aria-hidden="true" />;
  const child = data.data;
  return (
    <div className="flex flex-col gap-1.5 border-t border-line pt-3" data-testid="child-attendance">
      <div className="flex items-baseline justify-between gap-2">
        <span className="text-[13px] font-semibold text-ink-2">{t("attendance.child.thisMonth")}</span>
        <span className="font-display text-lg font-bold num">{formatPercent(child.presentPercent)}</span>
      </div>
      <p className="text-[12.5px] text-ink-3">
        {child.daysMarked > 0
          ? t("attendance.child.days", {
              present: child.counts.present + child.counts.late,
              total: child.daysMarked,
            })
          : t("attendance.child.noneYet")}
      </p>
      <p className="text-[12.5px] text-ink-3">
        {child.recentAbsences.length === 0
          ? t("attendance.child.noAbsences")
          : t("attendance.child.lastAbsences", {
              dates: child.recentAbsences.map((a) => formatPlainDate(a.date, locale)).join(", "),
            })}
      </p>
    </div>
  );
}
