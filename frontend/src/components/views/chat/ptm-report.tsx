"use client";

import { useState } from "react";
import { ErrorState, LoadingRows } from "@/components/ui/states";
import { Figure, ReportFrame } from "@/components/views/reports/report-frame";
import { ptmApi, ptmPaths } from "@/lib/chat-api";
import { formatPlainDate } from "@/lib/format";
import { localeFor, useI18n } from "@/lib/i18n";
import type { PtmAttendanceSummary, PtmSectionAttendance, PtmTeacherAttendance } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";

/* The reports hub's PTM attendance summary (ptm.manage): who booked and who came, by class and by teacher. */

/** "75%" of booked families who came; "—" before anyone is marked. Pure, exported for tests. */
export function cameText(row: Pick<PtmSectionAttendance, "attended" | "noShow">): string {
  const marked = row.attended + row.noShow;
  return marked === 0 ? "—" : `${Math.round((row.attended * 100) / marked)}%`;
}

export function PtmAttendanceReportView({ initialEventId }: { initialEventId?: string }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const events = useApiData("ptm:events", ptmApi.events);
  const list = (events.data ?? []).filter((e) => e.status === "SCHEDULED");
  const [eventId, setEventId] = useState(initialEventId ?? "");
  const chosen = list.find((e) => e.id === eventId) ?? (eventId ? undefined : list[0]);
  const id = chosen?.id ?? "";
  const report = useApiData(id ? `ptm:attendance:${id}` : null, () => ptmApi.attendance(id));
  const data = report.data && report.data.eventId === id ? report.data : undefined;
  const problem = events.data && list.length === 0 ? t("ptm.report.noMeetings") : null;

  return (
    <ReportFrame
      title={t("ptm.report.title")}
      testId="report-ptm"
      filterSummary={chosen ? [`${chosen.title} · ${formatPlainDate(chosen.date, locale)}`] : []}
      problem={problem}
      excel={id ? { path: ptmPaths.attendanceCsv(id), fileName: "ptm-attendance.csv" } : null}
      downloadLabel={t("ptm.report.csv")}
      ready={Boolean(data)}
      filters={
        <label className="field w-full sm:w-auto sm:min-w-64">
          <span className="field-label">{t("ptm.report.meeting")}</span>
          <select className="input" name="eventId" value={id} onChange={(e) => setEventId(e.target.value)}>
            {list.map((e) => (
              <option key={e.id} value={e.id}>
                {e.title} · {formatPlainDate(e.date, locale)}
              </option>
            ))}
          </select>
        </label>
      }
    >
      {problem ? null : events.error && !events.data ? (
        <ErrorState error={events.error} onRetry={events.reload} />
      ) : !events.data ? (
        <LoadingRows rows={4} />
      ) : report.error && !data ? (
        <ErrorState error={report.error} onRetry={report.reload} />
      ) : !data ? (
        <LoadingRows rows={6} />
      ) : (
        <AttendanceBody report={data} />
      )}
    </ReportFrame>
  );
}

function AttendanceBody({ report }: { report: PtmAttendanceSummary }) {
  const { t } = useI18n();
  const total = report.total;
  return (
    <>
      <div className="report-figures">
        <Figure label={t("ptm.report.students")} value={total.students} />
        <Figure label={t("ptm.report.booked")} value={total.booked} testId="ptm-report-booked" />
        <Figure label={t("ptm.report.attended")} value={total.attended} testId="ptm-report-attended" />
        <Figure label={t("ptm.report.noShow")} value={total.noShow} />
        <Figure label={t("ptm.report.notMarked")} value={total.notMarked} />
        <Figure label={t("ptm.report.came")} value={cameText(total)} />
      </div>
      <h2 className="mt-2 text-[15px]">{t("ptm.report.byClass")}</h2>
      <CountsTable
        first={t("ptm.col.class")}
        second={t("ptm.report.students")}
        rows={report.sections.map((s) => ({ ...s, key: s.sectionId ?? s.label, name: s.label, size: s.students }))}
        total={{ ...total, key: "total", name: t("ptm.report.total"), size: total.students }}
        testId="ptm-report-classes"
      />
      <h2 className="mt-2 text-[15px]">{t("ptm.report.byTeacher")}</h2>
      <CountsTable
        first={t("ptm.col.teacher")}
        second={t("ptm.report.slots")}
        rows={report.teachers.map((tr: PtmTeacherAttendance) => ({ ...tr, key: tr.teacherId, name: tr.name ?? "—", size: tr.slots }))}
        testId="ptm-report-teachers"
      />
    </>
  );
}

type CountRow = { key: string; name: string; size: number; booked: number; attended: number; noShow: number; notMarked: number };

function CountsTable({
  first,
  second,
  rows,
  total,
  testId,
}: {
  first: string;
  second: string;
  rows: CountRow[];
  total?: CountRow;
  testId: string;
}) {
  const { t } = useI18n();
  if (rows.length === 0) return <p className="empty">{t("ptm.report.empty")}</p>;
  const cells = (row: CountRow) => (
    <>
      <td className="r num">{row.size}</td>
      <td className="r num">{row.booked}</td>
      <td className="r num">{row.attended}</td>
      <td className="r num">{row.noShow}</td>
      <td className="r num">{row.notMarked}</td>
      <td className="r num">{cameText(row)}</td>
    </>
  );
  return (
    <div className="table-wrap">
      <table className="table report-table" data-testid={testId}>
        <thead>
          <tr>
            <th scope="col">{first}</th>
            <th scope="col" className="r">
              {second}
            </th>
            <th scope="col" className="r">
              {t("ptm.report.booked")}
            </th>
            <th scope="col" className="r">
              {t("ptm.report.attended")}
            </th>
            <th scope="col" className="r">
              {t("ptm.report.noShow")}
            </th>
            <th scope="col" className="r">
              {t("ptm.report.notMarked")}
            </th>
            <th scope="col" className="r">
              {t("ptm.report.came")}
            </th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={row.key}>
              <th scope="row">{row.name}</th>
              {cells(row)}
            </tr>
          ))}
        </tbody>
        {total ? (
          <tfoot>
            <tr>
              <th scope="row">{total.name}</th>
              {cells(total)}
            </tr>
          </tfoot>
        ) : null}
      </table>
    </div>
  );
}
