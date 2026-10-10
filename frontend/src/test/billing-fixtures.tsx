import { render } from "@testing-library/react";
import { vi } from "vitest";
import { ToastProvider } from "@/components/ui/toast";
import { AuthContext, type AuthContextValue } from "@/lib/auth";
import type {
  BillingNotice,
  Invoice,
  InvoiceSummary,
  Me,
  PlanView,
  PlatformHealth,
  RenewalRow,
  SchoolAccount,
  SchoolBilling,
} from "@/lib/types";

/** Test-only billing data: Sunrise Public School in Telangana, Akshara's sample seller also in Telangana. */

export const SCHOOL_ADMIN_BILLING = ["dashboard.view", "settings.manage", "billing.read", "users.read"];
export const BILLING_READ_ONLY = ["dashboard.view", "billing.read"];

export const PLANS: PlanView[] = [
  {
    plan: "STARTER",
    pricePerStudentPerYearPaise: 83_00,
    pricePerStudentPerMonthPaise: 8_30,
    maxStudents: 300,
    maxBranches: 1,
    features: ["core", "fees_online", "parent_app", "email_support"],
    notIncluded: ["transport_library", "report_cards", "api_access"],
    popular: false,
    schools: null,
  },
  {
    plan: "GROWTH",
    pricePerStudentPerYearPaise: 200_00,
    pricePerStudentPerMonthPaise: 20_00,
    maxStudents: 1500,
    maxBranches: 3,
    features: ["everything_starter", "exams_timetable", "transport_library", "sms_credits", "priority_support"],
    notIncluded: ["api_access"],
    popular: true,
    schools: null,
  },
  {
    plan: "ENTERPRISE",
    pricePerStudentPerYearPaise: 500_00,
    pricePerStudentPerMonthPaise: 50_00,
    maxStudents: null,
    maxBranches: null,
    features: ["everything_growth", "custom_domain", "api_access", "success_manager", "dedicated_database"],
    notIncluded: [],
    popular: false,
    schools: null,
  },
];

export const NO_NOTICE: BillingNotice = {
  kind: null,
  trialEndsAt: null,
  trialDaysLeft: null,
  overduePaise: 0,
  overdueInvoices: 0,
  oldestDueDate: null,
};

export function invoiceSummary(overrides: Partial<InvoiceSummary> = {}): InvoiceSummary {
  return {
    id: "inv1",
    invoiceNo: "AKS/26-27/000007",
    invoiceDate: "2026-10-09",
    dueDate: "2026-10-24",
    periodStart: "2026-10-09",
    periodEnd: "2027-10-08",
    plan: "GROWTH",
    billingCycle: "YEARLY",
    totalPaise: 59_000_00,
    paidPaise: 0,
    balancePaise: 59_000_00,
    status: "ISSUED",
    overdue: false,
    ...overrides,
  };
}

/** 250 students on Growth, yearly: ₹50,000 + CGST ₹4,500 + SGST ₹4,500 = ₹59,000. */
export function invoice(overrides: Partial<Invoice> = {}): Invoice {
  return {
    id: "inv1",
    invoiceNo: "AKS/26-27/000007",
    financialYear: "2026-27",
    invoiceDate: "2026-10-09",
    dueDate: "2026-10-24",
    seller: {
      name: "Akshara School Cloud (sample seller)",
      address: "Sample address - set BILLING_SELLER_ADDRESS",
      stateCode: "36",
      gstin: "36XXXXX0000X1ZX",
    },
    buyer: { name: "Sunrise Educational Trust", address: "12 MG Road, Hyderabad", stateCode: "36", gstin: null },
    sacCode: "998315",
    plan: "GROWTH",
    billingCycle: "YEARLY",
    periodStart: "2026-10-09",
    periodEnd: "2027-10-08",
    billedStudents: 250,
    unitPricePaise: 200_00,
    taxablePaise: 50_000_00,
    taxSplit: "CGST_SGST",
    cgstRateBp: 900,
    sgstRateBp: 900,
    igstRateBp: 0,
    cgstPaise: 4_500_00,
    sgstPaise: 4_500_00,
    igstPaise: 0,
    totalPaise: 59_000_00,
    amountInWords: "Rupees Fifty Nine Thousand Only",
    status: "ISSUED",
    paidPaise: 0,
    balancePaise: 59_000_00,
    paidOn: null,
    overdue: false,
    issuedByName: "Platform Admin",
    cancelledAt: null,
    cancelledByName: null,
    cancelReason: null,
    payments: [],
    sample: true,
    ...overrides,
  };
}

export function schoolBilling(overrides: Partial<SchoolBilling> = {}): SchoolBilling {
  return {
    status: "TRIAL",
    plan: "STARTER",
    trialEndsAt: "2026-10-12T06:30:00Z",
    trialDaysLeft: 3,
    activeStudents: 240,
    plans: PLANS,
    subscription: null,
    details: { legalName: null, address: null, stateCode: null, gstin: null },
    invoices: [],
    notice: { ...NO_NOTICE, kind: "TRIAL_ENDING", trialEndsAt: "2026-10-12T06:30:00Z", trialDaysLeft: 3 },
    ...overrides,
  };
}

export const SUNRISE_SUMMARY = {
  id: "t1",
  name: "Sunrise Public School",
  code: "sunrise-public",
  status: "TRIAL",
  plan: "STARTER",
  board: "CBSE",
  city: "Hyderabad",
  createdAt: "2026-09-28T05:00:00Z",
  trialEndsAt: "2026-10-12T06:30:00Z",
  userCount: 7,
};

export function account(overrides: Partial<SchoolAccount> = {}): SchoolAccount {
  return {
    school: SUNRISE_SUMMARY,
    activeStudents: 240,
    subscription: null,
    details: { legalName: "Sunrise Educational Trust", address: "12 MG Road, Hyderabad", stateCode: "36", gstin: null },
    suspension: null,
    invoices: [],
    unpaidPaise: 0,
    overduePaise: 0,
    notice: NO_NOTICE,
    ...overrides,
  };
}

export const RENEWALS: RenewalRow[] = [
  {
    tenantId: "t2",
    name: "Bright Minds Academy",
    code: "bright-minds",
    status: "PAST_DUE",
    plan: "GROWTH",
    kind: "OVERDUE",
    date: "2026-09-14",
    days: -25,
    amountPaise: 2_36_000_00,
    invoices: 2,
  },
  {
    tenantId: "t3",
    name: "Little Lotus Kindergarten",
    code: "little-lotus",
    status: "ACTIVE",
    plan: "STARTER",
    kind: "DUE_FOR_RENEWAL",
    date: "2026-10-21",
    days: 12,
    amountPaise: 97_940_00,
    invoices: null,
  },
  {
    tenantId: "t1",
    name: "Sunrise Public School",
    code: "sunrise-public",
    status: "TRIAL",
    plan: "STARTER",
    kind: "TRIAL_ENDING",
    date: "2026-10-12",
    days: 3,
    amountPaise: null,
    invoices: null,
  },
];

export const HEALTH: PlatformHealth = {
  checkedAt: "2026-10-09T06:30:00Z",
  schools: { total: 42, byStatus: { TRIAL: 9, ACTIVE: 30, PAST_DUE: 2, SUSPENDED: 1 } },
  users: 1840,
  activeStudents: 25_310,
  outbox: { queued: 12, failed: 3 },
  database: { reachable: true, latencyMs: 4 },
  app: { version: "0.1.0-SNAPSHOT", startedAt: "2026-10-08T04:00:00Z", uptimeSeconds: 95_400 },
};

const SUPER_ADMIN: Me = {
  id: "p1",
  name: "Platform Admin",
  email: "root@akshara.test",
  roles: [],
  permissions: ["platform.admin"],
  platformAdmin: true,
  tenant: null,
};

/** Renders `ui` as the Super Admin (no school), inside the auth context and toast provider. */
export function renderAsSuperAdmin(ui: React.ReactNode) {
  const value = {
    status: "authenticated",
    me: SUPER_ADMIN,
    accessToken: "t",
    login: vi.fn(),
    platformLogin: vi.fn(),
    logout: vi.fn(),
    reloadMe: vi.fn(),
  } as AuthContextValue;
  return render(
    <AuthContext.Provider value={value}>
      <ToastProvider>{ui}</ToastProvider>
    </AuthContext.Provider>,
  );
}

/** Renders `ui` as a user of Sunrise Public School with these permissions. */
export function renderInSchool(permissions: string[], ui: React.ReactNode) {
  const value = {
    status: "authenticated",
    me: {
      id: "u1",
      name: "Priya Nair",
      email: "admin@school.test",
      roles: ["SCHOOL_ADMIN"],
      permissions,
      platformAdmin: false,
      tenant: {
        id: "t1",
        name: "Sunrise Public School",
        code: "sunrise-public",
        status: "TRIAL",
        plan: "STARTER",
        board: "CBSE",
        city: "Hyderabad",
        trialEndsAt: "2026-10-12T06:30:00Z",
      },
    },
    accessToken: "t",
    login: vi.fn(),
    platformLogin: vi.fn(),
    logout: vi.fn(),
    reloadMe: vi.fn(),
  } as AuthContextValue;
  return render(
    <AuthContext.Provider value={value}>
      <ToastProvider>{ui}</ToastProvider>
    </AuthContext.Provider>,
  );
}
