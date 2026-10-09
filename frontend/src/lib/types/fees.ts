/**
 * Fees API shapes (docs/api/phase-1-fees.md). Every amount is in paise (₹1 = 100 paise) as a
 * whole number; the screens take and show rupees.
 */
import type { PlainDate, SchoolProfile, StudentStatus } from "./school";

export const FEE_HEAD_KINDS = ["TUITION", "ADMISSION", "ANNUAL", "EXAM", "TRANSPORT", "LAB", "OTHER"] as const;
export type FeeHeadKind = (typeof FEE_HEAD_KINDS)[number];

/** Modes the counter takes. ONLINE is only ever set by the payment gateway. */
export const COUNTER_PAYMENT_MODES = ["CASH", "UPI", "CARD", "CHEQUE", "BANK_TRANSFER"] as const;
export const PAYMENT_MODES = [...COUNTER_PAYMENT_MODES, "ONLINE"] as const;
export type PaymentMode = (typeof PAYMENT_MODES)[number];
export type CounterPaymentMode = (typeof COUNTER_PAYMENT_MODES)[number];

export const DUE_STATUSES = ["UPCOMING", "DUE", "PARTIAL", "OVERDUE", "PAID"] as const;
export type DueStatus = (typeof DUE_STATUSES)[number];

export const CONCESSION_TYPES = ["SIBLING", "STAFF_WARD", "MERIT", "RTE", "OTHER"] as const;
export type ConcessionType = (typeof CONCESSION_TYPES)[number];
export type ConcessionMode = "PERCENT" | "FIXED";

export const LATE_FEE_MODES = ["NONE", "FLAT", "PER_DAY"] as const;
export type LateFeeMode = (typeof LATE_FEE_MODES)[number];

export type StructureStatus = "DRAFT" | "PUBLISHED";
export type ReceiptStatus = "ISSUED" | "CANCELLED";
export type OrderStatus = "CREATED" | "PAID" | "FAILED";

/** Largest single amount the API accepts: ₹1 crore. */
export const MAX_AMOUNT_PAISE = 1_000_000_000;
export const MAX_INSTALMENTS = 12;

/* ------------------------------------------------------------------ setup */

export type FeeHead = {
  id: string;
  name: string;
  kind: FeeHeadKind;
  oneTime: boolean;
  active: boolean;
  displayOrder: number;
  inUse: boolean;
};

export type FeeHeadRequest = { name: string; kind: FeeHeadKind; oneTime: boolean; active: boolean };

/** One class of a year; `id` and `status` are null when the class has no structure yet. */
export type StructureSummary = {
  id: string | null;
  classId: string;
  className: string;
  status: StructureStatus | null;
  totalPaise: number;
  instalmentCount: number;
  publishedAt: string | null;
  studentsWithDues: number;
};

export type StructureHead = {
  headId: string;
  name: string;
  kind: FeeHeadKind;
  oneTime: boolean;
  amountPaise: number;
};

export type HeadShare = { headId: string; amountPaise: number };

export type Instalment = {
  id: string;
  seq: number;
  label: string;
  dueDate: PlainDate;
  amountPaise: number;
  shares: HeadShare[];
};

export type FeeStructure = {
  id: string;
  academicYearId: string;
  academicYearName: string;
  classId: string;
  className: string;
  status: StructureStatus;
  publishedAt: string | null;
  heads: StructureHead[];
  instalments: Instalment[];
  totalPaise: number;
  studentsWithDues: number;
};

export type DuesChange = { studentsCreated: number; studentsUpdated: number; paidCellsKept: number };

export type StructureResult = { structure: FeeStructure; dues: DuesChange };

export type InstalmentRequest = { label?: string | null; dueDate: PlainDate; shares?: HeadShare[] | null };

export type StructureRequest = {
  academicYearId: string;
  classId: string;
  heads: HeadShare[];
  instalments: InstalmentRequest[];
};

export type LateFeeRule = {
  mode: LateFeeMode;
  graceDays: number;
  flatPaise: number;
  perDayPaise: number;
  capPaise: number;
};

export type HeadRef = { id: string; name: string };

export type Concession = {
  id: string;
  studentId: string;
  studentName: string;
  admissionNo: string;
  className: string | null;
  sectionName: string | null;
  academicYearId: string;
  academicYearName: string;
  type: ConcessionType;
  mode: ConcessionMode;
  /** Percentage as a decimal number, e.g. 10 or 12.5 (PERCENT only). */
  percent: number | null;
  fixedPaise: number | null;
  heads: HeadRef[];
  reason: string;
  approvedByName: string | null;
  createdAt: string;
  status: "ACTIVE" | "REVOKED";
  revokedAt: string | null;
  revokedByName: string | null;
  revokeReason: string | null;
  /** What the concession takes off the student's dues this year. */
  studentConcessionPaise: number;
};

export type ConcessionRequest = {
  studentId: string;
  academicYearId?: string | null;
  type: ConcessionType;
  mode?: ConcessionMode | null;
  percent?: number | null;
  fixedPaise?: number | null;
  headIds?: string[] | null;
  reason: string;
};

/* ------------------------------------------------------------------ dues */

export type FeeStudentRef = {
  id: string;
  fullName: string;
  admissionNo: string;
  status: StudentStatus;
  className: string | null;
  sectionName: string | null;
  rollNo: number | null;
};

export type HeadDue = {
  dueId: string;
  headId: string;
  headName: string;
  grossPaise: number;
  concessionPaise: number;
  netPaise: number;
  paidPaise: number;
  balancePaise: number;
  status: DueStatus;
};

export type InstalmentDue = {
  instalmentId: string;
  seq: number;
  label: string;
  dueDate: PlainDate;
  academicYearId: string;
  academicYearName: string;
  status: DueStatus;
  grossPaise: number;
  concessionPaise: number;
  netPaise: number;
  paidPaise: number;
  balancePaise: number;
  lateFeePaise: number;
  lateFeeWaived: boolean;
  daysOverdue: number;
  heads: HeadDue[];
};

export type FeeTotals = {
  grossPaise: number;
  concessionPaise: number;
  netPaise: number;
  paidPaise: number;
  balancePaise: number;
  overduePaise: number;
  lateFeePaise: number;
  /** Balance due by today plus late fees. */
  payableNowPaise: number;
};

export type ReceiptSummary = {
  id: string;
  receiptNo: string;
  receivedOn: PlainDate;
  receivedAt: string;
  studentId: string;
  studentName: string;
  admissionNo: string;
  classLabel: string | null;
  mode: PaymentMode;
  source: "COUNTER" | "ONLINE";
  amountPaise: number;
  lateFeePaise: number;
  status: ReceiptStatus;
  collectedByName: string | null;
};

export type StudentFees = {
  student: FeeStudentRef;
  instalments: InstalmentDue[];
  totals: FeeTotals;
  concessions: Concession[];
  receipts: ReceiptSummary[];
  lateFeeRule: LateFeeRule;
  asOf: PlainDate;
};

export type StudentHit = {
  id: string;
  fullName: string;
  admissionNo: string;
  status: StudentStatus;
  className: string | null;
  sectionName: string | null;
  rollNo: number | null;
  guardianName: string | null;
};

/* ------------------------------------------------------------------ payments and receipts */

export type PaymentRequest = {
  amountPaise: number;
  mode: CounterPaymentMode;
  chequeNo?: string | null;
  bankName?: string | null;
  reference?: string | null;
  instalmentIds?: string[] | null;
  includeLateFee?: boolean;
  remarks?: string | null;
};

export type ReceiptLine = {
  kind: "DUE" | "LATE_FEE" | "ADVANCE";
  instalmentLabel: string | null;
  headName: string | null;
  amountPaise: number;
};

export type Receipt = {
  id: string;
  receiptNo: string;
  financialYear: string;
  receivedOn: PlainDate;
  receivedAt: string;
  studentId: string;
  studentName: string;
  admissionNo: string;
  classLabel: string | null;
  mode: PaymentMode;
  chequeNo: string | null;
  bankName: string | null;
  reference: string | null;
  source: "COUNTER" | "ONLINE";
  gatewayPaymentId: string | null;
  amountPaise: number;
  lateFeePaise: number;
  amountInWords: string;
  remarks: string | null;
  collectedByName: string | null;
  status: ReceiptStatus;
  cancelledAt: string | null;
  cancelledByName: string | null;
  cancelReason: string | null;
  lines: ReceiptLine[];
  school: SchoolProfile;
};

export type ReceiptPage = {
  items: ReceiptSummary[];
  page: number;
  size: number;
  total: number;
  /** Sum of the issued receipts that match the filters. */
  totalPaise: number;
};

export type ReceiptQuery = {
  from?: PlainDate;
  to?: PlainDate;
  mode?: PaymentMode;
  status?: ReceiptStatus;
  q?: string;
  studentId?: string;
  page?: number;
  size?: number;
};

/* ------------------------------------------------------------------ online payments */

export type PaymentOrder = {
  id: string;
  gateway: string;
  gatewayOrderId: string;
  amountPaise: number;
  currency: string;
  status: OrderStatus;
  studentId: string;
  studentName: string;
  instalments: string[];
  receiptId: string | null;
  failureReason: string | null;
};

export type SandboxCheckout = {
  gatewayOrderId: string;
  orderId: string;
  studentId: string;
  amountPaise: number;
  currency: string;
  schoolName: string;
  studentName: string;
  instalments: string[];
  status: OrderStatus;
};

export type CheckoutResult = {
  orderId: string;
  gatewayOrderId: string;
  status: "SUCCESS" | "FAILED";
  gatewayPaymentId: string | null;
  signature: string | null;
};

/* ------------------------------------------------------------------ reports */

export type CollectionTotal = { amountPaise: number; receiptCount: number };

export type FeesOverview = {
  academicYearId: string | null;
  academicYearName: string | null;
  asOf: PlainDate;
  today: CollectionTotal;
  thisMonth: CollectionTotal;
  outstandingPaise: number;
  overduePaise: number;
  overdueStudents: number;
  remainingThisYearPaise: number;
  recentReceipts: ReceiptSummary[];
};

export type CollectionReport = {
  from: PlainDate;
  to: PlainDate;
  totalPaise: number;
  receiptCount: number;
  cancelledCount: number;
  byMode: { mode: PaymentMode; receiptCount: number; amountPaise: number }[];
  byHead: { headId: string | null; headName: string; amountPaise: number }[];
  lateFeePaise: number;
  advancePaise: number;
};

export type OutstandingRow = {
  classId: string | null;
  className: string | null;
  sectionId: string | null;
  sectionName: string | null;
  students: number;
  netPaise: number;
  paidPaise: number;
  balancePaise: number;
  dueSoFarPaise: number;
  overduePaise: number;
};

export type OutstandingReport = {
  academicYearId: string | null;
  academicYearName: string | null;
  asOf: PlainDate;
  rows: OutstandingRow[];
  total: OutstandingRow;
};

export type OverdueRow = {
  studentId: string;
  fullName: string;
  admissionNo: string;
  className: string | null;
  sectionName: string | null;
  guardianName: string | null;
  /** Masked, e.g. "98XXXXXX01". */
  guardianPhone: string | null;
  overduePaise: number;
  lateFeePaise: number;
  oldestDueDate: PlainDate;
  daysOverdue: number;
  instalments: string[];
  lastReminderAt: string | null;
};

export type OverdueReport = {
  academicYearId: string | null;
  academicYearName: string | null;
  asOf: PlainDate;
  rows: OverdueRow[];
  totalOverduePaise: number;
};

export type ReportFilter = { yearId?: string; classId?: string; sectionId?: string; minDays?: number };

export type ReminderResult = { requested: number; skipped: number };
