"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useState } from "react";
import { Dialog } from "@/components/ui/dialog";
import { TextAreaField } from "@/components/ui/field";
import { Pill } from "@/components/ui/pill";
import { FormAlert } from "@/components/ui/states";
import { useAuth } from "@/lib/auth";
import { toApiError } from "@/lib/api";
import { errorMessage } from "@/lib/error-message";
import { translateOr, useI18n, type MessageKey } from "@/lib/i18n";
import { inventoryApi } from "@/lib/inventory-api";
import { assetTone, conditionTone, movementTone, orderTone, requisitionTone, resultTone } from "@/lib/inventory";
import { hasAnyPermission, hasPermission, PERMISSIONS } from "@/lib/permissions";
import type { AssetCondition, AssetStatus, MovementKind, PurchaseOrderStatus, RequisitionStatus, VerificationResult } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";

/* Parts shared by the inventory screens (docs/api/phase-3-inventory.md): the section's navigation, pills and lookups. */

type InventoryLink = { href: string; labelKey: MessageKey; exact?: boolean; permission?: string };

const INVENTORY_LINKS: InventoryLink[] = [
  { href: "/app/inventory", labelKey: "inventory.nav.overview", exact: true },
  { href: "/app/inventory/items", labelKey: "inventory.nav.items", permission: PERMISSIONS.inventoryRead },
  { href: "/app/inventory/stock-takes", labelKey: "inventory.nav.stockTakes", permission: PERMISSIONS.inventoryManage },
  { href: "/app/inventory/assets", labelKey: "inventory.nav.assets", permission: PERMISSIONS.inventoryRead },
  { href: "/app/inventory/verification", labelKey: "inventory.nav.verification", permission: PERMISSIONS.inventoryManage },
  { href: "/app/inventory/requisitions", labelKey: "inventory.nav.requisitions", permission: PERMISSIONS.inventoryRead },
  { href: "/app/inventory/approvals", labelKey: "inventory.nav.approvals", permission: PERMISSIONS.purchaseApprove },
  { href: "/app/inventory/rfqs", labelKey: "inventory.nav.rfqs", permission: PERMISSIONS.inventoryManage },
  { href: "/app/inventory/purchase-orders", labelKey: "inventory.nav.orders", permission: PERMISSIONS.inventoryRead },
  { href: "/app/inventory/setup", labelKey: "inventory.nav.setup", permission: PERMISSIONS.inventoryManage },
];

/** The inventory section's own navigation, shown under each inventory page's heading. */
export function InventoryNav() {
  const { t } = useI18n();
  const { me } = useAuth();
  const pathname = usePathname() ?? "";
  if (!hasAnyPermission(me, [PERMISSIONS.inventoryRead, PERMISSIONS.purchaseApprove])) return null;
  return (
    <nav className="subnav no-print" aria-label={t("inventory.nav.label")}>
      {INVENTORY_LINKS.filter((link) => !link.permission || hasPermission(me, link.permission)).map((link) => {
        const active = link.exact ? pathname === link.href : pathname === link.href || pathname.startsWith(`${link.href}/`);
        return (
          <Link key={link.href} href={link.href} aria-current={active ? "page" : undefined}>
            {t(link.labelKey)}
          </Link>
        );
      })}
    </nav>
  );
}

export function RequisitionPill({ status }: { status: RequisitionStatus }) {
  const { t } = useI18n();
  return (
    <Pill tone={requisitionTone(status)} dot>
      {translateOr(t, `inventory.reqStatus.${status}`, status)}
    </Pill>
  );
}

export function OrderPill({ status }: { status: PurchaseOrderStatus }) {
  const { t } = useI18n();
  return (
    <Pill tone={orderTone(status)} dot>
      {translateOr(t, `inventory.poStatus.${status}`, status)}
    </Pill>
  );
}

export function AssetStatusPill({ status }: { status: AssetStatus }) {
  const { t } = useI18n();
  return (
    <Pill tone={assetTone(status)} dot>
      {translateOr(t, `inventory.assetStatus.${status}`, status)}
    </Pill>
  );
}

export function ConditionPill({ condition }: { condition: AssetCondition }) {
  const { t } = useI18n();
  return <Pill tone={conditionTone(condition)}>{translateOr(t, `inventory.condition.${condition}`, condition)}</Pill>;
}

export function ResultPill({ result }: { result: VerificationResult | null }) {
  const { t } = useI18n();
  return (
    <Pill tone={resultTone(result)} dot>
      {result ? translateOr(t, `inventory.result.${result}`, result) : t("inventory.result.pending")}
    </Pill>
  );
}

export function MovementPill({ kind }: { kind: MovementKind }) {
  const { t } = useI18n();
  return <Pill tone={movementTone(kind)}>{translateOr(t, `inventory.movement.${kind}`, kind)}</Pill>;
}

/** Categories, locations, vendors, departments and people for the forms (inventory.read). */
export function useLookups() {
  return useApiData("inventory:lookups", inventoryApi.lookups);
}

export function useItems() {
  return useApiData("inventory:items", inventoryApi.items);
}

/** Whether the signed-in person manages the stores. */
export function useCanManage(): boolean {
  const { me } = useAuth();
  return hasPermission(me, PERMISSIONS.inventoryManage);
}

export function useCanApprove(): boolean {
  const { me } = useAuth();
  return hasPermission(me, PERMISSIONS.purchaseApprove);
}

/** "Not set" for a missing name. */
export function useNameOf(): (name: string | null | undefined) => string {
  const { t } = useI18n();
  return (name) => name ?? t("inventory.none");
}

/**
 * Asks for a reason (rejecting, cancelling, writing off, disposing) or an optional note, and runs the action with
 * it; a refusal from the API is shown in place.
 */
export function ReasonDialog({
  open,
  title,
  description,
  label,
  confirmLabel,
  required = true,
  danger = true,
  onConfirm,
  onClose,
  testId,
}: {
  open: boolean;
  title: string;
  description?: string;
  label: string;
  confirmLabel: string;
  required?: boolean;
  danger?: boolean;
  onConfirm: (text: string) => Promise<void>;
  onClose: () => void;
  testId?: string;
}) {
  const { t } = useI18n();
  const [text, setText] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [formError, setFormError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const close = () => {
    setText("");
    setError(null);
    setFormError(null);
    onClose();
  };

  const confirm = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (required && !text.trim()) {
      setError(t("inventory.v.reason"));
      return;
    }
    if (text.trim().length > 500) {
      setError(t("inventory.v.textLong"));
      return;
    }
    setBusy(true);
    setFormError(null);
    try {
      await onConfirm(text.trim());
      setText("");
    } catch (caught) {
      const problem = toApiError(caught);
      setFormError(problem.errors?.reason ?? problem.errors?.note ?? errorMessage(problem, t));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog open={open} onClose={close} title={title} description={description} closeLabel={t("common.close")}>
      <form method="post" className="flex flex-col gap-3.5" onSubmit={(e) => void confirm(e)} noValidate data-testid={testId}>
        <FormAlert message={formError} />
        <TextAreaField
          label={label}
          name="reason"
          value={text}
          onChange={(e) => {
            setText(e.target.value);
            setError(null);
          }}
          error={error ?? undefined}
          rows={3}
          maxLength={500}
          required={required}
          data-autofocus
        />
        <div className="flex justify-end gap-2">
          <button type="button" className="btn" onClick={close}>
            {t("common.cancel")}
          </button>
          <button type="submit" className={`btn ${danger ? "btn-danger" : "btn-primary"}`} disabled={busy}>
            {busy ? t("common.working") : confirmLabel}
          </button>
        </div>
      </form>
    </Dialog>
  );
}

/** A labelled figure in a summary strip (a list of these sits in a `rep-kpis` grid). */
export function Stat({
  label,
  value,
  sub,
  testId,
  tone,
  attention,
  href,
}: {
  label: string;
  value: string;
  sub?: string;
  testId?: string;
  tone?: "bad" | "good";
  attention?: boolean;
  href?: string;
}) {
  const body = (
    <>
      <span className="text-[13px] font-medium text-ink-2">{label}</span>
      <span className={`kpi-value${tone === "bad" ? " text-bad" : tone === "good" ? " text-good" : ""}`}>{value}</span>
      {sub ? <span className="text-[12.5px] text-ink-3">{sub}</span> : null}
    </>
  );
  return (
    <li className={`rep-kpi${attention ? " is-attention" : ""}`} data-testid={testId}>
      {href ? (
        <Link href={href} className="flex flex-col gap-0.5">
          {body}
        </Link>
      ) : (
        body
      )}
    </li>
  );
}
