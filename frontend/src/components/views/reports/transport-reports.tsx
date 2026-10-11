"use client";

import { useState } from "react";
import { ErrorState, LoadingRows } from "@/components/ui/states";
import {
  clockTime,
  DocumentStatePill,
  documentLabel,
  eventLabel,
  tripKindLabel,
} from "@/components/views/transport/transport-shared";
import { api } from "@/lib/api";
import { formatPaise, formatPlainDate, todayInIndia } from "@/lib/format";
import { localeFor, useI18n } from "@/lib/i18n";
import {
  timeOfDay,
  transportApi,
  transportReportPaths,
  type BoardingParams,
  type DocumentsParams,
  type RouteStudentsParams,
  type TransportFeesParams,
} from "@/lib/transport-api";
import type { RouteSummary } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { DateFilter, Figure, nameOf, ReportFrame } from "./report-frame";

/* The transport reports in the reports hub (docs/api/phase-2-transport.md, "Reports"). Downloads are CSV files (with a
   byte order mark, so Excel reads them as UTF-8). */

const DATE = /^\d{4}-\d{2}-\d{2}$/;

function useRoutes(): RouteSummary[] {
  return useApiData("transport:routes", transportApi.routes).data ?? [];
}

function RouteFilter({ routes, value, onChange }: { routes: RouteSummary[]; value: string; onChange: (id: string) => void }) {
  const { t } = useI18n();
  return (
    <label className="field w-full sm:w-auto sm:min-w-44">
      <span className="field-label">{t("transport.col.route")}</span>
      <select className="input" name="routeId" value={value} onChange={(e) => onChange(e.target.value)}>
        <option value="">{t("transport.alloc.allRoutes")}</option>
        {routes.map((r) => (
          <option key={r.id} value={r.id}>
            {r.name}
          </option>
        ))}
      </select>
    </label>
  );
}

/* ------------------------------------------------------------------ route-wise students */

/** Each route's students by stop on a day, with a guardian to call: printed for the attendant. */
export function TransportRouteStudentsReportView() {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const routes = useRoutes();
  const [routeId, setRouteId] = useState("");
  const [date, setDate] = useState(() => todayInIndia());
  const problem = DATE.test(date) ? null : t("reports.v.date");
  const params: RouteStudentsParams = { routeId: routeId || undefined, date };
  const report = useApiData(problem ? null : `transport:report:roster:${JSON.stringify(params)}`, () => transportApi.routeStudents(params));
  const data = report.loading ? undefined : report.data;
  const routeName = nameOf(routes, routeId);
  const summary = [routeName ?? t("transport.alloc.allRoutes"), DATE.test(date) ? t("reports.filter.dateIs", { date: formatPlainDate(date, locale) }) : null].filter(
    (s): s is string => Boolean(s),
  );

  return (
    <ReportFrame
      title={t("transport.report.roster.title")}
      testId="report-transport-roster"
      filterSummary={summary}
      problem={problem}
      excel={problem ? null : { path: transportReportPaths.routeStudentsCsv(params), fileName: `transport-route-students-${date}.csv` }}
      downloadLabel={t("transport.report.csv")}
      ready={Boolean(data)}
      filters={
        <>
          <RouteFilter routes={routes} value={routeId} onChange={setRouteId} />
          <DateFilter name="date" label={t("reports.filter.date")} value={date} onChange={setDate} invalid={problem !== null} />
        </>
      }
    >
      {problem ? null : report.error && !data ? (
        <ErrorState error={report.error} onRetry={report.reload} />
      ) : !data ? (
        <LoadingRows rows={5} />
      ) : (
        <>
          <div className="report-figures">
            <Figure label={t("transport.report.roster.students")} value={data.students} testId="transport-roster-count" />
            <Figure label={t("transport.report.roster.routes")} value={data.routes.length} />
          </div>
          {data.routes.length === 0 ? (
            <p className="empty">{t("transport.report.roster.empty")}</p>
          ) : (
            data.routes.map((route) => (
              <section key={route.routeId} className="transport-roster" aria-labelledby={`roster-${route.routeId}`} data-testid="roster-route">
                <h2 id={`roster-${route.routeId}`} className="text-[16px]">
                  {route.name}
                  {route.vehicleRegistrationNo ? <span className="mono ml-2 text-[13px] font-normal text-ink-2">{route.vehicleRegistrationNo}</span> : null}
                </h2>
                <p className="text-[12.5px] text-ink-2">
                  {[
                    route.driverName ? t("transport.report.roster.driver", { name: route.driverName }) : null,
                    route.attendantName ? t("transport.report.roster.attendant", { name: route.attendantName }) : null,
                    route.seats ? t("transport.report.roster.seats", { students: route.students, seats: route.seats }) : null,
                  ]
                    .filter(Boolean)
                    .join(" · ")}
                </p>
                {route.stops.every((stop) => stop.students.length === 0) ? (
                  <p className="text-[13px] text-ink-3">{t("transport.route.noStudents")}</p>
                ) : (
                  <div className="table-wrap">
                    <table className="table report-table">
                      <caption className="sr-only">{route.name}</caption>
                      <thead>
                        <tr>
                          <th scope="col">{t("transport.col.stop")}</th>
                          <th scope="col">{t("transport.col.student")}</th>
                          <th scope="col">{t("transport.report.roster.guardian")}</th>
                          <th scope="col" className="w-10 text-center">
                            {t("transport.report.roster.tick")}
                          </th>
                        </tr>
                      </thead>
                      <tbody>
                        {route.stops.flatMap((stop) =>
                          stop.students.map((row, i) => (
                            <tr key={`${stop.stopId}:${row.studentId}`}>
                              <td className="whitespace-nowrap">
                                {i === 0 ? (
                                  <>
                                    <b className="block">
                                      {stop.seq}. {stop.name}
                                    </b>
                                    <span className="block text-[12.5px] text-ink-3">
                                      {[timeOfDay(stop.pickupTime), timeOfDay(stop.dropTime)].filter(Boolean).join(" – ")}
                                    </span>
                                  </>
                                ) : null}
                              </td>
                              <th scope="row">
                                <span className="block font-semibold">{row.name}</span>
                                <span className="block text-[12.5px] font-normal text-ink-3">
                                  {[row.classLabel, row.admissionNo].filter(Boolean).join(" · ")}
                                </span>
                              </th>
                              <td>
                                <span className="block">{row.guardianName ?? "—"}</span>
                                <span className="mono block text-[12.5px] text-ink-3">{row.guardianPhone ?? ""}</span>
                              </td>
                              <td className="text-center">
                                <span className="transport-tick" aria-hidden="true" />
                              </td>
                            </tr>
                          )),
                        )}
                      </tbody>
                    </table>
                  </div>
                )}
              </section>
            ))
          )}
        </>
      )}
    </ReportFrame>
  );
}

/* ------------------------------------------------------------------ documents */

const WITHIN = [0, 30, 60, 90, 180, 365];

/** Vehicle papers and driving licences that have expired, expire soon or have no date on record. */
export function TransportDocumentsReportView() {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const [withinDays, setWithinDays] = useState(30);
  const params: DocumentsParams = { withinDays };
  const report = useApiData(`transport:report:documents:${withinDays}`, () => transportApi.documents(params));
  const data = report.loading ? undefined : report.data;
  const summary = [
    withinDays === 0 ? t("transport.report.docs.expiredOnly") : t("transport.report.docs.withinIs", { days: withinDays }),
    data ? t("library.report.asOf", { date: formatPlainDate(data.asOf, locale) }) : null,
  ].filter((s): s is string => Boolean(s));

  return (
    <ReportFrame
      title={t("transport.report.docs.title")}
      testId="report-transport-documents"
      filterSummary={summary}
      problem={null}
      excel={{ path: transportReportPaths.documentsCsv(params), fileName: "transport-documents.csv" }}
      downloadLabel={t("transport.report.csv")}
      ready={Boolean(data)}
      filters={
        <label className="field w-full sm:w-auto sm:min-w-52">
          <span className="field-label">{t("transport.report.docs.within")}</span>
          <select className="input" name="withinDays" value={withinDays} onChange={(e) => setWithinDays(Number(e.target.value))}>
            {WITHIN.map((days) => (
              <option key={days} value={days}>
                {days === 0 ? t("transport.report.docs.expiredOnly") : t("transport.report.docs.withinIs", { days })}
              </option>
            ))}
          </select>
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
            <Figure label={t("transport.report.docs.count")} value={data.rows.length} testId="transport-docs-count" />
            <Figure label={t("transport.report.docs.expired")} value={data.rows.filter((r) => r.state === "EXPIRED").length} />
          </div>
          {data.rows.length === 0 ? (
            <p className="empty">{t("transport.report.docs.empty")}</p>
          ) : (
            <div className="table-wrap">
              <table className="table report-table" data-testid="transport-docs-table">
                <caption className="sr-only">{t("transport.report.docs.title")}</caption>
                <thead>
                  <tr>
                    <th scope="col">{t("transport.report.docs.subject")}</th>
                    <th scope="col">{t("transport.report.docs.document")}</th>
                    <th scope="col">{t("transport.report.docs.expiresOn")}</th>
                    <th scope="col">{t("transport.report.docs.state")}</th>
                  </tr>
                </thead>
                <tbody>
                  {data.rows.map((row) => (
                    <tr key={`${row.subjectId}:${row.document}`}>
                      <th scope="row">
                        <span className={`block font-semibold ${row.subjectType === "VEHICLE" ? "mono" : ""}`}>{row.subject}</span>
                        <span className="block text-[12.5px] font-normal text-ink-3">{t(`transport.report.docs.type.${row.subjectType}`)}</span>
                      </th>
                      <td>{documentLabel(t, row.document)}</td>
                      <td className="whitespace-nowrap">{row.expiresOn ? formatPlainDate(row.expiresOn, locale) : "—"}</td>
                      <td>
                        <DocumentStatePill state={row.state} daysLeft={row.daysLeft} />
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

/* ------------------------------------------------------------------ fees by route */

/** What each route's students were billed for the bus this year, what came in, what is due and overdue. */
export function TransportFeesReportView() {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const routes = useRoutes();
  const years = useApiData("reports:years", api.listYears).data ?? [];
  const [yearId, setYearId] = useState("");
  const [routeId, setRouteId] = useState("");
  const params: TransportFeesParams = { yearId: yearId || undefined, routeId: routeId || undefined };
  const report = useApiData(`transport:report:fees:${JSON.stringify(params)}`, () => transportApi.fees(params));
  const data = report.loading ? undefined : report.data;
  const summary = [
    data ? t("reports.filter.yearIs", { name: data.academicYearName }) : null,
    nameOf(routes, routeId) ?? t("transport.alloc.allRoutes"),
    data ? t("library.report.asOf", { date: formatPlainDate(data.asOf, locale) }) : null,
  ].filter((s): s is string => Boolean(s));

  return (
    <ReportFrame
      title={t("transport.report.fees.title")}
      testId="report-transport-fees"
      filterSummary={summary}
      problem={null}
      excel={{ path: transportReportPaths.feesCsv(params), fileName: "transport-fees.csv" }}
      downloadLabel={t("transport.report.csv")}
      ready={Boolean(data)}
      filters={
        <>
          <label className="field w-full sm:w-auto sm:min-w-40">
            <span className="field-label">{t("reports.filter.year")}</span>
            <select className="input" name="yearId" value={yearId} onChange={(e) => setYearId(e.target.value)}>
              <option value="">{t("reports.filter.currentYear")}</option>
              {years.map((y) => (
                <option key={y.id} value={y.id}>
                  {y.name}
                </option>
              ))}
            </select>
          </label>
          <RouteFilter routes={routes} value={routeId} onChange={setRouteId} />
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
            <Figure label={t("transport.report.fees.billed")} value={formatPaise(data.totals.billedPaise)} />
            <Figure label={t("transport.report.fees.collected")} value={formatPaise(data.totals.collectedPaise)} testId="transport-fees-collected" />
            <Figure label={t("transport.report.fees.outstanding")} value={formatPaise(data.totals.outstandingPaise)} />
            <Figure label={t("transport.report.fees.overdue")} value={formatPaise(data.totals.overduePaise)} />
          </div>
          {data.routes.length === 0 ? (
            <p className="empty">{t("transport.report.fees.empty")}</p>
          ) : (
            <div className="table-wrap">
              <table className="table report-table" data-testid="transport-fees-table">
                <caption className="sr-only">{t("transport.report.fees.title")}</caption>
                <thead>
                  <tr>
                    <th scope="col">{t("transport.col.route")}</th>
                    <th scope="col" className="r">
                      {t("transport.col.students")}
                    </th>
                    <th scope="col" className="r">
                      {t("transport.report.fees.billed")}
                    </th>
                    <th scope="col" className="r">
                      {t("transport.report.fees.collected")}
                    </th>
                    <th scope="col" className="r">
                      {t("transport.report.fees.outstanding")}
                    </th>
                    <th scope="col" className="r">
                      {t("transport.report.fees.overdue")}
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {data.routes.map((row) => (
                    <tr key={row.routeId ?? "none"}>
                      <th scope="row">{row.routeName ?? t("transport.report.fees.noRoute")}</th>
                      <td className="r num">{row.students}</td>
                      <td className="r num">{formatPaise(row.billedPaise)}</td>
                      <td className="r num">{formatPaise(row.collectedPaise)}</td>
                      <td className="r num">{formatPaise(row.outstandingPaise)}</td>
                      <td className={`r num ${row.overduePaise > 0 ? "font-semibold text-bad" : ""}`}>{formatPaise(row.overduePaise)}</td>
                    </tr>
                  ))}
                </tbody>
                <tfoot>
                  <tr>
                    <th scope="row">{t("transport.report.fees.total")}</th>
                    <td className="r num">{data.totals.students}</td>
                    <td className="r num">{formatPaise(data.totals.billedPaise)}</td>
                    <td className="r num">{formatPaise(data.totals.collectedPaise)}</td>
                    <td className="r num">{formatPaise(data.totals.outstandingPaise)}</td>
                    <td className="r num">{formatPaise(data.totals.overduePaise)}</td>
                  </tr>
                </tfoot>
              </table>
            </div>
          )}
          <p className="text-[12.5px] text-ink-3">{t("transport.report.fees.note")}</p>
        </>
      )}
    </ReportFrame>
  );
}

/* ------------------------------------------------------------------ boarding log */

/** Every boarding, drop, absence and delay recorded on a day, in time order. */
export function TransportBoardingReportView() {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const routes = useRoutes();
  const [routeId, setRouteId] = useState("");
  const [date, setDate] = useState(() => todayInIndia());
  const problem = DATE.test(date) ? null : t("reports.v.date");
  const params: BoardingParams = { date, routeId: routeId || undefined };
  const report = useApiData(problem ? null : `transport:report:boarding:${JSON.stringify(params)}`, () => transportApi.boarding(params));
  const data = report.loading ? undefined : report.data;
  const summary = [DATE.test(date) ? t("reports.filter.dateIs", { date: formatPlainDate(date, locale) }) : null, nameOf(routes, routeId) ?? t("transport.alloc.allRoutes")].filter(
    (s): s is string => Boolean(s),
  );

  return (
    <ReportFrame
      title={t("transport.report.boarding.title")}
      testId="report-transport-boarding"
      filterSummary={summary}
      problem={problem}
      excel={problem ? null : { path: transportReportPaths.boardingCsv(params), fileName: `transport-boarding-${date}.csv` }}
      downloadLabel={t("transport.report.csv")}
      ready={Boolean(data)}
      filters={
        <>
          <DateFilter name="date" label={t("reports.filter.date")} value={date} onChange={setDate} invalid={problem !== null} />
          <RouteFilter routes={routes} value={routeId} onChange={setRouteId} />
        </>
      }
    >
      {problem ? null : report.error && !data ? (
        <ErrorState error={report.error} onRetry={report.reload} />
      ) : !data ? (
        <LoadingRows rows={5} />
      ) : (
        <>
          <div className="report-figures">
            <Figure label={t("transport.boarding.BOARDED")} value={data.boarded} testId="transport-boarded-count" />
            <Figure label={t("transport.boarding.DROPPED")} value={data.dropped} />
            <Figure label={t("transport.boarding.ABSENT")} value={data.absent} />
            <Figure label={t("transport.report.boarding.delays")} value={data.delays} />
          </div>
          {data.rows.length === 0 ? (
            <p className="empty">{t("transport.report.boarding.empty")}</p>
          ) : (
            <div className="table-wrap">
              <table className="table report-table" data-testid="transport-boarding-table">
                <caption className="sr-only">{t("transport.report.boarding.title")}</caption>
                <thead>
                  <tr>
                    <th scope="col">{t("transport.report.boarding.time")}</th>
                    <th scope="col">{t("transport.col.route")}</th>
                    <th scope="col">{t("transport.report.boarding.what")}</th>
                    <th scope="col">{t("transport.col.student")}</th>
                    <th scope="col">{t("transport.col.stop")}</th>
                    <th scope="col">{t("transport.report.boarding.by")}</th>
                  </tr>
                </thead>
                <tbody>
                  {data.rows.map((row, i) => (
                    <tr key={i}>
                      <td className="num whitespace-nowrap">{clockTime(row.at, locale)}</td>
                      <td>
                        <span className="block">{row.routeName}</span>
                        <span className="block text-[12.5px] text-ink-3">{tripKindLabel(t, row.tripKind)}</span>
                      </td>
                      <td>
                        <span className="block">{eventLabel(t, row.kind)}</span>
                        {row.kind === "DELAYED" ? (
                          <span className="block text-[12.5px] text-ink-3">{t("transport.report.boarding.delay", { minutes: row.minutes ?? 0, reason: row.note ?? "" })}</span>
                        ) : null}
                      </td>
                      <td>
                        {row.studentName ? (
                          <>
                            <span className="block">{row.studentName}</span>
                            <span className="block text-[12.5px] text-ink-3">{[row.classLabel, row.admissionNo].filter(Boolean).join(" · ")}</span>
                          </>
                        ) : (
                          "—"
                        )}
                      </td>
                      <td>{row.stopName ?? "—"}</td>
                      <td>{row.byName ?? "—"}</td>
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
