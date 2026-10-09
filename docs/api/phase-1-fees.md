# Phase 1 API: fees

Same conventions as [Phase 0](phase-0.md) and [Phase 1](phase-1.md): JSON under `/api`,
`Authorization: Bearer <accessToken>`, RFC 9457 problem details, `404` for ids of another school in
a path, `400` with a field error for ids of another school in a body, `409` for conflicts.

- **Money is in paise** (₹1 = 100 paise) as whole numbers: `amountPaise: 1150000` is ₹11,500. The
  web app takes and shows rupees in Indian grouping (`₹1,84,300`).
- Dates are `"YYYY-MM-DD"`; "today" is today in India (Asia/Kolkata).
- The **financial year** runs April to March (`2026-27`); the **academic year** is the school's
  (Phase 1 setup). Fee structures, dues and concessions belong to an academic year; receipt numbers
  to a financial year.
- Every change writes an audit event (listed at the end). Nothing is deleted: receipts and
  allocations are append-only, cancellations add reversal rows.
- No GST lines: school fees are shown as single amounts per head.

## Permissions

| Permission | Who has it (seeded roles) | Allows |
| --- | --- | --- |
| `fees.read` | School Admin, Principal, Accountant | every fee screen and report, receipts, CSV exports |
| `fees.collect` | School Admin, Accountant | counter payments, online orders for a student, reminders |
| `fees.manage` (new) | School Admin, Accountant | heads, structures, concessions, late fee rule, waivers, receipt cancellation |
| `child.view` | School Admin, Parent | `/api/me/children/{studentId}/fees` for the caller's own children |

`V6__fees.sql` appends `fees.manage` to the built-in School Admin and Accountant roles of every
existing school (new schools get it from the role catalogue). Teachers, front office, parents and
students get `403` from every `/api/fees` endpoint.

## Setup — `/api/fees`

| Method | Path | Permission | Body | Success |
| --- | --- | --- | --- | --- |
| GET | `/heads` | read | | `200 FeeHead[]` in display order |
| POST | `/heads` | manage | `HeadForm` | `201 FeeHead`; `409` on a duplicate name |
| PUT | `/heads/{id}` | manage | `HeadForm` | `200 FeeHead` |
| POST | `/heads/defaults` | manage | | `200 FeeHead[]`: adds Tuition, Admission (one-time), Annual, Exam, Lab and Transport where missing |
| GET | `/late-fee-rule` | read | | `200 LateFeeRule` (`NONE` until set) |
| PUT | `/late-fee-rule` | manage | `LateFeeRule` | `200 LateFeeRule` |
| GET | `/structures?yearId=` | read | | `200 StructureSummary[]`: every class of the year (current year by default), with or without a structure |
| GET | `/structures/{id}` | read | | `200 FeeStructure` |
| POST | `/structures` | manage | `StructureForm` | `201 StructureResult`; `409` if the class already has one that year |
| PUT | `/structures/{id}` | manage | `StructureForm` (same year and class) | `200 StructureResult` |
| POST | `/structures/{id}/publish` | manage | | `200 StructureResult`; publishing again creates dues for students who joined since |
| GET | `/concessions?yearId=&studentId=` | read | | `200 Concession[]` |
| POST | `/concessions` | manage | `ConcessionForm` | `201 Concession` |
| POST | `/concessions/{id}/revoke` | manage | `{ reason }` | `200 Concession`; `409` if already revoked |

```ts
type FeeHeadKind = "TUITION" | "ADMISSION" | "ANNUAL" | "EXAM" | "TRANSPORT" | "LAB" | "OTHER";
type HeadForm = { name: string /* ≤60 */; kind: FeeHeadKind; oneTime?: boolean; active?: boolean };
type FeeHead = HeadForm & { id: string; displayOrder: number; inUse: boolean };

type LateFeeRule = { mode: "NONE" | "FLAT" | "PER_DAY"; graceDays: number /* 0–365 */;
  flatPaise: number; perDayPaise: number; capPaise: number /* 0 = no cap */ };

type StructureForm = {
  academicYearId: string; classId: string;
  heads: { headId: string; amountPaise: number }[];             // amount per student for the year
  instalments: { label?: string /* ≤40 */; dueDate: string;     // 1–12, each due after the one before
                 shares?: { headId: string; amountPaise: number }[] }[];
};
type StructureResult = { structure: FeeStructure;
  dues: { studentsCreated: number; studentsUpdated: number; paidCellsKept: number } };
```

**Instalment plans.** Leave `shares` out on every instalment to split each head evenly: each
instalment gets `floor(amount / n)` and the **last instalment absorbs the rounding**; a one-time
head falls wholly in the first instalment. Or give `shares` on every instalment: each head's shares
must add up exactly to its amount (`400` on `instalments`: "Tuition fee: the instalments add up to
₹42,000, not ₹40,000."). Unnamed instalments are called Annual, Term n, Quarter n or Instalment n.

**Editing.** A DRAFT structure is not charged to anyone. Saving a PUBLISHED structure re-prices
only what is unpaid: a student's cell that has payments keeps at least what was paid (an amount
cut below what was paid keeps the paid amount, and is counted in `paidCellsKept`); an instalment
that has payments, waivers or paid dues cannot be removed (`409 Has payments`). Paid amounts never
change.

**Concessions.** Types `SIBLING`, `STAFF_WARD`, `MERIT`, `RTE`, `OTHER`; approved by the caller
(`fees.manage`), with a reason.

```ts
type ConcessionForm = { studentId: string; academicYearId?: string /* current year */;
  type: "SIBLING" | "STAFF_WARD" | "MERIT" | "RTE" | "OTHER";
  mode?: "PERCENT" | "FIXED"; percent?: number /* 0.01–100, 2 decimals */; fixedPaise?: number;
  headIds?: string[]; reason: string /* ≤500 */ };
```

- `RTE` ignores mode, value and heads: it is always 100% of the school's active `TUITION` heads, and
  a student can have one RTE concession a year (`409` on `type`).
- A percentage applies to every cell (instalment × head) of the chosen heads, rounded half up per
  cell. A fixed amount is spread over those cells in proportion to their amounts, the last cell
  taking the rounding. Several concessions add up, but **a due never drops below zero**.
- Granting or revoking re-prices the student's unpaid dues at once, like a structure edit.

## Dues

A student's dues are one row per **student × academic year × instalment × head** with `gross`,
`concession`, `net = gross − concession`, `paid`, `balance = net − paid`, the due date and a status:

| Status | When |
| --- | --- |
| `PAID` | balance is 0 |
| `OVERDUE` | balance > 0 and the due date has passed |
| `PARTIAL` | something paid, not yet overdue |
| `DUE` | nothing paid, due within 30 days |
| `UPCOMING` | nothing paid, due later |

**When dues are created (decision: lazy, plus on publish).** Publishing a structure creates dues for
every active student enrolled in that class for that year. Students who join the class later get
their dues the first time their fees are read (`GET /students/{id}`, the parent's endpoint), paid
(counter or online) or a school-wide report runs (`overview`, `outstanding`, `overdue`,
`reminders`), so nobody is missed and no listener on the students module is needed. Re-publishing
does the same for the whole class. `FeeDuesService.generateForStudent(studentId)` is public for a
future `StudentEnrolled` event. A student who changes class keeps one set of dues for the year.

**Late fees** are computed on read from the school's rule and are never stored as dues: FLAT
charges `flatPaise` per instalment once `graceDays` have passed; PER_DAY charges `perDayPaise` for
each day after the grace days, up to `capPaise` per instalment. A late fee is collected at payment
time as its own receipt line (kind `LATE_FEE`), and can be waived per student and instalment.

| Method | Path | Permission | Body | Success |
| --- | --- | --- | --- | --- |
| GET | `/students?q=` | read | | `200 StudentHit[]` (≤20): name, admission number or parent's name |
| GET | `/students/{id}` | read | | `200 StudentFees` |
| POST | `/students/{id}/late-fee-waivers` | manage | `{ instalmentId, reason }` | `200 StudentFees`; `409` if already waived; `400 instalmentId` if not the student's |

```ts
type StudentFees = {
  student: { id; fullName; admissionNo; status; className; sectionName; rollNo };
  instalments: {                       // instalments with dues, oldest first
    instalmentId; seq; label; dueDate; academicYearId; academicYearName; status: DueStatus;
    grossPaise; concessionPaise; netPaise; paidPaise; balancePaise;
    lateFeePaise; lateFeeWaived: boolean; daysOverdue: number;
    heads: { dueId; headId; headName; grossPaise; concessionPaise; netPaise; paidPaise; balancePaise; status }[];
  }[];
  totals: { grossPaise; concessionPaise; netPaise; paidPaise; balancePaise; overduePaise; lateFeePaise;
            payableNowPaise /* balance due by today + late fees */ };
  concessions: Concession[]; receipts: ReceiptSummary[]; lateFeeRule: LateFeeRule; asOf: string;
};
```

## Payments and receipts

| Method | Path | Permission | Body | Success |
| --- | --- | --- | --- | --- |
| POST | `/students/{id}/payments` | collect | `PaymentForm` | `201 Receipt` |
| GET | `/receipts?from&to&mode&status&q&studentId&page&size` | read | | `200 ReceiptPage` (newest first, size ≤100; `totalPaise` counts issued receipts) |
| GET | `/receipts/{id}` | read | | `200 Receipt` |
| POST | `/receipts/{id}/cancel` | manage | `{ reason }` | `200 Receipt` (CANCELLED); `409` if already cancelled |
| GET | `/receipts/export.csv?from&to` | read | | `200 text/csv`: the receipts register |

```ts
type PaymentForm = { amountPaise: number; mode: "CASH" | "CHEQUE" | "UPI" | "CARD" | "BANK_TRANSFER";
  chequeNo?: string; bankName?: string;      // CHEQUE: both required
  reference?: string;                        // UPI, BANK_TRANSFER: required (UTR); CARD: POS approval code, optional
  instalmentIds?: string[];                  // pay only these (oldest first); default: oldest dues first
  includeLateFee?: boolean /* default true */; remarks?: string };
```

- **Allocation.** The amount fills the oldest instalment first (its late fee, then each head in the
  school's head order), then the next; a short payment leaves only the newest items part paid. With
  `instalmentIds`, only those instalments are paid. More than what is due answers `400 amountPaise`
  ("This is more than the ₹X due."); nothing due answers `409 Nothing due`. `ONLINE` is refused at
  the counter. Payments for one student are serialised with a transaction-scoped advisory lock.
- **Receipt numbers** are per school per financial year, gap-free: `RCPT/2026-27/000123`. The
  counter row `fees.receipt_counter (tenant_id, financial_year)` is created if missing, then locked
  `FOR UPDATE` and incremented in the payment's transaction, so a rolled-back payment never burns a
  number and concurrent payments get consecutive numbers.
- **Receipts are immutable.** Cancelling needs `fees.manage` and a reason: the receipt is marked
  `CANCELLED` (who, when, why), every allocation gets a matching reversal row, and the dues become
  payable again. The receipt number is not reused.

```ts
type Receipt = { id; receiptNo; financialYear; receivedOn; receivedAt; studentId; studentName; admissionNo;
  classLabel; mode; chequeNo; bankName; reference; source: "COUNTER" | "ONLINE"; gatewayPaymentId;
  amountPaise; lateFeePaise; amountInWords /* "Rupees Eleven Thousand Five Hundred Only" */; remarks;
  collectedByName; status: "ISSUED" | "CANCELLED"; cancelledAt; cancelledByName; cancelReason;
  lines: { kind: "DUE" | "LATE_FEE" | "ADVANCE"; instalmentLabel; headName; amountPaise }[];
  school: SchoolProfile /* header: name, address, phone, email, UDISE, board */ };
```

The printable receipt (`/app/fees/receipts/{id}` and the parent's receipt page) prints only the
receipt; parents save it as PDF from the print dialog.

Receipts register CSV columns: `Receipt no, Date, Student, Admission no, Class, Mode, Cheque no,
Bank, Reference, Amount, Late fee, Source, Collected by, Status, Cancelled on, Cancel reason`.
Amounts are rupees with two decimals; cells starting with `= + - @` are prefixed with `'`.

## Online payments

`PaymentGateway` (in `com.akshara.fees`) is modelled on Razorpay:

```java
GatewayOrder createOrder(OrderRequest request);                       // amount, currency, our receipt id
boolean verifyPaymentSignature(String orderId, String paymentId, String signature);
boolean verifyWebhookSignature(WebhookRequest request);               // raw body + headers
WebhookEvent parseWebhook(WebhookRequest request);                    // event id, kind, order, payment, amount
```

`akshara.payments.gateway` picks the implementation (`sandbox` by default). The only one built is
**`SandboxPaymentGateway`**: no network at all; order ids `order_SBX…`, payment ids `pay_SBX…`;
signatures are lowercase hex HMAC-SHA256 with `akshara.payments.sandbox.secret`
(`PAYMENTS_SANDBOX_SECRET`; a random secret per start when unset): payment signature over
`orderId + "|" + paymentId`, webhook signature over the raw body in header `X-Sandbox-Signature`,
compared in constant time. The webhook body has Razorpay's shape:
`{ id, event: "payment.captured" | "payment.failed", payload: { payment: { entity: { id, order_id, amount, error_description } } } }`.

| Method | Path | Permission | Body | Success |
| --- | --- | --- | --- | --- |
| POST | `/api/fees/students/{id}/orders` | collect | `{ instalmentIds }` | `201 PaymentOrder` for those instalments' balance plus today's late fee |
| GET | `/api/fees/orders/{orderId}` | collect | | `200 PaymentOrder` |
| POST | `/api/fees/orders/{orderId}/verify` | collect | `{ gatewayPaymentId, signature }` | `200 Receipt` |
| POST | `/api/me/children/{studentId}/fees/orders` | child.view, own child | `{ instalmentIds }` | `201 PaymentOrder` |
| GET | `/api/me/children/{studentId}/fees/orders/{orderId}` | child.view, own child | | `200 PaymentOrder` |
| POST | `/api/me/children/{studentId}/fees/orders/{orderId}/verify` | child.view, own child | `{ gatewayPaymentId, signature }` | `200 Receipt` |
| GET | `/api/payments/sandbox/orders/{gatewayOrderId}` | child.view (own child) or fees.collect | | `200 SandboxCheckout` (for the sandbox checkout page) |
| POST | `/api/payments/sandbox/orders/{gatewayOrderId}/complete` | same | `{ outcome: "SUCCESS" \| "FAILURE" }` | `200 { orderId, gatewayOrderId, status, gatewayPaymentId, signature }` |
| POST | `/api/public/payments/webhook/{gateway}` | none (signed) | raw gateway body | `200 { outcome }` |

```ts
type PaymentOrder = { id; gateway; gatewayOrderId; amountPaise; currency: "INR";
  status: "CREATED" | "PAID" | "FAILED"; studentId; studentName; instalments: string[];
  receiptId: string | null; failureReason: string | null };
```

- **Flow.** Create an order → the browser opens the gateway's checkout (here the web app's sandbox
  page `/app/pay/sandbox/{gatewayOrderId}`, which offers Success or Failure) → the checkout returns
  `gatewayPaymentId` and `signature` → `verify` checks the signature (`400 signature` if wrong) and
  records the payment as an `ONLINE` receipt. FAILURE delivers a signed `payment.failed` webhook.
- **Exactly once.** The order row is locked while a payment is recorded; verifying a paid order
  again with the same payment id returns the same receipt, a different payment id is `409`. The
  webhook verifies the signature first (`400 Invalid signature`), stores the gateway event id
  (unique per gateway), and answers `DUPLICATE` for a replayed event, `ALREADY_RECORDED` when
  `verify` got there first, `RECORDED`, `FAILED`, or `IGNORED` (unknown order or an amount that does
  not match). A webhook never records a payment twice.
- The webhook endpoint is public; it finds the school from the order id through the
  `SECURITY DEFINER` function `fees.payment_order_tenant(gateway, gateway_order_id)`, then runs as
  that school under row-level security. Unknown gateways answer `404`; bodies over 64 KB `400`.
- An online payment pays the chosen instalments first, then any other dues; anything left over is an
  `ADVANCE` line (it can only happen if dues changed while the payer was at the checkout). Online
  receipts show "Online payment" as the collector.

### Razorpay adapter (documented, not built)

A `RazorpayPaymentGateway implements PaymentGateway`, selected with
`akshara.payments.gateway=razorpay`:

- **Keys** (`key_id`, `key_secret`, `webhook_secret`) come from AWS Secrets Manager (for example
  `akshara/<env>/razorpay`), read at start-up through the Spring Cloud AWS Secrets Manager config
  import or the AWS SDK with the task's IAM role. Nothing is in the repository or in environment
  files; per-school keys (Razorpay Route or each school's own account) would be stored as a secret
  name per tenant.
- `createOrder` → `POST https://api.razorpay.com/v1/orders` with `{ amount, currency: "INR", receipt:
  <payment_order.id>, notes: { tenant, student } }`, Basic auth `key_id:key_secret`. Make this call
  outside the database transaction (create the `payment_order` row, commit, call Razorpay, then store
  `gatewayOrderId`), with a timeout and retries on the idempotent `receipt` field.
- `verifyPaymentSignature` = hex HMAC-SHA256(`order_id|payment_id`, `key_secret`) — the same scheme
  as the sandbox. `verifyWebhookSignature` = HMAC-SHA256(raw body, `webhook_secret`) against
  `X-Razorpay-Signature`; the event id is the `X-Razorpay-Event-Id` header.
- The web app loads `https://checkout.razorpay.com/v1/checkout.js` (CSP `script-src`, `frame-src`
  and `connect-src` must allow Razorpay) with the order id and posts the handler's
  `razorpay_payment_id` and `razorpay_signature` to `verify`.
- Webhook URL in the Razorpay dashboard: `https://<host>/api/public/payments/webhook/razorpay`,
  events `payment.captured` and `payment.failed`.

## Reports and reminders — `/api/fees`

| Method | Path | Permission | Success |
| --- | --- | --- | --- |
| GET | `/reports/overview` | read | `200 Overview`: today's and this month's collection, outstanding and overdue for the current year, overdue students, latest 8 receipts |
| GET | `/reports/collection?from&to` | read | `200 CollectionReport`: issued receipts by mode and by head, plus late fees and advances (range ≤ 731 days) |
| GET | `/reports/outstanding?yearId&classId&sectionId` | read | `200 OutstandingReport`: per class and section — students, net, paid, balance, due so far, overdue — and a total row |
| GET | `/reports/overdue?yearId&classId&sectionId&minDays` | read | `200 OverdueReport`: students with overdue dues, oldest first, with the parent's name, a **masked** phone (`98XXXXXX01`), instalments, days late, late fee and the last reminder |
| POST | `/reminders` | collect | `{ studentIds }` → `200 { requested, skipped }` |
| GET | `/reports/tally.csv?from&to` | read | `200 text/csv` for Tally import |

**Reminders.** For each student who is still overdue, a `fees.fee_reminder` row is stored (who asked,
amount, days late) and a `FeeReminderRequested` event is published; students who are no longer
overdue are skipped. The fees module sends nothing itself: the notifications module listens to the
event.

**Tally CSV** columns: `Date` (`dd-MM-yyyy`), `Receipt No`, `Ledger` (the fee head's name, `Late
fee` or `Advance fee`), `Amount` (rupees, two decimals), `Mode`, `Narration` ("Fee from Arjun
Sharma (AKS/2026/001), Class 5 A, Quarter 2, cheque 123456 State Bank of India"). One row per
receipt and ledger. A receipt cancelled within the range adds negative rows dated on the
cancellation day with narration "Cancelled receipt RCPT/…: reason", so each day's totals match the
cash book.

## Parent view

| Method | Path | Permission | Success |
| --- | --- | --- | --- |
| GET | `/api/me/children/{studentId}/fees` | child.view | `200 StudentFees` for the caller's own child; any other student is `404` |
| GET | `/api/me/children/{studentId}/fees/receipts/{receiptId}` | child.view | `200 Receipt` of that child; otherwise `404` |

Plus the order endpoints above. Parents see the school's dues, concessions and receipts for the
child and pay online; they never reach `/api/fees` (`403`).

## Events

Published with Spring's `ApplicationEventPublisher` inside the transaction that made the change;
listeners should use `@TransactionalEventListener` (after commit).

```java
record FeePaymentReceived(UUID tenantId, UUID receiptId, String receiptNo, UUID studentId,
    long amountPaise, PaymentMode mode, Instant receivedAt) {}        // every counter or online receipt
record FeeReminderRequested(UUID tenantId, UUID reminderId, UUID studentId, long overduePaise,
    long daysOverdue, LocalDate oldestDueDate) {}                       // one per reminded student
```

## Audit actions

`fee_head.created|updated`, `fee_structure.saved|published`, `concession.granted|revoked`,
`late_fee_rule.updated`, `late_fee.waived`, `fee_payment.recorded`, `receipt.cancelled`,
`fee_reminders.requested`, `payment_order.created|failed`. Entity types: `fee_head`,
`fee_structure`, `concession`, `late_fee_rule`, `late_fee_waiver`, `receipt`, `fee_reminder`,
`payment_order`. Details hold ids, receipt numbers, amounts and counts; never phone numbers.

## Data

Schema `fees` (`V6__fees.sql`): `fee_head`, `fee_structure`, `fee_instalment`,
`fee_instalment_share`, `late_fee_rule`, `concession`, `concession_head`, `student_due`,
`receipt_counter`, `receipt`, `payment_allocation`, `late_fee_waiver`, `payment_order`,
`gateway_event`, `fee_reminder`. Every table has `tenant_id`, the `tenant_isolation` policy and
composite `(tenant_id, id)` foreign keys; `receipt`, `payment_allocation`, `gateway_event` and
`fee_reminder` are insert-only for the application role apart from the receipt's cancellation
columns. Students, classes and years are referenced by id only (other modules' tables).

**Demo data** (`DemoFeesData`): 2026-27 structures for every class (tuition ₹27,000–₹63,000 by class,
annual charges, exam fee), four quarterly instalments due 10 Jun, 10 Sep, 10 Dec and 10 Feb, a late
fee of ₹10 a day after 7 days (at most ₹500), Q1 paid by most students in mixed modes, about 15%
with Q2 overdue, one part payment, one cancelled cheque, a 10% sibling concession for Diya Sharma
and RTE for two students. Accounts (`accounts@…`) collected the payments.
