import { apiFetch, pathSegment } from "./api";
import type {
  BillingDetailsRequest,
  BillingNotice,
  ChangeSubscriptionRequest,
  Invoice,
  InvoiceCheckoutResult,
  InvoiceOrder,
  InvoicePaymentRequest,
  PlanAccess,
  PlanView,
  PlatformHealth,
  RenewalRow,
  SandboxInvoiceCheckout,
  SchoolAccount,
  SchoolBilling,
  StartSubscriptionRequest,
  TenantStatus,
} from "./types";

/* Billing endpoints (docs/api/phase-1-billing.md). Kept apart from api.ts so the slices merge cleanly. */

const id = pathSegment;
const school = (tenantId: string) => `/api/platform/billing/schools/${id(tenantId)}`;

/** The school's own billing (billing.read): plan, trial, invoices and the banner. */
export const billingApi = {
  mine: () => apiFetch<SchoolBilling>("/api/billing"),
  notice: () => apiFetch<BillingNotice>("/api/billing/notice"),
  /** What the school's plan allows now (any signed-in member of a school). */
  planAccess: () => apiFetch<PlanAccess>("/api/billing/plan-access"),
  invoice: (invoiceId: string) => apiFetch<Invoice>(`/api/billing/invoices/${id(invoiceId)}`),
  updateDetails: (body: BillingDetailsRequest) =>
    apiFetch<SchoolBilling>("/api/billing/details", { method: "PUT", body }),
  /** Starts (or reuses) an online payment of the invoice's whole balance (billing.read + settings.manage). */
  payOnline: (invoiceId: string) =>
    apiFetch<InvoiceOrder>(`/api/billing/invoices/${id(invoiceId)}/orders`, { method: "POST" }),
  order: (orderId: string) => apiFetch<InvoiceOrder>(`/api/billing/orders/${id(orderId)}`),
  verify: (orderId: string, body: { gatewayPaymentId: string; signature: string }) =>
    apiFetch<Invoice>(`/api/billing/orders/${id(orderId)}/verify`, { method: "POST", body }),
  sandboxCheckout: (gatewayOrderId: string) =>
    apiFetch<SandboxInvoiceCheckout>(`/api/billing/sandbox/orders/${id(gatewayOrderId)}`),
  sandboxComplete: (gatewayOrderId: string, outcome: "SUCCESS" | "FAILURE") =>
    apiFetch<InvoiceCheckoutResult>(`/api/billing/sandbox/orders/${id(gatewayOrderId)}/complete`, {
      method: "POST",
      body: { outcome },
    }),
};

/** The Super Admin's console (platform.admin). */
export const platformBillingApi = {
  health: () => apiFetch<PlatformHealth>("/api/platform/health"),
  plans: () => apiFetch<PlanView[]>("/api/platform/billing/plans"),
  renewals: () => apiFetch<RenewalRow[]>("/api/platform/billing/renewals"),
  account: (tenantId: string) => apiFetch<SchoolAccount>(school(tenantId)),
  updateDetails: (tenantId: string, body: BillingDetailsRequest) =>
    apiFetch<SchoolAccount>(`${school(tenantId)}/details`, { method: "PUT", body }),
  start: (tenantId: string, body: StartSubscriptionRequest) =>
    apiFetch<SchoolAccount>(`${school(tenantId)}/subscription`, { method: "POST", body }),
  change: (tenantId: string, body: ChangeSubscriptionRequest) =>
    apiFetch<SchoolAccount>(`${school(tenantId)}/subscription`, { method: "PUT", body }),
  issueNext: (tenantId: string) => apiFetch<Invoice>(`${school(tenantId)}/invoices`, { method: "POST" }),
  invoice: (tenantId: string, invoiceId: string) =>
    apiFetch<Invoice>(`${school(tenantId)}/invoices/${id(invoiceId)}`),
  recordPayment: (tenantId: string, invoiceId: string, body: InvoicePaymentRequest) =>
    apiFetch<Invoice>(`${school(tenantId)}/invoices/${id(invoiceId)}/payments`, { method: "POST", body }),
  cancelInvoice: (tenantId: string, invoiceId: string, reason: string) =>
    apiFetch<Invoice>(`${school(tenantId)}/invoices/${id(invoiceId)}/cancel`, {
      method: "POST",
      body: { reason },
    }),
  setStatus: (tenantId: string, status: Extract<TenantStatus, "ACTIVE" | "PAST_DUE">) =>
    apiFetch<SchoolAccount>(`${school(tenantId)}/status`, { method: "POST", body: { status } }),
  suspend: (tenantId: string, reason: string) =>
    apiFetch<SchoolAccount>(`${school(tenantId)}/suspend`, { method: "POST", body: { reason } }),
  reactivate: (tenantId: string) =>
    apiFetch<SchoolAccount>(`${school(tenantId)}/reactivate`, { method: "POST", body: {} }),
};
