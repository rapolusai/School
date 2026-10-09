"use client";

import { ArrowLeft, Printer } from "lucide-react";
import Link from "next/link";
import { ErrorState, LoadingRows } from "@/components/ui/states";
import { useAuth } from "@/lib/auth";
import { formatLongDate, todayInIndia } from "@/lib/format";
import { localeFor, useI18n } from "@/lib/i18n";
import { timetableApi } from "@/lib/timetable-api";
import { useApiData } from "@/lib/use-api-data";

const PLAIN_DATE = /^\d{4}-\d{2}-\d{2}$/;

/** The day's substitution sheet, laid out for printing and pinning in the staff room. */
export function SubstitutionSheet({ date: requested }: { date?: string }) {
  const { t, lang } = useI18n();
  const { me } = useAuth();
  const date = requested && PLAIN_DATE.test(requested) ? requested : todayInIndia();
  const day = useApiData(`timetable:subs:${date}`, () => timetableApi.substitutions(date));
  const rows =
    day.data?.absences
      .flatMap((a) => a.periods.map((p) => ({ absent: a.teacherName, reason: a.reason, ...p })))
      .sort((a, b) => a.period - b.period || a.sectionLabel.localeCompare(b.sectionLabel)) ?? [];

  return (
    <>
      <div className="no-print mb-3 flex flex-wrap gap-2">
        <Link href={`/app/timetable?tab=substitutions&date=${encodeURIComponent(date)}`} className="btn">
          <ArrowLeft size={18} aria-hidden="true" />
          {t("timetable.subs.back")}
        </Link>
        <button type="button" className="btn btn-primary" onClick={() => window.print()} disabled={!day.data}>
          <Printer size={18} aria-hidden="true" />
          {t("timetable.subs.printNow")}
        </button>
      </div>
      <article className="sub-sheet card" data-testid="substitution-sheet">
        <header className="mb-3">
          <p className="eyebrow">{me?.tenant?.name}</p>
          <h1 className="mt-1">{t("timetable.subs.sheetTitle")}</h1>
          <p className="mt-1 text-ink-2">{formatLongDate(`${date}T12:00:00+05:30`, localeFor(lang))}</p>
        </header>
        {day.error && !day.data ? (
          <ErrorState error={day.error} onRetry={day.reload} />
        ) : !day.data ? (
          <LoadingRows rows={5} />
        ) : rows.length === 0 ? (
          <p className="empty">{t("timetable.subs.sheetEmpty")}</p>
        ) : (
          <div className="table-wrap">
            <table className="table sub-sheet-table">
              <thead>
                <tr>
                  <th scope="col">{t("timetable.period")}</th>
                  <th scope="col">{t("timetable.section")}</th>
                  <th scope="col">{t("timetable.subject")}</th>
                  <th scope="col">{t("timetable.subs.absentCol")}</th>
                  <th scope="col">{t("timetable.subs.substituteCol")}</th>
                  <th scope="col">{t("timetable.subs.signCol")}</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((r) => (
                  <tr key={`${r.sectionId}:${r.period}`}>
                    <td>
                      <b>{r.label}</b>
                      <span className="block text-[12px] text-ink-3">
                        {r.startsAt}–{r.endsAt}
                      </span>
                    </td>
                    <td>
                      {r.sectionLabel}
                      {r.room ? <span className="block text-[12px] text-ink-3">{r.room}</span> : null}
                    </td>
                    <td>{r.subjectName}</td>
                    <td>{r.absent}</td>
                    <td>{r.substitute ? <b>{r.substitute.teacherName}</b> : <span className="text-bad">{t("timetable.day.notCovered")}</span>}</td>
                    <td className="sub-sign" />
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {day.data && rows.length > 0 ? (
          <p className="mt-3 text-[12.5px] text-ink-3">
            {t("timetable.subs.covered", { covered: day.data.periodsCovered, total: day.data.periodsToCover })}
          </p>
        ) : null}
      </article>
    </>
  );
}
