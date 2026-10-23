"use client";

import { ArrowRight } from "lucide-react";
import Link from "next/link";
import { ErrorState, LoadingRows, PageHead } from "@/components/ui/states";
import { useAuth } from "@/lib/auth";
import { formatPaise, formatPlainDate } from "@/lib/format";
import { localeFor, plural, useI18n } from "@/lib/i18n";
import { inventoryApi } from "@/lib/inventory-api";
import { hasPermission, PERMISSIONS } from "@/lib/permissions";
import { useApiData } from "@/lib/use-api-data";
import { InventoryNav, OrderPill, RequisitionPill, Stat, useItems } from "./inventory-ui";

/**
 * /app/inventory: the stores at a glance. Readers see their own requisitions and the items; the storekeeper also
 * sees low stock, orders waiting and open verification rounds; approvers see what waits for them.
 */
export function InventoryOverview() {
  const { t, lang } = useI18n();
  const { me } = useAuth();
  const locale = localeFor(lang);
  const canRead = hasPermission(me, PERMISSIONS.inventoryRead);
  const canManage = hasPermission(me, PERMISSIONS.inventoryManage);
  const canApprove = hasPermission(me, PERMISSIONS.purchaseApprove);
  const items = useItems();
  const requisitions = useApiData(canRead ? "inventory:requisitions:" : null, () => inventoryApi.requisitions());
  const orders = useApiData(canRead ? "inventory:orders::" : null, () => inventoryApi.orders());
  const rounds = useApiData(canManage ? "inventory:rounds" : null, inventoryApi.rounds);
  const queue = useApiData(canApprove ? "inventory:queue" : null, inventoryApi.queue);

  const loading = (canRead && (!items.data || !requisitions.data || !orders.data)) || (canManage && !rounds.data) || (canApprove && !queue.data);
  const error = items.error ?? requisitions.error ?? orders.error ?? rounds.error ?? queue.error;
  const low = (items.data ?? []).filter((i) => i.active && i.low);
  const waiting = (requisitions.data ?? []).filter((r) => r.status === "SUBMITTED" || r.status === "HEAD_APPROVED");
  const openOrders = (orders.data ?? []).filter((o) => o.status === "APPROVED" || o.status === "PARTIAL");
  const submittedOrders = (orders.data ?? []).filter((o) => o.status === "SUBMITTED");
  const openRounds = (rounds.data ?? []).filter((r) => r.status === "OPEN");
  const recent = (requisitions.data ?? []).slice(0, 6);
  const recentOrders = (orders.data ?? []).slice(0, 6);

  return (
    <>
      <PageHead
        eyebrow={t("inventory.eyebrow")}
        title={t("inventory.overview.title")}
        actions={
          canRead ? (
            <Link href="/app/inventory/requisitions?new=1" className="btn btn-primary" data-testid="overview-new-requisition">
              {t("inventory.req.new")}
            </Link>
          ) : null
        }
      />
      <InventoryNav />
      {error && loading ? (
        <ErrorState
          error={error}
          onRetry={() => {
            items.reload();
            requisitions.reload();
            orders.reload();
            rounds.reload();
            queue.reload();
          }}
        />
      ) : loading ? (
        <LoadingRows rows={4} />
      ) : (
        <ul className="rep-kpis" data-testid="overview-figures">
          <Stat label={t("inventory.overview.items")} value={String((items.data ?? []).filter((i) => i.active).length)} href="/app/inventory/items" testId="overview-items" />
          {canManage ? (
            <Stat label={t("inventory.overview.lowStock")} value={String(low.length)} attention={low.length > 0} tone={low.length > 0 ? "bad" : undefined} href="/app/inventory/items?filter=low" testId="overview-low" />
          ) : null}
          <Stat label={t("inventory.overview.reqWaiting")} value={String(waiting.length)} attention={waiting.length > 0} href="/app/inventory/requisitions" testId="overview-req-waiting" />
          {canApprove && queue.data ? (
            <Stat
              label={t("inventory.overview.forMe")}
              value={String(queue.data.requisitions.length + queue.data.orders.length)}
              sub={queue.data.limited ? t("inventory.approvals.myLimit", { limit: formatPaise(queue.data.limitPaise ?? 0) }) : t("inventory.approvals.noLimit")}
              attention={queue.data.requisitions.length + queue.data.orders.length > 0}
              href="/app/inventory/approvals"
              testId="overview-queue"
            />
          ) : null}
          <Stat label={t("inventory.overview.ordersOpen")} value={String(openOrders.length)} sub={plural(t, "inventory.overview.ordersSubmitted", submittedOrders.length)} href="/app/inventory/purchase-orders" testId="overview-orders" />
          {canManage ? (
            <Stat label={t("inventory.overview.rounds")} value={String(openRounds.length)} attention={openRounds.length > 0} href="/app/inventory/verification" testId="overview-rounds" />
          ) : null}
        </ul>
      )}

      <div className="grid gap-3.5 lg:grid-cols-2">
        <section className="card" aria-labelledby="overview-reqs">
          <div className="card-head">
            <h2 id="overview-reqs">{t("inventory.overview.recentReqs")}</h2>
            <Link href="/app/inventory/requisitions" className="btn btn-sm">
              {t("inventory.viewAll")}
              <ArrowRight size={16} aria-hidden="true" />
            </Link>
          </div>
          {!requisitions.data ? (
            <LoadingRows rows={3} />
          ) : recent.length === 0 ? (
            <p className="empty">{t("inventory.req.empty")}</p>
          ) : (
            <ul className="rowcards" data-testid="overview-req-list">
              {recent.map((r) => (
                <li key={r.id}>
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
                </li>
              ))}
            </ul>
          )}
        </section>
        <section className="card" aria-labelledby="overview-orders-title">
          <div className="card-head">
            <h2 id="overview-orders-title">{t("inventory.overview.recentOrders")}</h2>
            <Link href="/app/inventory/purchase-orders" className="btn btn-sm">
              {t("inventory.viewAll")}
              <ArrowRight size={16} aria-hidden="true" />
            </Link>
          </div>
          {!orders.data ? (
            <LoadingRows rows={3} />
          ) : recentOrders.length === 0 ? (
            <p className="empty">{t("inventory.po.empty")}</p>
          ) : (
            <ul className="rowcards" data-testid="overview-order-list">
              {recentOrders.map((o) => (
                <li key={o.id}>
                  <Link href={`/app/inventory/purchase-orders/${o.id}`} className="rowcard">
                    <span className="min-w-0 flex-1">
                      <b className="block truncate font-semibold">
                        <span className="mono">{o.number}</span> · {o.vendorName}
                      </b>
                      <span className="block truncate text-[12.5px] text-ink-3">{formatPlainDate(o.orderedOn, locale)}</span>
                    </span>
                    <span className="flex flex-col items-end gap-1">
                      <span className="num font-semibold">{formatPaise(o.totalPaise)}</span>
                      <OrderPill status={o.status} />
                    </span>
                  </Link>
                </li>
              ))}
            </ul>
          )}
        </section>
      </div>
    </>
  );
}
