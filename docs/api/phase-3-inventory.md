# Phase 3 API: inventory and procurement

Same conventions as [Phase 0](phase-0.md) and [Phase 1](phase-1.md): JSON under `/api`, a Bearer access token,
RFC 9457 problem details with `errors.<field>`, ids from another school in a path answer `404` and in a body answer
`400` on that field, conflicts answer `409`, dates are `"YYYY-MM-DD"` days in India (Asia/Kolkata), times are
instants (ISO-8601), and every change is written to the audit trail. As in accounts: **money is in paise** (`long`),
GST rates are in basis points (`1800` = 18%), and financial years are Indian, `"2026-27"`.

**This is the storekeeper's book, not the accountant's.** It answers "what do we have, where is it, who holds it,
what is running low, and what have we ordered" for the people running the school. Depreciation is *computed* for
the register and never posted anywhere; the accounts module keeps the money.

**In this slice:** item categories (with a depreciation rate for assets) and locations; items (consumable or
asset) with a unit, a reorder level and a preferred vendor; fixed assets with an auto tag (`AST-000001`),
printable label sheets, straight-line depreciation computed on read, transfers between locations and custodians,
disposal with approval, and verification rounds with scanning; stock movements (receipt, issue, return,
write-off) with a per-item ledger, low-stock flags and stock takes with variance; procurement (a requisition from
any department, approved by the department head and then by an approver within a limit, an RFQ with up to three
quotes, a purchase order with GST per line, partial goods receipts and the vendor's invoice); six reports with CSV;
an inventory card on the dashboard; and demo data. Migration `V24__inventory.sql`; module `com.akshara.inventory`.
Vendors come from the accounts module (`/api/accounts/vendors`) and are only referenced here.

**Not in this slice:** posting depreciation or purchases to the accounts, vendor payments (accounts does that from
the `PurchaseReceived` event), barcode printing hardware (labels are an HTML sheet), serial-numbered stock,
multi-currency, and anything written to the accounts tables.

## Permissions

| Permission | Who has it (seeded roles) | Allows |
| --- | --- | --- |
| `inventory.read` (new) | School Admin, Principal, Teacher, Accountant, Librarian, Storekeeper | items, stock, assets, rounds, the reports; raising and editing one's own department's requisitions; deciding as department head |
| `inventory.manage` (new) | School Admin, Storekeeper | categories, locations, items, movements, stock takes, assets, transfers, disposal requests, verification rounds, approvers, RFQs, purchase orders, receipts and invoices |
| `purchase.approve` (new) | School Admin, Principal | the approval queue; approving, passing on or rejecting requisitions and purchase orders within a limit; approving or refusing asset disposals; read access to procurement, assets and lookups |

**Storekeeper** is a new seeded role (12 roles): `dashboard.view`, `leave.request`, `notices.read`,
`timetable.read`, `inventory.read`, `inventory.manage`. `V24__inventory.sql` adds it to existing schools and
appends the codes to the matching built-in roles (idempotent); new schools get them from `RoleCatalog`.

## Setup — `/api/inventory`

| Method | Path | Permission | Success |
| --- | --- | --- | --- |
| GET | `/lookups` | read or approve | `200 Lookups` (active categories, locations, vendors, departments, staff) |
| GET | `/approvers` | read or approve | `200 ApproverView[]` |
| GET | `/approvers/candidates` | `inventory.manage` | `200 PersonView[]`: everyone with `purchase.approve` |
| PUT | `/approvers` | `inventory.manage` | `200 ApproverView[]`: the list replaces the previous one; `400 approvers[i].userId` when the person lacks `purchase.approve` or is listed twice |
| GET/POST | `/categories` | read / manage | `200 CategoryView[]` / `201 CategoryView`; `409 name` on a duplicate |
| PUT/DELETE | `/categories/{id}` | `inventory.manage` | `200` / `204`; `409 id` when in use (mark inactive instead) |
| GET/POST | `/locations` | read / manage | `200 LocationView[]` / `201 LocationView`; `409 name` on a duplicate |
| PUT/DELETE | `/locations/{id}` | `inventory.manage` | `200` / `204`; `409 id` when it holds assets or stock |

```ts
type LocationKind = "STORE" | "LAB" | "LIBRARY" | "OFFICE" | "CLASSROOM_BLOCK" | "OTHER";
type ApproversForm = { approvers: { userId: string; limitPaise: number | null }[] };   // ≤ 20, null = no limit
type ApproverView = { userId: string; userName: string; limitPaise: number | null };
type CategoryForm = { name: string; depreciationRateBp: number; active: boolean };     // ≤ 80, 0..10000
type CategoryView = CategoryForm & { id: string; inUse: boolean };
type LocationForm = { name: string; kind: LocationKind; active: boolean };
type LocationView = LocationForm & { id: string; inUse: boolean };
type PersonView = { id: string; name: string; departmentName: string | null };
type Lookups = { categories: CategoryView[]; locations: LocationView[];
  vendors: { id: string; name: string; active: boolean }[]; departments: { id: string; name: string }[];
  people: PersonView[] };
```

## Items and stock — `/api/inventory`

| Method | Path | Permission | Success |
| --- | --- | --- | --- |
| GET | `/items` | `inventory.read` | `200 ItemView[]` with stock on hand and the `low` flag |
| GET | `/items/{id}` | `inventory.read` | `200 ItemView` |
| GET | `/items/{id}/ledger` | `inventory.read` | `200 ItemLedger`: movements newest first with a running balance |
| POST | `/items` | `inventory.manage` | `201 ItemView`; `409 name` on a duplicate |
| PUT | `/items/{id}` | `inventory.manage` | `200 ItemView`; `409 kind` once it has movements |
| DELETE | `/items/{id}` | `inventory.manage` | `204`; `409 id` once it has movements |
| GET | `/movements?from&to&kind` | `inventory.read` | `200 MovementView[]` |
| POST | `/movements/receipt` | `inventory.manage` | `201 MovementView` (a direct receipt, not against an order) |
| POST | `/movements/issue` | `inventory.manage` | `201 MovementView`; `409 quantity` above stock; `400 departmentId` when neither department nor person |
| POST | `/movements/return` | `inventory.manage` | `201 MovementView` (same body as an issue) |
| POST | `/movements/write-off` | `inventory.manage` | `201 MovementView`; `409 quantity` above stock |
| GET | `/stock-takes` | `inventory.read` | `200 StockTakeView[]` (without lines) |
| GET | `/stock-takes/{id}` | `inventory.read` | `200 StockTakeView` with lines |
| POST | `/stock-takes` | `inventory.manage` | `201 StockTakeView`: every active consumable with its expected quantity |
| POST | `/stock-takes/{id}/count` | `inventory.manage` | `200 StockTakeView`; `409 id` when closed |
| POST | `/stock-takes/{id}/close` | `inventory.manage` | `200 StockTakeView`: an `ADJUSTMENT` movement per variance |

Only consumables carry stock; an asset item answers `400` on `itemId`. A movement date in the future answers
`400 movedOn`.

```ts
type ItemKind = "CONSUMABLE" | "ASSET";
type MovementKind = "RECEIPT" | "ISSUE" | "RETURN" | "WRITE_OFF" | "ADJUSTMENT";
type ItemForm = { name: string; categoryId: string; unit: string; kind: ItemKind; reorderLevel: number;
  preferredVendorId: string | null; active: boolean };
type ItemView = ItemForm & { id: string; categoryName: string; preferredVendorName: string | null;
  stock: number; low: boolean };                                 // low = stock ≤ reorderLevel (reorderLevel > 0)
type ReceiptForm = { itemId: string; locationId: string | null; quantity: number; unitCostPaise: number | null;
  movedOn: string; purpose: string | null };
type IssueForm = { itemId: string; locationId: string | null; quantity: number; movedOn: string;
  departmentId: string | null; issuedToId: string | null; purpose: string };     // purpose required, ≤ 500
type WriteOffForm = { itemId: string; locationId: string | null; quantity: number; movedOn: string; reason: string };
type MovementView = { id: string; itemId: string; itemName: string; unit: string; kind: MovementKind;
  direction: 1 | -1; quantity: number; unitCostPaise: number | null; movedOn: string;
  locationId: string | null; locationName: string | null; departmentId: string | null; departmentName: string | null;
  issuedToId: string | null; issuedToName: string | null; purpose: string | null;
  purchaseOrderId: string | null; goodsReceiptId: string | null; stockTakeId: string | null;
  createdByName: string; balance: number };
type ItemLedger = { item: ItemView; movements: MovementView[] };
type StockTakeForm = { locationId: string | null; notes: string | null };
type CountForm = { counts: { itemId: string; countedQty: number }[] };          // ≤ 500
type StockTakeView = { id: string; locationId: string | null; locationName: string | null; status: "OPEN" | "CLOSED";
  startedOn: string; closedAt: string | null; notes: string | null; createdByName: string;
  lines: number; counted: number; variances: number;
  items: { itemId: string; itemName: string; unit: string; expectedQty: number;
    countedQty: number | null; variance: number | null }[] };
```

## Assets — `/api/inventory`

| Method | Path | Permission | Success |
| --- | --- | --- | --- |
| GET | `/assets?status&locationId&custodianId` | read or approve | `200 AssetView[]` |
| GET | `/assets/labels?ids=a,b` | read or approve | `200 AssetLabel[]` (all in-use assets without `ids`) |
| GET | `/assets/by-tag/{tag}` | read or approve | `200 AssetDetail` |
| GET | `/assets/{id}` | read or approve | `200 AssetDetail` with transfers |
| POST | `/assets` | `inventory.manage` | `201 AssetView` with the next tag `AST-nnnnnn` |
| PUT | `/assets/{id}` | `inventory.manage` | `200 AssetView`; `409 id` when disposed |
| POST | `/assets/{id}/transfer` | `inventory.manage` | `200 AssetDetail`; `400 toLocationId` when neither target given |
| POST | `/assets/{id}/disposal` | `inventory.manage` | `200 AssetView` (`DISPOSAL_REQUESTED`); `409 id` if already requested |
| POST | `/assets/{id}/disposal/approve` | `purchase.approve` | `200 AssetView` (`DISPOSED`, `disposedOn` today) |
| POST | `/assets/{id}/disposal/refuse` | `purchase.approve` | `200 AssetView` (back to `IN_USE`) |
| GET | `/verification-rounds` | read or approve | `200 RoundView[]` (without items) |
| GET | `/verification-rounds/{id}` | read or approve | `200 RoundView` with items |
| POST | `/verification-rounds` | `inventory.manage` | `201 RoundView`: every in-use asset (of the location, when given) |
| POST | `/verification-rounds/{id}/verify` | `inventory.manage` | `200 RoundView`; by `assetId` or a scanned `assetTag` (case-insensitive) |
| POST | `/verification-rounds/{id}/close` | `inventory.manage` | `200 RoundView`; found assets get `lastVerifiedOn` |

Depreciation is straight line, `cost × rate × days / 365` from the purchase date to today, capped at the cost;
`writtenDownPaise = cost − depreciation`. Nothing is posted.

```ts
type AssetCondition = "GOOD" | "FAIR" | "POOR" | "DAMAGED";
type AssetStatus = "IN_USE" | "DISPOSAL_REQUESTED" | "DISPOSED";
type AssetForm = { name: string; categoryId: string; itemId: string | null; locationId: string | null;
  custodianId: string | null; purchaseDate: string; costPaise: number; vendorId: string | null;
  serialNo: string | null; condition: AssetCondition; warrantyExpiresOn: string | null; notes: string | null };
type AssetView = AssetForm & { id: string; assetTag: string; categoryName: string; depreciationRateBp: number;
  locationName: string | null; custodianName: string | null; vendorName: string | null; warrantyExpired: boolean;
  status: AssetStatus; disposalReason: string | null; disposalRequestedBy: string | null;
  disposedOn: string | null; disposedByName: string | null; lastVerifiedOn: string | null;
  depreciationPaise: number; writtenDownPaise: number };
type TransferForm = { toLocationId: string | null; toCustodianId: string | null; reason: string | null; movedOn: string };
type AssetDetail = { asset: AssetView; transfers: { id: string; fromLocationName: string | null;
  toLocationName: string | null; fromCustodianName: string | null; toCustodianName: string | null;
  reason: string | null; movedOn: string; createdByName: string }[] };
type AssetLabel = { id: string; assetTag: string; name: string; categoryName: string; locationName: string | null };
type ReasonForm = { reason: string };                                           // ≤ 500
type RoundForm = { name: string; locationId: string | null };
type VerifyForm = { assetId: string | null; assetTag: string | null; result: "FOUND" | "MISSING" | "DAMAGED";
  note: string | null };
type RoundView = { id: string; name: string; locationId: string | null; locationName: string | null;
  status: "OPEN" | "CLOSED"; startedOn: string; closedAt: string | null; createdByName: string;
  assets: number; checked: number; found: number; missing: number; damaged: number;
  items: { assetId: string; assetTag: string; assetName: string; locationName: string | null;
    custodianName: string | null; result: VerificationResult | null; note: string | null; checkedAt: string | null }[] };
```

## Procurement — `/api/inventory`

A requisition is raised by anyone with `inventory.read` for their own department (School Admin and Storekeeper for
any). On submit it goes to the department head (`SUBMITTED`) unless there is none or the head is the requester,
then to the purchase approvers (`HEAD_APPROVED`). An approver with a limit at or above the amount approves; one
with a smaller limit passes it on (`ESCALATED`, `escalatedAbovePaise`), after which only a larger limit can act;
`409` when nobody has one. An approver without a listed limit may approve any amount. The same chain (without the
head stage) approves a purchase order. Numbers are per financial year: `REQ/2026-27/000001` (given on submit),
`RFQ/…`, `PO/…` (given on creation) and `GRN/…` for each receipt.

| Method | Path | Permission | Success |
| --- | --- | --- | --- |
| GET | `/requisitions?status` | read or approve | `200 RequisitionView[]`: the viewer's own, their department's as head, all for manage/approve |
| GET | `/requisitions/{id}` | read or approve | `200 RequisitionView` |
| POST | `/requisitions?submit=false` | `inventory.read` | `201 RequisitionView`; `400 departmentId` for another department |
| PUT | `/requisitions/{id}` | `inventory.read` | `200`; `409 id` unless draft |
| POST | `/requisitions/{id}/submit` | `inventory.read` | `200 RequisitionView`; `409 id` if already submitted |
| DELETE | `/requisitions/{id}` | `inventory.read` | `204`; `409 id` unless draft |
| POST | `/requisitions/{id}/head-approve` | `inventory.read` | `200` (body `NoteForm`, optional); `409 id` unless the caller is the head and it is `SUBMITTED` |
| POST | `/requisitions/{id}/head-reject` | `inventory.read` | `200` (body `ReasonForm`) |
| POST | `/requisitions/{id}/approve` | `purchase.approve` | `200` (`APPROVED` or escalated); `409 id` per the chain |
| POST | `/requisitions/{id}/reject` | `purchase.approve` | `200` (body `ReasonForm`) |
| GET | `/approvals/queue` | `purchase.approve` | `200 ApprovalQueue`: what waits for this approver |
| GET | `/rfqs?requisitionId` | read or approve | `200 RfqView[]` |
| GET | `/rfqs/{id}` | read or approve | `200 RfqView` |
| POST | `/rfqs` | `inventory.manage` | `201 RfqView` |
| POST | `/rfqs/{id}/quotes` | `inventory.manage` | `201 RfqView`; `409 vendorId` on a fourth vendor or a repeat |
| POST | `/rfqs/{id}/select` | `inventory.manage` | `200 RfqView` (`SELECTED`, with the reason) |
| POST | `/rfqs/{id}/cancel` | `inventory.manage` | `200 RfqView` |
| GET | `/purchase-orders?status&vendorId` | read or approve | `200 OrderRow[]` |
| GET | `/purchase-orders/{id}` | read or approve | `200 OrderView` with lines, approvals and receipts |
| POST | `/purchase-orders?submit=false` | `inventory.manage` | `201 OrderView`; `400 requisitionId` unless that requisition is approved; `400 lines` above ₹10 crore |
| PUT | `/purchase-orders/{id}` | `inventory.manage` | `200`; `409 id` unless draft |
| POST | `/purchase-orders/{id}/submit` | `inventory.manage` | `200 OrderView` (`SUBMITTED`) |
| POST | `/purchase-orders/{id}/approve` | `purchase.approve` | `200` (`APPROVED` or escalated); the linked requisition becomes `ORDERED` |
| POST | `/purchase-orders/{id}/reject` | `purchase.approve` | `200` (body `ReasonForm`) |
| POST | `/purchase-orders/{id}/cancel` | `inventory.manage` | `200`; `409 id` once anything is received |
| POST | `/purchase-orders/{id}/receipts` | `inventory.manage` | `201 OrderView` (`PARTIAL` or `RECEIVED`); `409 lines[i].quantity` above what is pending; `400 receivedOn` before the order date |
| POST | `/purchase-orders/{id}/invoice` | `inventory.manage` | `200 OrderView`; `409 id` unless approved or received |

A receipt of an item line writes a `RECEIPT` stock movement (consumables) at the line's unit price and publishes
`PurchaseReceived`.

```ts
type RequisitionStatus = "DRAFT" | "SUBMITTED" | "HEAD_APPROVED" | "APPROVED" | "REJECTED" | "ORDERED";
type RequisitionForm = { departmentId: string | null; neededBy: string | null; purpose: string;
  lines: { itemId: string | null; description: string; quantity: number; unit: string;
    estimatedUnitPaise: number }[] };                                             // 1..50 lines
type ApprovalView = { stage: "HEAD" | "PURCHASE"; action: "APPROVED" | "REJECTED" | "ESCALATED";
  approverId: string; approverName: string; limitPaise: number | null; note: string | null; at: string };
type RequisitionView = { id: string; number: string | null; status: RequisitionStatus; departmentId: string | null;
  departmentName: string | null; requestedById: string; requestedByName: string; neededBy: string | null;
  purpose: string; estimatedPaise: number; headUserId: string | null; headName: string | null;
  escalatedAbovePaise: number | null; rejectionReason: string | null; submittedAt: string | null;
  decidedAt: string | null; createdAt: string;
  lines: { id: string; lineNo: number; itemId: string | null; itemName: string | null; description: string;
    quantity: number; unit: string; estimatedUnitPaise: number; estimatedPaise: number }[];
  approvals: ApprovalView[]; canAct: boolean };                 // canAct: the viewer may decide it now
type NoteForm = { note: string | null };
type RfqForm = { title: string; requisitionId: string | null };
type QuoteForm = { vendorId: string; amountPaise: number; deliveryDays: number | null; validUntil: string | null;
  notes: string | null };
type SelectQuoteForm = { quoteId: string; reason: string };
type RfqView = { id: string; number: string; requisitionId: string | null; requisitionNumber: string | null;
  title: string; status: "OPEN" | "SELECTED" | "CANCELLED"; selectedQuoteId: string | null;
  selectionReason: string | null; createdByName: string; createdAt: string;
  quotes: (QuoteForm & { id: string; vendorName: string; selected: boolean })[] };
type PurchaseOrderStatus = "DRAFT" | "SUBMITTED" | "APPROVED" | "PARTIAL" | "RECEIVED" | "REJECTED" | "CANCELLED";
type PurchaseOrderForm = { vendorId: string; requisitionId: string | null; rfqId: string | null; orderedOn: string;
  expectedOn: string | null; terms: string | null;
  lines: { itemId: string | null; description: string; quantity: number; unit: string; unitPaise: number;
    gstRateBp: number }[] };                                                      // 1..50 lines
type OrderRow = { id: string; number: string; status: PurchaseOrderStatus; vendorId: string; vendorName: string;
  orderedOn: string; expectedOn: string | null; totalPaise: number; lines: number; receivedLines: number;
  requisitionNumber: string | null; createdAt: string };
type OrderView = OrderRow & { financialYear: string; vendorGstin: string | null; vendorAddress: string | null;
  requisitionId: string | null; rfqId: string | null; rfqNumber: string | null; terms: string | null;
  subtotalPaise: number; taxPaise: number; escalatedAbovePaise: number | null; rejectionReason: string | null;
  invoiceNumber: string | null; invoiceDate: string | null; invoicePaise: number | null; createdByName: string;
  submittedAt: string | null; approvedAt: string | null;
  lines: { id: string; lineNo: number; itemId: string | null; itemName: string | null; description: string;
    quantity: number; unit: string; unitPaise: number; gstRateBp: number; amountPaise: number; taxPaise: number;
    totalPaise: number; receivedQty: number }[];                 // taxPaise = amount × bp / 10000, half up
  approvals: ApprovalView[]; receipts: ReceiptView[]; canAct: boolean };
type ReceiveForm = { receivedOn: string; locationId: string | null; invoiceNumber: string | null;
  notes: string | null; lines: { poLineId: string; quantity: number }[] };
type ReceiptView = { id: string; number: string; receivedOn: string; locationId: string | null;
  locationName: string | null; invoiceNumber: string | null; notes: string | null; totalPaise: number;
  receivedByName: string;
  lines: { poLineId: string; description: string; quantity: number; unit: string; unitPaise: number }[] };
type InvoiceForm = { invoiceNumber: string; invoiceDate: string; invoicePaise: number };
type ApprovalQueue = { requisitions: RequisitionView[]; orders: OrderRow[]; limitPaise: number | null;
  limited: boolean };
```

## Reports — `/api/inventory/reports` (`inventory.read`)

Each report has a JSON view and a `.csv` twin (UTF-8 with BOM, `text/csv`, `Content-Disposition: attachment`).

| Path | Params | Body |
| --- | --- | --- |
| `/stock-on-hand` | — | `StockOnHand`: per category (items, stock, value at last receipt cost) and every item |
| `/low-stock` | — | `LowStock`: items at or below their reorder level, with the preferred vendor |
| `/asset-register` | — | `AssetRegister`: in-use assets by location with cost and written-down value |
| `/assets-by-custodian` | — | `AssetsByCustodian` |
| `/purchase-orders` | `from`, `to` (ordered on) | `OrdersReport`: by status, by vendor, and the rows |
| `/consumption` | `from`, `to` (issued on) | `Consumption`: issues per department per month, quantity and value |

```ts
type StockOnHand = { asOf: string; categories: { categoryId: string; categoryName: string; items: number;
  stock: number; valuePaise: number }[]; items: ItemView[] };
type LowStock = { items: ItemView[] };
type AssetRegister = { asOf: string; assets: number; costPaise: number; writtenDownPaise: number;
  locations: { locationId: string | null; locationName: string; assets: number; costPaise: number;
    writtenDownPaise: number; items: AssetView[] }[] };
type AssetsByCustodian = { assets: number; custodians: { custodianId: string | null; custodianName: string;
  assets: number; costPaise: number; items: AssetView[] }[] };
type OrdersReport = { byStatus: { status: PurchaseOrderStatus; orders: number; totalPaise: number }[];
  byVendor: { vendorId: string; vendorName: string; orders: number; totalPaise: number }[]; orders: OrderRow[] };
type Consumption = { from: string; to: string; months: string[]; departments: string[];
  cells: { department: string; month: string; quantity: number; valuePaise: number }[] };
```

## Dashboard

`GET /api/dashboard` carries `inventory: InventoryCard | null` for callers with `inventory.manage`:
`{ lowStockItems, requisitionsWaiting, ordersWaiting, openVerificationRounds, assetsVerificationDue }`
(`assetsVerificationDue`: in-use assets never verified, or not in the last year).

## Events

| Event | Published when | Payload |
| --- | --- | --- |
| `com.akshara.inventory.PurchaseReceived` | goods are received against a purchase order (inside that transaction) | `tenantId, purchaseOrderId, purchaseOrderNumber, goodsReceiptId, goodsReceiptNumber, vendorId, vendorName, amountPaise` (this receipt, GST included), `invoiceNumber, receivedOn, complete, items[{itemId, description, quantity, unit, unitPaise, gstRateBp}], receivedById, receivedAt` |

Nothing in this slice listens to other modules' events; vendors, departments and staff are read through their
modules' public services.

## Audit actions

`inventory_category.*`, `inventory_location.*`, `inventory_item.*` (`created|updated|deleted`),
`stock.received|issued|returned|written_off|adjusted`, `stock_take.started|counted|closed`,
`asset.created|updated|transferred|disposal_requested|disposed|disposal_refused|verified`,
`verification_round.started|closed`, `purchase_approvers.updated`,
`requisition.created|updated|submitted|deleted|head_approved|approved|escalated|rejected` (a head's rejection is
`requisition.rejected` with stage `HEAD`), `rfq.created|quoted|selected|cancelled`,
`purchase_order.created|updated|submitted|approved|escalated|rejected|cancelled|received|invoiced`.
