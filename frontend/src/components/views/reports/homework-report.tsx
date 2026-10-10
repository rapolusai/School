"use client";

import { useState } from "react";
import { ErrorState, LoadingRows } from "@/components/ui/states";
import { api } from "@/lib/api";
import { formatPlainDate, todayInIndia } from "@/lib/format";
import { localeFor, useI18n } from "@/lib/i18n";
import { reportPaths, reportsApi, type HomeworkParams } from "@/lib/reports-api";
import type { CompletionLine, HomeworkCompletionReport } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { formatPercent } from "../attendance/attendance-shared";
import { addDays, ClassFilter, DateFilter, Figure, nameOf, rangeProblem, ReportFrame, useClasses } from "./report-frame";
import { PercentBar } from "./sections-report";

/** The API's default window: homework due in the last 30 days. */
const DEFAULT_DAYS = 30;

/** Homework due between two days, by section and subject: handed in, late, reviewed and waiting. */
export function HomeworkReportView() {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const [today] = useState(() => todayInIndia());
  const [from, setFrom] = useState(() => addDays(today, -DEFAULT_DAYS));
  const [to, setTo] = useState(today);
  const [classId, setClassId] = useState("");
  const [subjectId, setSubjectId] = useState("");
  const classes = useClasses();
  const subjects = useApiData("reports:subjects", api.listSubjects).data ?? [];

  const problemKey = rangeProblem(from, to);
  const problem = problemKey ? t(problemKey) : null;
  const params: HomeworkParams = { from, to, classId: classId || undefined, subjectId: subjectId || undefined };
  const report = useApiData(problem ? null : `reports:homework:${from}:${to}:${classId}:${subjectId}`, () =>
    reportsApi.homework(params),
  );
  const data =
    report.data &&
    report.data.from === from &&
    report.data.to === to &&
    (report.data.classId ?? "") === classId &&
    (report.data.subjectId ?? "") === subjectId
      ? report.data
      : undefined;

  const className = nameOf(classes, classId);
  const subjectName = nameOf(subjects, subjectId);
  const summary = [
    t("reports.homework.dueBetween", { from: formatPlainDate(from, locale), to: formatPlainDate(to, locale) }),
    className ? t("reports.filter.classIs", { name: className }) : t("reports.filter.allClasses"),
    subjectName ? t("reports.filter.subjectIs", { name: subjectName }) : t("reports.filter.allSubjects"),
  ];

  return (
    <ReportFrame
      title={t("reports.homework.title")}
      testId="report-homework"
      filterSummary={summary}
      problem={problem}
      excel={{ path: reportPaths.homeworkXlsx(params), fileName: `homework-completion-${from}-to-${to}.xlsx` }}
      ready={Boolean(data)}
      filters={
        <>
          <DateFilter name="from" label={t("reports.homework.dueFrom")} value={from} invalid={problem !== null} onChange={setFrom} />
          <DateFilter name="to" label={t("reports.homework.dueTo")} value={to} invalid={problem !== null} onChange={setTo} />
          <ClassFilter classes={classes} value={classId} onChange={setClassId} />
          <label className="field w-full sm:w-auto sm:min-w-44">
            <span className="field-label">{t("reports.filter.subject")}</span>
            <select className="input" name="subjectId" value={subjectId} onChange={(e) => setSubjectId(e.target.value)}>
              <option value="">{t("reports.filter.allSubjects")}</option>
              {subjects.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.name}
                </option>
              ))}
            </select>
          </label>
        </>
      }
    >
      {problem ? null : report.error && !data ? (
        <ErrorState error={report.error} onRetry={report.reload} />
      ) : !data ? (
        <LoadingRows rows={5} />
      ) : (
        <HomeworkBody report={data} />
      )}
    </ReportFrame>
  );
}

function HomeworkBody({ report }: { report: HomeworkCompletionReport }) {
  const { t } = useI18n();
  const total = report.total;
  const cells = (line: CompletionLine) => [
    line.homework,
    line.expected,
    line.submitted,
    line.late,
    line.reviewed,
    line.needsRedo,
    line.waiting,
  ];
  return (
    <>
      <div className="report-figures">
        <Figure label={t("reports.homework.completion")} value={formatPercent(total.completionPercent)} testId="completion-percent" />
        <Figure label={t("reports.homework.set")} value={total.homework} />
        <Figure label={t("reports.homework.online")} value={total.online} />
        <Figure label={t("reports.homework.waiting")} value={total.waiting} />
      </div>
      {report.rows.length === 0 ? (
        <p className="empty">{t("reports.homework.empty")}</p>
      ) : (
        <>
          <div className="table-wrap hidden md:block">
            <table className="table report-table" data-testid="homework-table">
              <caption className="sr-only">{t("reports.homework.title")}</caption>
              <thead>
                <tr>
                  <th scope="col">{t("reports.col.section")}</th>
                  <th scope="col">{t("reports.col.subject")}</th>
                  {(
                    [
                      "reports.col.homework",
                      "reports.col.expected",
                      "reports.col.submitted",
                      "reports.col.late",
                      "reports.col.reviewed",
                      "reports.col.redo",
                      "reports.col.waiting",
                      "reports.col.completion",
                    ] as const
                  ).map((key) => (
                    <th key={key} scope="col" className="r">
                      {t(key)}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {report.rows.map((line) => (
                  <tr key={`${line.sectionId}:${line.subjectId}`}>
                    <th scope="row">{line.sectionLabel ?? "—"}</th>
                    <td>{line.subjectName ?? "—"}</td>
                    {cells(line).map((value, i) => (
                      <td key={i} className="r num">
                        {value}
                      </td>
                    ))}
                    <td className="r">
                      <span className="inline-flex items-center gap-2">
                        <PercentBar value={line.completionPercent} />
                        <span className="num">{formatPercent(line.completionPercent)}</span>
                      </span>
                    </td>
                  </tr>
                ))}
              </tbody>
              <tfoot>
                <tr>
                  <th scope="row">{t("reports.total")}</th>
                  <td />
                  {cells(total).map((value, i) => (
                    <td key={i} className="r num">
                      {value}
                    </td>
                  ))}
                  <td className="r num">{formatPercent(total.completionPercent)}</td>
                </tr>
              </tfoot>
            </table>
          </div>
          <ul className="rowcards md:hidden" data-testid="homework-cards">
            {report.rows.map((line) => (
              <li key={`${line.sectionId}:${line.subjectId}`} className="rowcard">
                <div className="min-w-0 flex-1">
                  <b className="block truncate font-semibold">
                    {[line.sectionLabel, line.subjectName].filter(Boolean).join(" · ")}
                  </b>
                  <span className="block text-[12.5px] text-ink-3">
                    {t("reports.homework.cardLine", {
                      homework: line.homework,
                      submitted: line.submitted,
                      expected: line.expected,
                      waiting: line.waiting,
                    })}
                  </span>
                  <PercentBar value={line.completionPercent} />
                </div>
                <b className="num">{formatPercent(line.completionPercent)}</b>
              </li>
            ))}
          </ul>
        </>
      )}
      <p className="text-[12.5px] text-ink-3">{t("reports.homework.rule")}</p>
    </>
  );
}
