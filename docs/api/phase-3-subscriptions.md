# Phase 3 API: school subscriptions (self-service)

Builds on [phase-1-billing.md](phase-1-billing.md) (plans, GST invoices, the Super Admin console). Same conventions:
JSON under `/api`, money in paise, dates in India, RFC 9457 problems, `404` for another school's ids.

## 1. Paying an invoice online

A School Admin pays an open subscription invoice from the school's Billing page (or the invoice's page). The money is
Akshara's revenue, so it goes through **Akshara's own payment gateway account**, configured separately from the
schools' fee gateway (`akshara.payments`, [phase-1-fees.md](phase-1-fees.md)):

| Property (env) | Default | Meaning |
| --- | --- | --- |
| `akshara.billing.payments.gateway` (`BILLING_PAYMENTS_GATEWAY`) | `sandbox` | `sandbox` (no network, no money) or `razorpay` |
| `akshara.billing.payments.sandbox-secret` (`BILLING_PAYMENTS_SANDBOX_SECRET`, else `PAYMENTS_SANDBOX_SECRET`) | random at startup | signs sandbox checkouts and webhooks |
| `akshara.billing.payments.razorpay.key-id` / `key-secret` / `webhook-secret` (`BILLING_RAZORPAY_KEY_ID`, `BILLING_RAZORPAY_KEY_SECRET`, `BILLING_RAZORPAY_WEBHOOK_SECRET`) | empty | Akshara's Razorpay account (test mode keys until launch); the API refuses to start with gateway `razorpay` and any of them missing |

The gateway implementations are the fees module's public `PaymentGateway` classes (`SandboxPaymentGateway`,
`RazorpayPaymentGateway`), built by the billing module with these keys (`withSecret`, `withKeys`); the billing module
never touches fee orders. Credentials never go in configuration files.

| Method | Path | Permission | Body | Success |
| --- | --- | --- | --- | --- |
| POST | `/api/billing/invoices/{id}/orders` | `billing.read` + `settings.manage` | | `201 InvoiceOrderView`; the order already open for the invoice (same gateway and amount) is returned again; `409` when the invoice is paid or cancelled; another school's invoice `404` |
| GET | `/api/billing/orders/{orderId}` | same | | `200 InvoiceOrderView` |
| POST | `/api/billing/orders/{orderId}/verify` | same | `{ gatewayPaymentId, signature }` | `200 InvoiceView`; `400 signature` when it does not match; the same payment again returns the invoice; another payment on a paid order `409` |
| GET | `/api/billing/sandbox/orders/{gatewayOrderId}` | same | | `200 SandboxInvoiceCheckout` (sandbox only) |
| POST | `/api/billing/sandbox/orders/{gatewayOrderId}/complete` | same | `{ outcome: "SUCCESS" \| "FAILURE" }` | `200 InvoiceCheckoutResult`: a payment id and signature to verify, or a signed `payment.failed` webhook delivered (order `FAILED`) |
| POST | `/api/public/billing/webhook/{gateway}` | none (signed) | the gateway's raw body | `200 { outcome }`: `RECORDED`, `ALREADY_RECORDED`, `FAILED`, `IGNORED` or `DUPLICATE`; `400` for a bad signature |

```ts
type InvoiceOrderView = { id: string; invoiceId: string; invoiceNo: string; gateway: "sandbox" | "razorpay";
  gatewayOrderId: string; amountPaise: number; currency: "INR"; status: "CREATED" | "PAID" | "FAILED";
  failureReason: string | null; checkoutKey: string | null /* Razorpay key id; null for the sandbox */;
  payeeName: string /* the seller on the invoice */ };
```

- **Whole balance only.** An order is for everything still due on the invoice.
- **Exactly once.** The payment is recorded by whichever arrives first: the browser's verification (checkout
  signature, HMAC-SHA256 of `order_id|payment_id` with the key secret) or the gateway's signed webhook
  (`payment.captured` / `order.paid`). Both lock the order row; every webhook event id is stored
  (`billing.gateway_event`), so replays answer `DUPLICATE`; an ONLINE invoice payment's reference (the gateway payment
  id) is unique in the database.
- **No double payment.** Orders for one invoice are made one at a time (`pg_advisory_xact_lock` on the invoice) and an
  open order is reused, so two tabs or repeated clicks share one gateway order, which takes one payment.
- **Recorded like a manual payment.** The same code path as the Super Admin's `POST …/payments`: an
  `InvoicePayment` with mode `ONLINE`, reference = gateway payment id, recorded by "Online payment", the invoice
  `PAID` once nothing is left, `invoice.payment_recorded` in the audit trail (with `gateway` and `gatewayOrderId`) and
  `SubscriptionInvoicePaid`. Manual payments never change a school's status, and neither do online ones; a school the
  renewal job marked past due goes back to active once nothing is overdue (section 2).
- If the invoice was paid by hand or cancelled after the order was made, a captured payment is not recorded on it:
  the order is `PAID` with `failureReason` asking for a refund at the gateway, audited as
  `invoice_order.refund_needed`.
- The Super Admin cannot record mode `ONLINE` by hand (`400 mode`).
- Razorpay: point a webhook in Akshara's Razorpay dashboard at `https://<domain>/api/public/billing/webhook/razorpay`
  for `payment.captured`, `order.paid` and `payment.failed`.

Web app: "Pay online" on each open invoice for School Admins; with Razorpay its checkout opens over the page, with the
sandbox the app's own page `/app/billing/pay/sandbox/{gatewayOrderId}` stands in for it and lands on the paid invoice.

Audit actions: `invoice_order.created`, `invoice_order.failed`, `invoice_order.refund_needed`.

Data (`V32__subscription_online_payments.sql`): `billing.payment_order` and `billing.gateway_event` with `tenant_id`,
the `tenant_isolation` policy and composite keys; `billing.payment_order_tenant(gateway, gatewayOrderId)` (security
definer) finds a webhook's school; `invoice_payment.mode` allows `ONLINE`.

## 2. Automatic renewals and suspension

`BillingRenewalJob` runs `BillingRenewals` once a day (`akshara.billing.renewals.cron`, default `0 15 2 * * *` India
time; `BILLING_RENEWALS_ENABLED=false` switches it off, and the `migrate` profile and tests switch it off). A round
takes `pg_try_advisory_xact_lock('billing.renewals')`, so with several API instances only one runs it and the others
skip; each school is then handled in its own transaction, as that school, with its subscription row locked, and every
step re-reads the current state, so a second run on the same day changes nothing. Suspended schools are left alone.
For each school, in order:

1. **Renewal.** A paying school whose next period starts within `renewals.lead-days` (default `0`: the day after the
   current period ends) and whose current period has a live invoice moves into the next period and gets its invoice
   from the same `InvoiceIssuer` as the Super Admin's "Issue next invoice" (dated the job's day, issued by
   "Akshara billing"), audited as `subscription.renewed` and `invoice.issued`. A period whose invoice was cancelled,
   or billed students the plan no longer covers, are left to the Super Admin.
2. **Past due.** An `ACTIVE` or `TRIAL` school with an unpaid invoice past its due date, or whose trial ended without
   a paid subscription, becomes `PAST_DUE` (`school.status_changed` with `reason` `invoice_overdue` or `trial_ended`
   and `automatic: true`). A school the job marked past due becomes `ACTIVE` again as soon as nothing is overdue
   (`reason: "settled"`): when the last overdue invoice is paid (by hand or online), when a lapsed trial starts paying,
   or at the next run. A school the Super Admin marked past due by hand is never made active by the job.
3. **Suspension.** A `PAST_DUE` school is suspended through the same path as the Super Admin's suspension
   (`school.suspended` with `automatic: true`, `SchoolSuspended`) once its oldest overdue invoice is more than
   `renewals.grace-days` (default `15`) past its due date, or its trial ended more than `renewals.trial-grace-days`
   (default `7`) ago. The grace period also counts from the school's last reactivation, so a school the Super Admin
   reactivates gets a whole new grace period; it goes back to `PAST_DUE` and is made active once it pays.

| Property (env) | Default |
| --- | --- |
| `akshara.billing.renewals.enabled` (`BILLING_RENEWALS_ENABLED`) | `true` |
| `akshara.billing.renewals.cron` (`BILLING_RENEWALS_CRON`) | `0 15 2 * * *` |
| `akshara.billing.renewals.lead-days` (`BILLING_RENEWAL_LEAD_DAYS`) | `0` (at most `renewal-window-days`) |
| `akshara.billing.renewals.grace-days` (`BILLING_GRACE_DAYS`) | `15` |
| `akshara.billing.renewals.trial-grace-days` (`BILLING_TRIAL_GRACE_DAYS`) | `7` |

The job's clock is injectable (`BillingRenewalJob(renewals, Clock)`), and `BillingRenewals.runFor(school, day,
instant)` runs one school as of a given day; tests use fixed dates.

Data (`V33__subscription_renewals.sql`): `billing.subscription.auto_past_due_since` (the job marked the school past
due) and `grace_restarted_on` (the last reactivation).

## 3. Plan limits

The plans' limits ([phase-1-billing.md](phase-1-billing.md), "Plans") are enforced by the API, not only shown on the
Billing page. **They apply once the school pays** (status `ACTIVE` or `PAST_DUE`): a school in its trial can try every
module and load its whole roll before choosing a plan.

**Student limit.** Adding students that would take the school's students on roll (status `ACTIVE`) past the plan's
`maxStudents` is refused with `409` and problem type `urn:akshara:problem:plan-student-limit`, whichever way they are
added: one by hand (`POST /api/students`), admitted from admissions (`POST /api/admissions/applications/{id}/admit`),
imported from CSV (`POST /api/students/import`) or from the school workbook (`POST /api/school-import`). An import is
all or nothing, so a file that does not fit writes nothing. A student who leaves frees a place. One school's additions
are counted one at a time (an advisory lock to the end of the transaction), so two at once cannot both take the last
place. The students module asks through its `EnrolmentLimit` interface, which the billing module implements.

```json
{ "type": "urn:akshara:problem:plan-student-limit", "title": "Student limit reached", "status": 409,
  "detail": "The STARTER plan covers up to 300 students on roll. …", "plan": "STARTER", "maxStudents": 300,
  "activeStudents": 300, "adding": 1 }
```

**Features.** A plan's `notIncluded` features that switch something off are mapped, in one place
(`billing.PlanFeatures`), to app modules (the web app's menu keys) and API paths:

| Feature | Modules | API paths |
| --- | --- | --- |
| `transport_library` | `transport`, `library`, `inventory` | `/api/transport/**`, `/api/me/transport`, `/api/library/**`, `/api/me/library`, `/api/inventory/**` |
| `report_cards` | `report_cards` | `/api/exams/report-cards/**`, `/api/me/report-card`, `/api/me/children/*/report-card` |

`api_access` has no switch yet (there is no public API to turn off); branches (`maxBranches`) are not enforced, since
the app has no branches. Bus devices (`/api/public/transport/…`) are never gated. A request to a gated path from a
school whose plan leaves the feature out is refused before the endpoint's own permission check:

```json
{ "type": "urn:akshara:problem:plan-feature", "title": "Not in your plan", "status": 403,
  "detail": "The school's STARTER plan does not include this. …", "feature": "transport_library", "plan": "STARTER" }
```

| Method | Path | Permission | Success |
| --- | --- | --- | --- |
| GET | `/api/billing/plan-access` | any signed-in member of a school | `200 PlanAccessView`; the platform console `404` |

```ts
type PlanAccessView = { plan: Plan; status: TenantStatus; enforced: boolean /* false in the trial */;
  excludedFeatures: string[] /* gated features the plan leaves out */; lockedFeatures: string[] /* locked now */;
  lockedModules: ("transport" | "library" | "inventory" | "report_cards")[]; maxStudents: number | null;
  activeStudents: number };
```

Web app: the shell fetches the plan access once per sign-in (and again after a plan change). A menu item of a locked
module shows a lock and "Upgrade" and leads to `/app/billing?upgrade=<module>` for someone who can see Billing (a
School Admin), and is hidden from everyone else; the Billing page says why and shows students on roll against the
limit. Both problem types have their own translated messages, so a refused request never reads as "no permission".
