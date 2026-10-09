import { apiFetch, getAccessToken, parseErrorResponse, refreshSession, toApiError } from "./api";
import type {
  CheckoutResult,
  CollectionReport,
  Concession,
  ConcessionRequest,
  FeeHead,
  FeeHeadRequest,
  FeesOverview,
  FeeStructure,
  LateFeeRule,
  OutstandingReport,
  OverdueReport,
  PaymentOrder,
  PaymentRequest,
  Receipt,
  ReceiptPage,
  ReceiptQuery,
  ReminderResult,
  ReportFilter,
  SandboxCheckout,
  StructureRequest,
  StructureResult,
  StructureSummary,
  StudentFees,
  StudentHit,
} from "./types";

/* Fees endpoints (docs/api/phase-1-fees.md). Kept apart from api.ts so the slices merge cleanly. */

const id = (value: string) => encodeURIComponent(value);

/** "?a=1&b=x" from the values that are set; arrays and empty strings are skipped. */
export function feesQuery(params: Record<string, string | number | undefined | null>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === "") continue;
    search.set(key, String(value));
  }
  const text = search.toString();
  return text ? `?${text}` : "";
}

const child = (studentId: string) => `/api/me/children/${id(studentId)}/fees`;

export const feesApi = {
  /* Heads and the late fee rule */
  listHeads: () => apiFetch<FeeHead[]>("/api/fees/heads"),
  createHead: (body: FeeHeadRequest) => apiFetch<FeeHead>("/api/fees/heads", { method: "POST", body }),
  updateHead: (headId: string, body: FeeHeadRequest) =>
    apiFetch<FeeHead>(`/api/fees/heads/${id(headId)}`, { method: "PUT", body }),
  addDefaultHeads: () => apiFetch<FeeHead[]>("/api/fees/heads/defaults", { method: "POST" }),
  getLateFeeRule: () => apiFetch<LateFeeRule>("/api/fees/late-fee-rule"),
  updateLateFeeRule: (body: LateFeeRule) =>
    apiFetch<LateFeeRule>("/api/fees/late-fee-rule", { method: "PUT", body }),

  /* Structures */
  listStructures: (yearId?: string) =>
    apiFetch<StructureSummary[]>(`/api/fees/structures${feesQuery({ yearId })}`),
  getStructure: (structureId: string) => apiFetch<FeeStructure>(`/api/fees/structures/${id(structureId)}`),
  createStructure: (body: StructureRequest) =>
    apiFetch<StructureResult>("/api/fees/structures", { method: "POST", body }),
  updateStructure: (structureId: string, body: StructureRequest) =>
    apiFetch<StructureResult>(`/api/fees/structures/${id(structureId)}`, { method: "PUT", body }),
  publishStructure: (structureId: string) =>
    apiFetch<StructureResult>(`/api/fees/structures/${id(structureId)}/publish`, { method: "POST" }),

  /* Concessions */
  listConcessions: (query: { yearId?: string; studentId?: string } = {}) =>
    apiFetch<Concession[]>(`/api/fees/concessions${feesQuery(query)}`),
  grantConcession: (body: ConcessionRequest) =>
    apiFetch<Concession>("/api/fees/concessions", { method: "POST", body }),
  revokeConcession: (concessionId: string, reason: string) =>
    apiFetch<Concession>(`/api/fees/concessions/${id(concessionId)}/revoke`, { method: "POST", body: { reason } }),

  /* A student's dues, counter payments and waivers */
  searchStudents: (q: string) => apiFetch<StudentHit[]>(`/api/fees/students${feesQuery({ q })}`),
  studentFees: (studentId: string) => apiFetch<StudentFees>(`/api/fees/students/${id(studentId)}`),
  collect: (studentId: string, body: PaymentRequest) =>
    apiFetch<Receipt>(`/api/fees/students/${id(studentId)}/payments`, { method: "POST", body }),
  waiveLateFee: (studentId: string, instalmentId: string, reason: string) =>
    apiFetch<StudentFees>(`/api/fees/students/${id(studentId)}/late-fee-waivers`, {
      method: "POST",
      body: { instalmentId, reason },
    }),

  /* Receipts */
  listReceipts: (query: ReceiptQuery = {}) => apiFetch<ReceiptPage>(`/api/fees/receipts${feesQuery(query)}`),
  getReceipt: (receiptId: string) => apiFetch<Receipt>(`/api/fees/receipts/${id(receiptId)}`),
  cancelReceipt: (receiptId: string, reason: string) =>
    apiFetch<Receipt>(`/api/fees/receipts/${id(receiptId)}/cancel`, { method: "POST", body: { reason } }),

  /* Reports */
  overview: () => apiFetch<FeesOverview>("/api/fees/reports/overview"),
  collection: (from: string, to: string) =>
    apiFetch<CollectionReport>(`/api/fees/reports/collection${feesQuery({ from, to })}`),
  outstanding: (filter: ReportFilter = {}) =>
    apiFetch<OutstandingReport>(`/api/fees/reports/outstanding${feesQuery(filter)}`),
  overdue: (filter: ReportFilter = {}) => apiFetch<OverdueReport>(`/api/fees/reports/overdue${feesQuery(filter)}`),
  remind: (studentIds: string[]) =>
    apiFetch<ReminderResult>("/api/fees/reminders", { method: "POST", body: { studentIds } }),

  /* A parent's own child */
  childFees: (studentId: string) => apiFetch<StudentFees>(child(studentId)),
  childOrder: (studentId: string, instalmentIds: string[]) =>
    apiFetch<PaymentOrder>(`${child(studentId)}/orders`, { method: "POST", body: { instalmentIds } }),
  childVerify: (studentId: string, orderId: string, body: { gatewayPaymentId: string; signature: string }) =>
    apiFetch<Receipt>(`${child(studentId)}/orders/${id(orderId)}/verify`, { method: "POST", body }),
  childReceipt: (studentId: string, receiptId: string) =>
    apiFetch<Receipt>(`${child(studentId)}/receipts/${id(receiptId)}`),

  /* Online payments taken by staff for a student */
  staffOrder: (studentId: string, instalmentIds: string[]) =>
    apiFetch<PaymentOrder>(`/api/fees/students/${id(studentId)}/orders`, { method: "POST", body: { instalmentIds } }),
  staffVerify: (orderId: string, body: { gatewayPaymentId: string; signature: string }) =>
    apiFetch<Receipt>(`/api/fees/orders/${id(orderId)}/verify`, { method: "POST", body }),

  /* The sandbox gateway's checkout page */
  sandboxCheckout: (gatewayOrderId: string) =>
    apiFetch<SandboxCheckout>(`/api/payments/sandbox/orders/${id(gatewayOrderId)}`),
  sandboxComplete: (gatewayOrderId: string, outcome: "SUCCESS" | "FAILURE") =>
    apiFetch<CheckoutResult>(`/api/payments/sandbox/orders/${id(gatewayOrderId)}/complete`, {
      method: "POST",
      body: { outcome },
    }),
};

/** Paths of the CSV exports (both need a date range). */
export const feesExports = {
  receipts: (from: string, to: string) => `/api/fees/receipts/export.csv${feesQuery({ from, to })}`,
  tally: (from: string, to: string) => `/api/fees/reports/tally.csv${feesQuery({ from, to })}`,
};

/**
 * Downloads a CSV export with the signed-in user's token (a plain link would not carry it):
 * fetches the file, then saves it under `filename` through a temporary object URL.
 */
export async function downloadCsv(path: string, filename: string): Promise<void> {
  const send = (token: string | null) =>
    fetch(path, {
      headers: { Accept: "text/csv", ...(token ? { Authorization: `Bearer ${token}` } : {}) },
      credentials: "include",
    });
  let res: Response;
  try {
    res = await send(getAccessToken());
    if (res.status === 401) {
      const session = await refreshSession();
      if (session) res = await send(session.accessToken);
    }
  } catch (error) {
    throw toApiError(error);
  }
  if (!res.ok) throw await parseErrorResponse(res);
  const blob = await res.blob();
  const url = URL.createObjectURL(blob);
  try {
    const link = document.createElement("a");
    link.href = url;
    link.download = filename;
    document.body.appendChild(link);
    link.click();
    link.remove();
  } finally {
    URL.revokeObjectURL(url);
  }
}
