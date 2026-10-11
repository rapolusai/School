import { apiFetch } from "./api";
import { reportQuery, type ReportParams } from "./reports-api";
import type {
  BorrowingRule,
  CollectRequest,
  CopiesRequest,
  CopyLookup,
  CopyRequest,
  CopyView,
  FinePage,
  FineReceipt,
  FineStatus,
  FineView,
  HoldRequest,
  HoldView,
  IsbnLookup,
  IssueRequest,
  LabelCopy,
  LibraryImportResult,
  LibraryIssuedReport,
  LibraryMember,
  LibraryMemberHistory,
  LibraryOverdueReport,
  LibraryPopularReport,
  LibrarySettings,
  LibrarySettingsRequest,
  LibrarySummary,
  LoanPage,
  LoanStatus,
  LoanView,
  LostRequest,
  MemberAccount,
  MemberType,
  MyLibrary,
  ReturnRequest,
  ReturnResult,
  RuleRequest,
  ScanResult,
  StockCheckDetail,
  StockCheckRow,
  TitleDetail,
  TitlePage,
  TitleRequest,
} from "./types";

/* Endpoint helpers for docs/api/phase-2-library.md. */

const id = (value: string) => encodeURIComponent(value);

export type TitleQuery = { q?: string; subject?: string; page?: number; size?: number };
export type LoanQuery = {
  status?: LoanStatus;
  overdue?: boolean;
  memberType?: MemberType;
  memberId?: string;
  page?: number;
  size?: number;
};
export type FineQuery = { status?: FineStatus; memberType?: MemberType; memberId?: string; page?: number; size?: number };
export type LabelQuery = { titleId?: string; from?: string; to?: string; q?: string };
export type OverdueParams = { memberType?: MemberType; classId?: string };
export type IssuedParams = { date?: string };
export type PopularParams = { from?: string; to?: string };
export type MemberHistoryParams = { memberType: MemberType; memberId: string };

const query = (params: Record<string, string | number | boolean | null | undefined>) =>
  reportQuery(
    Object.fromEntries(
      Object.entries(params).map(([key, value]) => [key, typeof value === "number" ? String(value) : value]),
    ) as ReportParams,
  );

/** The JSON path and the CSV path (which Excel opens) of each library report. */
export const libraryReportPaths = {
  overdue: (p: OverdueParams) => `/api/library/reports/overdue${query(p)}`,
  overdueCsv: (p: OverdueParams) => `/api/library/reports/overdue.csv${query(p)}`,
  issued: (p: IssuedParams) => `/api/library/reports/issued${query(p)}`,
  issuedCsv: (p: IssuedParams) => `/api/library/reports/issued.csv${query(p)}`,
  popular: (p: PopularParams) => `/api/library/reports/popular${query(p)}`,
  popularCsv: (p: PopularParams) => `/api/library/reports/popular.csv${query(p)}`,
  memberHistory: (p: MemberHistoryParams) => `/api/library/reports/member-history${query(p)}`,
  memberHistoryCsv: (p: MemberHistoryParams) => `/api/library/reports/member-history.csv${query(p)}`,
  missingCsv: (stockCheckId: string) => `/api/library/stock-checks/${id(stockCheckId)}/missing.csv`,
};

export const libraryApi = {
  // Catalogue
  titles: (q: TitleQuery = {}) => apiFetch<TitlePage>(`/api/library/titles${query(q)}`),
  subjects: () => apiFetch<string[]>("/api/library/subjects"),
  title: (titleId: string) => apiFetch<TitleDetail>(`/api/library/titles/${id(titleId)}`),
  createTitle: (body: TitleRequest) => apiFetch<TitleDetail>("/api/library/titles", { method: "POST", body }),
  updateTitle: (titleId: string, body: TitleRequest) =>
    apiFetch<TitleDetail>(`/api/library/titles/${id(titleId)}`, { method: "PUT", body }),
  deleteTitle: (titleId: string) => apiFetch<void>(`/api/library/titles/${id(titleId)}`, { method: "DELETE" }),
  addCopies: (titleId: string, body: CopiesRequest) =>
    apiFetch<TitleDetail>(`/api/library/titles/${id(titleId)}/copies`, { method: "POST", body }),
  updateCopy: (copyId: string, body: CopyRequest) =>
    apiFetch<CopyView>(`/api/library/copies/${id(copyId)}`, { method: "PUT", body }),
  lookup: (accessionNo: string) =>
    apiFetch<CopyLookup>(`/api/library/copies/lookup${query({ accessionNo })}`),
  labels: (q: LabelQuery) => apiFetch<LabelCopy[]>(`/api/library/labels${query(q)}`),
  isbn: (value: string) => apiFetch<IsbnLookup>(`/api/library/isbn${query({ value })}`),
  importCsv: (csv: string, dryRun: boolean) =>
    apiFetch<LibraryImportResult>(`/api/library/titles/import?dryRun=${dryRun}`, { method: "POST", body: { csv } }),

  // Desk
  members: (q: string) => apiFetch<LibraryMember[]>(`/api/library/members${query({ q })}`),
  account: (type: MemberType, memberId: string) =>
    apiFetch<MemberAccount>(`/api/library/members/${type}/${id(memberId)}`),
  issue: (body: IssueRequest) => apiFetch<LoanView>("/api/library/loans", { method: "POST", body }),
  loans: (q: LoanQuery = {}) => apiFetch<LoanPage>(`/api/library/loans${query(q)}`),
  renew: (loanId: string) => apiFetch<LoanView>(`/api/library/loans/${id(loanId)}/renew`, { method: "POST" }),
  lost: (loanId: string, body: LostRequest) =>
    apiFetch<ReturnResult>(`/api/library/loans/${id(loanId)}/lost`, { method: "POST", body }),
  giveBack: (body: ReturnRequest) => apiFetch<ReturnResult>("/api/library/returns", { method: "POST", body }),
  holds: () => apiFetch<HoldView[]>("/api/library/reservations"),
  placeHold: (body: HoldRequest) => apiFetch<HoldView>("/api/library/reservations", { method: "POST", body }),
  cancelHold: (holdId: string) =>
    apiFetch<HoldView>(`/api/library/reservations/${id(holdId)}/cancel`, { method: "POST" }),
  fines: (q: FineQuery = {}) => apiFetch<FinePage>(`/api/library/fines${query(q)}`),
  collect: (body: CollectRequest) => apiFetch<FineReceipt>("/api/library/fines/collect", { method: "POST", body }),
  waive: (fineId: string, reason: string) =>
    apiFetch<FineView>(`/api/library/fines/${id(fineId)}/waive`, { method: "POST", body: { reason } }),
  receipt: (receiptNo: string) => apiFetch<FineReceipt>(`/api/library/fines/receipt${query({ no: receiptNo })}`),

  // Setup
  settings: () => apiFetch<LibrarySettings>("/api/library/settings"),
  saveSettings: (body: LibrarySettingsRequest) =>
    apiFetch<LibrarySettings>("/api/library/settings", { method: "PUT", body }),
  rules: () => apiFetch<BorrowingRule[]>("/api/library/rules"),
  saveRule: (type: MemberType, body: RuleRequest) =>
    apiFetch<BorrowingRule>(`/api/library/rules/${type}`, { method: "PUT", body }),

  // Stock verification
  stockChecks: () => apiFetch<StockCheckRow[]>("/api/library/stock-checks"),
  startStockCheck: (note: string | null) =>
    apiFetch<StockCheckRow>("/api/library/stock-checks", { method: "POST", body: { note } }),
  stockCheck: (checkId: string) => apiFetch<StockCheckDetail>(`/api/library/stock-checks/${id(checkId)}`),
  scan: (checkId: string, accessionNo: string) =>
    apiFetch<ScanResult>(`/api/library/stock-checks/${id(checkId)}/scans`, { method: "POST", body: { accessionNo } }),
  closeStockCheck: (checkId: string) =>
    apiFetch<StockCheckDetail>(`/api/library/stock-checks/${id(checkId)}/close`, { method: "POST" }),

  // Reports and the dashboard
  summary: () => apiFetch<LibrarySummary>("/api/library/summary"),
  overdue: (p: OverdueParams) => apiFetch<LibraryOverdueReport>(libraryReportPaths.overdue(p)),
  issued: (p: IssuedParams) => apiFetch<LibraryIssuedReport>(libraryReportPaths.issued(p)),
  popular: (p: PopularParams) => apiFetch<LibraryPopularReport>(libraryReportPaths.popular(p)),
  memberHistory: (p: MemberHistoryParams) => apiFetch<LibraryMemberHistory>(libraryReportPaths.memberHistory(p)),

  // Families
  mine: () => apiFetch<MyLibrary>("/api/me/library"),
};

/** Accession numbers as the API stores them: letters in capitals, no spaces. A scanner may send either case. */
export function normalizeAccession(value: string): string {
  return value.trim().toUpperCase();
}

/** The API's accession number rule (letters, digits, / and -, up to 30). */
export const ACCESSION_PATTERN = /^[A-Za-z0-9/-]{1,30}$/;
