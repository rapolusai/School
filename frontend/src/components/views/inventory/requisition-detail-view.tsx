"use client";

import { ArrowLeft, Check, Pencil, Send, Trash2, X } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { toApiError } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { errorMessage } from "@/lib/error-message";
import { formatDateTime, formatPaise, formatPlainDate } from "@/lib/format";
import { localeFor, translateOr, useI18n } from "@/lib/i18n";
import { inventoryApi } from "@/lib/inventory-api";
import type { PurchaseApprovalView, RequisitionView } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { InventoryNav, ReasonDialog, RequisitionPill, useCanManage } from "./inventory-ui";
import { RequisitionFormDialog } from "./requisition-form";

/**
 * /app/inventory/requisitions/{id}: the lines and the approval trail; the requester edits, sends or deletes a
 * draft, the department head and then a purchase approver settle it, and the storekeeper raises an RFQ or order.
 */
export function RequisitionDetailView({ id }: { id: string }) {
  const { t, lang } = useI18n();
  const { me } = useAuth();
  const router = useRouter();
  const { toast } = useToast();
  const locale = localeFor(lang);
  const canManage = useCanManage();
  const detail = useApiData(`inventory:requisition:${id}`, () => inventoryApi.requisition(id));
  const [editing, setEditing] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [rejecting, setRejecting] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const r = detail.data;
  const mine = Boolean(r && me && r.requestedById === me.id);
  const draft = r?.status === "DRAFT";
  const headStage = r?.status === "SUBMITTED";

  const run = async (action: () => Promise<RequisitionView>, message: (saved: RequisitionView) => string) => {
    setBusy(true);
    setError(null);
    try {
      const saved = await action();
      toast(message(saved));
      detail.reload();
    } catch (caught) {
      setError(errorMessage(toApiError(caught), t));
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <PageHead
        eyebrow={t("inventory.eyebrow")}
        title={r ? (r.number ?? t("inventory.req.draft")) : t("inventory.req.single")}
        actions={
          <>
            <Link href="/app/inventory/requisitions" className="btn">
              <ArrowLeft size={18} aria-hidden="true" />
              {t("common.back")}
            </Link>
            {r && draft && (mine || canManage) ? (
              <>
                <button type="button" className="btn" onClick={() => setEditing(true)} data-testid="requisition-edit">
                  <Pencil size={18} aria-hidden="true" />
                  {t("common.edit")}
                </button>
                <button type="button" className="btn" onClick={() => setDeleting(true)} data-testid="requisition-delete">
                  <Trash2 size={18} aria-hidden="true" />
                  {t("common.delete")}
                </button>
                <button type="button" className="btn btn-primary" disabled={busy} onClick={() => void run(() => inventoryApi.submitRequisition(id), (s) => t("inventory.req.sent", { number: s.number ?? "" }))} data-testid="requisition-send">
                  <Send size={18} aria-hidden="true" />
                  {t("inventory.sendForApproval")}
                </button>
              </>
            ) : null}
            {r?.canAct ? (
              <>
                <button
                  type="button"
                  className="btn btn-primary"
                  disabled={busy}
                  onClick={() =>
                    void run(
                      () => (headStage ? inventoryApi.headApprove(id, { note: null }) : inventoryApi.approveRequisition(id, { note: null })),
                      (s) => (s.status === "APPROVED" ? t("inventory.req.approved") : s.status === "HEAD_APPROVED" && !headStage ? t("inventory.passedOn") : t("inventory.req.headApproved")),
                    )
                  }
                  data-testid="requisition-approve"
                >
                  <Check size={18} aria-hidden="true" />
                  {t("inventory.approve")}
                </button>
                <button type="button" className="btn" disabled={busy} onClick={() => setRejecting(true)} data-testid="requisition-reject">
                  <X size={18} aria-hidden="true" />
                  {t("inventory.reject")}
                </button>
              </>
            ) : null}
            {r && canManage && r.status === "APPROVED" ? (
              <>
                <Link href={`/app/inventory/rfqs?requisitionId=${r.id}`} className="btn" data-testid="requisition-rfq">
                  {t("inventory.req.askQuotes")}
                </Link>
                <Link href={`/app/inventory/purchase-orders?new=1&requisitionId=${r.id}`} className="btn btn-primary" data-testid="requisition-order">
                  {t("inventory.req.raiseOrder")}
                </Link>
              </>
            ) : null}
          </>
        }
      />
      <InventoryNav />
      <FormAlert message={error} />
      {detail.error && !r ? (
        <ErrorState error={detail.error} onRetry={detail.reload} />
      ) : !r ? (
        <LoadingRows rows={6} />
      ) : (
        <>
          {r.status === "REJECTED" ? (
            <p className="alert alert-warn" data-testid="requisition-rejected">
              {t("inventory.rejectedBecause", { reason: r.rejectionReason ?? "" })}
            </p>
          ) : r.escalatedAbovePaise !== null && r.status === "HEAD_APPROVED" ? (
            <p className="alert alert-info" data-testid="requisition-escalated">
              {t("inventory.escalatedAbove", { limit: formatPaise(r.escalatedAbovePaise) })}
            </p>
          ) : headStage ? (
            <p className="alert alert-info">{t("inventory.req.waitingHead", { name: r.headName ?? "" })}</p>
          ) : null}
          <div className="grid gap-3.5 lg:grid-cols-3">
            <section className="card lg:col-span-2" aria-labelledby="req-lines">
              <div className="card-head">
                <h2 id="req-lines">{r.purpose}</h2>
                <RequisitionPill status={r.status} />
              </div>
              <div className="table-wrap">
                <table className="table" data-testid="requisition-lines">
                  <thead>
                    <tr>
                      <th scope="col">#</th>
                      <th scope="col">{t("inventory.col.description")}</th>
                      <th scope="col" className="r">
                        {t("inventory.col.quantity")}
                      </th>
                      <th scope="col" className="r">
                        {t("inventory.req.estimatedPrice")}
                      </th>
                      <th scope="col" className="r">
                        {t("inventory.col.amount")}
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    {r.lines.map((l) => (
                      <tr key={l.id} data-testid="requisition-line-row">
                        <td className="num">{l.lineNo}</td>
                        <td>
                          {l.description}
                          {l.itemName && l.itemName !== l.description ? <span className="block text-[12.5px] text-ink-3">{l.itemName}</span> : null}
                        </td>
                        <td className="r num">
                          {l.quantity} {l.unit}
                        </td>
                        <td className="r num">{formatPaise(l.estimatedUnitPaise)}</td>
                        <td className="r num font-semibold">{formatPaise(l.estimatedPaise)}</td>
                      </tr>
                    ))}
                  </tbody>
                  <tfoot>
                    <tr>
                      <th scope="row" colSpan={4}>
                        {t("inventory.req.estimated")}
                      </th>
                      <td className="r num font-bold" data-testid="requisition-estimated">
                        {formatPaise(r.estimatedPaise)}
                      </td>
                    </tr>
                  </tfoot>
                </table>
              </div>
            </section>
            <section className="card" aria-labelledby="req-facts">
              <div className="card-head">
                <h2 id="req-facts">{t("inventory.details")}</h2>
              </div>
              <dl className="kv" data-testid="requisition-facts">
                <dt>{t("inventory.col.requestedBy")}</dt>
                <dd>{r.requestedByName}</dd>
                <dt>{t("inventory.col.department")}</dt>
                <dd>{r.departmentName ?? "—"}</dd>
                <dt>{t("inventory.col.neededBy")}</dt>
                <dd>{r.neededBy ? formatPlainDate(r.neededBy, locale) : "—"}</dd>
                <dt>{t("inventory.col.submitted")}</dt>
                <dd>{r.submittedAt ? formatDateTime(r.submittedAt, locale) : "—"}</dd>
                <dt>{t("inventory.req.head")}</dt>
                <dd>{r.headName ?? t("inventory.none")}</dd>
              </dl>
            </section>
          </div>
          <ApprovalTrail approvals={r.approvals} locale={locale} />
        </>
      )}

      {editing && r ? (
        <RequisitionFormDialog
          requisition={r}
          onClose={() => setEditing(false)}
          onSaved={(saved, submitted) => {
            setEditing(false);
            toast(submitted ? t("inventory.req.sent", { number: saved.number ?? "" }) : t("inventory.saved"));
            detail.reload();
          }}
        />
      ) : null}
      <ConfirmDialog
        open={deleting}
        title={t("inventory.req.deleteTitle")}
        body={t("inventory.req.deleteBody")}
        confirmLabel={t("common.delete")}
        onClose={() => setDeleting(false)}
        onConfirm={async () => {
          await inventoryApi.deleteRequisition(id);
          toast(t("common.deleted"));
          router.push("/app/inventory/requisitions");
        }}
      />
      <ReasonDialog
        open={rejecting}
        title={t("inventory.reject")}
        label={t("inventory.col.reason")}
        confirmLabel={t("inventory.reject")}
        testId="reject-form"
        onClose={() => setRejecting(false)}
        onConfirm={async (reason) => {
          await (headStage ? inventoryApi.headReject(id, { reason }) : inventoryApi.rejectRequisition(id, { reason }));
          setRejecting(false);
          toast(t("inventory.rejected"));
          detail.reload();
        }}
      />
    </>
  );
}

/** The approval history of a requisition or order. */
export function ApprovalTrail({ approvals, locale }: { approvals: PurchaseApprovalView[]; locale: string }) {
  const { t } = useI18n();
  return (
    <section className="card" aria-labelledby="trail-title">
      <div className="card-head">
        <h2 id="trail-title">{t("inventory.trail")}</h2>
      </div>
      {approvals.length === 0 ? (
        <p className="empty" data-testid="trail-empty">
          {t("inventory.trail.empty")}
        </p>
      ) : (
        <ol className="rowcards" data-testid="trail-list">
          {approvals.map((a, i) => (
            <li key={i} className="rowcard">
              <span className="min-w-0 flex-1">
                <b className="block font-semibold">
                  {translateOr(t, `inventory.stage.${a.stage}`, a.stage)} · {translateOr(t, `inventory.action.${a.action}`, a.action)}
                </b>
                <span className="block text-[12.5px] text-ink-3">
                  {a.approverName}
                  {a.limitPaise !== null ? ` · ${t("inventory.limitOf", { limit: formatPaise(a.limitPaise) })}` : ""} · {formatDateTime(a.at, locale)}
                  {a.note ? ` · ${a.note}` : ""}
                </span>
              </span>
            </li>
          ))}
        </ol>
      )}
    </section>
  );
}
