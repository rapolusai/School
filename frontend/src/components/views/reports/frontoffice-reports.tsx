"use client";

import { useState } from "react";
import { EnquiryStatusPill, PassStatusPill, purposeLabel, sourceLabel, topicLabel, when } from "@/components/views/frontoffice/frontoffice-shared";
import { collectorLine } from "@/components/views/frontoffice/passes-view";
import { ErrorState, LoadingRows } from "@/components/ui/states";
import { Pill } from "@/components/ui/pill";
import { formatPlainDate, todayInIndia } from "@/lib/format";
import { frontOfficeApi, frontOfficeReportPaths, type RangeParams } from "@/lib/frontoffice-api";
import { localeFor, plural, translateOr, useI18n } from "@/lib/i18n";
import type { NamedCount } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { DateFilter, Figure, monthStart, rangeProblem, ReportFrame } from "./report-frame";

/* The front office's reports in the reports hub (docs/api/phase-2-frontoffice.md, "Reports"). Downloads are CSV
   files (UTF-8 with a BOM) that open in Excel. */

function useRange() {
  const [today] = useState(() => todayInIndia());
  const [from, setFrom] = useState(() => monthStart(today));
  const [to, setTo] = useState(today);
  const problem = rangeProblem(from, to);
  const params: RangeParams = { from, to };
  return { today, from, to, setFrom, setTo, problem, params };
}

function RangeFilters({ r }: { r: ReturnType<typeof useRange> }) {
  const { t } = useI18n();
  return (
    <>
      <DateFilter name="from" label={t("reports.filter.from")} value={r.from} onChange={r.setFrom} invalid={Boolean(r.problem)} />
      <DateFilter name="to" label={t("reports.filter.to")} value={r.to} onChange={r.setTo} invalid={Boolean(r.problem)} />
    </>
  );
}

function rangeSummary(t: ReturnType<typeof useI18n>["t"], from: string, to: string, locale: string): string[] {
  return [t("frontoffice.report.range", { from: formatPlainDate(from, locale), to: formatPlainDate(to, locale) })];
}

/** Small "name: count" chips for a breakdown. */
function Breakdown({ title, rows, label }: { title: string; rows: NamedCount[]; label: (name: string) => string }) {
  if (rows.length === 0) return null;
  return (
    <div className="flex flex-col gap-1.5">
      <p className="text-[12px] font-semibold uppercase tracking-wide text-ink-3">{title}</p>
      <ul className="flex flex-wrap gap-1.5">
        {rows.map((row) => (
          <li key={row.name}>
            <Pill tone="neutral">
              {label(row.name)}: {row.count}
            </Pill>
          </li>
        ))}
      </ul>
    </div>
  );
}

/* ------------------------------------------------------------------ visitors */

export function FrontOfficeVisitorsReportView() {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const r = useRange();
  const report = useApiData(r.problem ? null : `frontoffice:report:visitors:${r.from}:${r.to}`, () => frontOfficeApi.visitorsReport(r.params));
  const data = report.loading ? undefined : report.data;
  return (
    <ReportFrame
      title={t("frontoffice.report.visitors.title")}
      testId="report-frontoffice-visitors"
      filterSummary={rangeSummary(t, r.from, r.to, locale)}
      problem={r.problem ? t(r.problem) : null}
      excel={{ path: frontOfficeReportPaths.visitorsCsv(r.params), fileName: "visitors.csv" }}
      downloadLabel={t("frontoffice.report.csv")}
      ready={Boolean(data)}
      filters={<RangeFilters r={r} />}
    >
      {report.error && !data ? (
        <ErrorState error={report.error} onRetry={report.reload} />
      ) : !data ? (
        <LoadingRows rows={4} />
      ) : (
        <>
          <div className="report-figures">
            <Figure label={t("frontoffice.report.visitors.total")} value={data.total} testId="visitors-total" />
            <Figure label={t("frontoffice.report.visitors.people")} value={data.people} />
          </div>
          <Breakdown title={t("frontoffice.report.byPurpose")} rows={data.byPurpose} label={(name) => translateOr(t, `frontoffice.purpose.${name}`, name)} />
          {data.byDay.length === 0 ? (
            <p className="empty">{t("frontoffice.report.empty")}</p>
          ) : (
            <div className="table-wrap">
              <table className="table report-table" data-testid="visitors-by-day">
                <caption className="sr-only">{t("frontoffice.report.byDay")}</caption>
                <thead>
                  <tr>
                    <th scope="col">{t("reports.col.date")}</th>
                    <th scope="col" className="r">
                      {t("frontoffice.report.visitors.total")}
                    </th>
                    <th scope="col" className="r">
                      {t("frontoffice.report.visitors.people")}
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {data.byDay.map((day) => (
                    <tr key={day.date}>
                      <td>{formatPlainDate(day.date, locale)}</td>
                      <td className="num r">{day.count}</td>
                      <td className="num r">{day.people}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          {data.rows.length > 0 ? (
            <div className="table-wrap">
              <table className="table report-table" data-testid="visitors-rows">
                <caption className="sr-only">{t("frontoffice.report.visitors.title")}</caption>
                <thead>
                  <tr>
                    <th scope="col">{t("frontoffice.field.name")}</th>
                    <th scope="col">{t("frontoffice.field.purpose")}</th>
                    <th scope="col">{t("frontoffice.field.toMeet")}</th>
                    <th scope="col">{t("frontoffice.slip.in")}</th>
                    <th scope="col">{t("frontoffice.slip.out")}</th>
                  </tr>
                </thead>
                <tbody>
                  {data.rows.map((v) => (
                    <tr key={v.id}>
                      <td>
                        <span className="block font-semibold">{v.name}</span>
                        <span className="block text-[12.5px] text-ink-3">{v.phoneMasked}</span>
                      </td>
                      <td>{purposeLabel(t, v.purpose)}</td>
                      <td>{v.toMeetName ?? "—"}</td>
                      <td>{when(v.checkedInAt, locale)}</td>
                      <td>{when(v.checkedOutAt, locale)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : null}
        </>
      )}
    </ReportFrame>
  );
}

/* ------------------------------------------------------------------ gate passes */

export function FrontOfficePassesReportView() {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const r = useRange();
  const report = useApiData(r.problem ? null : `frontoffice:report:passes:${r.from}:${r.to}`, () => frontOfficeApi.passesReport(r.params));
  const data = report.loading ? undefined : report.data;
  return (
    <ReportFrame
      title={t("frontoffice.report.passes.title")}
      testId="report-frontoffice-passes"
      filterSummary={rangeSummary(t, r.from, r.to, locale)}
      problem={r.problem ? t(r.problem) : null}
      excel={{ path: frontOfficeReportPaths.passesCsv(r.params), fileName: "gate-passes.csv" }}
      downloadLabel={t("frontoffice.report.csv")}
      ready={Boolean(data)}
      filters={<RangeFilters r={r} />}
    >
      {report.error && !data ? (
        <ErrorState error={report.error} onRetry={report.reload} />
      ) : !data ? (
        <LoadingRows rows={4} />
      ) : (
        <>
          <div className="report-figures">
            <Figure label={t("frontoffice.report.passes.total")} value={data.total} testId="passes-total" />
          </div>
          {data.byDay.length === 0 ? (
            <p className="empty">{t("frontoffice.report.empty")}</p>
          ) : (
            <div className="table-wrap">
              <table className="table report-table" data-testid="passes-by-day">
                <caption className="sr-only">{t("frontoffice.report.byDay")}</caption>
                <thead>
                  <tr>
                    <th scope="col">{t("reports.col.date")}</th>
                    {(["requested", "approved", "released", "rejected", "cancelled", "expired", "locked"] as const).map((key) => (
                      <th key={key} scope="col" className="r">
                        {translateOr(t, `frontoffice.passStatus.${key.toUpperCase()}`, key)}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {data.byDay.map((day) => (
                    <tr key={day.date}>
                      <td>{formatPlainDate(day.date, locale)}</td>
                      <td className="num r">{day.requested}</td>
                      <td className="num r">{day.approved}</td>
                      <td className="num r">{day.released}</td>
                      <td className="num r">{day.rejected}</td>
                      <td className="num r">{day.cancelled}</td>
                      <td className="num r">{day.expired}</td>
                      <td className="num r">{day.locked}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          {data.rows.length > 0 ? (
            <div className="table-wrap">
              <table className="table report-table" data-testid="passes-rows">
                <caption className="sr-only">{t("frontoffice.report.passes.title")}</caption>
                <thead>
                  <tr>
                    <th scope="col">{t("frontoffice.pass.student")}</th>
                    <th scope="col">{t("frontoffice.pass.pickupAt")}</th>
                    <th scope="col">{t("frontoffice.pass.collectorKind")}</th>
                    <th scope="col">{t("frontoffice.col.status")}</th>
                    <th scope="col">{t("frontoffice.gate.collectedBy")}</th>
                  </tr>
                </thead>
                <tbody>
                  {data.rows.map((p) => (
                    <tr key={p.id}>
                      <td>
                        <span className="block font-semibold">{p.studentName}</span>
                        <span className="block text-[12.5px] text-ink-3">{p.className ?? ""}</span>
                      </td>
                      <td>{when(p.pickupAt, locale)}</td>
                      <td>{collectorLine(t, p)}</td>
                      <td>
                        <PassStatusPill status={p.status} />
                      </td>
                      <td>{p.collectedByName ?? "—"}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : null}
        </>
      )}
    </ReportFrame>
  );
}

/* ------------------------------------------------------------------ enquiries */

export function FrontOfficeEnquiriesReportView() {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const r = useRange();
  const report = useApiData(r.problem ? null : `frontoffice:report:enquiries:${r.from}:${r.to}`, () => frontOfficeApi.enquiriesReport(r.params));
  const data = report.loading ? undefined : report.data;
  return (
    <ReportFrame
      title={t("frontoffice.report.enquiries.title")}
      testId="report-frontoffice-enquiries"
      filterSummary={rangeSummary(t, r.from, r.to, locale)}
      problem={r.problem ? t(r.problem) : null}
      excel={{ path: frontOfficeReportPaths.enquiriesCsv(r.params), fileName: "enquiries.csv" }}
      downloadLabel={t("frontoffice.report.csv")}
      ready={Boolean(data)}
      filters={<RangeFilters r={r} />}
    >
      {report.error && !data ? (
        <ErrorState error={report.error} onRetry={report.reload} />
      ) : !data ? (
        <LoadingRows rows={4} />
      ) : (
        <>
          <div className="report-figures">
            <Figure label={t("frontoffice.report.enquiries.total")} value={data.total} testId="enquiries-total" />
          </div>
          <Breakdown title={t("frontoffice.report.bySource")} rows={data.bySource} label={(name) => translateOr(t, `frontoffice.source.${name}`, name)} />
          <Breakdown title={t("frontoffice.report.byTopic")} rows={data.byTopic} label={(name) => translateOr(t, `frontoffice.topic.${name}`, name)} />
          <Breakdown title={t("frontoffice.report.byOutcome")} rows={data.byOutcome} label={(name) => translateOr(t, `frontoffice.enquiryStatus.${name}`, name)} />
          {data.rows.length === 0 ? (
            <p className="empty">{t("frontoffice.report.empty")}</p>
          ) : (
            <div className="table-wrap">
              <table className="table report-table" data-testid="enquiries-rows">
                <caption className="sr-only">{t("frontoffice.report.enquiries.title")}</caption>
                <thead>
                  <tr>
                    <th scope="col">{t("frontoffice.enquiries.name")}</th>
                    <th scope="col">{t("frontoffice.enquiries.source")}</th>
                    <th scope="col">{t("frontoffice.enquiries.topic")}</th>
                    <th scope="col">{t("frontoffice.col.status")}</th>
                    <th scope="col">{t("frontoffice.field.outcome")}</th>
                  </tr>
                </thead>
                <tbody>
                  {data.rows.map((e) => (
                    <tr key={e.id}>
                      <td>
                        <span className="block font-semibold">{e.name}</span>
                        <span className="block text-[12.5px] text-ink-3">{when(e.enquiredAt, locale)}</span>
                      </td>
                      <td>{sourceLabel(t, e.source)}</td>
                      <td>{topicLabel(t, e.topic)}</td>
                      <td>
                        <EnquiryStatusPill status={e.status} />
                      </td>
                      <td>{e.outcome ?? "—"}</td>
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

/* ------------------------------------------------------------------ follow-ups overdue */

export function FrontOfficeFollowUpsReportView() {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const report = useApiData("frontoffice:report:follow-ups", frontOfficeApi.followUpsReport);
  const data = report.data;
  return (
    <ReportFrame
      title={t("frontoffice.report.followUps.title")}
      testId="report-frontoffice-follow-ups"
      filterSummary={data ? [t("frontoffice.report.asOf", { date: formatPlainDate(data.asOf, locale) })] : []}
      problem={null}
      excel={{ path: frontOfficeReportPaths.followUpsCsv(), fileName: "follow-ups.csv" }}
      downloadLabel={t("frontoffice.report.csv")}
      ready={Boolean(data)}
      filters={null}
    >
      {report.error && !data ? (
        <ErrorState error={report.error} onRetry={report.reload} />
      ) : !data ? (
        <LoadingRows rows={4} />
      ) : (
        <>
          <div className="report-figures">
            <Figure label={t("frontoffice.followUps.overdueFigure")} value={data.overdue} testId="follow-ups-overdue" />
            <Figure label={t("frontoffice.followUps.openFigure")} value={data.rows.length} />
          </div>
          {data.rows.length === 0 ? (
            <p className="empty">{t("frontoffice.followUps.empty")}</p>
          ) : (
            <div className="table-wrap">
              <table className="table report-table" data-testid="follow-ups-rows">
                <caption className="sr-only">{t("frontoffice.report.followUps.title")}</caption>
                <thead>
                  <tr>
                    <th scope="col">{t("frontoffice.enquiries.name")}</th>
                    <th scope="col">{t("frontoffice.followUps.kind")}</th>
                    <th scope="col">{t("frontoffice.field.followUpOn")}</th>
                    <th scope="col" className="r">
                      {t("frontoffice.followUps.daysOverdueCol")}
                    </th>
                    <th scope="col">{t("frontoffice.field.assignedTo")}</th>
                  </tr>
                </thead>
                <tbody>
                  {data.rows.map((row) => (
                    <tr key={`${row.kind}:${row.id}`}>
                      <td>
                        <span className="block font-semibold">{row.name}</span>
                        <span className="block text-[12.5px] text-ink-3">{row.about}</span>
                      </td>
                      <td>{row.kind === "CALL" ? t("frontoffice.followUps.kindCall") : t("frontoffice.followUps.kindEnquiry")}</td>
                      <td>{formatPlainDate(row.followUpOn, locale)}</td>
                      <td className="num r">{row.daysOverdue > 0 ? plural(t, "frontoffice.followUps.daysOverdue", row.daysOverdue) : "—"}</td>
                      <td>{row.assignedToName ?? "—"}</td>
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
