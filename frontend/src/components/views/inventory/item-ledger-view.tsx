"use client";

import { ArrowLeft, Download, PackageMinus, PackagePlus, Undo2, XCircle } from "lucide-react";
import Link from "next/link";
import { useState } from "react";
import { ErrorState, LoadingRows, PageHead } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { formatPaise, formatPlainDate } from "@/lib/format";
import { localeFor, useI18n } from "@/lib/i18n";
import { inventoryApi } from "@/lib/inventory-api";
import type { ItemLedger, MovementView } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { InventoryNav, MovementPill, useCanManage } from "./inventory-ui";
import { MovementDialog, type MovementDialogKind } from "./movement-dialog";

/** The ledger's rows as CSV text (with a BOM, so Excel reads the names), newest first as shown. Exported for tests. */
export function ledgerCsv(ledger: ItemLedger): string {
  const cell = (v: string | number | null | undefined) => {
    const s = v === null || v === undefined ? "" : String(v);
    return /[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
  };
  const head = ["Date", "Kind", "In", "Out", "Balance", "Location", "Department", "Person", "Purpose", "By"];
  const rows = ledger.movements.map((m) =>
    [
      m.movedOn,
      m.kind,
      m.direction > 0 ? m.quantity : "",
      m.direction < 0 ? m.quantity : "",
      m.balance,
      m.locationName,
      m.departmentName,
      m.issuedToName,
      m.purpose,
      m.createdByName,
    ].map(cell).join(","),
  );
  return `﻿${[head.join(","), ...rows].join("\r\n")}\r\n`;
}

/** /app/inventory/items/{id}: the item, its stock and every movement with a running balance; the storekeeper's receipt, issue, return and write-off. */
export function ItemLedgerView({ id }: { id: string }) {
  const { t, lang } = useI18n();
  const { toast } = useToast();
  const locale = localeFor(lang);
  const canManage = useCanManage();
  const ledger = useApiData(`inventory:ledger:${id}`, () => inventoryApi.ledger(id));
  const [dialog, setDialog] = useState<MovementDialogKind | null>(null);
  const data = ledger.data;

  const download = () => {
    if (!data) return;
    const blob = new Blob([ledgerCsv(data)], { type: "text/csv;charset=utf-8" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `ledger-${data.item.name.replace(/[^\w-]+/g, "-").toLowerCase()}.csv`;
    a.click();
    URL.revokeObjectURL(url);
  };

  const actions: { kind: MovementDialogKind; label: string; icon: React.ReactNode }[] = [
    { kind: "RECEIPT", label: t("inventory.move.receipt"), icon: <PackagePlus size={16} aria-hidden="true" /> },
    { kind: "ISSUE", label: t("inventory.move.issue"), icon: <PackageMinus size={16} aria-hidden="true" /> },
    { kind: "RETURN", label: t("inventory.move.return"), icon: <Undo2 size={16} aria-hidden="true" /> },
    { kind: "WRITE_OFF", label: t("inventory.move.writeOff"), icon: <XCircle size={16} aria-hidden="true" /> },
  ];

  return (
    <>
      <PageHead
        eyebrow={t("inventory.eyebrow")}
        title={data ? data.item.name : t("inventory.ledger.title")}
        actions={
          <>
            <Link href="/app/inventory/items" className="btn">
              <ArrowLeft size={18} aria-hidden="true" />
              {t("common.back")}
            </Link>
            <button type="button" className="btn" onClick={download} disabled={!data} data-testid="ledger-csv">
              <Download size={18} aria-hidden="true" />
              {t("inventory.report.csv")}
            </button>
          </>
        }
      />
      <InventoryNav />

      {ledger.error && !data ? (
        <ErrorState error={ledger.error} onRetry={ledger.reload} />
      ) : !data ? (
        <LoadingRows rows={6} />
      ) : (
        <>
          <section className="card" aria-labelledby="ledger-item">
            <div className="card-head">
              <h2 id="ledger-item">{t("inventory.ledger.item")}</h2>
              {canManage && data.item.kind === "CONSUMABLE" ? (
                <div className="flex flex-wrap gap-2 no-print">
                  {actions.map((a) => (
                    <button key={a.kind} type="button" className="btn btn-sm" onClick={() => setDialog(a.kind)} data-testid={`ledger-${a.kind.toLowerCase()}`}>
                      {a.icon}
                      {a.label}
                    </button>
                  ))}
                </div>
              ) : null}
            </div>
            <dl className="kv" data-testid="ledger-summary">
              <dt>{t("inventory.col.stock")}</dt>
              <dd className={`num font-semibold${data.item.low && data.item.active ? " text-bad" : ""}`} data-testid="ledger-stock">
                {data.item.stock} {data.item.unit}
                {data.item.low && data.item.active ? ` · ${t("inventory.items.low")}` : ""}
              </dd>
              <dt>{t("inventory.col.category")}</dt>
              <dd>{data.item.categoryName ?? "—"}</dd>
              <dt>{t("inventory.col.kind")}</dt>
              <dd>{t(data.item.kind === "ASSET" ? "inventory.kind.ASSET" : "inventory.kind.CONSUMABLE")}</dd>
              <dt>{t("inventory.col.reorderLevel")}</dt>
              <dd className="num">{data.item.reorderLevel}</dd>
              <dt>{t("inventory.item.preferredVendor")}</dt>
              <dd>{data.item.preferredVendorName ?? "—"}</dd>
            </dl>
          </section>

          <section className="card" aria-labelledby="ledger-title">
            <div className="card-head">
              <h2 id="ledger-title">{t("inventory.ledger.movements")}</h2>
            </div>
            {data.movements.length === 0 ? (
              <p className="empty" data-testid="ledger-empty">
                {t("inventory.ledger.empty")}
              </p>
            ) : (
              <LedgerTable rows={data.movements} locale={locale} />
            )}
          </section>
        </>
      )}

      {dialog && data ? (
        <MovementDialog
          kind={dialog}
          item={data.item}
          onClose={() => setDialog(null)}
          onSaved={() => {
            setDialog(null);
            toast(t("inventory.move.saved"));
            ledger.reload();
          }}
        />
      ) : null}
    </>
  );
}

function LedgerTable({ rows, locale }: { rows: MovementView[]; locale: string }) {
  const { t } = useI18n();
  const who = (m: MovementView) => [m.departmentName, m.issuedToName].filter(Boolean).join(" · ") || "—";
  return (
    <>
      <div className="table-wrap hidden md:block">
        <table className="table" data-testid="ledger-table">
          <thead>
            <tr>
              <th scope="col">{t("inventory.col.date")}</th>
              <th scope="col">{t("inventory.col.kind")}</th>
              <th scope="col" className="r">
                {t("inventory.col.in")}
              </th>
              <th scope="col" className="r">
                {t("inventory.col.out")}
              </th>
              <th scope="col" className="r">
                {t("inventory.col.balance")}
              </th>
              <th scope="col">{t("inventory.col.location")}</th>
              <th scope="col">{t("inventory.col.toWhom")}</th>
              <th scope="col">{t("inventory.col.purpose")}</th>
              <th scope="col" className="r">
                {t("inventory.col.unitCost")}
              </th>
            </tr>
          </thead>
          <tbody>
            {rows.map((m) => (
              <tr key={m.id} data-testid="ledger-row">
                <td className="whitespace-nowrap">{formatPlainDate(m.movedOn, locale)}</td>
                <td>
                  <MovementPill kind={m.kind} />
                </td>
                <td className="r num">{m.direction > 0 ? m.quantity : ""}</td>
                <td className="r num">{m.direction < 0 ? m.quantity : ""}</td>
                <td className="r num font-semibold">{m.balance}</td>
                <td>{m.locationName ?? "—"}</td>
                <td>{who(m)}</td>
                <td>
                  {m.purpose ?? "—"}
                  {m.purchaseOrderId ? (
                    <Link href={`/app/inventory/purchase-orders/${m.purchaseOrderId}`} className="link ml-1 text-[12.5px]">
                      {t("inventory.ledger.order")}
                    </Link>
                  ) : null}
                </td>
                <td className="r num">{m.unitCostPaise !== null ? formatPaise(m.unitCostPaise) : "—"}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <ul className="rowcards md:hidden" data-testid="ledger-cards">
        {rows.map((m) => (
          <li key={m.id} className="rowcard">
            <span className="min-w-0 flex-1">
              <b className="block truncate font-semibold">
                {m.direction > 0 ? "+" : "−"}
                {m.quantity} · {formatPlainDate(m.movedOn, locale)}
              </b>
              <span className="block truncate text-[12.5px] text-ink-3">
                {who(m)}
                {m.purpose ? ` · ${m.purpose}` : ""}
              </span>
            </span>
            <span className="flex flex-col items-end gap-1">
              <span className="num font-semibold">{m.balance}</span>
              <MovementPill kind={m.kind} />
            </span>
          </li>
        ))}
      </ul>
    </>
  );
}
