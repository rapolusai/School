import type { PillTone } from "@/components/ui/pill";
import { parseRupees } from "./format";
import type {
  AssetCondition,
  AssetStatus,
  MovementKind,
  OrderLineRequest,
  OrderRequest,
  OrderView,
  PlainDate,
  PurchaseOrderStatus,
  RequisitionLineRequest,
  RequisitionRequest,
  RequisitionStatus,
  VerificationResult,
} from "./types";
import type { Problems } from "./use-form";

/* Pure helpers for the inventory screens (docs/api/phase-3-inventory.md). Money is in paise, GST in basis points. */

/** The largest amount the API accepts on one line or document: ten crore rupees. */
export const MAX_PAISE = 10_000_000_000;
export const MAX_QUANTITY = 1_000_000;
/** Asset tags as the API prints them: AST-000001. A scanner may send either case. */
export const ASSET_TAG_PATTERN = /^AST-\d{6}$/i;

export function normalizeTag(value: string): string {
  return value.trim().toUpperCase();
}

/** GST on an amount at a rate in basis points, rounded half up to the paisa, as the API computes it. */
export function gstOn(amountPaise: number, rateBp: number): number {
  return Math.floor((amountPaise * rateBp + 5_000) / 10_000);
}

export type LineTotals = { amountPaise: number; taxPaise: number; totalPaise: number };

/** One order line's amount, tax and total. */
export function lineTotals(quantity: number, unitPaise: number, gstRateBp: number): LineTotals {
  const amountPaise = quantity * unitPaise;
  const taxPaise = gstOn(amountPaise, gstRateBp);
  return { amountPaise, taxPaise, totalPaise: amountPaise + taxPaise };
}

/** The subtotal, tax and total of a set of lines. */
export function orderTotals(lines: readonly { quantity: number; unitPaise: number; gstRateBp: number }[]): LineTotals {
  return lines.reduce(
    (sum, line) => {
      const l = lineTotals(line.quantity, line.unitPaise, line.gstRateBp);
      return { amountPaise: sum.amountPaise + l.amountPaise, taxPaise: sum.taxPaise + l.taxPaise, totalPaise: sum.totalPaise + l.totalPaise };
    },
    { amountPaise: 0, taxPaise: 0, totalPaise: 0 },
  );
}

/**
 * Straight-line depreciation of an asset bought on `purchaseDate` for `costPaise` at `rateBp` a year, as of a day:
 * per day over 365, never more than the cost. Mirrors InventoryMath.depreciation.
 */
export function depreciation(costPaise: number, rateBp: number, purchaseDate: PlainDate, asOf: PlainDate): number {
  if (costPaise <= 0 || rateBp <= 0 || asOf <= purchaseDate) return 0;
  const days = Math.round((Date.parse(asOf) - Date.parse(purchaseDate)) / 86_400_000);
  const perYear = Math.floor((costPaise * rateBp) / 10_000);
  return Math.min(costPaise, Math.floor((perYear * days) / 365));
}

/** What is still to be received on an order, per line. */
export function outstanding(order: Pick<OrderView, "lines">): { poLineId: string; pending: number }[] {
  return order.lines.map((line) => ({ poLineId: line.id, pending: Math.max(0, line.quantity - line.receivedQty) }));
}

export function requisitionTone(status: RequisitionStatus): PillTone {
  switch (status) {
    case "DRAFT":
      return "neutral";
    case "SUBMITTED":
    case "HEAD_APPROVED":
      return "warn";
    case "APPROVED":
      return "info";
    case "ORDERED":
      return "good";
    case "REJECTED":
      return "bad";
  }
}

export function orderTone(status: PurchaseOrderStatus): PillTone {
  switch (status) {
    case "DRAFT":
      return "neutral";
    case "SUBMITTED":
      return "warn";
    case "APPROVED":
    case "PARTIAL":
      return "info";
    case "RECEIVED":
      return "good";
    case "REJECTED":
    case "CANCELLED":
      return "bad";
  }
}

export function assetTone(status: AssetStatus): PillTone {
  switch (status) {
    case "IN_USE":
      return "good";
    case "DISPOSAL_REQUESTED":
      return "warn";
    case "DISPOSED":
      return "neutral";
  }
}

export function conditionTone(condition: AssetCondition): PillTone {
  switch (condition) {
    case "GOOD":
      return "good";
    case "FAIR":
      return "info";
    case "POOR":
      return "warn";
    case "DAMAGED":
      return "bad";
  }
}

export function resultTone(result: VerificationResult | null): PillTone {
  switch (result) {
    case "FOUND":
      return "good";
    case "MISSING":
      return "bad";
    case "DAMAGED":
      return "warn";
    default:
      return "neutral";
  }
}

export function movementTone(kind: MovementKind): PillTone {
  switch (kind) {
    case "RECEIPT":
    case "RETURN":
      return "good";
    case "ISSUE":
      return "info";
    case "WRITE_OFF":
      return "bad";
    case "ADJUSTMENT":
      return "warn";
  }
}

/** "" for nothing, else the trimmed text. */
export function blank(value: string): string | null {
  const v = value.trim();
  return v ? v : null;
}

/** A whole number of units, or null when the text is not one. */
export function parseQuantity(text: string): number | null {
  const v = text.trim();
  if (!/^\d{1,7}$/.test(v)) return null;
  return Number(v);
}

/* ------------------------------------------------------------------ items */

export type ItemValues = { name: string; categoryId: string; unit: string; kind: "CONSUMABLE" | "ASSET"; reorderLevel: string; preferredVendorId: string; active: boolean };

/** The same checks POST /api/inventory/items makes. Exported for tests. */
export function validateItem(values: ItemValues): Problems {
  const problems: Problems = {};
  if (!values.name.trim()) problems.name = "inventory.v.name";
  else if (values.name.trim().length > 120) problems.name = "inventory.v.nameLong";
  if (!values.categoryId) problems.categoryId = "inventory.v.category";
  if (!values.unit.trim()) problems.unit = "inventory.v.unit";
  const level = parseQuantity(values.reorderLevel || "0");
  if (level === null) problems.reorderLevel = "inventory.v.quantity0";
  return problems;
}

/* ------------------------------------------------------------------ movements */

export type MovementValues = { itemId: string; locationId: string; quantity: string; unitCost: string; movedOn: string; departmentId: string; issuedToId: string; purpose: string };

/** The checks the receipt, issue, return and write-off endpoints make. Exported for tests. */
export function validateMovement(values: MovementValues, kind: MovementKind, today: PlainDate, stock?: number): Problems {
  const problems: Problems = {};
  if (!values.itemId) problems.itemId = "inventory.v.item";
  const qty = parseQuantity(values.quantity);
  if (qty === null || qty <= 0 || qty > MAX_QUANTITY) problems.quantity = "inventory.v.quantity";
  else if ((kind === "ISSUE" || kind === "WRITE_OFF") && stock !== undefined && qty > stock) problems.quantity = "inventory.v.notEnough";
  if (!values.movedOn) problems.movedOn = "inventory.v.date";
  else if (values.movedOn > today) problems.movedOn = "inventory.v.futureDate";
  if (kind === "RECEIPT" && values.unitCost.trim()) {
    const cost = parseRupees(values.unitCost);
    if (cost === null || cost < 0 || cost > MAX_PAISE) problems.unitCost = "inventory.v.amount";
  }
  if (kind === "ISSUE" && !values.purpose.trim()) problems.purpose = "inventory.v.purpose";
  if (kind === "WRITE_OFF" && !values.purpose.trim()) problems.purpose = "inventory.v.reason";
  if (values.purpose.trim().length > 500) problems.purpose = "inventory.v.textLong";
  return problems;
}

/* ------------------------------------------------------------------ assets */

export type AssetValues = {
  name: string;
  categoryId: string;
  itemId: string;
  locationId: string;
  custodianId: string;
  purchaseDate: string;
  cost: string;
  vendorId: string;
  serialNo: string;
  condition: AssetCondition;
  warrantyExpiresOn: string;
  notes: string;
};

/** The same checks POST /api/inventory/assets makes. Exported for tests. */
export function validateAsset(values: AssetValues, today: PlainDate): Problems {
  const problems: Problems = {};
  if (!values.name.trim()) problems.name = "inventory.v.name";
  else if (values.name.trim().length > 120) problems.name = "inventory.v.nameLong";
  if (!values.categoryId) problems.categoryId = "inventory.v.category";
  if (!values.purchaseDate) problems.purchaseDate = "inventory.v.date";
  else if (values.purchaseDate > today) problems.purchaseDate = "inventory.v.futureDate";
  const cost = parseRupees(values.cost);
  if (cost === null || cost < 0 || cost > MAX_PAISE) problems.cost = "inventory.v.amount";
  if (values.warrantyExpiresOn && values.purchaseDate && values.warrantyExpiresOn < values.purchaseDate) problems.warrantyExpiresOn = "inventory.v.warrantyBeforePurchase";
  if (values.serialNo.trim().length > 80) problems.serialNo = "inventory.v.textLong";
  if (values.notes.trim().length > 500) problems.notes = "inventory.v.textLong";
  return problems;
}

/* ------------------------------------------------------------------ requisitions */

export type LineValues = { itemId: string; description: string; quantity: string; unit: string; unitPrice: string; gstRateBp: string };

export const emptyLine = (): LineValues => ({ itemId: "", description: "", quantity: "1", unit: "", unitPrice: "", gstRateBp: "1800" });

export type RequisitionValues = { departmentId: string; neededBy: string; purpose: string; lines: LineValues[] };

/** The same checks POST /api/inventory/requisitions makes: a purpose and at least one complete line. Exported for tests. */
export function validateRequisition(values: RequisitionValues, today: PlainDate): Problems {
  const problems: Problems = {};
  if (!values.purpose.trim()) problems.purpose = "inventory.v.purpose";
  else if (values.purpose.trim().length > 500) problems.purpose = "inventory.v.textLong";
  if (values.neededBy && values.neededBy < today) problems.neededBy = "inventory.v.neededByPast";
  if (values.lines.length === 0) problems.lines = "inventory.v.lines";
  values.lines.forEach((line, i) => {
    if (!line.description.trim() || line.description.trim().length > 200) problems[`lines.${i}.description`] = "inventory.v.lineDescription";
    const qty = parseQuantity(line.quantity);
    if (qty === null || qty <= 0 || qty > MAX_QUANTITY) problems[`lines.${i}.quantity`] = "inventory.v.quantity";
    if (!line.unit.trim()) problems[`lines.${i}.unit`] = "inventory.v.unit";
    const price = line.unitPrice.trim() ? parseRupees(line.unitPrice) : 0;
    if (price === null || price < 0 || price > MAX_PAISE) problems[`lines.${i}.unitPrice`] = "inventory.v.amount";
  });
  return problems;
}

export function toRequisitionRequest(values: RequisitionValues): RequisitionRequest {
  const lines: RequisitionLineRequest[] = values.lines.map((line) => ({
    itemId: line.itemId || null,
    description: line.description.trim(),
    quantity: parseQuantity(line.quantity) ?? 0,
    unit: line.unit.trim(),
    estimatedUnitPaise: line.unitPrice.trim() ? (parseRupees(line.unitPrice) ?? 0) : 0,
  }));
  return { departmentId: values.departmentId || null, neededBy: values.neededBy || null, purpose: values.purpose.trim(), lines };
}

/* ------------------------------------------------------------------ purchase orders */

export type OrderValues = { vendorId: string; requisitionId: string; rfqId: string; orderedOn: string; expectedOn: string; terms: string; lines: LineValues[] };

/** The same checks POST /api/inventory/purchase-orders makes. Exported for tests. */
export function validateOrder(values: OrderValues, today: PlainDate): Problems {
  const problems: Problems = {};
  if (!values.vendorId) problems.vendorId = "inventory.v.vendor";
  if (!values.orderedOn) problems.orderedOn = "inventory.v.date";
  else if (values.orderedOn > today) problems.orderedOn = "inventory.v.futureDate";
  if (values.expectedOn && values.orderedOn && values.expectedOn < values.orderedOn) problems.expectedOn = "inventory.v.expectedBeforeOrder";
  if (values.terms.trim().length > 1000) problems.terms = "inventory.v.textLong";
  if (values.lines.length === 0) problems.lines = "inventory.v.lines";
  values.lines.forEach((line, i) => {
    if (!line.description.trim() || line.description.trim().length > 200) problems[`lines.${i}.description`] = "inventory.v.lineDescription";
    const qty = parseQuantity(line.quantity);
    if (qty === null || qty <= 0 || qty > MAX_QUANTITY) problems[`lines.${i}.quantity`] = "inventory.v.quantity";
    if (!line.unit.trim()) problems[`lines.${i}.unit`] = "inventory.v.unit";
    const price = parseRupees(line.unitPrice);
    if (price === null || price < 0 || price > MAX_PAISE) problems[`lines.${i}.unitPrice`] = "inventory.v.amount";
  });
  const total = orderTotals(toOrderLines(values.lines)).totalPaise;
  if (total > MAX_PAISE) problems.lines = "inventory.v.total";
  return problems;
}

export function toOrderLines(lines: readonly LineValues[]): OrderLineRequest[] {
  return lines.map((line) => ({
    itemId: line.itemId || null,
    description: line.description.trim(),
    quantity: parseQuantity(line.quantity) ?? 0,
    unit: line.unit.trim(),
    unitPaise: parseRupees(line.unitPrice) ?? 0,
    gstRateBp: Number(line.gstRateBp) || 0,
  }));
}

export function toOrderRequest(values: OrderValues): OrderRequest {
  return {
    vendorId: values.vendorId,
    requisitionId: values.requisitionId || null,
    rfqId: values.rfqId || null,
    orderedOn: values.orderedOn,
    expectedOn: values.expectedOn || null,
    terms: blank(values.terms),
    lines: toOrderLines(values.lines),
  };
}

/* ------------------------------------------------------------------ receipts */

export type ReceiveValues = { receivedOn: string; locationId: string; invoiceNumber: string; notes: string; quantities: Record<string, string> };

/** The same checks POST /api/inventory/purchase-orders/{id}/receipts makes: at least one line, none above what is pending. */
export function validateReceive(values: ReceiveValues, order: Pick<OrderView, "lines" | "orderedOn">, today: PlainDate): Problems {
  const problems: Problems = {};
  if (!values.receivedOn) problems.receivedOn = "inventory.v.date";
  else if (values.receivedOn > today) problems.receivedOn = "inventory.v.futureDate";
  else if (values.receivedOn < order.orderedOn) problems.receivedOn = "inventory.v.receivedBeforeOrder";
  if (values.invoiceNumber.trim().length > 60) problems.invoiceNumber = "inventory.v.textLong";
  let any = false;
  for (const { poLineId, pending } of outstanding(order)) {
    const text = (values.quantities[poLineId] ?? "").trim();
    if (!text || text === "0") continue;
    const qty = parseQuantity(text);
    if (qty === null || qty <= 0) problems[`lines.${poLineId}`] = "inventory.v.quantity";
    else if (qty > pending) problems[`lines.${poLineId}`] = "inventory.v.overReceipt";
    else any = true;
  }
  if (!any && Object.keys(problems).length === 0) problems.lines = "inventory.v.receiveSomething";
  return problems;
}

/** A line's text with its number: "00001 · Chalk". */
export function lineLabel(lineNo: number, description: string): string {
  return `${lineNo}. ${description}`;
}
