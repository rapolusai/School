/** Types mirroring docs/api/phase-1-billing.md (plans, subscriptions, GST invoices, platform health). */

import type { Plan, TenantStatus, TenantSummary } from "../types";
import type { PlainDate } from "./school";

export const BILLING_CYCLES = ["YEARLY", "MONTHLY"] as const;
export type BillingCycle = (typeof BILLING_CYCLES)[number];

export const INVOICE_PAYMENT_MODES = ["BANK_TRANSFER", "UPI", "CHEQUE", "CARD"] as const;
export type InvoicePaymentMode = (typeof INVOICE_PAYMENT_MODES)[number];

export type InvoiceStatus = "ISSUED" | "PAID" | "CANCELLED";

export type TaxSplit = "CGST_SGST" | "IGST";

export type NoticeKind = "TRIAL_ENDING" | "TRIAL_ENDED" | "PAYMENT_OVERDUE";

export type RenewalKind = "OVERDUE" | "DUE_FOR_RENEWAL" | "TRIAL_ENDING";

/** A plan of the catalogue. Prices are per student before GST; `schools` is for the Super Admin only. */
export type PlanView = {
  plan: Plan;
  pricePerStudentPerYearPaise: number;
  pricePerStudentPerMonthPaise: number;
  maxStudents: number | null;
  maxBranches: number | null;
  /** Feature codes, shown with billing.feature.<code>. */
  features: string[];
  notIncluded: string[];
  popular: boolean;
  schools: number | null;
};

/** The school as the buyer on its invoices. */
export type BillingDetails = {
  legalName: string | null;
  address: string | null;
  stateCode: string | null;
  gstin: string | null;
};

export type SubscriptionView = {
  billingCycle: BillingCycle;
  billedStudents: number;
  periodStart: PlainDate;
  periodEnd: PlainDate;
  nextRenewalOn: PlainDate;
  paidSince: PlainDate;
  unitPricePaise: number;
  nextInvoiceTaxablePaise: number;
};

export type Suspension = { suspendedAt: string; reason: string };

export type InvoiceSummary = {
  id: string;
  invoiceNo: string;
  invoiceDate: PlainDate;
  dueDate: PlainDate;
  periodStart: PlainDate;
  periodEnd: PlainDate;
  plan: Plan;
  billingCycle: BillingCycle;
  totalPaise: number;
  paidPaise: number;
  balancePaise: number;
  status: InvoiceStatus;
  overdue: boolean;
};

export type Party = { name: string; address: string | null; stateCode: string; gstin: string | null };

export type InvoicePayment = {
  id: string;
  amountPaise: number;
  mode: InvoicePaymentMode;
  reference: string;
  paidOn: PlainDate;
  recordedByName: string;
  recordedAt: string;
};

/** A GST tax invoice. Rates are in basis points (900 = 9%). `sample` is true while the seller's GSTIN is a placeholder. */
export type Invoice = {
  id: string;
  invoiceNo: string;
  financialYear: string;
  invoiceDate: PlainDate;
  dueDate: PlainDate;
  seller: Party;
  buyer: Party;
  sacCode: string;
  plan: Plan;
  billingCycle: BillingCycle;
  periodStart: PlainDate;
  periodEnd: PlainDate;
  billedStudents: number;
  unitPricePaise: number;
  taxablePaise: number;
  taxSplit: TaxSplit;
  cgstRateBp: number;
  sgstRateBp: number;
  igstRateBp: number;
  cgstPaise: number;
  sgstPaise: number;
  igstPaise: number;
  totalPaise: number;
  amountInWords: string;
  status: InvoiceStatus;
  paidPaise: number;
  balancePaise: number;
  paidOn: PlainDate | null;
  overdue: boolean;
  issuedByName: string;
  cancelledAt: string | null;
  cancelledByName: string | null;
  cancelReason: string | null;
  payments: InvoicePayment[];
  sample: boolean;
};

/** The banner for a school's admins. `kind` is null when there is nothing to say. */
export type BillingNotice = {
  kind: NoticeKind | null;
  trialEndsAt: string | null;
  trialDaysLeft: number | null;
  overduePaise: number;
  overdueInvoices: number;
  oldestDueDate: PlainDate | null;
};

/** GET /api/billing: the school's own Billing page. */
export type SchoolBilling = {
  status: TenantStatus;
  plan: Plan;
  trialEndsAt: string | null;
  trialDaysLeft: number | null;
  activeStudents: number;
  plans: PlanView[];
  subscription: SubscriptionView | null;
  details: BillingDetails;
  invoices: InvoiceSummary[];
  notice: BillingNotice;
};

/** GET /api/platform/billing/schools/{id}: one school's billing as the Super Admin manages it. */
export type SchoolAccount = {
  school: TenantSummary;
  activeStudents: number;
  subscription: SubscriptionView | null;
  details: BillingDetails;
  suspension: Suspension | null;
  invoices: InvoiceSummary[];
  unpaidPaise: number;
  overduePaise: number;
  notice: BillingNotice;
};

export type RenewalRow = {
  tenantId: string;
  name: string;
  code: string;
  status: TenantStatus;
  plan: Plan;
  kind: RenewalKind;
  date: PlainDate;
  /** Days left (negative: days past). */
  days: number;
  amountPaise: number | null;
  invoices: number | null;
};

export type PlatformHealth = {
  checkedAt: string;
  schools: { total: number; byStatus: Record<TenantStatus, number> } | null;
  users: number | null;
  activeStudents: number | null;
  outbox: { queued: number; failed: number } | null;
  database: { reachable: boolean; latencyMs: number | null };
  app: { version: string; startedAt: string; uptimeSeconds: number };
};

export type BillingDetailsRequest = {
  legalName: string | null;
  address: string | null;
  stateCode: string;
  gstin: string | null;
};

export type StartSubscriptionRequest = {
  plan: Plan;
  billingCycle: BillingCycle;
  billedStudents: number;
  periodStart: PlainDate | null;
};

export type ChangeSubscriptionRequest = {
  plan: Plan;
  billingCycle: BillingCycle | null;
  billedStudents: number | null;
};

export type InvoicePaymentRequest = {
  amountPaise: number | null;
  mode: InvoicePaymentMode;
  reference: string;
  paidOn: PlainDate;
};

/** GST state codes (first two digits of a GSTIN), with names in billing.state.<code>. */
export const GST_STATE_CODES = [
  "01", "02", "03", "04", "05", "06", "07", "08", "09", "10", "11", "12", "13", "14", "15", "16", "17", "18",
  "19", "20", "21", "22", "23", "24", "26", "27", "29", "30", "31", "32", "33", "34", "35", "36", "37", "38",
] as const;
