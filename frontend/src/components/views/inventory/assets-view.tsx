"use client";

import { Plus, Printer } from "lucide-react";
import Link from "next/link";
import { useState } from "react";
import { ErrorState, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { formatPaise, formatPlainDate } from "@/lib/format";
import { localeFor, translateOr, useI18n } from "@/lib/i18n";
import { inventoryApi } from "@/lib/inventory-api";
import { ASSET_STATUSES, type AssetStatus, type AssetView } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { AssetForm } from "./asset-form";
import { AssetStatusPill, ConditionPill, InventoryNav, useCanManage, useLookups } from "./inventory-ui";

/** /app/inventory/assets: the asset register, filtered by status, location and custodian; a new asset and the labels. */
export function AssetsView() {
  const { t, lang } = useI18n();
  const { toast } = useToast();
  const locale = localeFor(lang);
  const canManage = useCanManage();
  const lookups = useLookups();
  const [status, setStatus] = useState<AssetStatus | "">("IN_USE");
  const [locationId, setLocationId] = useState("");
  const [custodianId, setCustodianId] = useState("");
  const [search, setSearch] = useState("");
  const [creating, setCreating] = useState(false);
  const list = useApiData(`inventory:assets:${status}:${locationId}:${custodianId}`, () =>
    inventoryApi.assets({ status: status || undefined, locationId: locationId || undefined, custodianId: custodianId || undefined }),
  );
  const q = search.trim().toLowerCase();
  const rows = (list.data ?? []).filter((a) => !q || `${a.assetTag} ${a.name} ${a.serialNo ?? ""}`.toLowerCase().includes(q));

  return (
    <>
      <PageHead
        eyebrow={t("inventory.eyebrow")}
        title={t("inventory.assets.title")}
        actions={
          <>
            {canManage ? (
              <Link href={`/app/inventory/assets/labels${rows.length ? `?ids=${rows.map((a) => a.id).join(",")}` : ""}`} className="btn" data-testid="assets-labels">
                <Printer size={18} aria-hidden="true" />
                {t("inventory.labels.title")}
              </Link>
            ) : null}
            {canManage ? (
              <button type="button" className="btn btn-primary" onClick={() => setCreating(true)} data-testid="asset-new">
                <Plus size={18} aria-hidden="true" />
                {t("inventory.assets.new")}
              </button>
            ) : null}
          </>
        }
      />
      <InventoryNav />

      <section className="card" aria-labelledby="assets-title">
        <div className="card-head">
          <div>
            <h2 id="assets-title">{t("inventory.assets.list")}</h2>
            {list.data ? (
              <p className="mt-1 text-sm text-ink-2" data-testid="assets-counts">
                {t("inventory.assets.counts", { count: rows.length, cost: formatPaise(rows.reduce((s, a) => s + a.costPaise, 0)) })}
              </p>
            ) : null}
          </div>
        </div>
        <form method="post" className="toolbar no-print" onSubmit={(e) => e.preventDefault()} aria-label={t("reports.filters")}>
          <label className="field w-full sm:w-auto sm:min-w-40">
            <span className="field-label">{t("inventory.col.status")}</span>
            <select className="input" name="status" value={status} onChange={(e) => setStatus(e.target.value as AssetStatus | "")}>
              <option value="">{t("inventory.assets.allStatuses")}</option>
              {ASSET_STATUSES.map((s) => (
                <option key={s} value={s}>
                  {translateOr(t, `inventory.assetStatus.${s}`, s)}
                </option>
              ))}
            </select>
          </label>
          <label className="field w-full sm:w-auto sm:min-w-40">
            <span className="field-label">{t("inventory.col.location")}</span>
            <select className="input" name="locationId" value={locationId} onChange={(e) => setLocationId(e.target.value)}>
              <option value="">{t("inventory.assets.allLocations")}</option>
              {(lookups.data?.locations ?? []).map((l) => (
                <option key={l.id} value={l.id}>
                  {l.name}
                </option>
              ))}
            </select>
          </label>
          <label className="field w-full sm:w-auto sm:min-w-40">
            <span className="field-label">{t("inventory.col.custodian")}</span>
            <select className="input" name="custodianId" value={custodianId} onChange={(e) => setCustodianId(e.target.value)}>
              <option value="">{t("inventory.assets.allCustodians")}</option>
              {(lookups.data?.people ?? []).map((p) => (
                <option key={p.id} value={p.id}>
                  {p.name}
                </option>
              ))}
            </select>
          </label>
          <label className="field w-full sm:w-auto sm:min-w-48">
            <span className="field-label">{t("inventory.assets.search")}</span>
            <input className="input" name="q" value={search} onChange={(e) => setSearch(e.target.value)} autoComplete="off" placeholder="AST-000001" />
          </label>
        </form>
        {list.error && !list.data ? (
          <ErrorState error={list.error} onRetry={list.reload} />
        ) : !list.data ? (
          <LoadingRows rows={6} />
        ) : rows.length === 0 ? (
          <p className="empty" data-testid="assets-empty">
            {canManage && !q && !locationId && !custodianId ? t("inventory.assets.emptyManage") : t("inventory.assets.empty")}
          </p>
        ) : (
          <AssetsTable rows={rows} locale={locale} />
        )}
      </section>

      {creating ? (
        <AssetForm
          asset={null}
          onClose={() => setCreating(false)}
          onSaved={(saved) => {
            setCreating(false);
            toast(t("inventory.assets.created", { tag: saved.assetTag }));
            list.reload();
          }}
        />
      ) : null}
    </>
  );
}

export function AssetsTable({ rows, locale }: { rows: AssetView[]; locale: string }) {
  const { t } = useI18n();
  return (
    <>
      <div className="table-wrap hidden md:block">
        <table className="table" data-testid="assets-table">
          <thead>
            <tr>
              <th scope="col">{t("inventory.col.tag")}</th>
              <th scope="col">{t("inventory.col.asset")}</th>
              <th scope="col">{t("inventory.col.location")}</th>
              <th scope="col">{t("inventory.col.custodian")}</th>
              <th scope="col">{t("inventory.col.purchased")}</th>
              <th scope="col" className="r">
                {t("inventory.col.cost")}
              </th>
              <th scope="col">{t("inventory.col.condition")}</th>
              <th scope="col">{t("inventory.col.status")}</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((a) => (
              <tr key={a.id} data-testid="assets-row">
                <td className="mono whitespace-nowrap">
                  <Link href={`/app/inventory/assets/${a.id}`} className="link font-semibold">
                    {a.assetTag}
                  </Link>
                </td>
                <td>
                  {a.name}
                  <span className="block text-[12.5px] text-ink-3">{a.categoryName ?? ""}</span>
                </td>
                <td>{a.locationName ?? "—"}</td>
                <td>{a.custodianName ?? "—"}</td>
                <td className="whitespace-nowrap">{formatPlainDate(a.purchaseDate, locale)}</td>
                <td className="r num">{formatPaise(a.costPaise)}</td>
                <td>
                  <ConditionPill condition={a.condition} />
                </td>
                <td>
                  <AssetStatusPill status={a.status} />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <ul className="rowcards md:hidden" data-testid="assets-cards">
        {rows.map((a) => (
          <li key={a.id}>
            <Link href={`/app/inventory/assets/${a.id}`} className="rowcard">
              <span className="min-w-0 flex-1">
                <b className="block truncate font-semibold">
                  <span className="mono">{a.assetTag}</span> · {a.name}
                </b>
                <span className="block truncate text-[12.5px] text-ink-3">
                  {a.locationName ?? "—"} · {a.custodianName ?? t("inventory.none")}
                </span>
              </span>
              <span className="flex flex-col items-end gap-1">
                <span className="num font-semibold">{formatPaise(a.costPaise)}</span>
                <AssetStatusPill status={a.status} />
              </span>
            </Link>
          </li>
        ))}
      </ul>
    </>
  );
}
