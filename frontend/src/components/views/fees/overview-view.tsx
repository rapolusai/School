"use client";

import { AlarmClock, ArrowRight, CalendarDays, Download, IndianRupee, Wallet } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { toApiError } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { errorMessage } from "@/lib/error-message";
import { downloadCsv, feesApi, feesExports } from "@/lib/fees-api";
import { formatPaise, formatPlainDate, todayInIndia } from "@/lib/format";
import { localeFor, plural, useI18n } from "@/lib/i18n";
import { hasPermission, PERMISSIONS } from "@/lib/permissions";
import type { ReceiptSummary } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { isPlainDate } from "@/lib/validation";
import { FeesNav, ReceiptStatusPill, useModeLabel } from "./fee-ui";
import { StudentSearch } from "./student-search";

/** Longest range the collection report and exports take (the API's limit). */
export const MAX_RANGE_DAYS = 731;

/** A message key when `from`–`to` is not a range the reports accept, else null. */
export function rangeProblem(from: string, to: string): "validation.date" | "fees.v.range" | "fees.v.rangeTooLong" | null {
  if (!isPlainDate(from) || !isPlainDate(to)) return "validation.date";
  if (to < from) return "fees.v.range";
  const days = (Date.parse(`${to}T00:00:00Z`) - Date.parse(`${from}T00:00:00Z`)) / 86_400_000;
  return days > MAX_RANGE_DAYS ? "fees.v.rangeTooLong" : null;
}

function Kpi({
  icon: Icon,
  label,
  value,
  sub,
  href,
  linkLabel,
  loading,
  tone,
}: {
  icon: typeof Wallet;
  label: string;
  value: string;
  sub?: string;
  href?: string;
  linkLabel?: string;
  loading: boolean;
  tone?: "bad";
}) {
  return (
    <section className="card flex flex-col gap-1.5">
      <div className="flex items-center justify-between gap-2">
        <h2 className="font-body text-[13px] font-medium text-ink-2">{label}</h2>
        <Icon size={18} className="text-ink-3" aria-hidden="true" />
      </div>
      {loading ? (
        <div className="skeleton h-8 w-24" aria-hidden="true" />
      ) : (
        <span className={`kpi-value${tone === "bad" ? " text-bad" : ""}`}>{value}</span>
      )}
      {sub ? <span className="text-[12.5px] font-semibold text-ink-3">{sub}</span> : null}
      {href && linkLabel ? (
        <Link href={href} className="link mt-auto inline-flex items-center gap-1 pt-2 text-[13.5px]">
          {linkLabel}
          <ArrowRight size={16} aria-hidden="true" />
        </Link>
      ) : null}
    </section>
  );
}

/** /app/fees: today's and this month's collection, what is outstanding and overdue, and a quick collect. */
export function FeesOverviewView() {
  const { t, lang } = useI18n();
  const { me } = useAuth();
  const router = useRouter();
  const locale = localeFor(lang);
  const canCollect = hasPermission(me, PERMISSIONS.feesCollect);
  const overview = useApiData("fees:overview", feesApi.overview);
  const o = overview.data;
  const loading = !o;

  return (
    <>
      <PageHead
        eyebrow={
          o
            ? [t("fees.eyebrow"), o.academicYearName, formatPlainDate(o.asOf, locale)].filter(Boolean).join(" · ")
            : t("fees.eyebrow")
        }
        title={t("fees.overview.title")}
        actions={
          canCollect ? (
            <Link href="/app/fees/collect" className="btn btn-primary">
              <IndianRupee size={18} aria-hidden="true" />
              {t("fees.overview.collect")}
            </Link>
          ) : null
        }
      />
      <FeesNav />

      {overview.error && !o ? (
        <section className="card">
          <ErrorState error={overview.error} onRetry={overview.reload} />
        </section>
      ) : (
        <div className="grid grid-cols-1 gap-3.5 sm:grid-cols-2 xl:grid-cols-4" data-testid="fees-kpis">
          <Kpi
            icon={Wallet}
            label={t("fees.overview.today")}
            value={o ? formatPaise(o.today.amountPaise) : "—"}
            sub={o ? plural(t, "fees.overview.receipts", o.today.receiptCount) : undefined}
            loading={loading}
          />
          <Kpi
            icon={CalendarDays}
            label={t("fees.overview.month")}
            value={o ? formatPaise(o.thisMonth.amountPaise) : "—"}
            sub={o ? plural(t, "fees.overview.receipts", o.thisMonth.receiptCount) : undefined}
            href="/app/fees/receipts"
            linkLabel={t("fees.overview.allReceipts")}
            loading={loading}
          />
          <Kpi
            icon={IndianRupee}
            label={t("fees.overview.outstanding")}
            value={o ? formatPaise(o.outstandingPaise) : "—"}
            sub={o ? t("fees.overview.notYetDue", { amount: formatPaise(o.remainingThisYearPaise) }) : undefined}
            href="/app/fees/dues"
            linkLabel={t("fees.overview.byClass")}
            loading={loading}
          />
          <Kpi
            icon={AlarmClock}
            label={t("fees.overview.overdue")}
            value={o ? formatPaise(o.overduePaise) : "—"}
            sub={o ? plural(t, "fees.overview.overdueStudents", o.overdueStudents) : undefined}
            href="/app/fees/dues?view=overdue"
            linkLabel={t("fees.overview.remind")}
            loading={loading}
            tone={o && o.overduePaise > 0 ? "bad" : undefined}
          />
        </div>
      )}

      <div className="grid grid-cols-1 gap-3.5 lg:grid-cols-2">
        {canCollect ? (
          <section className="card" aria-labelledby="quick-collect">
            <div className="card-head">
              <h2 id="quick-collect">{t("fees.overview.quickCollect")}</h2>
            </div>
            <StudentSearch onPick={(hit) => router.push(`/app/fees/collect?student=${encodeURIComponent(hit.id)}`)} />
          </section>
        ) : null}
        <section className="card" aria-labelledby="recent-receipts">
          <div className="card-head">
            <h2 id="recent-receipts">{t("fees.overview.recent")}</h2>
            <Link href="/app/fees/receipts" className="link text-[13.5px]">
              {t("fees.overview.allReceipts")}
            </Link>
          </div>
          {!o ? (
            overview.error ? null : (
              <LoadingRows rows={3} />
            )
          ) : o.recentReceipts.length === 0 ? (
            <p className="empty">{t("fees.overview.noReceipts")}</p>
          ) : (
            <RecentReceipts receipts={o.recentReceipts} />
          )}
        </section>
      </div>

      <CollectionReportCard initialDate={o?.asOf ?? todayInIndia()} />
    </>
  );
}

function RecentReceipts({ receipts }: { receipts: ReceiptSummary[] }) {
  const { lang } = useI18n();
  const locale = localeFor(lang);
  const modeLabel = useModeLabel();
  return (
    <ul className="list">
      {receipts.map((r) => (
        <li key={r.id} className="li items-center">
          <div className="min-w-0 flex-1">
            <Link href={`/app/fees/receipts/${r.id}`} className="font-semibold hover:text-accent">
              {r.studentName}
            </Link>
            <p className="mono truncate text-[12.5px] text-ink-3">{r.receiptNo}</p>
            <p className="truncate text-[12.5px] text-ink-3">
              {formatPlainDate(r.receivedOn, locale)} · {modeLabel(r.mode)}
            </p>
          </div>
          <div className="flex flex-col items-end gap-1">
            <span className="num font-semibold">{formatPaise(r.amountPaise)}</span>
            {r.status === "CANCELLED" ? <ReceiptStatusPill status={r.status} /> : null}
          </div>
        </li>
      ))}
    </ul>
  );
}

/** Collection between two dates by mode and by head, with the receipts register and Tally exports. */
function CollectionReportCard({ initialDate }: { initialDate: string }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const modeLabel = useModeLabel();
  const [from, setFrom] = useState(initialDate);
  const [to, setTo] = useState(initialDate);
  const [exportError, setExportError] = useState<string | null>(null);
  const [exporting, setExporting] = useState<"receipts" | "tally" | null>(null);
  const problem = rangeProblem(from, to);
  const report = useApiData(problem ? null : `fees:collection:${from}:${to}`, () => feesApi.collection(from, to));
  const r = report.data;

  const exportCsv = async (kind: "receipts" | "tally") => {
    if (problem) return;
    setExporting(kind);
    setExportError(null);
    try {
      await downloadCsv(feesExports[kind](from, to), `${kind === "tally" ? "tally" : "receipts"}-${from}-to-${to}.csv`);
    } catch (caught) {
      setExportError(errorMessage(toApiError(caught), t));
    } finally {
      setExporting(null);
    }
  };

  return (
    <section className="card" aria-labelledby="collection-title">
      <div className="card-head">
        <div>
          <h2 id="collection-title">{t("fees.report.title")}</h2>
          <p className="mt-1 text-sm text-ink-2">{t("fees.report.sub")}</p>
        </div>
        <div className="flex flex-wrap gap-2">
          <button
            type="button"
            className="btn btn-sm"
            disabled={Boolean(problem) || exporting !== null}
            onClick={() => exportCsv("receipts")}
          >
            <Download size={16} aria-hidden="true" />
            {exporting === "receipts" ? t("common.working") : t("fees.report.exportReceipts")}
          </button>
          <button
            type="button"
            className="btn btn-sm"
            disabled={Boolean(problem) || exporting !== null}
            onClick={() => exportCsv("tally")}
          >
            <Download size={16} aria-hidden="true" />
            {exporting === "tally" ? t("common.working") : t("fees.report.exportTally")}
          </button>
        </div>
      </div>
      <div className="toolbar">
        <label className="field min-w-[150px] flex-1 sm:flex-none">
          <span className="field-label">{t("fees.report.from")}</span>
          <input className="input" type="date" name="from" value={from} onChange={(e) => setFrom(e.target.value)} />
        </label>
        <label className="field min-w-[150px] flex-1 sm:flex-none">
          <span className="field-label">{t("fees.report.to")}</span>
          <input
            className="input"
            type="date"
            name="to"
            value={to}
            min={from || undefined}
            onChange={(e) => setTo(e.target.value)}
          />
        </label>
      </div>
      {problem ? <p className="field-error">{t(problem)}</p> : null}
      <FormAlert message={exportError} />
      {problem ? null : report.error && !r ? (
        <ErrorState error={report.error} onRetry={report.reload} />
      ) : !r ? (
        <LoadingRows rows={3} />
      ) : (
        <div className="flex flex-col gap-4" aria-busy={report.loading}>
          <p className="text-sm text-ink-2">
            {t("fees.report.summary", {
              amount: formatPaise(r.totalPaise),
              count: r.receiptCount,
              from: formatPlainDate(r.from, locale),
              to: formatPlainDate(r.to, locale),
            })}
            {r.cancelledCount ? ` ${plural(t, "fees.report.cancelled", r.cancelledCount)}` : ""}
          </p>
          {r.receiptCount === 0 ? (
            <p className="empty">{t("fees.report.empty")}</p>
          ) : (
            <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
              <table className="table" data-testid="collection-by-mode">
                <caption className="mb-1 text-left text-[13px] font-semibold text-ink-2">
                  {t("fees.report.byMode")}
                </caption>
                <thead>
                  <tr>
                    <th scope="col">{t("fees.report.mode")}</th>
                    <th scope="col" className="r">
                      {t("fees.report.receipts")}
                    </th>
                    <th scope="col" className="r">
                      {t("fees.report.amount")}
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {r.byMode.map((m) => (
                    <tr key={m.mode}>
                      <td>{modeLabel(m.mode)}</td>
                      <td className="r num">{m.receiptCount}</td>
                      <td className="r num">{formatPaise(m.amountPaise)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              <table className="table" data-testid="collection-by-head">
                <caption className="mb-1 text-left text-[13px] font-semibold text-ink-2">
                  {t("fees.report.byHead")}
                </caption>
                <thead>
                  <tr>
                    <th scope="col">{t("fees.report.head")}</th>
                    <th scope="col" className="r">
                      {t("fees.report.amount")}
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {r.byHead.map((h) => (
                    <tr key={h.headId ?? h.headName}>
                      <td>{h.headName}</td>
                      <td className="r num">{formatPaise(h.amountPaise)}</td>
                    </tr>
                  ))}
                  {r.lateFeePaise ? (
                    <tr>
                      <td>{t("fees.receipt.lateFee")}</td>
                      <td className="r num">{formatPaise(r.lateFeePaise)}</td>
                    </tr>
                  ) : null}
                  {r.advancePaise ? (
                    <tr>
                      <td>{t("fees.receipt.advance")}</td>
                      <td className="r num">{formatPaise(r.advancePaise)}</td>
                    </tr>
                  ) : null}
                </tbody>
              </table>
            </div>
          )}
        </div>
      )}
    </section>
  );
}
