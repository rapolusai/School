# Phase 1 API: billing (Super Admin console)

Akshara's own billing of schools: plans, subscriptions, GST tax invoices, payments the Super Admin records by hand,
renewals, manual suspension and a platform health page. Same conventions as [Phase 0](phase-0.md) and
[Phase 1](phase-1.md): JSON under `/api`, `Authorization: Bearer <accessToken>`, RFC 9457 problem details, `404` for
ids of another school in a path, `400` with a field error, `409` for conflicts.

- **Money is in paise** (₹1 = 100 paise) as whole numbers. Rates are in basis points (`900` = 9%).
- Dates are `"YYYY-MM-DD"`; "today" is today in India (Asia/Kolkata). Periods are inclusive.
- The **financial year** runs April to March (`2026-27`). Invoice numbers belong to a financial year.
- **Nothing happens automatically.** No school is ever suspended, marked past due or invoiced by a job: the renewals
  list only shows what needs the Super Admin's attention.
- **No payment gateway.** Schools pay Akshara by bank transfer, UPI, cheque or card outside the app; the Super Admin
  records what arrived. Schools never pay from their Billing page.

## Permissions

| Permission | Who has it | Allows |
| --- | --- | --- |
| `platform.admin` | Super Admin (platform users only) | every `/api/platform/**` endpoint below |
| `billing.read` (new) | School Admin | `/api/billing`, `/api/billing/notice`, `/api/billing/invoices/{id}` |
| `billing.read` + `settings.manage` | School Admin | `PUT /api/billing/details` |

`billing.read` is in `Permissions.ALL_SCHOOL`, so new schools' School Admin role gets it from `RoleCatalog`;
`V10__platform_billing.sql` appends it once to the built-in School Admin role of every existing school. Other built-in
roles get `403` from `/api/billing`; school users get `403` from `/api/platform/**`.

## Plans

| Plan | Per student a year | A month (monthly billing) | Students | Branches |
| --- | --- | --- | --- | --- |
| `STARTER` | ₹83 (`8300`) | ₹8.30 | up to 300 | 1 |
| `GROWTH` | ₹200 (`20000`) | ₹20 | up to 1,500 | 3 |
| `ENTERPRISE` | ₹500 (`50000`) | ₹50 | unlimited | unlimited |

Prices are before GST and kept in code (`PlanCatalog`). They follow the prototype's list prices of ₹2,499, ₹5,999 and
₹14,999 a month for a school of 300 students billed yearly (a year = ten months), and are placeholders until the owner
sets real ones. Yearly billing gets two months free: a month costs a tenth of the yearly price, rounded half up to whole
paise. An invoice is `billedStudents × unit price` for one period; the Super Admin chooses the billed students (the
console suggests the students on roll) and a plan's student limit is enforced.

## Super Admin — `/api/platform`

The Super Admin has no school of their own. Each `/billing/schools/{id}/…` endpoint first looks up the school (`404`
when unknown) and then works as that school, so invoices and payments are read and written under row-level security,
and every change goes to that school's audit trail with the Super Admin's name.

| Method | Path | Body | Success |
| --- | --- | --- | --- |
| GET | `/health` | | `200 PlatformHealth` |
| GET | `/billing/plans` | | `200 PlanView[]` with `schools` on each plan |
| GET | `/billing/renewals` | | `200 RenewalRow[]`, most urgent first |
| GET | `/billing/schools/{id}` | | `200 SchoolAccount` |
| PUT | `/billing/schools/{id}/details` | `DetailsForm` | `200 SchoolAccount` |
| POST | `/billing/schools/{id}/subscription` | `StartForm` | `201 SchoolAccount`; issues the first invoice; `409` when already paying or suspended, or the state is not set |
| PUT | `/billing/schools/{id}/subscription` | `ChangeForm` | `200 SchoolAccount`; applies from the next invoice, no proration |
| POST | `/billing/schools/{id}/invoices` | | `201 InvoiceView`: the next invoice (see Renewals); `409` too early, not paying, over the plan's limit |
| GET | `/billing/schools/{id}/invoices/{invoiceId}` | | `200 InvoiceView`; another school's invoice is `404` |
| POST | `/billing/schools/{id}/invoices/{invoiceId}/payments` | `PaymentForm` | `200 InvoiceView`; `409` when paid or cancelled; `400 amountPaise` above the balance, `400 paidOn` in the future |
| POST | `/billing/schools/{id}/invoices/{invoiceId}/cancel` | `{ reason }` | `200 InvoiceView`; `409` when already cancelled or anything is paid |
| POST | `/billing/schools/{id}/status` | `{ status: "ACTIVE" \| "PAST_DUE" }` | `200 SchoolAccount`; `400` for other statuses, `409` on trial or suspended |
| POST | `/billing/schools/{id}/suspend` | `{ reason }` (≤500) | `200 SchoolAccount`; `409` when already suspended |
| POST | `/billing/schools/{id}/reactivate` | | `200 SchoolAccount`: back to the status before the suspension; `409` when not suspended |

```ts
type Plan = "STARTER" | "GROWTH" | "ENTERPRISE";
type BillingCycle = "MONTHLY" | "YEARLY";
type DetailsForm = { legalName?: string /* ≤200, defaults to the school's name */; address?: string /* ≤500 */;
  stateCode: string /* two-digit GST state code: the place of supply */; gstin?: string | null /* optional */ };
type StartForm = { plan: Plan; billingCycle: BillingCycle; billedStudents: number /* 1–100000 */;
  periodStart?: string /* today by default; up to a year back or 90 days ahead */ };
type ChangeForm = { plan: Plan; billingCycle?: BillingCycle; billedStudents?: number }; // both required when paying
type PaymentForm = { amountPaise?: number /* the balance by default */;
  mode: "BANK_TRANSFER" | "UPI" | "CHEQUE" | "CARD"; reference: string /* UTR etc., ≤100 */; paidOn: string };

type SchoolAccount = { school: TenantSummary; activeStudents: number; subscription: SubscriptionView | null;
  details: BillingDetails; suspension: { suspendedAt: string; reason: string } | null;
  invoices: InvoiceSummary[]; unpaidPaise: number; overduePaise: number; notice: Notice };
type SubscriptionView = { billingCycle: BillingCycle; billedStudents: number; periodStart: string; periodEnd: string;
  nextRenewalOn: string; paidSince: string; unitPricePaise: number; nextInvoiceTaxablePaise: number };
type RenewalRow = { tenantId: string; name: string; code: string; status: TenantStatus; plan: Plan;
  kind: "OVERDUE" | "DUE_FOR_RENEWAL" | "TRIAL_ENDING"; date: string; days: number /* negative: days past */;
  amountPaise: number | null; invoices: number | null };
type PlatformHealth = { checkedAt: string;
  schools: { total: number; byStatus: Record<TenantStatus, number> } | null; users: number | null;
  activeStudents: number | null; outbox: { queued: number; failed: number } | null;
  database: { reachable: boolean; latencyMs: number | null };
  app: { version: string; startedAt: string; uptimeSeconds: number } };
```

Billing details: the GSTIN, when given, must have the right shape and check character (mod-36), and its first two
digits must be the chosen state (`400 gstin`). Details apply to future invoices only; issued invoices keep theirs.
A school needs a state before it can start paying or be invoiced.

## School — `/api/billing`

| Method | Path | Permission | Body | Success |
| --- | --- | --- | --- | --- |
| GET | `/api/billing` | `billing.read` | | `200 SchoolBilling` |
| GET | `/api/billing/notice` | `billing.read` | | `200 Notice`: what the banner on every page says |
| GET | `/api/billing/invoices/{id}` | `billing.read` | | `200 InvoiceView`; another school's invoice is `404` |
| PUT | `/api/billing/details` | `billing.read` + `settings.manage` | `DetailsForm` | `200 SchoolBilling` |

```ts
type SchoolBilling = { status: TenantStatus; plan: Plan; trialEndsAt: string | null;
  trialDaysLeft: number | null /* rounded up, like the dashboard's trial banner */; activeStudents: number;
  plans: PlanView[]; subscription: SubscriptionView | null; details: BillingDetails;
  invoices: InvoiceSummary[]; notice: Notice };
type Notice = { kind: "TRIAL_ENDING" | "TRIAL_ENDED" | "PAYMENT_OVERDUE" | null; trialEndsAt: string | null;
  trialDaysLeft: number | null; overduePaise: number; overdueInvoices: number; oldestDueDate: string | null };
```

`PAYMENT_OVERDUE` when an unpaid invoice is past its due date or the school is marked past due; `TRIAL_ENDING` in the
last 7 days of a trial (`akshara.billing.trial-warning-days`); `TRIAL_ENDED` after it. The web app shows the banner to
School Admins on every page except Billing (on the dashboard only when overdue, since the dashboard has its own trial
banner).

## GST invoices

- **Seller** (Akshara) from properties `akshara.billing.seller.*` (env `BILLING_SELLER_NAME`, `_ADDRESS`,
  `_STATE_CODE`, `_GSTIN`). The defaults are placeholders: "Akshara School Cloud (sample seller)", state `36` and the
  GSTIN `36XXXXX0000X1ZX`, which can never be valid. While the seller's GSTIN is not valid, every invoice has
  `sample: true` and says it is not a valid tax invoice. No real GSTIN is in the repository.
- **Buyer** is the school's billing details at the time of issue; its GSTIN is optional ("not registered").
- **Tax** is 18% of the taxable value. Seller and buyer in the same state: CGST 9% + SGST 9%, each rounded half up to
  whole paise (`taxSplit: "CGST_SGST"`); otherwise IGST 18% (`"IGST"`). The place of supply is the buyer's state.
- **SAC** `998315` (hosting and IT infrastructure provisioning, which covers software as a service;
  `akshara.billing.sac-code`), to be confirmed by Akshara's tax adviser. The amount in words is in Indian numbering
  ("Rupees Thirteen Thousand Nine Hundred Twenty Four Only").
- **Numbers** look like `AKS/26-27/000001` (16 characters at most, prefix `akshara.billing.invoice-prefix`), one
  series for all schools, **gapless within a financial year**: `billing.invoice_counter` has one row per year, locked
  `FOR UPDATE` while the invoice is written, so concurrent invoices get consecutive numbers and an invoice that rolls
  back gives its number back. A cancelled invoice keeps its number.
- **Due** 15 days after the invoice date (`payment-terms-days`), or on the period's first day if later.
- **Immutable.** The runtime role may insert invoices and payments but may only update an invoice's status, payment
  and cancellation columns, and may never delete either. Cancelling needs a reason and is allowed only while nothing
  is paid; the period can then be invoiced again.

`InvoiceView`: `id, invoiceNo, financialYear, invoiceDate, dueDate, seller, buyer` (`{ name, address, stateCode,
gstin }`), `sacCode, plan, billingCycle, periodStart, periodEnd, billedStudents, unitPricePaise, taxablePaise,
taxSplit, cgstRateBp, sgstRateBp, igstRateBp, cgstPaise, sgstPaise, igstPaise, totalPaise, amountInWords, status`
(`ISSUED | PAID | CANCELLED`), `paidPaise, balancePaise, paidOn, overdue, issuedByName, cancelledAt, cancelledByName,
cancelReason, payments[]` (`{ id, amountPaise, mode, reference, paidOn, recordedByName, recordedAt }`), `sample`.
The web app prints it from `/app/billing/invoices/{id}` (school) and `/app/platform/schools/{id}/invoices/{invoiceId}`
(Super Admin); only the invoice prints.

## Renewals

`GET /billing/renewals` lists, most urgent first: `OVERDUE` (unpaid invoices past their due date; date = the oldest
due date), `DUE_FOR_RENEWAL` (paying schools whose next period starts within 30 days, `renewal-window-days`, or has
started, with no invoice yet; amount = the next invoice with GST, or before GST while the state is not set) and `TRIAL_ENDING` (trials ending within 7 days or
ended). Suspended schools are left out. The Super Admin issues the next invoice with `POST …/invoices`: when the
current period's invoice was cancelled it re-invoices that period, otherwise it renews into the next period, which is
allowed from 30 days before it starts (`409` before). Monthly periods run calendar month to calendar month from the
start day (a period starting on the 29th–31st moves to the 28th once).

## Suspension

- The Super Admin suspends with a reason; the school's previous status is kept and comes back on reactivation.
- While suspended, **sign-in and refresh are refused** (`401`). Only someone who gives the right email and password
  learns why: their problem has `type: "urn:akshara:problem:school-paused"` and the message "Sign-in to this school is
  paused at the moment. Please contact the school office." Wrong passwords and unknown users get the usual generic
  message, so nothing is revealed to anyone else. The failed sign-in is audited as `auth.login_failed` with
  `reason: "school_suspended"`.
- Sessions end at their next refresh (access tokens live up to 15 minutes); refresh tokens refused while suspended
  stay revoked after reactivation.
- **Public pages refuse politely:** the admission info and enquiry form of a suspended school are `404`, which the
  web app shows as "Enquiry form not available".
- A suspended school cannot start a subscription or change status (`409`) and is left out of the renewals list.

## Platform health

Schools by status, people who can sign in, active students, the notifications outbox (queued and failed), database
reachability and latency, and the app's version and uptime. Cross-school counts come from `security definer`
functions that return counts and sums only (`billing.tenant_student_counts()`, `billing.outbox_counts()`,
`billing.unpaid_invoice_totals(date)`, like V1's `platform.tenant_user_counts()`), with `execute` revoked from public
and granted to the runtime role only. Row-level security is not relaxed: the runtime role without a school still sees
no school's rows. Counts are `null` when the database cannot be reached.

## Events

Published in the transaction that made the change, from the `com.akshara.billing` package:
`SchoolSuspended(tenantId, suspendedAt)`, `SchoolReactivated(tenantId, status)`,
`SubscriptionInvoiceIssued(tenantId, invoiceId, invoiceNo, totalPaise, dueDate)` and
`SubscriptionInvoicePaid(tenantId, invoiceId, invoiceNo, totalPaise, paidOn)`. Nothing listens yet.

## Audit actions

`billing_details.updated`, `subscription.started`, `subscription.changed`, `invoice.issued`,
`invoice.payment_recorded`, `invoice.cancelled`, `school.status_changed`, `school.suspended` (`reason`,
`previousStatus`), `school.reactivated` (`status`), with English and Hindi labels in the web app.

## Data (`V10__platform_billing.sql`)

- `billing.subscription`: one row per school (cycle, billed students, current period, billing details, suspension).
  Platform data like `platform.tenant`: no row-level security; the API shows a school only its own. Listed as not
  school-owned in `RowLevelSecurityCoverageIT`.
- `billing.invoice_counter`: Akshara's invoice series, one row per financial year. Platform data.
- `billing.invoice` and `billing.invoice_payment`: `tenant_id`, the `tenant_isolation` policy (`USING` and
  `WITH CHECK`), composite `(tenant_id, id)` foreign keys, grants to the runtime role only. One live invoice per
  school and period (a partial unique index ignores cancelled ones).

No personal data is added: billing details are the school's (legal name, address, state, GSTIN).

## Demo data

With `DEMO_ENABLED=true` the demo school gets billing details in Telangana, a yearly Growth subscription for every
student on roll from 1 April of the current financial year, and its invoice, paid in full by bank transfer
(reference `DEMO-UTR-000001`). The school becomes `ACTIVE`.
