"use client";

import { Plus, TriangleAlert } from "lucide-react";
import Link from "next/link";
import { useState } from "react";
import { ErrorState, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { translateOr, useI18n } from "@/lib/i18n";
import type { ItemView } from "@/lib/types";
import { InventoryNav, useCanManage, useItems } from "./inventory-ui";
import { ItemFormDialog } from "./item-form";

export type ItemsFilter = "all" | "low" | "consumable" | "asset";

/** /app/inventory/items: every item with its stock on hand; low stock first when asked. */
export function ItemsView({ initialFilter = "all" }: { initialFilter?: ItemsFilter }) {
  const { t } = useI18n();
  const { toast } = useToast();
  const canManage = useCanManage();
  const [filter, setFilter] = useState<ItemsFilter>(initialFilter);
  const [search, setSearch] = useState("");
  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState<ItemView | null>(null);
  const items = useItems();

  const q = search.trim().toLowerCase();
  const rows = (items.data ?? []).filter((item) => {
    if (filter === "low" && !(item.low && item.active)) return false;
    if (filter === "consumable" && item.kind !== "CONSUMABLE") return false;
    if (filter === "asset" && item.kind !== "ASSET") return false;
    if (q && !`${item.name} ${item.categoryName ?? ""}`.toLowerCase().includes(q)) return false;
    return true;
  });
  const lowCount = (items.data ?? []).filter((i) => i.low && i.active).length;

  return (
    <>
      <PageHead
        eyebrow={t("inventory.eyebrow")}
        title={t("inventory.items.title")}
        actions={
          canManage ? (
            <button type="button" className="btn btn-primary" onClick={() => setCreating(true)} data-testid="item-new">
              <Plus size={18} aria-hidden="true" />
              {t("inventory.items.new")}
            </button>
          ) : null
        }
      />
      <InventoryNav />

      <section className="card" aria-labelledby="items-title">
        <div className="card-head">
          <div>
            <h2 id="items-title">{t("inventory.items.list")}</h2>
            {items.data ? (
              <p className="mt-1 text-sm text-ink-2" data-testid="items-counts">
                {t("inventory.items.counts", { total: items.data.length, low: lowCount })}
              </p>
            ) : null}
          </div>
        </div>
        <form method="post" className="toolbar no-print" onSubmit={(e) => e.preventDefault()} aria-label={t("reports.filters")}>
          <label className="field w-full sm:w-auto sm:min-w-44">
            <span className="field-label">{t("inventory.items.filter")}</span>
            <select className="input" name="filter" value={filter} onChange={(e) => setFilter(e.target.value as ItemsFilter)}>
              <option value="all">{t("inventory.items.filter.all")}</option>
              <option value="low">{t("inventory.items.filter.low")}</option>
              <option value="consumable">{t("inventory.items.filter.consumable")}</option>
              <option value="asset">{t("inventory.items.filter.asset")}</option>
            </select>
          </label>
          <label className="field w-full sm:w-auto sm:min-w-56">
            <span className="field-label">{t("inventory.items.search")}</span>
            <input className="input" name="q" value={search} onChange={(e) => setSearch(e.target.value)} autoComplete="off" />
          </label>
        </form>
        {items.error && !items.data ? (
          <ErrorState error={items.error} onRetry={items.reload} />
        ) : !items.data ? (
          <LoadingRows rows={6} />
        ) : rows.length === 0 ? (
          <p className="empty" data-testid="items-empty">
            {items.data.length === 0 && canManage ? t("inventory.items.emptyManage") : t("inventory.items.empty")}
          </p>
        ) : (
          <ItemsTable rows={rows} onEdit={canManage ? setEditing : undefined} />
        )}
      </section>

      {creating || editing ? (
        <ItemFormDialog
          item={editing}
          onClose={() => {
            setCreating(false);
            setEditing(null);
          }}
          onSaved={() => {
            setCreating(false);
            setEditing(null);
            toast(t("inventory.items.saved"));
            items.reload();
          }}
        />
      ) : null}
    </>
  );
}

export function ItemsTable({ rows, onEdit }: { rows: ItemView[]; onEdit?: (item: ItemView) => void }) {
  const { t } = useI18n();
  const stock = (item: ItemView) => (
    <span className={`num font-semibold${item.low && item.active ? " text-bad" : ""}`}>
      {item.low && item.active ? <TriangleAlert size={14} aria-label={t("inventory.items.low")} className="mr-1 inline" /> : null}
      {item.stock} {item.unit}
    </span>
  );
  return (
    <>
      <div className="table-wrap hidden md:block">
        <table className="table" data-testid="items-table">
          <thead>
            <tr>
              <th scope="col">{t("inventory.col.item")}</th>
              <th scope="col">{t("inventory.col.category")}</th>
              <th scope="col">{t("inventory.col.kind")}</th>
              <th scope="col" className="r">
                {t("inventory.col.stock")}
              </th>
              <th scope="col" className="r">
                {t("inventory.col.reorderLevel")}
              </th>
              <th scope="col">{t("inventory.col.vendor")}</th>
              {onEdit ? <th scope="col" className="r">{t("common.actions")}</th> : null}
            </tr>
          </thead>
          <tbody>
            {rows.map((item) => (
              <tr key={item.id} data-testid="items-row">
                <td>
                  <Link href={`/app/inventory/items/${item.id}`} className="link font-semibold">
                    {item.name}
                  </Link>
                  {!item.active ? <span className="ml-2 pill pill-neutral">{t("inventory.inactive")}</span> : null}
                </td>
                <td>{item.categoryName ?? "—"}</td>
                <td>{translateOr(t, `inventory.kind.${item.kind}`, item.kind)}</td>
                <td className="r">{stock(item)}</td>
                <td className="r num">{item.reorderLevel}</td>
                <td>{item.preferredVendorName ?? "—"}</td>
                {onEdit ? (
                  <td className="r">
                    <button type="button" className="btn btn-sm" onClick={() => onEdit(item)}>
                      {t("common.edit")}
                    </button>
                  </td>
                ) : null}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <ul className="rowcards md:hidden" data-testid="items-cards">
        {rows.map((item) => (
          <li key={item.id}>
            <Link href={`/app/inventory/items/${item.id}`} className="rowcard">
              <span className="min-w-0 flex-1">
                <b className="block truncate font-semibold">{item.name}</b>
                <span className="block truncate text-[12.5px] text-ink-3">
                  {item.categoryName ?? "—"} · {translateOr(t, `inventory.kind.${item.kind}`, item.kind)}
                </span>
              </span>
              {stock(item)}
            </Link>
          </li>
        ))}
      </ul>
    </>
  );
}
