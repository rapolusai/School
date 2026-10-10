"use client";

import { Fragment, useState } from "react";
import { ErrorState, LoadingRows } from "@/components/ui/states";
import { formatPlainDate, todayInIndia } from "@/lib/format";
import { localeFor, useI18n } from "@/lib/i18n";
import { reportPaths, reportsApi, type SectionsParams } from "@/lib/reports-api";
import type { SectionAttendanceReport } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { formatPercent } from "../attendance/attendance-shared";
import { ClassFilter, DateFilter, Figure, monthStart, nameOf, rangeProblem, ReportFrame, useClasses } from "./report-frame";

/** A percentage bar for a row: the share of present marks, readable in both themes. */
export function PercentBar({ value }: { value: number | null }) {
  return (
    <span className="pct-bar" aria-hidden="true">
      <span style={{ width: `${value === null ? 0 : Math.max(0, Math.min(100, value))}%` }} />
    </span>
  );
}

/** Attendance % by class and section between two days. */
export function SectionsReportView() {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const [today] = useState(() => todayInIndia());
  const [from, setFrom] = useState(() => monthStart(today));
  const [to, setTo] = useState(today);
  const [classId, setClassId] = useState("");
  const classes = useClasses();

  const problemKey = rangeProblem(from, to);
  const problem = problemKey ? t(problemKey) : null;
  const params: SectionsParams = { from, to, classId: classId || undefined };
  const report = useApiData(problem ? null : `reports:sections:${from}:${to}:${classId}`, () =>
    reportsApi.sections(params),
  );
  const data =
    report.data && report.data.from === from && report.data.to === to && (report.data.classId ?? "") === classId
      ? report.data
      : undefined;

  const className = nameOf(classes, classId);
  const summary = [
    t("reports.filter.rangeIs", { from: formatPlainDate(from, locale), to: formatPlainDate(to, locale) }),
    className ? t("reports.filter.classIs", { name: className }) : t("reports.filter.allClasses"),
  ];

  return (
    <ReportFrame
      title={t("reports.sections.title")}
      testId="report-sections"
      filterSummary={summary}
      problem={problem}
      excel={{ path: reportPaths.sectionsXlsx(params), fileName: `attendance-by-section-${from}-to-${to}.xlsx` }}
      ready={Boolean(data)}
      filters={
        <>
          <DateFilter
            name="from"
            label={t("reports.filter.from")}
            value={from}
            max={today}
            invalid={problem !== null}
            onChange={setFrom}
          />
          <DateFilter
            name="to"
            label={t("reports.filter.to")}
            value={to}
            max={today}
            invalid={problem !== null}
            onChange={setTo}
          />
          <ClassFilter classes={classes} value={classId} onChange={setClassId} />
        </>
      }
    >
      {problem ? null : report.error && !data ? (
        <ErrorState error={report.error} onRetry={report.reload} />
      ) : !data ? (
        <LoadingRows rows={5} />
      ) : (
        <SectionsBody report={data} />
      )}
    </ReportFrame>
  );
}

function SectionsBody({ report }: { report: SectionAttendanceReport }) {
  const { t } = useI18n();
  const hasRows = report.classes.some((c) => c.sections.length > 0);
  return (
    <>
      <div className="report-figures">
        <Figure label={t("reports.sections.overall")} value={formatPercent(report.presentPercent)} testId="overall-percent" />
        <Figure label={t("reports.sections.schoolDays")} value={report.schoolDays} />
        <Figure label={t("reports.sections.holidays")} value={report.holidays} />
        <Figure label={t("reports.sections.students")} value={report.students} />
      </div>
      {!hasRows ? (
        <p className="empty">{t("reports.sections.empty")}</p>
      ) : (
        <>
          <div className="table-wrap hidden md:block">
            <table className="table report-table" data-testid="sections-table">
              <caption className="sr-only">{t("reports.sections.title")}</caption>
              <thead>
                <tr>
                  <th scope="col">{t("reports.col.classSection")}</th>
                  <th scope="col" className="r">
                    {t("reports.col.students")}
                  </th>
                  <th scope="col" className="r">
                    {t("reports.col.daysMarked")}
                  </th>
                  <th scope="col" className="r">
                    {t("reports.col.present")}
                  </th>
                  <th scope="col" className="r">
                    {t("reports.col.late")}
                  </th>
                  <th scope="col" className="r">
                    {t("reports.col.halfDay")}
                  </th>
                  <th scope="col" className="r">
                    {t("reports.col.absent")}
                  </th>
                  <th scope="col" className="r">
                    {t("reports.col.leave")}
                  </th>
                  <th scope="col" className="r">
                    {t("reports.col.percent")}
                  </th>
                </tr>
              </thead>
              <tbody>
                {report.classes.map((line) => (
                  <Fragment key={line.classId}>
                    <tr className="report-group-row">
                      <th scope="row">
                        {line.className}
                      </th>
                      <td className="r num">{line.students}</td>
                      <td />
                      <td className="r num">{line.counts.present}</td>
                      <td className="r num">{line.counts.late}</td>
                      <td className="r num">{line.counts.halfDay}</td>
                      <td className="r num">{line.counts.absent}</td>
                      <td className="r num">{line.counts.leave}</td>
                      <td className="r num font-semibold">{formatPercent(line.presentPercent)}</td>
                    </tr>
                    {line.sections.map((section) => (
                      <tr key={section.sectionId} data-testid="section-row">
                        <th scope="row" className="report-indent">
                          {section.label}
                        </th>
                        <td className="r num">{section.students}</td>
                        <td className="r num">{section.daysMarked}</td>
                        <td className="r num">{section.counts.present}</td>
                        <td className="r num">{section.counts.late}</td>
                        <td className="r num">{section.counts.halfDay}</td>
                        <td className="r num">{section.counts.absent}</td>
                        <td className="r num">{section.counts.leave}</td>
                        <td className="r">
                          <span className="inline-flex items-center gap-2">
                            <PercentBar value={section.presentPercent} />
                            <span className="num">{formatPercent(section.presentPercent)}</span>
                          </span>
                        </td>
                      </tr>
                    ))}
                  </Fragment>
                ))}
              </tbody>
              <tfoot>
                <tr>
                  <th scope="row">{t("reports.total")}</th>
                  <td className="r num">{report.students}</td>
                  <td className="r num">{report.daysMarked}</td>
                  <td className="r num">{report.counts.present}</td>
                  <td className="r num">{report.counts.late}</td>
                  <td className="r num">{report.counts.halfDay}</td>
                  <td className="r num">{report.counts.absent}</td>
                  <td className="r num">{report.counts.leave}</td>
                  <td className="r num">{formatPercent(report.presentPercent)}</td>
                </tr>
              </tfoot>
            </table>
          </div>
          <div className="md:hidden" data-testid="sections-cards">
            {report.classes.map((line) => (
              <section key={line.classId} className="report-card-group">
                <h3 className="flex items-baseline justify-between gap-2 text-[14px] font-semibold">
                  <span>{line.className}</span>
                  <span className="num">{formatPercent(line.presentPercent)}</span>
                </h3>
                <ul className="rowcards">
                  {line.sections.map((section) => (
                    <li key={section.sectionId} className="rowcard">
                      <div className="min-w-0 flex-1">
                        <b className="block truncate font-semibold">{section.label}</b>
                        <span className="block text-[12.5px] text-ink-3">
                          {t("reports.sections.cardLine", {
                            students: section.students,
                            days: section.daysMarked,
                            absent: section.counts.absent,
                          })}
                        </span>
                        <PercentBar value={section.presentPercent} />
                      </div>
                      <b className="num">{formatPercent(section.presentPercent)}</b>
                    </li>
                  ))}
                </ul>
              </section>
            ))}
          </div>
        </>
      )}
      <p className="text-[12.5px] text-ink-3">{t("reports.sections.rule")}</p>
    </>
  );
}
