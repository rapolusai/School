"use client";

import { BellRing } from "lucide-react";
import Link from "next/link";
import { useId, useState } from "react";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { api, toApiError } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { errorMessage } from "@/lib/error-message";
import { feesApi } from "@/lib/fees-api";
import { classLabel, formatDateTime, formatPaise, formatPlainDate } from "@/lib/format";
import { localeFor, plural, useI18n } from "@/lib/i18n";
import { hasPermission, PERMISSIONS } from "@/lib/permissions";
import type { OutstandingRow, OverdueRow, ReportFilter } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { FeesNav } from "./fee-ui";

export type DuesTab = "outstanding" | "overdue";

/** /app/fees/dues: what each class and section still owes, and the students who are overdue. */
export function DuesView({ initialTab = "outstanding" }: { initialTab?: DuesTab }) {
  const { t } = useI18n();
  const { me } = useAuth();
  const baseId = useId();
  const [tab, setTab] = useState<DuesTab>(initialTab);
  const [classId, setClassId] = useState("");
  const [sectionId, setSectionId] = useState("");
  const canAcademics = hasPermission(me, PERMISSIONS.academicsRead);
  const classes = useApiData(canAcademics ? "academics:classes" : null, api.listClasses);
  const classList = classes.data ?? [];
  const sections = classList.find((c) => c.id === classId)?.sections ?? [];
  const filter: ReportFilter = { classId: classId || undefined, sectionId: sectionId || undefined };
  const tabs: DuesTab[] = ["outstanding", "overdue"];

  return (
    <>
      <PageHead eyebrow={t("fees.eyebrow")} title={t("fees.dues.title")} />
      <FeesNav />
      <div role="tablist" aria-label={t("fees.dues.title")} className="tabs">
        {tabs.map((key) => (
          <button
            key={key}
            type="button"
            role="tab"
            id={`${baseId}-tab-${key}`}
            aria-selected={tab === key}
            aria-controls={`${baseId}-panel`}
            tabIndex={tab === key ? 0 : -1}
            onClick={() => setTab(key)}
            onKeyDown={(event) => {
              if (event.key === "ArrowRight" || event.key === "ArrowLeft") {
                event.preventDefault();
                setTab(key === "outstanding" ? "overdue" : "outstanding");
              }
            }}
          >
            {t(key === "outstanding" ? "fees.dues.tab.outstanding" : "fees.dues.tab.overdue")}
          </button>
        ))}
      </div>
      <div role="tabpanel" id={`${baseId}-panel`} aria-labelledby={`${baseId}-tab-${tab}`}>
        <section className="card">
          {canAcademics ? (
            <div className="toolbar">
              <label className="min-w-[140px] flex-1 sm:flex-none">
                <span className="sr-only">{t("students.filter.class")}</span>
                <select
                  className="input"
                  value={classId}
                  onChange={(e) => {
                    setClassId(e.target.value);
                    setSectionId("");
                  }}
                  aria-label={t("students.filter.class")}
                >
                  <option value="">{t("students.filter.allClasses")}</option>
                  {classList.map((c) => (
                    <option key={c.id} value={c.id}>
                      {c.name}
                    </option>
                  ))}
                </select>
              </label>
              {classId && sections.length > 1 ? (
                <label className="min-w-[120px] flex-1 sm:flex-none">
                  <span className="sr-only">{t("students.filter.section")}</span>
                  <select
                    className="input"
                    value={sectionId}
                    onChange={(e) => setSectionId(e.target.value)}
                    aria-label={t("students.filter.section")}
                  >
                    <option value="">{t("students.filter.allSections")}</option>
                    {sections.map((s) => (
                      <option key={s.id} value={s.id}>
                        {t("setup.sections.label", { name: s.name })}
                      </option>
                    ))}
                  </select>
                </label>
              ) : null}
            </div>
          ) : null}
          {tab === "outstanding" ? <Outstanding filter={filter} /> : <Overdue filter={filter} />}
        </section>
      </div>
    </>
  );
}

function Outstanding({ filter }: { filter: ReportFilter }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const report = useApiData(`fees:outstanding:${JSON.stringify(filter)}`, () => feesApi.outstanding(filter));
  const r = report.data;
  if (report.error && !r) return <ErrorState error={report.error} onRetry={report.reload} />;
  if (!r) return <LoadingRows rows={5} />;
  if (!r.academicYearId) return <p className="empty">{t("fees.noYear")}</p>;
  if (r.rows.length === 0) return <p className="empty">{t("fees.dues.noOutstanding")}</p>;

  const place = (row: OutstandingRow) =>
    row.sectionName ? classLabel(row.className, row.sectionName) : (row.className ?? t("fees.dues.noSection"));

  return (
    <div aria-busy={report.loading}>
      <p className="mb-3 text-sm text-ink-2">
        {t("fees.dues.asOf", { year: r.academicYearName ?? "", date: formatPlainDate(r.asOf, locale) })}
      </p>
      <div className="table-wrap hidden md:block">
        <table className="table" data-testid="outstanding-table">
          <thead>
            <tr>
              <th scope="col">{t("fees.dues.col.section")}</th>
              <th scope="col" className="r">
                {t("fees.dues.col.students")}
              </th>
              <th scope="col" className="r">
                {t("fees.dues.col.net")}
              </th>
              <th scope="col" className="r">
                {t("fees.dues.col.paid")}
              </th>
              <th scope="col" className="r">
                {t("fees.dues.col.dueSoFar")}
              </th>
              <th scope="col" className="r">
                {t("fees.dues.col.overdue")}
              </th>
              <th scope="col" className="r">
                {t("fees.dues.col.balance")}
              </th>
            </tr>
          </thead>
          <tbody>
            {r.rows.map((row) => (
              <tr key={`${row.classId}:${row.sectionId}`}>
                <td className="whitespace-nowrap font-semibold">{place(row)}</td>
                <td className="r num">{row.students}</td>
                <td className="r num">{formatPaise(row.netPaise)}</td>
                <td className="r num">{formatPaise(row.paidPaise)}</td>
                <td className="r num">{formatPaise(row.dueSoFarPaise)}</td>
                <td className={`r num${row.overduePaise > 0 ? " text-bad" : ""}`}>{formatPaise(row.overduePaise)}</td>
                <td className="r num font-semibold">{formatPaise(row.balancePaise)}</td>
              </tr>
            ))}
          </tbody>
          <tfoot>
            <tr>
              <th scope="row" className="text-left">
                {t("fees.dues.total")}
              </th>
              <td className="r num font-semibold">{r.total.students}</td>
              <td className="r num font-semibold">{formatPaise(r.total.netPaise)}</td>
              <td className="r num font-semibold">{formatPaise(r.total.paidPaise)}</td>
              <td className="r num font-semibold">{formatPaise(r.total.dueSoFarPaise)}</td>
              <td className="r num font-semibold">{formatPaise(r.total.overduePaise)}</td>
              <td className="r num font-semibold">{formatPaise(r.total.balancePaise)}</td>
            </tr>
          </tfoot>
        </table>
      </div>
      <ul className="rowcards md:hidden" data-testid="outstanding-cards">
        {[...r.rows, r.total].map((row, index) => (
          <li key={index} className="rowcard flex-col items-stretch gap-1">
            <div className="flex justify-between gap-2">
              <b className="font-semibold">{index === r.rows.length ? t("fees.dues.total") : place(row)}</b>
              <span className="text-[12.5px] text-ink-3">{plural(t, "fees.dues.students", row.students)}</span>
            </div>
            <dl className="dues-card-amounts">
              <dt>{t("fees.dues.col.balance")}</dt>
              <dd className="num font-semibold">{formatPaise(row.balancePaise)}</dd>
              <dt>{t("fees.dues.col.overdue")}</dt>
              <dd className={`num${row.overduePaise > 0 ? " text-bad" : ""}`}>{formatPaise(row.overduePaise)}</dd>
              <dt>{t("fees.dues.col.paid")}</dt>
              <dd className="num">{formatPaise(row.paidPaise)}</dd>
            </dl>
          </li>
        ))}
      </ul>
    </div>
  );
}

function Overdue({ filter }: { filter: ReportFilter }) {
  const { t, lang } = useI18n();
  const { me } = useAuth();
  const { toast } = useToast();
  const locale = localeFor(lang);
  const canRemind = hasPermission(me, PERMISSIONS.feesCollect);
  const [minDays, setMinDays] = useState("");
  const [selected, setSelected] = useState<ReadonlySet<string>>(new Set());
  const [sending, setSending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const days = /^\d{1,4}$/.test(minDays) ? Number(minDays) : undefined;
  const query: ReportFilter = { ...filter, minDays: days };
  const report = useApiData(`fees:overdue:${JSON.stringify(query)}`, () => feesApi.overdue(query));
  const r = report.data;
  const rows = r?.rows ?? [];
  const visible = rows.filter((row) => selected.has(row.studentId));
  const allSelected = rows.length > 0 && visible.length === rows.length;

  const toggle = (id: string) =>
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });

  const remind = async () => {
    if (!visible.length || sending) return;
    setSending(true);
    setError(null);
    try {
      const result = await feesApi.remind(visible.map((row) => row.studentId));
      toast(
        result.skipped
          ? t("fees.remind.doneSkipped", { requested: result.requested, skipped: result.skipped })
          : plural(t, "fees.remind.done", result.requested),
      );
      setSelected(new Set());
      report.reload();
    } catch (caught) {
      setError(errorMessage(toApiError(caught), t));
    } finally {
      setSending(false);
    }
  };

  const checkbox = (row: OverdueRow) =>
    canRemind ? (
      <input
        type="checkbox"
        className="h-[18px] w-[18px] accent-[var(--accent)]"
        checked={selected.has(row.studentId)}
        onChange={() => toggle(row.studentId)}
        aria-label={t("fees.remind.select", { name: row.fullName })}
      />
    ) : null;

  const lastReminder = (row: OverdueRow) =>
    row.lastReminderAt ? t("fees.remind.last", { date: formatDateTime(row.lastReminderAt, locale) }) : null;

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-end gap-2">
        <label className="field w-[170px]">
          <span className="field-label">{t("fees.overdue.minDays")}</span>
          <input
            className="input"
            inputMode="numeric"
            name="minDays"
            value={minDays}
            maxLength={4}
            placeholder="0"
            onChange={(e) => setMinDays(e.target.value.replace(/\D/g, ""))}
          />
        </label>
        {canRemind ? (
          <button
            type="button"
            className="btn btn-primary ml-auto"
            disabled={!visible.length || sending}
            onClick={remind}
          >
            <BellRing size={18} aria-hidden="true" />
            {sending
              ? t("common.working")
              : visible.length
                ? plural(t, "fees.remind.button", visible.length)
                : t("fees.remind.pick")}
          </button>
        ) : null}
      </div>
      <FormAlert message={error} />
      {report.error && !r ? (
        <ErrorState error={report.error} onRetry={report.reload} />
      ) : !r ? (
        <LoadingRows rows={5} />
      ) : !r.academicYearId ? (
        <p className="empty">{t("fees.noYear")}</p>
      ) : rows.length === 0 ? (
        <p className="empty" data-testid="overdue-empty">
          {t("fees.overdue.none")}
        </p>
      ) : (
        <div aria-busy={report.loading}>
          <p className="mb-2 text-sm text-ink-2">
            {plural(t, "fees.overdue.summary", rows.length, { amount: formatPaise(r.totalOverduePaise) })}
          </p>
          <div className="table-wrap hidden md:block">
            <table className="table" data-testid="overdue-table">
              <thead>
                <tr>
                  {canRemind ? (
                    <th scope="col" className="w-8">
                      <input
                        type="checkbox"
                        className="h-[18px] w-[18px] accent-[var(--accent)]"
                        checked={allSelected}
                        onChange={() =>
                          setSelected(allSelected ? new Set() : new Set(rows.map((row) => row.studentId)))
                        }
                        aria-label={t("fees.remind.selectAll")}
                      />
                    </th>
                  ) : null}
                  <th scope="col">{t("fees.receipt.student")}</th>
                  <th scope="col">{t("students.col.guardian")}</th>
                  <th scope="col">{t("fees.overdue.col.instalments")}</th>
                  <th scope="col" className="r">
                    {t("fees.overdue.col.days")}
                  </th>
                  <th scope="col" className="r">
                    {t("fees.dues.col.overdue")}
                  </th>
                  <th scope="col" />
                </tr>
              </thead>
              <tbody>
                {rows.map((row) => (
                  <tr key={row.studentId}>
                    {canRemind ? <td>{checkbox(row)}</td> : null}
                    <td>
                      <span className="block leading-tight">
                        <b className="font-semibold">{row.fullName}</b>
                        <span className="block text-[12.5px] text-ink-3">
                          {[classLabel(row.className, row.sectionName), row.admissionNo].filter(Boolean).join(" · ")}
                        </span>
                      </span>
                    </td>
                    <td>
                      <span className="block leading-tight">
                        {row.guardianName ?? "—"}
                        <span className="block text-[12.5px] text-ink-3 num">{row.guardianPhone ?? ""}</span>
                      </span>
                    </td>
                    <td>
                      <span className="block leading-tight">
                        {row.instalments.join(", ")}
                        <span className="block text-[12.5px] text-ink-3">
                          {[t("fees.overdue.since", { date: formatPlainDate(row.oldestDueDate, locale) }), lastReminder(row)]
                            .filter(Boolean)
                            .join(" · ")}
                        </span>
                      </span>
                    </td>
                    <td className="r num">{row.daysOverdue}</td>
                    <td className="r num">
                      <b className="text-bad">{formatPaise(row.overduePaise)}</b>
                      {row.lateFeePaise ? (
                        <span className="block text-[12.5px] text-ink-3">
                          {t("fees.overdue.lateFee", { amount: formatPaise(row.lateFeePaise) })}
                        </span>
                      ) : null}
                    </td>
                    <td className="r">
                      {canRemind ? (
                        <Link
                          href={`/app/fees/collect?student=${encodeURIComponent(row.studentId)}`}
                          className="btn btn-sm"
                        >
                          {t("fees.nav.collect")}
                        </Link>
                      ) : null}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <ul className="rowcards md:hidden" data-testid="overdue-cards">
            {rows.map((row) => (
              <li key={row.studentId} className="rowcard items-start">
                {canRemind ? <span className="pt-0.5">{checkbox(row)}</span> : null}
                <span className="min-w-0 flex-1">
                  <b className="block truncate font-semibold">{row.fullName}</b>
                  <span className="block truncate text-[12.5px] text-ink-3">
                    {[classLabel(row.className, row.sectionName), row.instalments.join(", ")].filter(Boolean).join(" · ")}
                  </span>
                  <span className="block truncate text-[12.5px] text-ink-3">
                    {[row.guardianName, row.guardianPhone].filter(Boolean).join(" · ")}
                  </span>
                  {lastReminder(row) ? (
                    <span className="block truncate text-[12.5px] text-ink-3">{lastReminder(row)}</span>
                  ) : null}
                </span>
                <span className="flex flex-col items-end gap-1">
                  <b className="num text-bad">{formatPaise(row.overduePaise)}</b>
                  <span className="text-[12.5px] text-ink-3">{plural(t, "fees.dues.daysLate", row.daysOverdue)}</span>
                  {canRemind ? (
                    <Link
                      href={`/app/fees/collect?student=${encodeURIComponent(row.studentId)}`}
                      className="link text-[13px]"
                    >
                      {t("fees.nav.collect")}
                    </Link>
                  ) : null}
                </span>
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}
