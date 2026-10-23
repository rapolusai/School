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
import { PURCHASE_ORDER_STATUSES, type OrderRow, type PurchaseOrderStatus } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { InventoryNav, OrderPill, useCanManage, useLookups } from "./inventory-ui";
import { OrderFormDialog } from "./order-form";

/** /app/inventory/purchase-orders: every order newest first, by status and vendor, and a new one. */
export function OrdersView({ openNew = false, requisitionId, rfqId }: { openNew?: boolean; requisitionId?: string; rfqId?: string }) {
  const { t, lang } = useI18n();
  const router = useRouter();
  const { toast } = useToast();
  const locale = localeFor(lang);
  const canManage = useCanManage();
  const lookups = useLookups();
  const [status, setStatus] = useState<PurchaseOrderStatus | "">("");
  const [vendorId, setVendorId] = useState("");
  const [creating, setCreating] = useState(openNew && canManage);
  const list = useApiData(`inventory:orders:${status}:${vendorId}`, () => inventoryApi.orders({ status: status || undefined, vendorId: vendorId || undefined }));

  return (
    <>
      <PageHead
        eyebrow={t("inventory.eyebrow")}
        title={t("inventory.po.title")}
        actions={
          canManage ? (
            <button type="button" className="btn btn-primary" onClick={() => setCreating(true)} data-testid="order-new">
              <Plus size={18} aria-hidden="true" />
              {t("inventory.po.new")}
            </button>
          ) : null
        }
      />
      <InventoryNav />
      <section className="card" aria-labelledby="orders-title">
        <div className="card-head">
          <h2 id="orders-title">{t("inventory.po.list")}</h2>
        </div>
        <form method="post" className="toolbar no-print" onSubmit={(e) => e.preventDefault()} aria-label={t("reports.filters")}>
          <label className="field w-full sm:w-auto sm:min-w-44">
            <span className="field-label">{t("inventory.col.status")}</span>
            <select className="input" name="status" value={status} onChange={(e) => setStatus(e.target.value as PurchaseOrderStatus | "")}>
              <option value="">{t("inventory.allStatuses")}</option>
              {PURCHASE_ORDER_STATUSES.map((s) => (
                <option key={s} value={s}>
                  {translateOr(t, `inventory.poStatus.${s}`, s)}
                </option>
              ))}
            </select>
          </label>
          <label className="field w-full sm:w-auto sm:min-w-44">
            <span className="field-label">{t("inventory.col.vendor")}</span>
            <select className="input" name="vendorId" value={vendorId} onChange={(e) => setVendorId(e.target.value)}>
              <option value="">{t("inventory.po.allVendors")}</option>
              {(lookups.data?.vendors ?? []).map((v) => (
                <option key={v.id} value={v.id}>
                  {v.name}
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
          <p className="empty" data-testid="orders-empty">
            {canManage && !status && !vendorId ? t("inventory.po.emptyManage") : t("inventory.po.empty")}
          </p>
        ) : (
          <OrdersTable rows={list.data} locale={locale} testId="orders" />
        )}
      </section>
      {creating ? (
        <OrderFormDialog
          order={null}
          requisitionId={requisitionId}
          rfqId={rfqId}
          onClose={() => setCreating(false)}
          onSaved={(saved, submitted) => {
            setCreating(false);
            toast(submitted ? t("inventory.po.sent", { number: saved.number }) : t("inventory.po.savedDraft", { number: saved.number }));
            router.push(`/app/inventory/purchase-orders/${saved.id}`);
          }}
        />
      ) : null}
    </>
  );
}

export function OrdersTable({ rows, locale, testId, action }: { rows: OrderRow[]; locale: string; testId: string; action?: (row: OrderRow) => React.ReactNode }) {
  const { t } = useI18n();
  return (
    <>
      <div className="table-wrap hidden md:block">
        <table className="table" data-testid={`${testId}-table`}>
          <thead>
            <tr>
              <th scope="col">{t("inventory.col.number")}</th>
              <th scope="col">{t("inventory.col.vendor")}</th>
              <th scope="col">{t("inventory.col.date")}</th>
              <th scope="col">{t("inventory.col.expected")}</th>
              <th scope="col" className="r">
                {t("inventory.col.total")}
              </th>
              <th scope="col">{t("inventory.col.received")}</th>
              <th scope="col">{t("inventory.col.status")}</th>
              {action ? <th scope="col" className="r">{t("common.actions")}</th> : null}
            </tr>
          </thead>
          <tbody>
            {rows.map((o) => (
              <tr key={o.id} data-testid={`${testId}-row`}>
                <td className="mono whitespace-nowrap">
                  <Link href={`/app/inventory/purchase-orders/${o.id}`} className="link font-semibold">
                    {o.number}
                  </Link>
                  {o.requisitionNumber ? <span className="block text-[12px] text-ink-3">{o.requisitionNumber}</span> : null}
                </td>
                <td>{o.vendorName}</td>
                <td className="whitespace-nowrap">{formatPlainDate(o.orderedOn, locale)}</td>
                <td className="whitespace-nowrap">{o.expectedOn ? formatPlainDate(o.expectedOn, locale) : "—"}</td>
                <td className="r num font-semibold">{formatPaise(o.totalPaise)}</td>
                <td className="num">
                  {o.receivedLines} / {o.lines}
                </td>
                <td>
                  <OrderPill status={o.status} />
                </td>
                {action ? <td className="r">{action(o)}</td> : null}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <ul className="rowcards md:hidden" data-testid={`${testId}-cards`}>
        {rows.map((o) => (
          <li key={o.id} data-testid={`${testId}-card`}>
            <Link href={`/app/inventory/purchase-orders/${o.id}`} className="rowcard">
              <span className="min-w-0 flex-1">
                <b className="block truncate font-semibold">
                  <span className="mono">{o.number}</span> · {o.vendorName}
                </b>
                <span className="block truncate text-[12.5px] text-ink-3">
                  {formatPlainDate(o.orderedOn, locale)} · {o.receivedLines}/{o.lines}
                </span>
              </span>
              <span className="flex flex-col items-end gap-1">
                <span className="num font-semibold">{formatPaise(o.totalPaise)}</span>
                <OrderPill status={o.status} />
              </span>
            </Link>
            {action ? <div className="flex justify-end gap-2 px-1 pb-2">{action(o)}</div> : null}
          </li>
        ))}
      </ul>
    </>
  );
}
