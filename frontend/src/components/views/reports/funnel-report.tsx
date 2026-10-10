"use client";

import { useState } from "react";
import { ErrorState, LoadingRows } from "@/components/ui/states";
import { api } from "@/lib/api";
import { formatPlainDate } from "@/lib/format";
import { localeFor, translateOr, useI18n } from "@/lib/i18n";
import { reportPaths, reportsApi, type FunnelParams } from "@/lib/reports-api";
import { APPLICATION_SOURCES, type Funnel, type FunnelLine } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { formatPercent } from "../attendance/attendance-shared";
import { FunnelBars, funnelRows } from "./charts";
import { ClassFilter, DateFilter, Figure, nameOf, ReportFrame, useClasses } from "./report-frame";

const DATE = /^\d{4}-\d{2}-\d{2}$/;

/** Admissions funnel with conversion rates, by class and by source. */
export function FunnelReportView() {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const [yearId, setYearId] = useState("");
  const [classId, setClassId] = useState("");
  const [source, setSource] = useState("");
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const classes = useClasses();
  const years = useApiData("reports:years", api.listYears).data ?? [];

  const problem =
    (from && !DATE.test(from)) || (to && !DATE.test(to))
      ? t("reports.v.dates")
      : from && to && to < from
        ? t("reports.v.order")
        : null;
  const params: FunnelParams = {
    yearId: yearId || undefined,
    classId: classId || undefined,
    source: source || undefined,
    from: from || undefined,
    to: to || undefined,
  };
  const key = `reports:funnel:${yearId}:${classId}:${source}:${from}:${to}`;
  const report = useApiData(problem ? null : key, () => reportsApi.funnel(params));
  const data =
    report.data &&
    (report.data.academicYearId ?? "") === yearId &&
    (report.data.classId ?? "") === classId &&
    (report.data.source ?? "") === source &&
    (report.data.from ?? "") === from &&
    (report.data.to ?? "") === to
      ? report.data
      : undefined;

  const yearName = nameOf(years, yearId);
  const className = nameOf(classes, classId);
  const summary = [
    yearName ? t("reports.filter.yearIs", { name: yearName }) : t("reports.filter.allYears"),
    className ? t("reports.filter.classIs", { name: className }) : t("reports.filter.allClasses"),
    source ? t("reports.filter.sourceIs", { name: translateOr(t, `admissions.source.${source}`, source) }) : t("reports.filter.allSources"),
    from || to
      ? t("reports.funnel.receivedBetween", {
          from: from ? formatPlainDate(from, locale) : "…",
          to: to ? formatPlainDate(to, locale) : "…",
        })
      : null,
  ].filter((s): s is string => Boolean(s));
  const slug = (yearName ?? "all-years").toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/(^-|-$)/g, "") || "year";

  return (
    <ReportFrame
      title={t("reports.funnel.title")}
      testId="report-funnel"
      filterSummary={summary}
      problem={problem}
      excel={{ path: reportPaths.funnelXlsx(params), fileName: `admissions-funnel-${slug}.xlsx` }}
      ready={Boolean(data)}
      filters={
        <>
          <label className="field w-full sm:w-auto sm:min-w-40">
            <span className="field-label">{t("reports.filter.year")}</span>
            <select className="input" name="yearId" value={yearId} onChange={(e) => setYearId(e.target.value)}>
              <option value="">{t("reports.filter.allYears")}</option>
              {years.map((y) => (
                <option key={y.id} value={y.id}>
                  {y.name}
                </option>
              ))}
            </select>
          </label>
          <ClassFilter classes={classes} value={classId} onChange={setClassId} />
          <label className="field w-full sm:w-auto sm:min-w-40">
            <span className="field-label">{t("reports.filter.source")}</span>
            <select className="input" name="source" value={source} onChange={(e) => setSource(e.target.value)}>
              <option value="">{t("reports.filter.allSources")}</option>
              {APPLICATION_SOURCES.map((s) => (
                <option key={s} value={s}>
                  {translateOr(t, `admissions.source.${s}`, s)}
                </option>
              ))}
            </select>
          </label>
          <DateFilter name="from" label={t("reports.funnel.receivedFrom")} value={from} invalid={problem !== null} onChange={setFrom} />
          <DateFilter name="to" label={t("reports.funnel.receivedTo")} value={to} invalid={problem !== null} onChange={setTo} />
        </>
      }
    >
      {problem ? null : report.error && !data ? (
        <ErrorState error={report.error} onRetry={report.reload} />
      ) : !data ? (
        <LoadingRows rows={5} />
      ) : (
        <FunnelBody funnel={data.funnel} />
      )}
    </ReportFrame>
  );
}

function FunnelBody({ funnel }: { funnel: Funnel }) {
  const { t } = useI18n();
  return (
    <>
      <div className="report-figures">
        <Figure label={t("reports.funnel.total")} value={funnel.total} testId="funnel-total" />
        <Figure label={t("reports.funnel.conversion")} value={formatPercent(funnel.conversionPercent)} testId="funnel-conversion" />
        <Figure label={t("reports.funnel.open")} value={funnel.open} />
        <Figure label={t("admissions.stage.REJECTED")} value={funnel.rejected} />
        <Figure label={t("admissions.stage.WITHDRAWN")} value={funnel.withdrawn} />
      </div>
      {funnel.total === 0 ? (
        <p className="empty">{t("reports.funnel.empty")}</p>
      ) : (
        <>
          <FunnelBars rows={funnelRows(funnel.stages, t)} caption={t("reports.funnel.title")} testId="funnel-chart" />
          <FunnelTable title={t("reports.funnel.byClass")} lines={funnel.byClass} name={(l) => l.className ?? "—"} testId="funnel-by-class" />
          <FunnelTable
            title={t("reports.funnel.bySource")}
            lines={funnel.bySource}
            name={(l) => (l.source ? translateOr(t, `admissions.source.${l.source}`, l.source) : "—")}
            testId="funnel-by-source"
          />
        </>
      )}
      <p className="text-[12.5px] text-ink-3">{t("reports.funnel.rule")}</p>
    </>
  );
}

function FunnelTable({
  title,
  lines,
  name,
  testId,
}: {
  title: string;
  lines: FunnelLine[];
  name: (line: FunnelLine) => string;
  testId: string;
}) {
  const { t } = useI18n();
  if (lines.length === 0) return null;
  const cells = (l: FunnelLine) => [l.total, l.applied, l.assessed, l.offered, l.admitted, l.rejected, l.withdrawn];
  return (
    <div className="flex flex-col gap-2">
      <h2 className="text-[15px]">{title}</h2>
      <div className="table-wrap hidden md:block">
        <table className="table report-table" data-testid={testId}>
          <caption className="sr-only">{title}</caption>
          <thead>
            <tr>
              <th scope="col">{testId === "funnel-by-class" ? t("reports.col.class") : t("reports.col.source")}</th>
              {(
                [
                  "reports.col.total",
                  "admissions.stage.APPLICATION",
                  "admissions.stage.ASSESSMENT",
                  "admissions.stage.OFFERED",
                  "admissions.stage.ADMITTED",
                  "admissions.stage.REJECTED",
                  "admissions.stage.WITHDRAWN",
                  "reports.col.conversion",
                ] as const
              ).map((key) => (
                <th key={key} scope="col" className="r">
                  {t(key)}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {lines.map((line) => (
              <tr key={`${line.classId ?? ""}:${line.source ?? ""}`}>
                <th scope="row">{name(line)}</th>
                {cells(line).map((value, i) => (
                  <td key={i} className="r num">
                    {value}
                  </td>
                ))}
                <td className="r num font-semibold">{formatPercent(line.conversionPercent)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <ul className="rowcards md:hidden">
        {lines.map((line) => (
          <li key={`${line.classId ?? ""}:${line.source ?? ""}`} className="rowcard">
            <div className="min-w-0 flex-1">
              <b className="block truncate font-semibold">{name(line)}</b>
              <span className="block text-[12.5px] text-ink-3">
                {t("reports.funnel.cardLine", { total: line.total, offered: line.offered, admitted: line.admitted })}
              </span>
            </div>
            <b className="num">{formatPercent(line.conversionPercent)}</b>
          </li>
        ))}
      </ul>
    </div>
  );
}
