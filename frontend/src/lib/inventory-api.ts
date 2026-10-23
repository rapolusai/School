import { apiFetch, pathSegment } from "./api";
import { reportQuery, type ReportParams } from "./reports-api";
import type {
  PurchaseApproversRequest,
  AssetDetail,
  AssetLabel,
  AssetRegister,
  AssetRequest,
  AssetsByCustodian,
  AssetStatus,
  AssetView,
  CategoryRequest,
  CategoryView,
  Consumption,
  CountRequest,
  InventoryApprovalQueue,
  InvoiceRequest,
  StockIssueRequest,
  ItemLedger,
  ItemRequest,
  ItemView,
  LocationRequest,
  LocationView,
  Lookups,
  LowStock,
  MovementKind,
  MovementView,
  NoteRequest,
  OrderRequest,
  OrderRow,
  OrdersReport,
  OrderView,
  PersonView,
  PurchaseApproverView,
  PurchaseOrderStatus,
  QuoteRequest,
  ReasonRequest,
  ReceiptRequest,
  ReceiveRequest,
  RequisitionRequest,
  RequisitionStatus,
  RequisitionView,
  RfqRequest,
  RfqView,
  RoundRequest,
  RoundView,
  SelectQuoteRequest,
  StockOnHand,
  StockTakeRequest,
  StockTakeView,
  TransferRequest,
  VerifyRequest,
  WriteOffRequest,
} from "./types";

/* Endpoint helpers for docs/api/phase-3-inventory.md. */

const id = pathSegment;
const q = (params: ReportParams) => reportQuery(params);

export type Period = { from: string; to: string };

/** The reports' CSV downloads (opened with the signed-in user's token; the server sets the file name). */
export const inventoryPaths = {
  stockOnHandCsv: () => "/api/inventory/reports/stock-on-hand.csv",
  lowStockCsv: () => "/api/inventory/reports/low-stock.csv",
  assetRegisterCsv: () => "/api/inventory/reports/asset-register.csv",
  assetsByCustodianCsv: () => "/api/inventory/reports/assets-by-custodian.csv",
  purchaseOrdersCsv: (p: Period) => `/api/inventory/reports/purchase-orders.csv${q(p)}`,
  consumptionCsv: (p: Period) => `/api/inventory/reports/consumption.csv${q(p)}`,
};

export const inventoryApi = {
  /* setup */
  lookups: () => apiFetch<Lookups>("/api/inventory/lookups"),
  approvers: () => apiFetch<PurchaseApproverView[]>("/api/inventory/approvers"),
  approverCandidates: () => apiFetch<PersonView[]>("/api/inventory/approvers/candidates"),
  saveApprovers: (body: PurchaseApproversRequest) => apiFetch<PurchaseApproverView[]>("/api/inventory/approvers", { method: "PUT", body }),
  categories: () => apiFetch<CategoryView[]>("/api/inventory/categories"),
  createCategory: (body: CategoryRequest) => apiFetch<CategoryView>("/api/inventory/categories", { method: "POST", body }),
  updateCategory: (categoryId: string, body: CategoryRequest) =>
    apiFetch<CategoryView>(`/api/inventory/categories/${id(categoryId)}`, { method: "PUT", body }),
  deleteCategory: (categoryId: string) => apiFetch<void>(`/api/inventory/categories/${id(categoryId)}`, { method: "DELETE" }),
  locations: () => apiFetch<LocationView[]>("/api/inventory/locations"),
  createLocation: (body: LocationRequest) => apiFetch<LocationView>("/api/inventory/locations", { method: "POST", body }),
  updateLocation: (locationId: string, body: LocationRequest) =>
    apiFetch<LocationView>(`/api/inventory/locations/${id(locationId)}`, { method: "PUT", body }),
  deleteLocation: (locationId: string) => apiFetch<void>(`/api/inventory/locations/${id(locationId)}`, { method: "DELETE" }),

  /* items and stock */
  items: () => apiFetch<ItemView[]>("/api/inventory/items"),
  item: (itemId: string) => apiFetch<ItemView>(`/api/inventory/items/${id(itemId)}`),
  ledger: (itemId: string) => apiFetch<ItemLedger>(`/api/inventory/items/${id(itemId)}/ledger`),
  createItem: (body: ItemRequest) => apiFetch<ItemView>("/api/inventory/items", { method: "POST", body }),
  updateItem: (itemId: string, body: ItemRequest) => apiFetch<ItemView>(`/api/inventory/items/${id(itemId)}`, { method: "PUT", body }),
  deleteItem: (itemId: string) => apiFetch<void>(`/api/inventory/items/${id(itemId)}`, { method: "DELETE" }),
  movements: (p: Period & { kind?: MovementKind }) => apiFetch<MovementView[]>(`/api/inventory/movements${q(p)}`),
  receive: (body: ReceiptRequest) => apiFetch<MovementView>("/api/inventory/movements/receipt", { method: "POST", body }),
  issue: (body: StockIssueRequest) => apiFetch<MovementView>("/api/inventory/movements/issue", { method: "POST", body }),
  giveBack: (body: StockIssueRequest) => apiFetch<MovementView>("/api/inventory/movements/return", { method: "POST", body }),
  writeOff: (body: WriteOffRequest) => apiFetch<MovementView>("/api/inventory/movements/write-off", { method: "POST", body }),
  stockTakes: () => apiFetch<StockTakeView[]>("/api/inventory/stock-takes"),
  stockTake: (takeId: string) => apiFetch<StockTakeView>(`/api/inventory/stock-takes/${id(takeId)}`),
  startStockTake: (body: StockTakeRequest) => apiFetch<StockTakeView>("/api/inventory/stock-takes", { method: "POST", body }),
  countStock: (takeId: string, body: CountRequest) =>
    apiFetch<StockTakeView>(`/api/inventory/stock-takes/${id(takeId)}/count`, { method: "POST", body }),
  closeStockTake: (takeId: string) => apiFetch<StockTakeView>(`/api/inventory/stock-takes/${id(takeId)}/close`, { method: "POST" }),

  /* assets */
  assets: (p: { status?: AssetStatus; locationId?: string; custodianId?: string } = {}) => apiFetch<AssetView[]>(`/api/inventory/assets${q(p)}`),
  assetLabels: (ids: string[]) => apiFetch<AssetLabel[]>(`/api/inventory/assets/labels${ids.length ? `?ids=${ids.map(id).join(",")}` : ""}`),
  assetByTag: (tag: string) => apiFetch<AssetView>(`/api/inventory/assets/by-tag/${id(tag)}`),
  asset: (assetId: string) => apiFetch<AssetDetail>(`/api/inventory/assets/${id(assetId)}`),
  createAsset: (body: AssetRequest) => apiFetch<AssetView>("/api/inventory/assets", { method: "POST", body }),
  updateAsset: (assetId: string, body: AssetRequest) => apiFetch<AssetView>(`/api/inventory/assets/${id(assetId)}`, { method: "PUT", body }),
  transferAsset: (assetId: string, body: TransferRequest) =>
    apiFetch<AssetDetail>(`/api/inventory/assets/${id(assetId)}/transfer`, { method: "POST", body }),
  requestDisposal: (assetId: string, body: ReasonRequest) =>
    apiFetch<AssetView>(`/api/inventory/assets/${id(assetId)}/disposal`, { method: "POST", body }),
  approveDisposal: (assetId: string, body: NoteRequest) =>
    apiFetch<AssetView>(`/api/inventory/assets/${id(assetId)}/disposal/approve`, { method: "POST", body }),
  refuseDisposal: (assetId: string, body: ReasonRequest) =>
    apiFetch<AssetView>(`/api/inventory/assets/${id(assetId)}/disposal/refuse`, { method: "POST", body }),
  rounds: () => apiFetch<RoundView[]>("/api/inventory/verification-rounds"),
  round: (roundId: string) => apiFetch<RoundView>(`/api/inventory/verification-rounds/${id(roundId)}`),
  startRound: (body: RoundRequest) => apiFetch<RoundView>("/api/inventory/verification-rounds", { method: "POST", body }),
  verify: (roundId: string, body: VerifyRequest) =>
    apiFetch<RoundView>(`/api/inventory/verification-rounds/${id(roundId)}/verify`, { method: "POST", body }),
  closeRound: (roundId: string) => apiFetch<RoundView>(`/api/inventory/verification-rounds/${id(roundId)}/close`, { method: "POST" }),

  /* procurement */
  requisitions: (p: { status?: RequisitionStatus } = {}) => apiFetch<RequisitionView[]>(`/api/inventory/requisitions${q(p)}`),
  requisition: (reqId: string) => apiFetch<RequisitionView>(`/api/inventory/requisitions/${id(reqId)}`),
  createRequisition: (body: RequisitionRequest, submit: boolean) =>
    apiFetch<RequisitionView>(`/api/inventory/requisitions${submit ? "?submit=true" : ""}`, { method: "POST", body }),
  updateRequisition: (reqId: string, body: RequisitionRequest) =>
    apiFetch<RequisitionView>(`/api/inventory/requisitions/${id(reqId)}`, { method: "PUT", body }),
  submitRequisition: (reqId: string) => apiFetch<RequisitionView>(`/api/inventory/requisitions/${id(reqId)}/submit`, { method: "POST" }),
  deleteRequisition: (reqId: string) => apiFetch<void>(`/api/inventory/requisitions/${id(reqId)}`, { method: "DELETE" }),
  headApprove: (reqId: string, body: NoteRequest) =>
    apiFetch<RequisitionView>(`/api/inventory/requisitions/${id(reqId)}/head-approve`, { method: "POST", body }),
  headReject: (reqId: string, body: ReasonRequest) =>
    apiFetch<RequisitionView>(`/api/inventory/requisitions/${id(reqId)}/head-reject`, { method: "POST", body }),
  approveRequisition: (reqId: string, body: NoteRequest) =>
    apiFetch<RequisitionView>(`/api/inventory/requisitions/${id(reqId)}/approve`, { method: "POST", body }),
  rejectRequisition: (reqId: string, body: ReasonRequest) =>
    apiFetch<RequisitionView>(`/api/inventory/requisitions/${id(reqId)}/reject`, { method: "POST", body }),
  queue: () => apiFetch<InventoryApprovalQueue>("/api/inventory/approvals/queue"),
  rfqs: () => apiFetch<RfqView[]>("/api/inventory/rfqs"),
  rfq: (rfqId: string) => apiFetch<RfqView>(`/api/inventory/rfqs/${id(rfqId)}`),
  createRfq: (body: RfqRequest) => apiFetch<RfqView>("/api/inventory/rfqs", { method: "POST", body }),
  addQuote: (rfqId: string, body: QuoteRequest) => apiFetch<RfqView>(`/api/inventory/rfqs/${id(rfqId)}/quotes`, { method: "POST", body }),
  selectQuote: (rfqId: string, body: SelectQuoteRequest) =>
    apiFetch<RfqView>(`/api/inventory/rfqs/${id(rfqId)}/select`, { method: "POST", body }),
  cancelRfq: (rfqId: string) => apiFetch<RfqView>(`/api/inventory/rfqs/${id(rfqId)}/cancel`, { method: "POST" }),
  orders: (p: { status?: PurchaseOrderStatus; vendorId?: string } = {}) => apiFetch<OrderRow[]>(`/api/inventory/purchase-orders${q(p)}`),
  order: (orderId: string) => apiFetch<OrderView>(`/api/inventory/purchase-orders/${id(orderId)}`),
  createOrder: (body: OrderRequest, submit: boolean) =>
    apiFetch<OrderView>(`/api/inventory/purchase-orders${submit ? "?submit=true" : ""}`, { method: "POST", body }),
  updateOrder: (orderId: string, body: OrderRequest) =>
    apiFetch<OrderView>(`/api/inventory/purchase-orders/${id(orderId)}`, { method: "PUT", body }),
  submitOrder: (orderId: string) => apiFetch<OrderView>(`/api/inventory/purchase-orders/${id(orderId)}/submit`, { method: "POST" }),
  approveOrder: (orderId: string, body: NoteRequest) =>
    apiFetch<OrderView>(`/api/inventory/purchase-orders/${id(orderId)}/approve`, { method: "POST", body }),
  rejectOrder: (orderId: string, body: ReasonRequest) =>
    apiFetch<OrderView>(`/api/inventory/purchase-orders/${id(orderId)}/reject`, { method: "POST", body }),
  cancelOrder: (orderId: string, body: ReasonRequest) =>
    apiFetch<OrderView>(`/api/inventory/purchase-orders/${id(orderId)}/cancel`, { method: "POST", body }),
  receiveOrder: (orderId: string, body: ReceiveRequest) =>
    apiFetch<OrderView>(`/api/inventory/purchase-orders/${id(orderId)}/receipts`, { method: "POST", body }),
  recordInvoice: (orderId: string, body: InvoiceRequest) =>
    apiFetch<OrderView>(`/api/inventory/purchase-orders/${id(orderId)}/invoice`, { method: "POST", body }),

  /* reports */
  stockOnHand: () => apiFetch<StockOnHand>("/api/inventory/reports/stock-on-hand"),
  lowStock: () => apiFetch<LowStock>("/api/inventory/reports/low-stock"),
  assetRegister: () => apiFetch<AssetRegister>("/api/inventory/reports/asset-register"),
  assetsByCustodian: () => apiFetch<AssetsByCustodian>("/api/inventory/reports/assets-by-custodian"),
  ordersReport: (p: Period) => apiFetch<OrdersReport>(`/api/inventory/reports/purchase-orders${q(p)}`),
  consumption: (p: Period) => apiFetch<Consumption>(`/api/inventory/reports/consumption${q(p)}`),
};
