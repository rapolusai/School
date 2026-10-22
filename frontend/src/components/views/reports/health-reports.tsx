"use client";

import { useState } from "react";
import { DuePill, verificationLabel } from "@/components/views/health/health-shared";
import { ErrorState, LoadingRows } from "@/components/ui/states";
import { Pill } from "@/components/ui/pill";
import { formatPlainDate, todayInIndia } from "@/lib/format";
import { healthApi, healthReportPaths, type RangeParams } from "@/lib/health-api";
import { localeFor, translateOr, useI18n } from "@/lib/i18n";
import { useApiData } from "@/lib/use-api-data";
import { DateFilter, Figure, rangeProblem, ReportFrame } from "./report-frame";

/* The health and safety reports in the reports hub (docs/api/phase-3-health.md, "Reports"). Counts only: no
   student's diagnosis appears here. Downloads are CSV files (UTF-8 with a BOM) that open in Excel. */

/** 1 April of the financial year that holds the date. Pure; exported for tests. */
export function financialYearStart(date: string): string {
  const year = Number(date.slice(0, 4));
  return `${Number(date.slice(5, 7)) >= 4 ? year : year - 1}-04-01`;
}

function useRange() {
  const [today] = useState(() => todayInIndia());
  const [from, setFrom] = useState(() => financialYearStart(today));
  const [to, setTo] = useState(today);
  const problem = rangeProblem(from, to);
  const params: RangeParams = { from, to };
  return { today, from, to, setFrom, setTo, problem, params };
}

export function HealthInfirmaryReportView() {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const r = useRange();
  const report = useApiData(r.problem ? null : `health:report:infirmary:${r.from}:${r.to}`, () => healthApi.infirmaryReport(r.params));
  const data = report.loading ? undefined : report.data;
  return (
    <ReportFrame
      title={t("health.report.infirmary.title")}
      testId="report-health-infirmary"
      filterSummary={[t("health.report.range", { from: formatPlainDate(r.from, locale), to: formatPlainDate(r.to, locale) })]}
      problem={r.problem ? t(r.problem) : null}
      excel={{ path: healthReportPaths.infirmaryCsv(r.params), fileName: "infirmary-visits.csv" }}
      downloadLabel={t("health.report.csv")}
      ready={Boolean(data)}
      filters={
        <>
          <DateFilter name="from" label={t("reports.filter.from")} value={r.from} onChange={r.setFrom} invalid={Boolean(r.problem)} />
          <DateFilter name="to" label={t("reports.filter.to")} value={r.to} onChange={r.setTo} invalid={Boolean(r.problem)} />
        </>
      }
    >
      {report.error && !data ? (
        <ErrorState error={report.error} onRetry={report.reload} />
      ) : !data ? (
        <LoadingRows rows={4} />
      ) : (
        <>
          <div className="report-figures">
            <Figure label={t("health.report.infirmary.total")} value={data.total} testId="infirmary-total" />
            {data.byOutcome.map((o) => (
              <Figure key={o.name} label={translateOr(t, `health.outcome.${o.name}`, o.name)} value={o.count} />
            ))}
          </div>
          {data.byMonth.length === 0 ? (
            <p className="empty">{t("health.report.empty")}</p>
          ) : (
            <div className="table-wrap">
              <table className="table report-table" data-testid="infirmary-by-month">
                <caption className="sr-only">{t("health.report.infirmary.title")}</caption>
                <thead>
                  <tr>
                    <th scope="col">{t("health.report.month")}</th>
                    <th scope="col" className="r">{t("health.report.infirmary.total")}</th>
                    <th scope="col" className="r">{t("health.report.infirmary.students")}</th>
                    <th scope="col" className="r">{t("health.report.infirmary.staff")}</th>
                    <th scope="col" className="r">{t("health.outcome.SENT_HOME")}</th>
                    <th scope="col" className="r">{t("health.outcome.HOSPITAL")}</th>
                  </tr>
                </thead>
                <tbody>
                  {data.byMonth.map((m) => (
                    <tr key={m.month}>
                      <td>{m.month}</td>
                      <td className="num r">{m.visits}</td>
                      <td className="num r">{m.students}</td>
                      <td className="num r">{m.staff}</td>
                      <td className="num r">{m.sentHome}</td>
                      <td className="num r">{m.hospital}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          {data.byComplaint.length > 0 ? (
            <div className="flex flex-col gap-1.5">
              <p className="text-[12px] font-semibold uppercase tracking-wide text-ink-3">{t("health.report.infirmary.byComplaint")}</p>
              <ul className="flex flex-wrap gap-1.5">
                {data.byComplaint.map((c) => (
                  <li key={c.name}>
                    <Pill tone="neutral">
                      {c.name}: {c.count}
                    </Pill>
                  </li>
                ))}
              </ul>
            </div>
          ) : null}
        </>
      )}
    </ReportFrame>
  );
}

export function HealthAllergiesReportView() {
  const { t } = useI18n();
  const report = useApiData("health:report:allergies", healthApi.allergiesReport);
  const data = report.data;
  return (
    <ReportFrame title={t("health.report.allergies.title")} testId="report-health-allergies" filterSummary={[]} problem={null} excel={{ path: healthReportPaths.allergiesCsv(), fileName: "allergies-by-class.csv" }} downloadLabel={t("health.report.csv")} ready={Boolean(data)} filters={null}>
      {report.error && !data ? (
        <ErrorState error={report.error} onRetry={report.reload} />
      ) : !data ? (
        <LoadingRows rows={4} />
      ) : (
        <>
          <p className="text-[13px] text-ink-3">{t("health.report.allergies.intro")}</p>
          <div className="report-figures">
            <Figure label={t("health.report.allergies.students")} value={data.students} />
            <Figure label={t("health.report.allergies.with")} value={data.withAllergies} testId="allergies-with" />
            <Figure label={t("health.report.allergies.severe")} value={data.severe} />
          </div>
          <div className="table-wrap">
            <table className="table report-table" data-testid="allergies-by-class">
              <caption className="sr-only">{t("health.report.allergies.title")}</caption>
              <thead>
                <tr>
                  <th scope="col">{t("health.report.class")}</th>
                  <th scope="col" className="r">{t("health.report.allergies.students")}</th>
                  <th scope="col" className="r">{t("health.report.allergies.with")}</th>
                  <th scope="col" className="r">{t("health.report.allergies.severe")}</th>
                </tr>
              </thead>
              <tbody>
                {data.byClass.map((c) => (
                  <tr key={c.sectionId}>
                    <td>{c.label}</td>
                    <td className="num r">{c.students}</td>
                    <td className="num r">{c.withAllergies}</td>
                    <td className="num r">{c.severe}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}
    </ReportFrame>
  );
}

export function HealthDrillsReportView() {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const report = useApiData("health:report:drills", healthApi.drillsReport);
  const data = report.data;
  return (
    <ReportFrame title={t("health.report.drills.title")} testId="report-health-drills" filterSummary={[]} problem={null} excel={{ path: healthReportPaths.drillsCsv(), fileName: "drills.csv" }} downloadLabel={t("health.report.csv")} ready={Boolean(data)} filters={null}>
      {report.error && !data ? (
        <ErrorState error={report.error} onRetry={report.reload} />
      ) : !data ? (
        <LoadingRows rows={4} />
      ) : (
        <>
          <div className="report-figures">
            <Figure label={t("health.report.drills.count")} value={data.rows.length} />
            <Figure label={t("health.report.drills.nextDue")} value={data.nextDue ? formatPlainDate(data.nextDue, locale) : "—"} testId="drills-next-due" />
            <Figure label={t("health.report.drills.state")} value={<DuePill due={data.due} />} />
          </div>
          {data.rows.length === 0 ? (
            <p className="empty">{t("health.report.empty")}</p>
          ) : (
            <div className="table-wrap">
              <table className="table report-table" data-testid="drills-rows">
                <caption className="sr-only">{t("health.report.drills.title")}</caption>
                <thead>
                  <tr>
                    <th scope="col">{t("reports.col.date")}</th>
                    <th scope="col">{t("health.drill.kind")}</th>
                    <th scope="col">{t("health.drill.block")}</th>
                    <th scope="col" className="r">{t("health.drill.seconds")}</th>
                    <th scope="col" className="r">{t("health.drill.attendance")}</th>
                    <th scope="col">{t("health.drill.nextDueField")}</th>
                    <th scope="col">{t("health.drill.issues")}</th>
                  </tr>
                </thead>
                <tbody>
                  {data.rows.map((d) => (
                    <tr key={d.id}>
                      <td>{formatPlainDate(d.drillDate, locale)}</td>
                      <td>{translateOr(t, `health.drillKind.${d.kind}`, d.kind)}</td>
                      <td>{d.block}</td>
                      <td className="num r">{d.timeToClearSeconds}</td>
                      <td className="num r">{d.attendance}</td>
                      <td>{d.nextDue ? formatPlainDate(d.nextDue, locale) : "—"}</td>
                      <td>{d.issues ?? "—"}</td>
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

export function HealthVerificationsReportView() {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const report = useApiData("health:report:verifications", healthApi.verificationsReport);
  const data = report.data;
  return (
    <ReportFrame title={t("health.report.verifications.title")} testId="report-health-verifications" filterSummary={[]} problem={null} excel={{ path: healthReportPaths.verificationsCsv(), fileName: "verifications-due.csv" }} downloadLabel={t("health.report.csv")} ready={Boolean(data)} filters={null}>
      {report.error && !data ? (
        <ErrorState error={report.error} onRetry={report.reload} />
      ) : !data ? (
        <LoadingRows rows={4} />
      ) : (
        <>
          <div className="report-figures">
            <Figure label={t("health.report.verifications.missing")} value={data.missing} testId="verifications-missing" />
            <Figure label={t("health.report.verifications.due")} value={data.due} />
          </div>
          {data.rows.length === 0 ? (
            <p className="empty">{t("health.report.verifications.allClear")}</p>
          ) : (
            <div className="table-wrap">
              <table className="table report-table" data-testid="verifications-rows">
                <caption className="sr-only">{t("health.report.verifications.title")}</caption>
                <thead>
                  <tr>
                    <th scope="col">{t("health.staff.name")}</th>
                    <th scope="col">{t("health.staff.verification")}</th>
                    <th scope="col">{t("health.staff.expires")}</th>
                    <th scope="col">{t("health.training.POCSO")}</th>
                    <th scope="col">{t("health.training.FIRST_AID")}</th>
                  </tr>
                </thead>
                <tbody>
                  {data.rows.map((r) => (
                    <tr key={r.userId}>
                      <td>
                        {r.name}
                        {r.designation ? <span className="block text-[12.5px] text-ink-3">{r.designation}</span> : null}
                      </td>
                      <td>
                        {verificationLabel(t, r.status)} · <DuePill due={r.due} />
                      </td>
                      <td>{r.expiresOn ? formatPlainDate(r.expiresOn, locale) : "—"}</td>
                      <td>{r.pocsoTrainingDue ? t("health.staff.renew") : t("health.due.OK")}</td>
                      <td>{r.firstAidTrainingDue ? t("health.staff.renew") : t("health.due.OK")}</td>
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
