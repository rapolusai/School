"use client";

import { ArrowLeft, ArrowRightLeft, Pencil, Printer, Trash2 } from "lucide-react";
import Link from "next/link";
import { useState } from "react";
import { Dialog } from "@/components/ui/dialog";
import { SelectField, TextAreaField, TextField } from "@/components/ui/field";
import { ErrorState, FormAlert, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { formatPaise, formatPlainDate, todayInIndia } from "@/lib/format";
import { localeFor, useI18n } from "@/lib/i18n";
import { inventoryApi } from "@/lib/inventory-api";
import { blank } from "@/lib/inventory";
import type { AssetDetail } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { useForm } from "@/lib/use-form";
import { AssetForm } from "./asset-form";
import { AssetStatusPill, ConditionPill, InventoryNav, ReasonDialog, useCanApprove, useCanManage, useLookups } from "./inventory-ui";

/**
 * /app/inventory/assets/{id}: the asset, its depreciation to today, the transfer history, and the storekeeper's
 * edit, transfer and disposal request; an approver settles a disposal.
 */
export function AssetDetailView({ id }: { id: string }) {
  const { t, lang } = useI18n();
  const { toast } = useToast();
  const locale = localeFor(lang);
  const canManage = useCanManage();
  const canApprove = useCanApprove();
  const detail = useApiData(`inventory:asset:${id}`, () => inventoryApi.asset(id));
  const [editing, setEditing] = useState(false);
  const [transferring, setTransferring] = useState(false);
  const [dialog, setDialog] = useState<"dispose" | "approve" | "refuse" | null>(null);
  const data = detail.data;
  const asset = data?.asset;
  const inUse = asset?.status === "IN_USE";

  const done = (message: string) => {
    setDialog(null);
    setEditing(false);
    setTransferring(false);
    toast(message);
    detail.reload();
  };

  return (
    <>
      <PageHead
        eyebrow={t("inventory.eyebrow")}
        title={asset ? `${asset.assetTag} · ${asset.name}` : t("inventory.asset.title")}
        actions={
          <>
            <Link href="/app/inventory/assets" className="btn">
              <ArrowLeft size={18} aria-hidden="true" />
              {t("common.back")}
            </Link>
            {asset && canManage ? (
              <Link href={`/app/inventory/assets/labels?ids=${asset.id}`} className="btn">
                <Printer size={18} aria-hidden="true" />
                {t("inventory.labels.single")}
              </Link>
            ) : null}
            {asset && canManage && inUse ? (
              <>
                <button type="button" className="btn" onClick={() => setEditing(true)} data-testid="asset-edit">
                  <Pencil size={18} aria-hidden="true" />
                  {t("common.edit")}
                </button>
                <button type="button" className="btn" onClick={() => setTransferring(true)} data-testid="asset-transfer">
                  <ArrowRightLeft size={18} aria-hidden="true" />
                  {t("inventory.asset.transfer")}
                </button>
                <button type="button" className="btn" onClick={() => setDialog("dispose")} data-testid="asset-dispose">
                  <Trash2 size={18} aria-hidden="true" />
                  {t("inventory.asset.requestDisposal")}
                </button>
              </>
            ) : null}
            {asset && canApprove && asset.status === "DISPOSAL_REQUESTED" ? (
              <>
                <button type="button" className="btn btn-primary" onClick={() => setDialog("approve")} data-testid="asset-approve-disposal">
                  {t("inventory.asset.approveDisposal")}
                </button>
                <button type="button" className="btn" onClick={() => setDialog("refuse")} data-testid="asset-refuse-disposal">
                  {t("inventory.asset.refuseDisposal")}
                </button>
              </>
            ) : null}
          </>
        }
      />
      <InventoryNav />
      {detail.error && !data ? (
        <ErrorState error={detail.error} onRetry={detail.reload} />
      ) : !data || !asset ? (
        <LoadingRows rows={6} />
      ) : (
        <>
          {asset.status === "DISPOSAL_REQUESTED" ? (
            <p className="alert alert-warn" data-testid="asset-disposal-note">
              {t("inventory.asset.disposalRequested", { by: asset.disposalRequestedBy ?? "", reason: asset.disposalReason ?? "" })}
            </p>
          ) : asset.status === "DISPOSED" ? (
            <p className="alert alert-info" data-testid="asset-disposed-note">
              {t("inventory.asset.disposedOn", { date: formatPlainDate(asset.disposedOn, locale), by: asset.disposedByName ?? "", reason: asset.disposalReason ?? "" })}
            </p>
          ) : null}
          <div className="grid gap-3.5 lg:grid-cols-2">
            <section className="card" aria-labelledby="asset-facts">
              <div className="card-head">
                <h2 id="asset-facts">{t("inventory.asset.details")}</h2>
                <AssetStatusPill status={asset.status} />
              </div>
              <dl className="kv" data-testid="asset-facts">
                <dt>{t("inventory.col.category")}</dt>
                <dd>{asset.categoryName ?? "—"}</dd>
                <dt>{t("inventory.col.location")}</dt>
                <dd data-testid="asset-location">{asset.locationName ?? "—"}</dd>
                <dt>{t("inventory.col.custodian")}</dt>
                <dd data-testid="asset-custodian">{asset.custodianName ?? "—"}</dd>
                <dt>{t("inventory.col.condition")}</dt>
                <dd>
                  <ConditionPill condition={asset.condition} />
                </dd>
                <dt>{t("inventory.col.serialNo")}</dt>
                <dd className="mono">{asset.serialNo ?? "—"}</dd>
                <dt>{t("inventory.col.vendor")}</dt>
                <dd>{asset.vendorName ?? "—"}</dd>
                <dt>{t("inventory.col.warranty")}</dt>
                <dd className={asset.warrantyExpired ? "text-bad" : undefined}>
                  {asset.warrantyExpiresOn ? formatPlainDate(asset.warrantyExpiresOn, locale) : "—"}
                  {asset.warrantyExpired ? ` · ${t("inventory.asset.warrantyExpired")}` : ""}
                </dd>
                <dt>{t("inventory.col.lastVerified")}</dt>
                <dd>{asset.lastVerifiedOn ? formatPlainDate(asset.lastVerifiedOn, locale) : t("common.never")}</dd>
                <dt>{t("inventory.col.notes")}</dt>
                <dd>{asset.notes ?? "—"}</dd>
              </dl>
            </section>
            <section className="card" aria-labelledby="asset-money">
              <div className="card-head">
                <h2 id="asset-money">{t("inventory.asset.value")}</h2>
              </div>
              <dl className="kv" data-testid="asset-value">
                <dt>{t("inventory.col.purchased")}</dt>
                <dd>{formatPlainDate(asset.purchaseDate, locale)}</dd>
                <dt>{t("inventory.col.cost")}</dt>
                <dd className="num font-semibold">{formatPaise(asset.costPaise)}</dd>
                <dt>{t("inventory.asset.rate")}</dt>
                <dd className="num">{asset.depreciationRateBp / 100}%</dd>
                <dt>{t("inventory.asset.depreciation")}</dt>
                <dd className="num" data-testid="asset-depreciation">
                  {formatPaise(asset.depreciationPaise)}
                </dd>
                <dt>{t("inventory.asset.writtenDown")}</dt>
                <dd className="num font-semibold" data-testid="asset-written-down">
                  {formatPaise(asset.writtenDownPaise)}
                </dd>
              </dl>
              <p className="mt-2 text-[12.5px] text-ink-3">{t("inventory.asset.depreciationNote")}</p>
            </section>
          </div>

          <section className="card" aria-labelledby="asset-transfers">
            <div className="card-head">
              <h2 id="asset-transfers">{t("inventory.asset.transfers")}</h2>
            </div>
            {data.transfers.length === 0 ? (
              <p className="empty" data-testid="transfers-empty">
                {t("inventory.asset.noTransfers")}
              </p>
            ) : (
              <div className="table-wrap">
                <table className="table" data-testid="transfers-table">
                  <thead>
                    <tr>
                      <th scope="col">{t("inventory.col.date")}</th>
                      <th scope="col">{t("inventory.col.from")}</th>
                      <th scope="col">{t("inventory.col.to")}</th>
                      <th scope="col">{t("inventory.col.reason")}</th>
                      <th scope="col">{t("inventory.col.by")}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.transfers.map((tr) => (
                      <tr key={tr.id} data-testid="transfer-row">
                        <td className="whitespace-nowrap">{formatPlainDate(tr.movedOn, locale)}</td>
                        <td>{[tr.fromLocationName, tr.fromCustodianName].filter(Boolean).join(" · ") || "—"}</td>
                        <td>{[tr.toLocationName, tr.toCustodianName].filter(Boolean).join(" · ") || "—"}</td>
                        <td>{tr.reason ?? "—"}</td>
                        <td>{tr.createdByName}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>
        </>
      )}

      {editing && asset ? <AssetForm asset={asset} onClose={() => setEditing(false)} onSaved={() => done(t("inventory.assets.saved"))} /> : null}
      {transferring && asset ? <TransferDialog asset={data} onClose={() => setTransferring(false)} onSaved={() => done(t("inventory.asset.transferred"))} /> : null}
      <ReasonDialog
        open={dialog === "dispose"}
        title={t("inventory.asset.requestDisposal")}
        description={t("inventory.asset.disposalDescription")}
        label={t("inventory.col.reason")}
        confirmLabel={t("inventory.asset.requestDisposal")}
        testId="disposal-form"
        onClose={() => setDialog(null)}
        onConfirm={async (reason) => {
          await inventoryApi.requestDisposal(id, { reason });
          done(t("inventory.asset.disposalSent"));
        }}
      />
      <ReasonDialog
        open={dialog === "approve"}
        title={t("inventory.asset.approveDisposal")}
        description={t("inventory.asset.approveDescription")}
        label={t("inventory.col.note")}
        confirmLabel={t("inventory.asset.approveDisposal")}
        required={false}
        danger={false}
        testId="approve-disposal-form"
        onClose={() => setDialog(null)}
        onConfirm={async (note) => {
          await inventoryApi.approveDisposal(id, { note: note || null });
          done(t("inventory.asset.disposed"));
        }}
      />
      <ReasonDialog
        open={dialog === "refuse"}
        title={t("inventory.asset.refuseDisposal")}
        label={t("inventory.col.reason")}
        confirmLabel={t("inventory.asset.refuseDisposal")}
        testId="refuse-disposal-form"
        onClose={() => setDialog(null)}
        onConfirm={async (reason) => {
          await inventoryApi.refuseDisposal(id, { reason });
          done(t("inventory.asset.disposalRefused"));
        }}
      />
    </>
  );
}

function TransferDialog({ asset, onClose, onSaved }: { asset: AssetDetail; onClose: () => void; onSaved: () => void }) {
  const { t } = useI18n();
  const today = todayInIndia();
  const lookups = useLookups();
  const form = useForm({ toLocationId: asset.asset.locationId ?? "", toCustodianId: asset.asset.custodianId ?? "", reason: "", movedOn: today });
  const save = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const problems: Record<string, "inventory.v.date" | "inventory.v.futureDate" | "inventory.v.transferSame"> = {};
    if (!form.values.movedOn) problems.movedOn = "inventory.v.date";
    else if (form.values.movedOn > today) problems.movedOn = "inventory.v.futureDate";
    if ((form.values.toLocationId || "") === (asset.asset.locationId ?? "") && (form.values.toCustodianId || "") === (asset.asset.custodianId ?? "")) {
      problems.toLocationId = "inventory.v.transferSame";
    }
    if (!form.check(problems, t, event.currentTarget)) return;
    const ok = await form.submit(t, async () => {
      await inventoryApi.transferAsset(asset.asset.id, {
        toLocationId: form.values.toLocationId || null,
        toCustodianId: form.values.toCustodianId || null,
        reason: blank(form.values.reason),
        movedOn: form.values.movedOn,
      });
    });
    if (ok) onSaved();
  };
  return (
    <Dialog open onClose={onClose} title={t("inventory.asset.transfer")} description={t("inventory.asset.transferDescription")} closeLabel={t("common.close")}>
      <form method="post" className="flex flex-col gap-3.5" onSubmit={(e) => void save(e)} noValidate data-testid="transfer-form">
        <FormAlert message={form.formError} />
        <SelectField
          label={t("inventory.col.location")}
          name="toLocationId"
          value={form.values.toLocationId}
          onChange={(e) => form.set("toLocationId", e.target.value)}
          options={[{ value: "", label: t("inventory.none") }, ...(lookups.data?.locations ?? []).filter((l) => l.active).map((l) => ({ value: l.id, label: l.name }))]}
          error={form.errors.toLocationId}
          data-autofocus
        />
        <SelectField
          label={t("inventory.col.custodian")}
          name="toCustodianId"
          value={form.values.toCustodianId}
          onChange={(e) => form.set("toCustodianId", e.target.value)}
          options={[{ value: "", label: t("inventory.none") }, ...(lookups.data?.people ?? []).map((p) => ({ value: p.id, label: p.departmentName ? `${p.name} · ${p.departmentName}` : p.name }))]}
          error={form.errors.toCustodianId}
        />
        <TextField label={t("inventory.col.date")} name="movedOn" type="date" value={form.values.movedOn} max={today} onChange={(e) => form.set("movedOn", e.target.value)} error={form.errors.movedOn} required />
        <TextAreaField label={t("inventory.col.reason")} name="reason" value={form.values.reason} onChange={(e) => form.set("reason", e.target.value)} error={form.errors.reason} rows={2} maxLength={500} />
        <div className="flex justify-end gap-2">
          <button type="button" className="btn" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={form.submitting}>
            {form.submitting ? t("common.saving") : t("inventory.asset.transfer")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}
