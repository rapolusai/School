"use client";

import { useState } from "react";
import { ErrorState, LoadingRows } from "@/components/ui/states";
import { Figure, ReportFrame } from "@/components/views/reports/report-frame";
import { api } from "@/lib/api";
import { assistApi, assistPaths } from "@/lib/assist-api";
import { formatDateTime } from "@/lib/format";
import { localeFor, useI18n, type MessageKey } from "@/lib/i18n";
import { useApiData } from "@/lib/use-api-data";
import { RiskPill } from "./assist-shared";

/** At-risk students by class (reports hub, Assistance): the watch list as last computed, with CSV and print. */
export function AtRiskReportView() {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const [classId, setClassId] = useState("");
  const classes = useApiData("assist:classes", () => api.listClasses());
  const report = useApiData(`assist:at-risk:${classId}`, () => assistApi.atRisk(classId || null));
  const data = report.data;
  const klass = (classes.data ?? []).find((c) => c.id === classId);
  return (
    <ReportFrame
      title={t("assist.report.atRisk.title")}
      testId="report-assist-at-risk"
      filterSummary={[klass ? klass.name : t("reports.filter.allClasses")]}
      problem={null}
      excel={{ path: assistPaths.atRiskCsv(classId || null), fileName: "at-risk-students.csv" }}
      downloadLabel={t("assist.report.csv")}
      ready={Boolean(data)}
      filters={
        <label className="field w-full sm:w-auto sm:min-w-40">
          <span className="field-label">{t("reports.filter.class")}</span>
          <select className="input" name="classId" value={classId} onChange={(e) => setClassId(e.target.value)}>
            <option value="">{t("reports.filter.allClasses")}</option>
            {(classes.data ?? []).map((c) => (
              <option key={c.id} value={c.id}>
                {c.name}
              </option>
            ))}
          </select>
        </label>
      }
    >
      {report.error && !data ? (
        <ErrorState error={report.error} onRetry={report.reload} />
      ) : !data ? (
        <LoadingRows rows={6} />
      ) : (
        <>
          <div className="report-figures">
            <Figure label={t("assist.report.total")} value={data.total} testId="at-risk-total" />
            <Figure label={t("assist.level.HIGH")} value={data.high} />
            <Figure label={t("assist.level.MEDIUM")} value={data.medium} />
            <Figure label={t("assist.level.LOW")} value={data.low} />
          </div>
          <p className="text-[13px] text-ink-3">
            {data.computedAt ? t("assist.report.computedAt", { when: formatDateTime(data.computedAt, locale) }) : t("assist.watch.neverRun")}
          </p>
          {data.entries.length === 0 ? (
            <p className="empty">{t("assist.watch.empty")}</p>
          ) : (
            <>
              <div className="table-wrap">
                <table className="table report-table" data-testid="at-risk-classes">
                  <caption className="sr-only">{t("assist.report.byClass")}</caption>
                  <thead>
                    <tr>
                      <th scope="col">{t("reports.filter.class")}</th>
                      <th scope="col" className="r">{t("assist.report.students")}</th>
                      <th scope="col" className="r">{t("assist.level.HIGH")}</th>
                      <th scope="col" className="r">{t("assist.level.MEDIUM")}</th>
                      <th scope="col" className="r">{t("assist.level.LOW")}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.classes.map((row) => (
                      <tr key={row.sectionId}>
                        <th scope="row">{row.classLabel}</th>
                        <td className="r num">{row.students}</td>
                        <td className="r num">{row.high}</td>
                        <td className="r num">{row.medium}</td>
                        <td className="r num">{row.low}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <div className="table-wrap">
                <table className="table report-table" data-testid="at-risk-students">
                  <caption className="sr-only">{t("assist.report.atRisk.title")}</caption>
                  <thead>
                    <tr>
                      <th scope="col">{t("assist.report.student")}</th>
                      <th scope="col">{t("reports.filter.class")}</th>
                      <th scope="col" className="r">{t("assist.report.score")}</th>
                      <th scope="col">{t("assist.report.level")}</th>
                      <th scope="col">{t("assist.watch.status")}</th>
                      <th scope="col">{t("assist.report.reasons")}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.entries.map((row) => (
                      <tr key={row.id}>
                        <th scope="row">{row.studentName}</th>
                        <td>{row.classLabel}</td>
                        <td className="r num">{row.score}</td>
                        <td>
                          <RiskPill level={row.level} />
                        </td>
                        <td>{t(`assist.status.${row.status}` as MessageKey)}</td>
                        <td>{row.reasons.map((r) => r.detail).join("; ")}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </>
          )}
        </>
      )}
    </ReportFrame>
  );
}

/** Remark drafts made and accepted, by teacher and month (reports hub, Assistance). */
export function DraftsAcceptedReportView() {
  const { t } = useI18n();
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const problem = from && to && from > to ? t("assist.report.monthsOrder") : null;
  const report = useApiData(problem ? null : `assist:accepted:${from}:${to}`, () => assistApi.accepted(from || null, to || null));
  const data = report.data;
  return (
    <ReportFrame
      title={t("assist.report.accepted.title")}
      testId="report-assist-drafts"
      filterSummary={data ? [`${data.from} – ${data.to}`] : []}
      problem={problem}
      excel={problem ? null : { path: assistPaths.acceptedCsv(from || null, to || null), fileName: "drafts-accepted.csv" }}
      downloadLabel={t("assist.report.csv")}
      ready={Boolean(data)}
      filters={
        <>
          <label className="field w-[calc(50%-4px)] sm:w-auto">
            <span className="field-label">{t("assist.report.from")}</span>
            <input className="input" type="month" name="from" value={from} onChange={(e) => setFrom(e.target.value)} />
          </label>
          <label className="field w-[calc(50%-4px)] sm:w-auto">
            <span className="field-label">{t("assist.report.to")}</span>
            <input className="input" type="month" name="to" value={to} onChange={(e) => setTo(e.target.value)} />
          </label>
        </>
      }
    >
      {report.error && !data ? (
        <ErrorState error={report.error} onRetry={report.reload} />
      ) : !data ? (
        <LoadingRows rows={5} />
      ) : (
        <>
          <div className="report-figures">
            <Figure label={t("assist.report.drafted")} value={data.drafted} />
            <Figure label={t("assist.report.acceptedCount")} value={data.accepted} testId="drafts-accepted" />
          </div>
          {data.rows.length === 0 ? (
            <p className="empty">{t("assist.report.noDrafts")}</p>
          ) : (
            <div className="table-wrap">
              <table className="table report-table" data-testid="drafts-table">
                <caption className="sr-only">{t("assist.report.accepted.title")}</caption>
                <thead>
                  <tr>
                    <th scope="col">{t("assist.report.teacher")}</th>
                    <th scope="col">{t("assist.usage.month")}</th>
                    <th scope="col" className="r">{t("assist.report.drafted")}</th>
                    <th scope="col" className="r">{t("assist.report.acceptedCount")}</th>
                  </tr>
                </thead>
                <tbody>
                  {data.rows.map((row) => (
                    <tr key={`${row.teacherName}:${row.month}`}>
                      <th scope="row">{row.teacherName}</th>
                      <td>{row.month}</td>
                      <td className="r num">{row.drafted}</td>
                      <td className="r num">{row.accepted}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </>
      )}
    </ReportFrame>
  );
}
