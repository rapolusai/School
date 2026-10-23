"use client";

import { Check, X } from "lucide-react";
import { useState } from "react";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { toApiError } from "@/lib/api";
import { errorMessage } from "@/lib/error-message";
import { formatPaise } from "@/lib/format";
import { localeFor, plural, useI18n } from "@/lib/i18n";
import { inventoryApi } from "@/lib/inventory-api";
import type { OrderRow, RequisitionView } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { InventoryNav, ReasonDialog } from "./inventory-ui";
import { OrdersTable } from "./orders-view";
import { RequisitionsTable } from "./requisitions-view";

type Target = { kind: "requisition"; id: string } | { kind: "order"; id: string };

/**
 * /app/inventory/approvals: the requisitions and purchase orders waiting for the signed-in approver (or for their
 * department head's word), each with Approve (or Pass on, above their limit) and Reject.
 */
export function InventoryApprovalsView() {
  const { t, lang } = useI18n();
  const { toast } = useToast();
  const locale = localeFor(lang);
  const queue = useApiData("inventory:queue", inventoryApi.queue);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [rejecting, setRejecting] = useState<Target | null>(null);
  const data = queue.data;

  const aboveLimit = (paise: number) => Boolean(data && data.limited && paise > (data.limitPaise ?? 0));

  const approve = async (target: Target) => {
    setBusy(target.id);
    setError(null);
    try {
      if (target.kind === "requisition") {
        const r = data?.requisitions.find((x) => x.id === target.id);
        const saved = r?.status === "SUBMITTED" ? await inventoryApi.headApprove(target.id, { note: null }) : await inventoryApi.approveRequisition(target.id, { note: null });
        toast(saved.status === "APPROVED" ? t("inventory.req.approved") : r?.status === "SUBMITTED" ? t("inventory.req.headApproved") : t("inventory.passedOn"));
      } else {
        const saved = await inventoryApi.approveOrder(target.id, { note: null });
        toast(saved.status === "APPROVED" ? t("inventory.po.approved") : t("inventory.passedOn"));
      }
      queue.reload();
    } catch (caught) {
      setError(errorMessage(toApiError(caught), t));
    } finally {
      setBusy(null);
    }
  };

  const buttons = (target: Target, paise: number, headStage = false) => (
    <>
      <button type="button" className="btn btn-sm btn-primary" disabled={busy === target.id} onClick={() => void approve(target)} data-testid={`approve-${target.id}`}>
        <Check size={16} aria-hidden="true" />
        {!headStage && aboveLimit(paise) ? t("inventory.passOn") : t("inventory.approve")}
      </button>
      <button type="button" className="btn btn-sm" disabled={busy === target.id} onClick={() => setRejecting(target)} data-testid={`reject-${target.id}`}>
        <X size={16} aria-hidden="true" />
        {t("inventory.reject")}
      </button>
    </>
  );

  return (
    <>
      <PageHead eyebrow={t("inventory.eyebrow")} title={t("inventory.approvals.title")} />
      <InventoryNav />
      <FormAlert message={error} />
      {queue.error && !data ? (
        <ErrorState error={queue.error} onRetry={queue.reload} />
      ) : !data ? (
        <LoadingRows rows={4} />
      ) : (
        <>
          <p className="text-sm text-ink-2" data-testid="queue-limit">
            {data.limited ? t("inventory.approvals.myLimit", { limit: formatPaise(data.limitPaise ?? 0) }) : t("inventory.approvals.noLimit")}
          </p>
          <section className="card" aria-labelledby="queue-reqs">
            <div className="card-head">
              <h2 id="queue-reqs">{plural(t, "inventory.approvals.reqCount", data.requisitions.length)}</h2>
            </div>
            {data.requisitions.length === 0 ? (
              <p className="empty" data-testid="queue-reqs-empty">
                {t("inventory.approvals.empty")}
              </p>
            ) : (
              <RequisitionsTable rows={data.requisitions} locale={locale} testId="queue-req" action={(r: RequisitionView) => (r.canAct ? buttons({ kind: "requisition", id: r.id }, r.estimatedPaise, r.status === "SUBMITTED") : <span className="text-[12.5px] text-ink-3">{t("inventory.approvals.someoneElse")}</span>)} />
            )}
          </section>
          <section className="card" aria-labelledby="queue-orders">
            <div className="card-head">
              <h2 id="queue-orders">{plural(t, "inventory.approvals.orderCount", data.orders.length)}</h2>
            </div>
            {data.orders.length === 0 ? (
              <p className="empty" data-testid="queue-orders-empty">
                {t("inventory.approvals.empty")}
              </p>
            ) : (
              <OrdersTable rows={data.orders} locale={locale} testId="queue-order" action={(o: OrderRow) => buttons({ kind: "order", id: o.id }, o.totalPaise)} />
            )}
          </section>
        </>
      )}
      <ReasonDialog
        open={rejecting !== null}
        title={t("inventory.reject")}
        label={t("inventory.col.reason")}
        confirmLabel={t("inventory.reject")}
        testId="queue-reject-form"
        onClose={() => setRejecting(null)}
        onConfirm={async (reason) => {
          if (!rejecting) return;
          if (rejecting.kind === "requisition") {
            const r = data?.requisitions.find((x) => x.id === rejecting.id);
            await (r?.status === "SUBMITTED" ? inventoryApi.headReject(rejecting.id, { reason }) : inventoryApi.rejectRequisition(rejecting.id, { reason }));
          } else {
            await inventoryApi.rejectOrder(rejecting.id, { reason });
          }
          setRejecting(null);
          toast(t("inventory.rejected"));
          queue.reload();
        }}
      />
    </>
  );
}
