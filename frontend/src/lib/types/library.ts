/** Types mirroring docs/api/phase-2-library.md. Money is in paise; plain dates are "YYYY-MM-DD". */

export const MEMBER_TYPES = ["STUDENT", "STAFF"] as const;
export type MemberType = (typeof MEMBER_TYPES)[number];

export const COPY_STATUSES = ["AVAILABLE", "ISSUED", "RESERVED", "LOST", "DAMAGED", "WITHDRAWN"] as const;
export type CopyStatus = (typeof COPY_STATUSES)[number];

/** The statuses a librarian may set on the copy form; ISSUED and RESERVED change only at the desk. */
export const EDITABLE_COPY_STATUSES = ["AVAILABLE", "DAMAGED", "LOST", "WITHDRAWN"] as const;

export const COPY_SOURCES = ["PURCHASE", "DONATION"] as const;
export type CopySource = (typeof COPY_SOURCES)[number];

export type LoanStatus = "ISSUED" | "RETURNED" | "LOST";
export type FineKind = "OVERDUE" | "LOST" | "DAMAGED";
export const FINE_STATUSES = ["DUE", "PAID", "WAIVED"] as const;
export type FineStatus = (typeof FINE_STATUSES)[number];
export const LIBRARY_PAYMENT_MODES = ["CASH", "UPI", "CARD", "CHEQUE", "BANK_TRANSFER"] as const;
export type LibraryPaymentMode = (typeof LIBRARY_PAYMENT_MODES)[number];
export type ReservationStatus = "WAITING" | "READY" | "FULFILLED" | "CANCELLED" | "EXPIRED";
export type StockCheckStatus = "OPEN" | "CLOSED";

/** A student (code: admission number, detail: class) or member of staff (code: employee code, detail: designation). */
export type LibraryMember = {
  type: MemberType;
  id: string;
  name: string | null;
  code: string | null;
  detail: string | null;
  active: boolean;
};

export type TitleRow = {
  id: string;
  isbn13: string | null;
  title: string;
  subtitle: string | null;
  authors: string | null;
  subject: string | null;
  callNumber: string | null;
  shelfLocation: string | null;
  copies: number;
  available: number;
  issued: number;
};

export type CatalogueStats = { titles: number; copies: number; issued: number; overdue: number };

export type TitlePage = { items: TitleRow[]; page: number; size: number; total: number; stats: CatalogueStats };

export type CopyLoan = {
  loanId: string;
  member: LibraryMember | null;
  issuedOn: string;
  dueOn: string;
  overdue: boolean;
};

export type HeldFor = { reservationId: string; member: LibraryMember | null; holdUntil: string };

export type CopyView = {
  id: string;
  titleId: string;
  accessionNo: string;
  status: CopyStatus;
  statusNote: string | null;
  pricePaise: number | null;
  source: CopySource;
  acquiredOn: string | null;
  loan: CopyLoan | null;
  heldFor: HeldFor | null;
};

export type QueueEntry = {
  reservationId: string;
  position: number;
  member: LibraryMember | null;
  status: ReservationStatus;
  holdUntil: string | null;
  createdAt: string;
};

export type TitleDetail = {
  id: string;
  isbn13: string | null;
  isbn10: string | null;
  title: string;
  subtitle: string | null;
  authors: string | null;
  publisher: string | null;
  publicationYear: number | null;
  edition: string | null;
  language: string | null;
  subject: string | null;
  callNumber: string | null;
  shelfLocation: string | null;
  copiesTotal: number;
  available: number;
  timesBorrowed: number;
  copies: CopyView[];
  queue: QueueEntry[];
};

export type TitleRef = { id: string; title: string; authors: string | null; copies: number };

export type BookSuggestion = {
  source: "CATALOGUE" | "ONLINE";
  title: string | null;
  subtitle: string | null;
  authors: string | null;
  publisher: string | null;
  publicationYear: number | null;
  edition: string | null;
  language: string | null;
  subject: string | null;
  callNumber: string | null;
};

export type IsbnLookup = {
  input: string;
  valid: boolean;
  problem: string | null;
  isbn13: string | null;
  isbn10: string | null;
  existing: TitleRef | null;
  suggestion: BookSuggestion | null;
};

export type TitleRequest = {
  isbn: string | null;
  title: string;
  subtitle: string | null;
  authors: string | null;
  publisher: string | null;
  publicationYear: number | null;
  edition: string | null;
  language: string | null;
  subject: string | null;
  callNumber: string | null;
  shelfLocation: string | null;
  /** New titles only: how many copies to add now (default 1). */
  copies?: number | null;
  pricePaise?: number | null;
  source?: CopySource | null;
  acquiredOn?: string | null;
};

export type CopiesRequest = { count: number; pricePaise: number | null; source: CopySource; acquiredOn: string | null };

export type CopyRequest = {
  pricePaise: number | null;
  source: CopySource;
  acquiredOn: string | null;
  status: CopyStatus;
  statusNote: string | null;
};

export type LabelCopy = {
  id: string;
  accessionNo: string;
  titleId: string;
  title: string;
  authors: string | null;
  callNumber: string | null;
  status: CopyStatus;
};

export type LoanView = {
  id: string;
  copyId: string;
  accessionNo: string;
  titleId: string;
  title: string;
  authors: string | null;
  member: LibraryMember | null;
  issuedOn: string;
  dueOn: string;
  renewals: number;
  renewalsLeft: number;
  status: LoanStatus;
  returnedOn: string | null;
  overdue: boolean;
  daysOverdue: number;
  fineSoFarPaise: number;
  issuedByName: string | null;
  returnedByName: string | null;
};

export type LoanPage = { items: LoanView[]; page: number; size: number; total: number };

export type CopyLookup = { copy: CopyView; title: TitleRef; loan: LoanView | null };

export type FineView = {
  id: string;
  loanId: string;
  titleId: string;
  title: string | null;
  accessionNo: string | null;
  member: LibraryMember | null;
  kind: FineKind;
  amountPaise: number;
  daysLate: number | null;
  note: string | null;
  status: FineStatus;
  receiptNo: string | null;
  paymentMode: LibraryPaymentMode | null;
  paymentReference: string | null;
  paidAt: string | null;
  collectedByName: string | null;
  waiveReason: string | null;
  waivedAt: string | null;
  waivedByName: string | null;
  createdAt: string;
};

export type FinePage = { items: FineView[]; page: number; size: number; total: number; totalDuePaise: number };

export type HoldView = {
  id: string;
  titleId: string;
  title: string | null;
  authors: string | null;
  member: LibraryMember | null;
  status: ReservationStatus;
  position: number;
  copyAccessionNo: string | null;
  holdUntil: string | null;
  readyAt: string | null;
  createdAt: string;
  createdByName: string | null;
};

export type ReturnResult = { loan: LoanView; fines: FineView[]; heldFor: HoldView | null };

export type BorrowingRule = {
  memberType: MemberType;
  maxBooks: number;
  loanDays: number;
  maxRenewals: number;
  finePerDayPaise: number;
  maxFinePaise: number;
  custom: boolean;
};

export type RuleRequest = Omit<BorrowingRule, "memberType" | "custom">;

export type MemberAccount = {
  member: LibraryMember;
  rule: BorrowingRule;
  loans: LoanView[];
  finesDue: FineView[];
  finesDuePaise: number;
  holds: HoldView[];
  history: LoanView[];
  canBorrow: boolean;
  blockedReason: string | null;
};

export type IssueRequest = { accessionNo: string; memberType: MemberType; memberId: string; issuedOn: string | null };

export type ReturnRequest = { accessionNo: string; damaged: boolean; chargePaise: number | null; note: string | null };

export type LostRequest = { chargePaise: number | null; note: string | null };

export type HoldRequest = { titleId: string; memberType: MemberType; memberId: string };

export type CollectRequest = { fineIds: string[]; mode: LibraryPaymentMode; reference: string | null };

export type FineReceipt = {
  receiptNo: string;
  schoolName: string;
  member: LibraryMember | null;
  fines: FineView[];
  totalPaise: number;
  mode: LibraryPaymentMode;
  reference: string | null;
  paidAt: string;
  collectedByName: string | null;
};

export type LibrarySettings = {
  accessionPrefix: string;
  nextAccessionNo: string;
  holdDays: number;
  remindersEnabled: boolean;
};

export type LibrarySettingsRequest = { accessionPrefix: string; holdDays: number; remindersEnabled: boolean };

export type StockCheckRow = {
  id: string;
  status: StockCheckStatus;
  note: string | null;
  startedByName: string | null;
  startedAt: string;
  closedByName: string | null;
  closedAt: string | null;
  seen: number;
  expected: number;
  missing: number;
};

export type MissingCopy = {
  copyId: string;
  accessionNo: string;
  titleId: string;
  title: string | null;
  callNumber: string | null;
  shelfLocation: string | null;
  status: CopyStatus;
};

export type SeenCopy = { accessionNo: string | null; title: string | null; seenAt: string };

export type StockCheckDetail = { check: StockCheckRow; missing: MissingCopy[]; latest: SeenCopy[] };

export type ScanResult = {
  accessionNo: string;
  title: string;
  status: CopyStatus;
  alreadySeen: boolean;
  warning: CopyStatus | null;
  seen: number;
  expected: number;
};

export type LibraryImportError = { row: number; column: string; message: string };

export type LibraryImportPreviewRow = {
  row: number;
  title: string;
  isbn13: string | null;
  authors: string | null;
  copies: number;
  existingTitle: boolean;
  accessionNo: string | null;
};

export type LibraryImportResult = {
  dryRun: boolean;
  committed: boolean;
  totalRows: number;
  validRows: number;
  invalidRows: number;
  titlesCreated: number;
  copiesCreated: number;
  errors: LibraryImportError[];
  ignoredColumns: string[];
  preview: LibraryImportPreviewRow[];
};

export type LibraryOverdueReport = { asOf: string; rows: LoanView[]; totalFinePaise: number };
export type LibraryIssuedReport = { date: string; rows: LoanView[] };
export type LibraryPopularRow = { titleId: string; title: string | null; authors: string | null; loans: number; copies: number };
export type LibraryPopularReport = { from: string; to: string; rows: LibraryPopularRow[] };
export type LibraryMemberHistory = {
  member: LibraryMember;
  loans: LoanView[];
  fines: FineView[];
  finesDuePaise: number;
  finesPaidPaise: number;
};

export type LibrarySummary = {
  issued: number;
  overdue: number;
  dueToday: number;
  holdsReady: number;
  finesDuePaise: number;
};

export type FamilyLoan = {
  title: string;
  authors: string | null;
  accessionNo: string;
  issuedOn: string;
  dueOn: string;
  overdue: boolean;
  daysOverdue: number;
  renewals: number;
  fineSoFarPaise: number;
};

export type FamilyHold = { title: string | null; status: ReservationStatus; position: number; holdUntil: string | null };

export type FamilyFine = {
  title: string | null;
  kind: FineKind;
  amountPaise: number;
  status: FineStatus;
  createdAt: string;
  receiptNo: string | null;
};

export type FamilyLibraryMember = {
  type: MemberType;
  id: string;
  name: string | null;
  className: string | null;
  loans: FamilyLoan[];
  holds: FamilyHold[];
  fines: FamilyFine[];
  finesDuePaise: number;
};

export type MyLibrary = { members: FamilyLibraryMember[] };
