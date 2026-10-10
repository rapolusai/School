"use client";

import { useState } from "react";
import { Pill } from "@/components/ui/pill";
import { ErrorState, LoadingRows } from "@/components/ui/states";
import { formatPlainDate, todayInIndia } from "@/lib/format";
import { localeFor, plural, useI18n } from "@/lib/i18n";
import { reportPaths, reportsApi, type AbsenteesParams } from "@/lib/reports-api";
import type { Absentee } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { STATUS_LABEL } from "../attendance/attendance-shared";
import { ClassFilter, DateFilter, Figure, nameOf, ReportFrame, useClasses } from "./report-frame";

const DATE = /^\d{4}-\d{2}-\d{2}$/;

/** Everyone marked absent (and, if asked, on leave) on one day, across the sections the person may see. */
export function AbsenteesReportView() {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const [today] = useState(() => todayInIndia());
  const [date, setDate] = useState(today);
  const [classId, setClassId] = useState("");
  const [includeLeave, setIncludeLeave] = useState(false);
  const classes = useClasses();

  const problem = !DATE.test(date) ? t("reports.v.date") : date > today ? t("reports.v.future") : null;
  const params: AbsenteesParams = { date, classId: classId || undefined, includeLeave };
  const key = `reports:absentees:${date}:${classId}:${includeLeave}`;
  const report = useApiData(problem ? null : key, () => reportsApi.absentees(params));
  const data =
    report.data && report.data.date === date && (report.data.classId ?? "") === classId && report.data.includeLeave === includeLeave
      ? report.data
      : undefined;

  const className = nameOf(classes, classId);
  const summary = [
    t("reports.filter.dateIs", { date: formatPlainDate(date, locale) }),
    className ? t("reports.filter.classIs", { name: className }) : t("reports.filter.allClasses"),
    includeLeave ? t("reports.absentees.withLeave") : t("reports.absentees.absentOnly"),
  ];

  return (
    <ReportFrame
      title={t("reports.absentees.title")}
      testId="report-absentees"
      filterSummary={summary}
      problem={problem}
      excel={{ path: reportPaths.absenteesXlsx(params), fileName: `absentees-${date}.xlsx` }}
      ready={Boolean(data)}
      filters={
        <>
          <DateFilter
            name="date"
            label={t("reports.filter.date")}
            value={date}
            max={today}
            invalid={problem !== null}
            onChange={setDate}
          />
          <ClassFilter classes={classes} value={classId} onChange={setClassId} />
          <label className="flex min-h-10 items-center gap-2 text-[14px] sm:self-end">
            <input
              type="checkbox"
              name="includeLeave"
              checked={includeLeave}
              onChange={(e) => setIncludeLeave(e.target.checked)}
            />
            {t("reports.absentees.includeLeave")}
          </label>
        </>
      }
    >
      {problem ? null : report.error && !data ? (
        <ErrorState error={report.error} onRetry={report.reload} />
      ) : !data ? (
        <LoadingRows rows={4} />
      ) : (
        <>
          <div className="report-figures">
            <Figure label={t("reports.absentees.absent")} value={data.absent} testId="absent-count" />
            {includeLeave ? <Figure label={t("reports.absentees.onLeave")} value={data.onLeave} /> : null}
            <Figure
              label={t("reports.sectionsMarked")}
              value={t("reports.ofTotal", { count: data.sectionsMarked, total: data.sectionCount })}
            />
          </div>
          {data.holiday ? (
            <p className="alert alert-info">{t("reports.holiday", { name: data.holiday })}</p>
          ) : null}
          {data.rows.length === 0 ? (
            <p className="empty">
              {data.sectionsMarked === 0
                ? t("reports.absentees.noneMarked", { date: formatPlainDate(date, locale) })
                : t("reports.absentees.none", { date: formatPlainDate(date, locale) })}
            </p>
          ) : (
            <AbsenteeList rows={data.rows} />
          )}
        </>
      )}
    </ReportFrame>
  );
}

function AbsenteeList({ rows }: { rows: Absentee[] }) {
  const { t } = useI18n();
  const status = (row: Absentee) => (
    <Pill tone={row.status === "ABSENT" ? "bad" : "info"}>{t(STATUS_LABEL[row.status])}</Pill>
  );
  const streak = (row: Absentee) => plural(t, "reports.absentees.streak", row.daysInARow);
  return (
    <>
      <div className="table-wrap hidden md:block">
        <table className="table" data-testid="absentees-table">
          <caption className="sr-only">{t("reports.absentees.title")}</caption>
          <thead>
            <tr>
              <th scope="col">{t("reports.col.student")}</th>
              <th scope="col">{t("reports.col.section")}</th>
              <th scope="col" className="r">
                {t("reports.col.roll")}
              </th>
              <th scope="col">{t("reports.col.status")}</th>
              <th scope="col" className="r">
                {t("reports.col.inARow")}
              </th>
              <th scope="col">{t("reports.col.markedBy")}</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => (
              <tr key={`${row.studentId}`}>
                <td>
                  <b className="font-semibold">{row.fullName}</b>
                  <span className="block text-[12.5px] text-ink-3">{row.admissionNo}</span>
                </td>
                <td>{row.sectionLabel}</td>
                <td className="r num">{row.rollNo ?? "—"}</td>
                <td>{status(row)}</td>
                <td className="r num">{row.daysInARow}</td>
                <td>{row.markedByName ?? "—"}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <ul className="rowcards md:hidden" data-testid="absentees-cards">
        {rows.map((row) => (
          <li key={row.studentId} className="rowcard">
            <div className="min-w-0 flex-1">
              <b className="block truncate font-semibold">{row.fullName}</b>
              <span className="block text-[12.5px] text-ink-3">
                {[row.sectionLabel, row.rollNo ? t("students.rollShort", { roll: row.rollNo }) : null]
                  .filter(Boolean)
                  .join(" · ")}
              </span>
              {row.daysInARow > 1 ? <span className="block text-[12.5px] text-ink-2">{streak(row)}</span> : null}
            </div>
            {status(row)}
          </li>
        ))}
      </ul>
    </>
  );
}
