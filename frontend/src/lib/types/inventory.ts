/**
 * Types mirroring docs/api/phase-3-inventory.md (assets, stock, vendors and purchase orders). Keep in sync with the
 * contract. Money is in paise, GST rates in basis points, quantities whole units.
 */

import type { ApprovalAction } from "./accounts";
import type { PlainDate } from "./school";

export type LocationKind = "STORE" | "LAB" | "LIBRARY" | "OFFICE" | "CLASSROOM_BLOCK" | "OTHER";
export const LOCATION_KINDS: readonly LocationKind[] = ["STORE", "LAB", "LIBRARY", "OFFICE", "CLASSROOM_BLOCK", "OTHER"];
export type InventoryItemKind = "CONSUMABLE" | "ASSET";
export type MovementKind = "RECEIPT" | "ISSUE" | "RETURN" | "WRITE_OFF" | "ADJUSTMENT";
export const MOVEMENT_KINDS: readonly MovementKind[] = ["RECEIPT", "ISSUE", "RETURN", "WRITE_OFF", "ADJUSTMENT"];
export type OpenClosed = "OPEN" | "CLOSED";
export type AssetCondition = "GOOD" | "FAIR" | "POOR" | "DAMAGED";
export const ASSET_CONDITIONS: readonly AssetCondition[] = ["GOOD", "FAIR", "POOR", "DAMAGED"];
export type AssetStatus = "IN_USE" | "DISPOSAL_REQUESTED" | "DISPOSED";
export const ASSET_STATUSES: readonly AssetStatus[] = ["IN_USE", "DISPOSAL_REQUESTED", "DISPOSED"];
export type VerificationResult = "FOUND" | "MISSING" | "DAMAGED";
export type RequisitionStatus = "DRAFT" | "SUBMITTED" | "HEAD_APPROVED" | "APPROVED" | "REJECTED" | "ORDERED";
export const REQUISITION_STATUSES: readonly RequisitionStatus[] = ["DRAFT", "SUBMITTED", "HEAD_APPROVED", "APPROVED", "REJECTED", "ORDERED"];
export type ApprovalStage = "HEAD" | "PURCHASE";
export type RfqStatus = "OPEN" | "SELECTED" | "CANCELLED";
export type PurchaseOrderStatus = "DRAFT" | "SUBMITTED" | "APPROVED" | "PARTIAL" | "RECEIVED" | "REJECTED" | "CANCELLED";
export const PURCHASE_ORDER_STATUSES: readonly PurchaseOrderStatus[] = ["DRAFT", "SUBMITTED", "APPROVED", "PARTIAL", "RECEIVED", "REJECTED", "CANCELLED"];
/** GST rates in basis points. */
export const GST_RATES_BP = [0, 500, 1200, 1800, 2800] as const;

/* ------------------------------------------------------------------ masters */

export type PurchaseApproverView = { userId: string; userName: string; limitPaise: number | null };
export type PurchaseApproversRequest = { approvers: { userId: string; limitPaise: number | null }[] };
export type CategoryView = { id: string; name: string; depreciationRateBp: number; active: boolean; inUse: boolean };
export type CategoryRequest = { name: string; depreciationRateBp: number; active: boolean };
export type LocationView = { id: string; name: string; kind: LocationKind; active: boolean; inUse: boolean };
export type LocationRequest = { name: string; kind: LocationKind; active: boolean };
export type PersonView = { id: string; name: string; departmentName: string | null };
export type DepartmentRef = { id: string; name: string };
export type VendorRef = { id: string; name: string; active: boolean };
export type Lookups = {
  categories: CategoryView[];
  locations: LocationView[];
  vendors: VendorRef[];
  departments: DepartmentRef[];
  people: PersonView[];
};

/* ------------------------------------------------------------------ items and stock */

export type ItemView = {
  id: string;
  name: string;
  categoryId: string;
  categoryName: string | null;
  unit: string;
  kind: InventoryItemKind;
  reorderLevel: number;
  preferredVendorId: string | null;
  preferredVendorName: string | null;
  active: boolean;
  stock: number;
  low: boolean;
};

export type ItemRequest = {
  name: string;
  categoryId: string;
  unit: string;
  kind: InventoryItemKind;
  reorderLevel: number;
  preferredVendorId: string | null;
  active: boolean;
};

export type MovementView = {
  id: string;
  itemId: string;
  itemName: string | null;
  unit: string | null;
  kind: MovementKind;
  direction: 1 | -1;
  quantity: number;
  unitCostPaise: number | null;
  movedOn: PlainDate;
  locationId: string | null;
  locationName: string | null;
  departmentId: string | null;
  departmentName: string | null;
  issuedToId: string | null;
  issuedToName: string | null;
  purpose: string | null;
  purchaseOrderId: string | null;
  goodsReceiptId: string | null;
  stockTakeId: string | null;
  createdByName: string;
  /** the running balance in the item's ledger (0 in the movements list) */
  balance: number;
};

export type ItemLedger = { item: ItemView; movements: MovementView[] };

export type ReceiptRequest = {
  itemId: string;
  locationId: string | null;
  quantity: number;
  unitCostPaise: number | null;
  movedOn: PlainDate;
  purpose: string | null;
};

export type StockIssueRequest = {
  itemId: string;
  locationId: string | null;
  quantity: number;
  movedOn: PlainDate;
  departmentId: string | null;
  issuedToId: string | null;
  purpose: string;
};

export type WriteOffRequest = { itemId: string; locationId: string | null; quantity: number; movedOn: PlainDate; reason: string };

export type StockTakeLineView = { itemId: string; itemName: string | null; unit: string | null; expectedQty: number; countedQty: number | null; variance: number | null };

export type StockTakeView = {
  id: string;
  locationId: string | null;
  locationName: string | null;
  status: OpenClosed;
  startedOn: PlainDate;
  closedAt: string | null;
  notes: string | null;
  createdByName: string;
  lines: number;
  counted: number;
  variances: number;
  items: StockTakeLineView[];
};

export type StockTakeRequest = { locationId: string | null; notes: string | null };
export type CountRequest = { counts: { itemId: string; countedQty: number }[] };

/* ------------------------------------------------------------------ assets */

export type AssetView = {
  id: string;
  assetTag: string;
  name: string;
  categoryId: string;
  categoryName: string | null;
  depreciationRateBp: number;
  itemId: string | null;
  locationId: string | null;
  locationName: string | null;
  custodianId: string | null;
  custodianName: string | null;
  purchaseDate: PlainDate;
  costPaise: number;
  vendorId: string | null;
  vendorName: string | null;
  serialNo: string | null;
  condition: AssetCondition;
  warrantyExpiresOn: PlainDate | null;
  warrantyExpired: boolean;
  status: AssetStatus;
  disposalReason: string | null;
  disposalRequestedBy: string | null;
  disposedOn: PlainDate | null;
  disposedByName: string | null;
  notes: string | null;
  lastVerifiedOn: PlainDate | null;
  depreciationPaise: number;
  writtenDownPaise: number;
};

export type AssetRequest = {
  name: string;
  categoryId: string;
  itemId: string | null;
  locationId: string | null;
  custodianId: string | null;
  purchaseDate: PlainDate;
  costPaise: number;
  vendorId: string | null;
  serialNo: string | null;
  condition: AssetCondition;
  warrantyExpiresOn: PlainDate | null;
  notes: string | null;
};

export type TransferView = {
  id: string;
  fromLocationName: string | null;
  toLocationName: string | null;
  fromCustodianName: string | null;
  toCustodianName: string | null;
  reason: string | null;
  movedOn: PlainDate;
  createdByName: string;
};

export type AssetDetail = { asset: AssetView; transfers: TransferView[] };
export type TransferRequest = { toLocationId: string | null; toCustodianId: string | null; reason: string | null; movedOn: PlainDate };
export type AssetLabel = { id: string; assetTag: string; name: string; categoryName: string | null; locationName: string | null };

export type VerificationItemView = {
  assetId: string;
  assetTag: string;
  assetName: string;
  locationName: string | null;
  custodianName: string | null;
  result: VerificationResult | null;
  note: string | null;
  checkedAt: string | null;
};

export type RoundView = {
  id: string;
  name: string;
  locationId: string | null;
  locationName: string | null;
  status: OpenClosed;
  startedOn: PlainDate;
  closedAt: string | null;
  createdByName: string;
  assets: number;
  checked: number;
  found: number;
  missing: number;
  damaged: number;
  items: VerificationItemView[];
};

export type RoundRequest = { name: string; locationId: string | null };
export type VerifyRequest = { assetId?: string | null; assetTag?: string | null; result: VerificationResult; note: string | null };

/* ------------------------------------------------------------------ procurement */

export type PurchaseApprovalView = {
  stage: ApprovalStage;
  action: ApprovalAction;
  approverId: string | null;
  approverName: string;
  limitPaise: number | null;
  note: string | null;
  at: string;
};

export type RequisitionLineView = {
  id: string;
  lineNo: number;
  itemId: string | null;
  itemName: string | null;
  description: string;
  quantity: number;
  unit: string;
  estimatedUnitPaise: number;
  estimatedPaise: number;
};

export type RequisitionView = {
  id: string;
  number: string | null;
  status: RequisitionStatus;
  departmentId: string | null;
  departmentName: string | null;
  requestedById: string | null;
  requestedByName: string;
  neededBy: PlainDate | null;
  purpose: string;
  estimatedPaise: number;
  headUserId: string | null;
  headName: string | null;
  escalatedAbovePaise: number | null;
  rejectionReason: string | null;
  submittedAt: string | null;
  decidedAt: string | null;
  createdAt: string;
  lines: RequisitionLineView[];
  approvals: PurchaseApprovalView[];
  /** the signed-in person may approve or reject it now */
  canAct: boolean;
};

export type RequisitionLineRequest = { itemId: string | null; description: string; quantity: number; unit: string; estimatedUnitPaise: number };
export type RequisitionRequest = { departmentId: string | null; neededBy: PlainDate | null; purpose: string; lines: RequisitionLineRequest[] };

export type QuoteView = {
  id: string;
  vendorId: string;
  vendorName: string;
  amountPaise: number;
  deliveryDays: number | null;
  validUntil: PlainDate | null;
  notes: string | null;
  selected: boolean;
};

export type RfqView = {
  id: string;
  number: string;
  requisitionId: string | null;
  requisitionNumber: string | null;
  title: string;
  status: RfqStatus;
  selectedQuoteId: string | null;
  selectionReason: string | null;
  createdByName: string;
  createdAt: string;
  quotes: QuoteView[];
};

export type RfqRequest = { title: string; requisitionId: string | null };
export type QuoteRequest = { vendorId: string; amountPaise: number; deliveryDays: number | null; validUntil: PlainDate | null; notes: string | null };
export type SelectQuoteRequest = { quoteId: string; reason: string };

export type OrderLineView = {
  id: string;
  lineNo: number;
  itemId: string | null;
  itemName: string | null;
  description: string;
  quantity: number;
  unit: string;
  unitPaise: number;
  gstRateBp: number;
  amountPaise: number;
  taxPaise: number;
  totalPaise: number;
  receivedQty: number;
};

export type ReceiptLineView = { poLineId: string; description: string | null; quantity: number; unit: string | null; unitPaise: number };

export type ReceiptView = {
  id: string;
  number: string;
  receivedOn: PlainDate;
  locationId: string | null;
  locationName: string | null;
  invoiceNumber: string | null;
  notes: string | null;
  totalPaise: number;
  receivedByName: string;
  lines: ReceiptLineView[];
};

export type OrderRow = {
  id: string;
  number: string;
  status: PurchaseOrderStatus;
  vendorId: string;
  vendorName: string;
  orderedOn: PlainDate;
  expectedOn: PlainDate | null;
  totalPaise: number;
  lines: number;
  receivedLines: number;
  requisitionNumber: string | null;
  createdAt: string;
};

export type OrderView = {
  id: string;
  number: string;
  financialYear: string;
  status: PurchaseOrderStatus;
  vendorId: string;
  vendorName: string;
  vendorGstin: string | null;
  vendorAddress: string | null;
  requisitionId: string | null;
  requisitionNumber: string | null;
  rfqId: string | null;
  rfqNumber: string | null;
  orderedOn: PlainDate;
  expectedOn: PlainDate | null;
  terms: string | null;
  subtotalPaise: number;
  taxPaise: number;
  totalPaise: number;
  escalatedAbovePaise: number | null;
  rejectionReason: string | null;
  invoiceNumber: string | null;
  invoiceDate: PlainDate | null;
  invoicePaise: number | null;
  createdByName: string;
  submittedAt: string | null;
  approvedAt: string | null;
  createdAt: string;
  lines: OrderLineView[];
  approvals: PurchaseApprovalView[];
  receipts: ReceiptView[];
  canAct: boolean;
};

export type OrderLineRequest = { itemId: string | null; description: string; quantity: number; unit: string; unitPaise: number; gstRateBp: number };
export type OrderRequest = {
  vendorId: string;
  requisitionId: string | null;
  rfqId: string | null;
  orderedOn: PlainDate;
  expectedOn: PlainDate | null;
  terms: string | null;
  lines: OrderLineRequest[];
};
export type ReceiveRequest = { receivedOn: PlainDate; locationId: string | null; invoiceNumber: string | null; notes: string | null; lines: { poLineId: string; quantity: number }[] };
export type InvoiceRequest = { invoiceNumber: string; invoiceDate: PlainDate; invoicePaise: number };
export type NoteRequest = { note: string | null };
export type ReasonRequest = { reason: string };

export type InventoryApprovalQueue = { requisitions: RequisitionView[]; orders: OrderRow[]; limitPaise: number | null; limited: boolean };

/** The dashboard card (inventory.manage). */
export type InventoryCard = {
  lowStockItems: number;
  requisitionsWaiting: number;
  ordersWaiting: number;
  openVerificationRounds: number;
  assetsVerificationDue: number;
};

/* ------------------------------------------------------------------ reports */

export type CategoryStock = { categoryId: string; categoryName: string | null; items: number; stock: number; valuePaise: number };
export type StockOnHand = { asOf: PlainDate; categories: CategoryStock[]; items: ItemView[] };
export type LowStock = { items: ItemView[] };
export type LocationAssets = { locationId: string | null; locationName: string | null; assets: number; costPaise: number; writtenDownPaise: number; items: AssetView[] };
export type AssetRegister = { asOf: PlainDate; assets: number; costPaise: number; writtenDownPaise: number; locations: LocationAssets[] };
export type CustodianAssets = { custodianId: string | null; custodianName: string | null; assets: number; costPaise: number; items: AssetView[] };
export type AssetsByCustodian = { assets: number; custodians: CustodianAssets[] };
export type StatusCount = { status: PurchaseOrderStatus; orders: number; totalPaise: number };
export type VendorOrders = { vendorId: string; vendorName: string; orders: number; totalPaise: number };
export type OrdersReport = { byStatus: StatusCount[]; byVendor: VendorOrders[]; orders: OrderRow[] };
export type ConsumptionCell = { department: string; month: string; quantity: number; valuePaise: number };
export type Consumption = { from: PlainDate; to: PlainDate; months: string[]; departments: string[]; cells: ConsumptionCell[] };
