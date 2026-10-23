"use client";

import { Plus } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { ErrorState, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { formatPaise, formatPlainDate } from "@/lib/format";
import { localeFor, translateOr, useI18n } from "@/lib/i18n";
import { inventoryApi } from "@/lib/inventory-api";
import { REQUISITION_STATUSES, type RequisitionStatus, type RequisitionView } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { InventoryNav, RequisitionPill } from "./inventory-ui";
import { RequisitionFormDialog } from "./requisition-form";

/** /app/inventory/requisitions: the requisitions the person may see, newest first, and a new one. */
export function RequisitionsView({ openNew = false }: { openNew?: boolean }) {
  const { t, lang } = useI18n();
  const router = useRouter();
  const { toast } = useToast();
  const locale = localeFor(lang);
  const [status, setStatus] = useState<RequisitionStatus | "">("");
  const [creating, setCreating] = useState(openNew);
  const list = useApiData(`inventory:requisitions:${status}`, () => inventoryApi.requisitions({ status: status || undefined }));

  return (
    <>
      <PageHead
        eyebrow={t("inventory.eyebrow")}
        title={t("inventory.req.title")}
        actions={
          <button type="button" className="btn btn-primary" onClick={() => setCreating(true)} data-testid="requisition-new">
            <Plus size={18} aria-hidden="true" />
            {t("inventory.req.new")}
          </button>
        }
      />
      <InventoryNav />
      <section className="card" aria-labelledby="req-title">
        <div className="card-head">
          <h2 id="req-title">{t("inventory.req.list")}</h2>
        </div>
        <form method="post" className="toolbar no-print" onSubmit={(e) => e.preventDefault()} aria-label={t("reports.filters")}>
          <label className="field w-full sm:w-auto sm:min-w-44">
            <span className="field-label">{t("inventory.col.status")}</span>
            <select className="input" name="status" value={status} onChange={(e) => setStatus(e.target.value as RequisitionStatus | "")}>
              <option value="">{t("inventory.allStatuses")}</option>
              {REQUISITION_STATUSES.map((s) => (
                <option key={s} value={s}>
                  {translateOr(t, `inventory.reqStatus.${s}`, s)}
                </option>
              ))}
            </select>
          </label>
        </form>
        {list.error && !list.data ? (
          <ErrorState error={list.error} onRetry={list.reload} />
        ) : !list.data ? (
          <LoadingRows rows={5} />
        ) : list.data.length === 0 ? (
          <p className="empty" data-testid="requisitions-empty">
            {t("inventory.req.empty")}
          </p>
        ) : (
          <RequisitionsTable rows={list.data} locale={locale} testId="requisitions" />
        )}
      </section>
      {creating ? (
        <RequisitionFormDialog
          requisition={null}
          onClose={() => setCreating(false)}
          onSaved={(saved, submitted) => {
            setCreating(false);
            toast(submitted ? t("inventory.req.sent", { number: saved.number ?? "" }) : t("inventory.req.savedDraft"));
            router.push(`/app/inventory/requisitions/${saved.id}`);
          }}
        />
      ) : null}
    </>
  );
}

export function RequisitionsTable({ rows, locale, testId, action }: { rows: RequisitionView[]; locale: string; testId: string; action?: (row: RequisitionView) => React.ReactNode }) {
  const { t } = useI18n();
  return (
    <>
      <div className="table-wrap hidden md:block">
        <table className="table" data-testid={`${testId}-table`}>
          <thead>
            <tr>
              <th scope="col">{t("inventory.col.number")}</th>
              <th scope="col">{t("inventory.col.purpose")}</th>
              <th scope="col">{t("inventory.col.department")}</th>
              <th scope="col">{t("inventory.col.requestedBy")}</th>
              <th scope="col">{t("inventory.col.neededBy")}</th>
              <th scope="col" className="r">
                {t("inventory.req.estimated")}
              </th>
              <th scope="col">{t("inventory.col.status")}</th>
              {action ? <th scope="col" className="r">{t("common.actions")}</th> : null}
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.id} data-testid={`${testId}-row`}>
                <td className="mono whitespace-nowrap">{r.number ?? "—"}</td>
                <td>
                  <Link href={`/app/inventory/requisitions/${r.id}`} className="link font-semibold">
                    {r.purpose}
                  </Link>
                  <span className="block text-[12.5px] text-ink-3">{t("inventory.req.lineCount", { count: r.lines.length })}</span>
                </td>
                <td>{r.departmentName ?? "—"}</td>
                <td>{r.requestedByName}</td>
                <td className="whitespace-nowrap">{r.neededBy ? formatPlainDate(r.neededBy, locale) : "—"}</td>
                <td className="r num font-semibold">{formatPaise(r.estimatedPaise)}</td>
                <td>
                  <RequisitionPill status={r.status} />
                </td>
                {action ? <td className="r">{action(r)}</td> : null}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <ul className="rowcards md:hidden" data-testid={`${testId}-cards`}>
        {rows.map((r) => (
          <li key={r.id} data-testid={`${testId}-card`}>
            <Link href={`/app/inventory/requisitions/${r.id}`} className="rowcard">
              <span className="min-w-0 flex-1">
                <b className="block truncate font-semibold">{r.purpose}</b>
                <span className="block truncate text-[12.5px] text-ink-3">
                  {r.number ?? t("inventory.req.draft")} · {r.requestedByName} · {r.departmentName ?? t("inventory.none")}
                </span>
              </span>
              <span className="flex flex-col items-end gap-1">
                <span className="num font-semibold">{formatPaise(r.estimatedPaise)}</span>
                <RequisitionPill status={r.status} />
              </span>
            </Link>
            {action ? <div className="flex justify-end gap-2 px-1 pb-2">{action(r)}</div> : null}
          </li>
        ))}
      </ul>
    </>
  );
}
