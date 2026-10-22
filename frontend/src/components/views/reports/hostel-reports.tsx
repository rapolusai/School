"use client";

import { useState } from "react";
import { HostelPassPill, hostelTypeLabel, passDates, passKindLabel, passStatusLabel, useLocale } from "@/components/views/hostel/hostel-shared";
import { ErrorState, LoadingRows } from "@/components/ui/states";
import { Pill } from "@/components/ui/pill";
import { formatPaise, formatPlainDate, todayInIndia } from "@/lib/format";
import { hostelApi, hostelReportPaths, type HostelRangeParams } from "@/lib/hostel-api";
import { useI18n } from "@/lib/i18n";
import type { HostelPassStatus } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { DateFilter, Figure, monthStart, rangeProblem, ReportFrame } from "./report-frame";

/* The hostel's reports in the reports hub (docs/api/phase-3-hostel.md, "Reports"). Downloads are CSV files
   (UTF-8 with a BOM) that open in Excel; the frame also prints. */

function useRange() {
  const [today] = useState(() => todayInIndia());
  const [from, setFrom] = useState(() => monthStart(today));
  const [to, setTo] = useState(today);
  const problem = rangeProblem(from, to);
  const params: HostelRangeParams = { from, to };
  return { today, from, to, setFrom, setTo, problem, params };
}

/* ------------------------------------------------------------------ occupancy */

export function HostelOccupancyReportView() {
  const { t } = useI18n();
  const locale = useLocale();
  const report = useApiData("hostel:report:occupancy", hostelApi.occupancyReport);
  const data = report.loading ? undefined : report.data;
  return (
    <ReportFrame
      title={t("hostel.report.occupancy.title")}
      testId="report-hostel-occupancy"
      filterSummary={data ? [t("hostel.report.asOf", { date: formatPlainDate(data.asOf, locale) })] : []}
      problem={null}
      excel={{ path: hostelReportPaths.occupancyCsv(), fileName: "hostel-occupancy.csv" }}
      downloadLabel={t("hostel.report.csv")}
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
            <Figure label={t("hostel.occupancy.beds")} value={data.beds} testId="occupancy-beds" />
            <Figure label={t("hostel.occupancy.occupied")} value={data.occupied} />
            <Figure label={t("hostel.occupancy.free")} value={data.free} />
            <Figure label={t("hostel.occupancy.percent")} value={`${data.occupancyPercent}%`} />
          </div>
          <div className="table-wrap">
            <table className="table report-table" data-testid="occupancy-by-hostel">
              <caption className="sr-only">{t("hostel.report.occupancy.byHostel")}</caption>
              <thead>
                <tr>
                  <th scope="col">{t("hostel.field.hostel")}</th>
                  <th scope="col">{t("hostel.field.type")}</th>
                  <th scope="col">{t("hostel.field.warden")}</th>
                  <th scope="col" className="r">{t("hostel.hostels.rooms")}</th>
                  <th scope="col" className="r">{t("hostel.occupancy.beds")}</th>
                  <th scope="col" className="r">{t("hostel.occupancy.occupied")}</th>
                  <th scope="col" className="r">{t("hostel.occupancy.free")}</th>
                  <th scope="col" className="r">{t("hostel.occupancy.percent")}</th>
                </tr>
              </thead>
              <tbody>
                {data.hostels.map((h) => (
                  <tr key={h.hostelId}>
                    <td>{h.hostelName}</td>
                    <td>{hostelTypeLabel(t, h.type)}</td>
                    <td>{h.wardenName ?? "—"}</td>
                    <td className="r num">{h.rooms}</td>
                    <td className="r num">{h.beds}</td>
                    <td className="r num">{h.occupied}</td>
                    <td className="r num">{h.free}</td>
                    <td className="r num">{h.occupancyPercent}%</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {data.rows.length === 0 ? (
            <p className="empty">{t("hostel.boarders.empty")}</p>
          ) : (
            <div className="table-wrap">
              <table className="table report-table" data-testid="occupancy-boarders">
                <caption className="sr-only">{t("hostel.boarders.title")}</caption>
                <thead>
                  <tr>
                    <th scope="col">{t("hostel.field.student")}</th>
                    <th scope="col">{t("hostel.field.className")}</th>
                    <th scope="col">{t("hostel.field.hostel")}</th>
                    <th scope="col">{t("hostel.field.roomBed")}</th>
                    <th scope="col">{t("hostel.field.since")}</th>
                    <th scope="col">{t("hostel.field.guardian")}</th>
                  </tr>
                </thead>
                <tbody>
                  {data.rows.map((b) => (
                    <tr key={b.allotmentId}>
                      <td>
                        {b.studentName} <span className="text-ink-3 mono">{b.admissionNo}</span>
                      </td>
                      <td>{b.className ?? "—"}</td>
                      <td>{b.hostelName}</td>
                      <td>
                        {b.blockName ? `${b.blockName} · ` : ""}
                        {b.roomNumber} / {b.bedLabel}
                      </td>
                      <td>{formatPlainDate(b.since, locale)}</td>
                      <td>
                        {b.guardianName ?? "—"} {b.guardianPhoneMasked ? <span className="text-ink-3">{b.guardianPhoneMasked}</span> : null}
                      </td>
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

/* ------------------------------------------------------------------ roll call */

export function HostelRollCallReportView() {
  const { t } = useI18n();
  const locale = useLocale();
  const r = useRange();
  const report = useApiData(r.problem ? null : `hostel:report:rollcall:${r.from}:${r.to}`, () => hostelApi.rollCallReport(r.params));
  const data = report.loading ? undefined : report.data;
  return (
    <ReportFrame
      title={t("hostel.report.rollCall.title")}
      testId="report-hostel-roll-call"
      filterSummary={[t("hostel.report.range", { from: formatPlainDate(r.from, locale), to: formatPlainDate(r.to, locale) })]}
      problem={r.problem ? t(r.problem) : null}
      excel={{ path: hostelReportPaths.rollCallCsv(r.params), fileName: "hostel-roll-call.csv" }}
      downloadLabel={t("hostel.report.csv")}
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
            <Figure label={t("hostel.report.rollCall.nights")} value={data.nights} testId="rollcall-nights" />
            <Figure label={t("hostel.rollCall.PRESENT")} value={data.present} />
            <Figure label={t("hostel.rollCall.ON_LEAVE")} value={data.onLeave} />
            <Figure label={t("hostel.rollCall.ABSENT")} value={data.absent} />
          </div>
          {data.rows.length === 0 ? (
            <p className="empty">{t("hostel.rollCall.registerEmpty")}</p>
          ) : (
            <div className="table-wrap">
              <table className="table report-table" data-testid="rollcall-rows">
                <caption className="sr-only">{t("hostel.rollCall.register")}</caption>
                <thead>
                  <tr>
                    <th scope="col">{t("hostel.rollCall.night")}</th>
                    <th scope="col">{t("hostel.field.hostel")}</th>
                    <th scope="col" className="r">{t("hostel.rollCall.PRESENT")}</th>
                    <th scope="col" className="r">{t("hostel.rollCall.ON_LEAVE")}</th>
                    <th scope="col" className="r">{t("hostel.rollCall.ABSENT")}</th>
                    <th scope="col">{t("hostel.rollCall.absentees")}</th>
                  </tr>
                </thead>
                <tbody>
                  {data.rows.map((row) => (
                    <tr key={`${row.hostelId}:${row.night}`}>
                      <td>{formatPlainDate(row.night, locale)}</td>
                      <td>{row.hostelName}</td>
                      <td className="r num">{row.present}</td>
                      <td className="r num">{row.onLeave}</td>
                      <td className="r num">{row.absent}</td>
                      <td>{row.absentees.join(", ") || "—"}</td>
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

/* ------------------------------------------------------------------ outings by month */

export function HostelOutingsReportView() {
  const { t } = useI18n();
  const locale = useLocale();
  const [month, setMonth] = useState(() => todayInIndia().slice(0, 7));
  const problem = /^\d{4}-\d{2}$/.test(month) ? null : t("hostel.v.month");
  const report = useApiData(problem ? null : `hostel:report:outings:${month}`, () => hostelApi.outingsReport(month));
  const data = report.loading ? undefined : report.data;
  return (
    <ReportFrame
      title={t("hostel.report.outings.title")}
      testId="report-hostel-outings"
      filterSummary={[t("hostel.report.month", { month })]}
      problem={problem}
      excel={{ path: hostelReportPaths.outingsCsv(month), fileName: "hostel-outings.csv" }}
      downloadLabel={t("hostel.report.csv")}
      ready={Boolean(data)}
      filters={
        <label className="field">
          <span className="field-label">{t("hostel.report.monthLabel")}</span>
          <input type="month" className="input" name="month" value={month} onChange={(e) => setMonth(e.target.value)} required />
        </label>
      }
    >
      {report.error && !data ? (
        <ErrorState error={report.error} onRetry={report.reload} />
      ) : !data ? (
        <LoadingRows rows={4} />
      ) : (
        <>
          <div className="report-figures">
            <Figure label={t("hostel.report.outings.total")} value={data.total} testId="outings-total" />
            <Figure label={t("hostel.passKind.OUTING")} value={data.outings} />
            <Figure label={t("hostel.passKind.HOME_LEAVE")} value={data.homeLeaves} />
            <Figure label={t("hostel.report.outings.late")} value={data.late} />
          </div>
          <ul className="flex flex-wrap gap-1.5">
            {data.byStatus.map((s) => (
              <li key={s.name}>
                <Pill tone="neutral">
                  {passStatusLabel(t, s.name as HostelPassStatus)}: {s.count}
                </Pill>
              </li>
            ))}
          </ul>
          {data.rows.length === 0 ? (
            <p className="empty">{t("hostel.passes.empty")}</p>
          ) : (
            <div className="table-wrap">
              <table className="table report-table" data-testid="outings-rows">
                <caption className="sr-only">{t("hostel.passes.title")}</caption>
                <thead>
                  <tr>
                    <th scope="col">{t("hostel.field.student")}</th>
                    <th scope="col">{t("hostel.field.hostel")}</th>
                    <th scope="col">{t("hostel.field.passKind")}</th>
                    <th scope="col">{t("hostel.report.outings.dates")}</th>
                    <th scope="col">{t("hostel.field.status")}</th>
                    <th scope="col">{t("hostel.field.reason")}</th>
                  </tr>
                </thead>
                <tbody>
                  {data.rows.map((p) => (
                    <tr key={p.id}>
                      <td>
                        {p.studentName} <span className="text-ink-3">{p.className}</span>
                      </td>
                      <td>{p.hostelName}</td>
                      <td>{passKindLabel(t, p.kind)}</td>
                      <td>{passDates(p.fromDate, p.toDate, locale, t)}</td>
                      <td>
                        <HostelPassPill status={p.status} late={p.late} />
                      </td>
                      <td>{p.reason}</td>
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

/* ------------------------------------------------------------------ fee dues of boarders */

export function HostelFeeDuesReportView() {
  const { t } = useI18n();
  const locale = useLocale();
  const report = useApiData("hostel:report:fee-dues", hostelApi.feeDuesReport);
  const data = report.loading ? undefined : report.data;
  return (
    <ReportFrame
      title={t("hostel.report.feeDues.title")}
      testId="report-hostel-fee-dues"
      filterSummary={data ? [t("hostel.report.asOf", { date: formatPlainDate(data.asOf, locale) })] : []}
      problem={null}
      excel={{ path: hostelReportPaths.feeDuesCsv(), fileName: "hostel-fee-dues.csv" }}
      downloadLabel={t("hostel.report.csv")}
      ready={Boolean(data)}
      filters={null}
    >
      {report.error && !data ? (
        <ErrorState error={report.error} onRetry={report.reload} />
      ) : !data ? (
        <LoadingRows rows={4} />
      ) : (
        <>
          <p className="text-[13px] text-ink-3">{t("hostel.report.feeDues.note")}</p>
          <div className="report-figures">
            <Figure label={t("hostel.boarders.title")} value={data.boarders} testId="dues-boarders" />
            <Figure label={t("hostel.report.feeDues.balance")} value={formatPaise(data.balancePaise)} />
            <Figure label={t("hostel.report.feeDues.overdue")} value={formatPaise(data.overduePaise)} />
          </div>
          {data.rows.length === 0 ? (
            <p className="empty">{t("hostel.boarders.empty")}</p>
          ) : (
            <div className="table-wrap">
              <table className="table report-table" data-testid="dues-rows">
                <caption className="sr-only">{t("hostel.report.feeDues.title")}</caption>
                <thead>
                  <tr>
                    <th scope="col">{t("hostel.field.student")}</th>
                    <th scope="col">{t("hostel.field.className")}</th>
                    <th scope="col">{t("hostel.field.hostel")}</th>
                    <th scope="col" className="r">{t("hostel.report.feeDues.net")}</th>
                    <th scope="col" className="r">{t("hostel.report.feeDues.paid")}</th>
                    <th scope="col" className="r">{t("hostel.report.feeDues.balance")}</th>
                    <th scope="col" className="r">{t("hostel.report.feeDues.overdue")}</th>
                  </tr>
                </thead>
                <tbody>
                  {data.rows.map((d) => (
                    <tr key={d.studentId}>
                      <td>
                        {d.studentName} <span className="text-ink-3 mono">{d.admissionNo}</span>
                      </td>
                      <td>{d.className ?? "—"}</td>
                      <td>
                        {d.hostelName} · {d.roomNumber}
                      </td>
                      <td className="r num">{formatPaise(d.netPaise)}</td>
                      <td className="r num">{formatPaise(d.paidPaise)}</td>
                      <td className="r num">{formatPaise(d.balancePaise)}</td>
                      <td className={`r num ${d.overduePaise > 0 ? "text-bad" : ""}`}>{formatPaise(d.overduePaise)}</td>
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
