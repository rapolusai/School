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
