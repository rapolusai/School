"use client";

import { ArrowLeft, ChevronLeft, ChevronRight, Download, Search, XCircle } from "lucide-react";
import Link from "next/link";
import { useRef, useState } from "react";
import { Dialog } from "@/components/ui/dialog";
import { TextAreaField } from "@/components/ui/field";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { toApiError } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { errorMessage } from "@/lib/error-message";
import { downloadCsv, feesApi, feesExports } from "@/lib/fees-api";
import { formatPaise, formatPlainDate, todayInIndia } from "@/lib/format";
import { localeFor, useI18n } from "@/lib/i18n";
import { hasPermission, PERMISSIONS } from "@/lib/permissions";
import { PAYMENT_MODES, type PaymentMode, type Receipt, type ReceiptQuery, type ReceiptStatus } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { useForm, type Problems } from "@/lib/use-form";
import { FeesNav, ReceiptStatusPill, useModeLabel } from "./fee-ui";
import { rangeProblem } from "./overview-view";
import { PrintButton, ReceiptDocument } from "./receipt-document";

export const RECEIPTS_PAGE_SIZE = 25;

type Filters = { from: string; to: string; mode: "" | PaymentMode; status: "" | ReceiptStatus };

/** The API query for the filters. Exported for tests. */
export function toReceiptQuery(filters: Filters, search: string, page: number): ReceiptQuery {
  return {
    from: filters.from || undefined,
    to: filters.to || undefined,
    mode: filters.mode || undefined,
    status: filters.status || undefined,
    q: search.trim() || undefined,
    page,
    size: RECEIPTS_PAGE_SIZE,
  };
}

/** /app/fees/receipts: every receipt, newest first, with filters and the register export. */
export function ReceiptsView() {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const modeLabel = useModeLabel();
  const today = todayInIndia();
  const [filters, setFilters] = useState<Filters>({ from: "", to: "", mode: "", status: "" });
  const [searchText, setSearchText] = useState("");
  const [search, setSearch] = useState("");
  const [page, setPage] = useState(0);
  const [exportError, setExportError] = useState<string | null>(null);
  const [exporting, setExporting] = useState(false);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const query = toReceiptQuery(filters, search, page);
  const receipts = useApiData(`fees:receipts:${JSON.stringify(query)}`, () => feesApi.listReceipts(query));
  const data = receipts.data;
  const rows = data?.items ?? [];

  const setFilter = <K extends keyof Filters>(key: K, value: Filters[K]) => {
    setFilters((prev) => ({ ...prev, [key]: value }));
    setPage(0);
  };
  const onSearch = (value: string) => {
    setSearchText(value);
    if (timer.current) clearTimeout(timer.current);
    timer.current = setTimeout(() => {
      setSearch(value);
      setPage(0);
    }, 250);
  };

  // The register export needs a range: the filter's, or this month so far.
  const exportFrom = filters.from || `${today.slice(0, 8)}01`;
  const exportTo = filters.to || today;
  const exportProblem = rangeProblem(exportFrom, exportTo);
  const exportCsv = async () => {
    setExporting(true);
    setExportError(null);
    try {
      await downloadCsv(feesExports.receipts(exportFrom, exportTo), `receipts-${exportFrom}-to-${exportTo}.csv`);
    } catch (caught) {
      setExportError(errorMessage(toApiError(caught), t));
    } finally {
      setExporting(false);
    }
  };

  const first = data && data.total > 0 ? data.page * data.size + 1 : 0;
  const last = data ? Math.min(data.total, data.page * data.size + rows.length) : 0;
  const lastPage = data ? Math.max(0, Math.ceil(data.total / data.size) - 1) : 0;
  const filtered = Boolean(search.trim() || filters.from || filters.to || filters.mode || filters.status);

  return (
    <>
      <PageHead
        eyebrow={t("fees.eyebrow")}
        title={t("fees.receipts.title")}
        actions={
          <button type="button" className="btn" onClick={exportCsv} disabled={exporting || Boolean(exportProblem)}>
            <Download size={18} aria-hidden="true" />
            {exporting ? t("common.working") : t("fees.receipts.export")}
          </button>
        }
      />
      <FeesNav />
      <FormAlert message={exportError} />

      <section className="card">
        <div className="toolbar">
          <label className="search">
            <Search size={18} aria-hidden="true" />
            <span className="sr-only">{t("fees.receipts.search")}</span>
            <input
              type="search"
              name="q"
              value={searchText}
              onChange={(e) => onSearch(e.target.value)}
              placeholder={t("fees.receipts.search")}
              maxLength={100}
            />
          </label>
          <label className="min-w-[140px] flex-1 sm:flex-none">
            <span className="sr-only">{t("fees.report.from")}</span>
            <input
              className="input"
              type="date"
              value={filters.from}
              max={filters.to || undefined}
              onChange={(e) => setFilter("from", e.target.value)}
              aria-label={t("fees.report.from")}
            />
          </label>
          <label className="min-w-[140px] flex-1 sm:flex-none">
            <span className="sr-only">{t("fees.report.to")}</span>
            <input
              className="input"
              type="date"
              value={filters.to}
              min={filters.from || undefined}
              onChange={(e) => setFilter("to", e.target.value)}
              aria-label={t("fees.report.to")}
            />
          </label>
          <label className="min-w-[130px] flex-1 sm:flex-none">
            <span className="sr-only">{t("fees.report.mode")}</span>
            <select
              className="input"
              value={filters.mode}
              onChange={(e) => setFilter("mode", e.target.value as Filters["mode"])}
              aria-label={t("fees.report.mode")}
            >
              <option value="">{t("fees.receipts.allModes")}</option>
              {PAYMENT_MODES.map((mode) => (
                <option key={mode} value={mode}>
                  {modeLabel(mode)}
                </option>
              ))}
            </select>
          </label>
          <label className="min-w-[130px] flex-1 sm:flex-none">
            <span className="sr-only">{t("fees.receipts.status")}</span>
            <select
              className="input"
              value={filters.status}
              onChange={(e) => setFilter("status", e.target.value as Filters["status"])}
              aria-label={t("fees.receipts.status")}
            >
              <option value="">{t("fees.receipts.allStatuses")}</option>
              <option value="ISSUED">{t("fees.receiptStatus.ISSUED")}</option>
              <option value="CANCELLED">{t("fees.receiptStatus.CANCELLED")}</option>
            </select>
          </label>
        </div>

        {receipts.error && !data ? (
          <ErrorState error={receipts.error} onRetry={receipts.reload} />
        ) : !data ? (
          <LoadingRows rows={6} />
        ) : rows.length === 0 ? (
          <p className="empty" data-testid="receipts-empty">
            {filtered ? t("fees.receipts.noMatch") : t("fees.receipts.empty")}
          </p>
        ) : (
          <div aria-busy={receipts.loading}>
            <div className="table-wrap hidden md:block">
              <table className="table" data-testid="receipts-table">
                <thead>
                  <tr>
                    <th scope="col">{t("fees.receipt.number")}</th>
                    <th scope="col">{t("fees.receipt.date")}</th>
                    <th scope="col">{t("fees.receipt.student")}</th>
                    <th scope="col">{t("fees.report.mode")}</th>
                    <th scope="col">{t("fees.receipts.status")}</th>
                    <th scope="col" className="r">
                      {t("fees.receipt.amount")}
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((r) => (
                    <tr key={r.id}>
                      <td>
                        <Link href={`/app/fees/receipts/${r.id}`} className="link mono whitespace-nowrap">
                          {r.receiptNo}
                        </Link>
                      </td>
                      <td className="whitespace-nowrap">{formatPlainDate(r.receivedOn, locale)}</td>
                      <td>
                        <span className="block leading-tight">
                          {r.studentName}
                          <span className="block text-[12.5px] text-ink-3">
                            {[r.classLabel, r.admissionNo].filter(Boolean).join(" · ")}
                          </span>
                        </span>
                      </td>
                      <td>{modeLabel(r.mode)}</td>
                      <td>
                        <ReceiptStatusPill status={r.status} />
                      </td>
                      <td className={`r num font-semibold${r.status === "CANCELLED" ? " text-ink-3 line-through" : ""}`}>
                        {formatPaise(r.amountPaise)}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <ul className="rowcards md:hidden" data-testid="receipts-cards">
              {rows.map((r) => (
                <li key={r.id}>
                  <Link href={`/app/fees/receipts/${r.id}`} className="rowcard">
                    <span className="min-w-0 flex-1">
                      <b className="block truncate font-semibold">{r.studentName}</b>
                      <span className="mono block truncate text-[12.5px] text-ink-3">{r.receiptNo}</span>
                      <span className="block truncate text-[12.5px] text-ink-3">
                        {formatPlainDate(r.receivedOn, locale)} · {modeLabel(r.mode)}
                      </span>
                    </span>
                    <span className="flex flex-col items-end gap-1">
                      <span className={`num font-semibold${r.status === "CANCELLED" ? " text-ink-3 line-through" : ""}`}>
                        {formatPaise(r.amountPaise)}
                      </span>
                      {r.status === "CANCELLED" ? <ReceiptStatusPill status={r.status} /> : null}
                    </span>
                  </Link>
                </li>
              ))}
            </ul>
            <div className="pager">
              <span>
                {t("fees.receipts.showing", { first, last, total: data.total })} ·{" "}
                {t("fees.receipts.totalIssued", { amount: formatPaise(data.totalPaise) })}
              </span>
              {data.total > data.size ? (
                <div className="flex gap-2">
                  <button
                    type="button"
                    className="btn btn-sm"
                    onClick={() => setPage((p) => Math.max(0, p - 1))}
                    disabled={page === 0}
                  >
                    <ChevronLeft size={16} aria-hidden="true" />
                    {t("common.previous")}
                  </button>
                  <button
                    type="button"
                    className="btn btn-sm"
                    onClick={() => setPage((p) => Math.min(lastPage, p + 1))}
                    disabled={page >= lastPage}
                  >
                    {t("common.next")}
                    <ChevronRight size={16} aria-hidden="true" />
                  </button>
                </div>
              ) : null}
            </div>
          </div>
        )}
      </section>
    </>
  );
}

/**
 * One receipt for staff: the printable receipt with Print and, for fees.manage, Cancel (which
 * keeps the receipt and reverses what it paid).
 */
export function StaffReceiptView({ id }: { id: string }) {
  const { t } = useI18n();
  const { me } = useAuth();
  const { toast } = useToast();
  const canManage = hasPermission(me, PERMISSIONS.feesManage);
  const receipt = useApiData(`fees:receipt:${id}`, () => feesApi.getReceipt(id));
  const [cancelling, setCancelling] = useState(false);
  const r = receipt.data;

  const back = (
    <Link href="/app/fees/receipts" className="link no-print inline-flex items-center gap-1 text-[13.5px]">
      <ArrowLeft size={16} aria-hidden="true" />
      {t("fees.receipts.back")}
    </Link>
  );

  if (receipt.error && !r) {
    return (
      <>
        {back}
        <section className="card">
          {receipt.error.status === 404 ? (
            <p className="empty">{t("fees.receipt.notFound")}</p>
          ) : (
            <ErrorState error={receipt.error} onRetry={receipt.reload} />
          )}
        </section>
      </>
    );
  }
  if (!r) {
    return (
      <>
        {back}
        <section className="card">
          <LoadingRows rows={6} />
        </section>
      </>
    );
  }

  return (
    <>
      {back}
      <div className="page-head no-print">
        <div className="min-w-0">
          <p className="eyebrow">{t("fees.eyebrow")}</p>
          <h1 className="mt-1">{t("fees.receipt.titleNo", { number: r.receiptNo })}</h1>
        </div>
        <div className="flex flex-wrap gap-2">
          <PrintButton />
          {canManage && r.status === "ISSUED" ? (
            <button type="button" className="btn" onClick={() => setCancelling(true)}>
              <XCircle size={18} aria-hidden="true" />
              {t("fees.cancel.button")}
            </button>
          ) : null}
        </div>
      </div>
      <section className="card">
        <ReceiptDocument receipt={r} />
      </section>
      {cancelling ? (
        <CancelReceiptDialog
          receipt={r}
          onClose={() => setCancelling(false)}
          onDone={() => {
            setCancelling(false);
            receipt.reload();
            toast(t("fees.cancel.done", { number: r.receiptNo }));
          }}
        />
      ) : null}
    </>
  );
}

function CancelReceiptDialog({
  receipt,
  onClose,
  onDone,
}: {
  receipt: Receipt;
  onClose: () => void;
  onDone: () => void;
}) {
  const { t } = useI18n();
  const form = useForm({ reason: "" });
  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const reason = form.values.reason.trim();
    const problems: Problems = {};
    if (!reason) problems.reason = "validation.required";
    else if (reason.length > 500) problems.reason = "validation.tooLong";
    if (!form.check(problems, t, event.currentTarget)) return;
    const ok = await form.submit(t, async () => {
      await feesApi.cancelReceipt(receipt.id, reason);
    });
    if (ok) onDone();
  };
  return (
    <Dialog
      open
      onClose={onClose}
      title={t("fees.cancel.title", { number: receipt.receiptNo })}
      description={t("fees.cancel.description", { amount: formatPaise(receipt.amountPaise) })}
      closeLabel={t("common.close")}
    >
      <form method="post" className="flex flex-col gap-3.5" onSubmit={onSubmit} noValidate>
        <FormAlert message={form.formError} />
        <TextAreaField
          label={t("fees.reason")}
          name="reason"
          value={form.values.reason}
          maxLength={500}
          placeholder={t("fees.cancel.placeholder")}
          onChange={(e) => form.set("reason", e.target.value)}
          error={form.errors.reason}
          required
          data-autofocus
        />
        <div className="flex justify-end gap-2">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-danger" disabled={form.submitting}>
            {form.submitting ? t("common.working") : t("fees.cancel.submit")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}
