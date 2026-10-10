/**
 * Data protection API shapes (docs/api/phase-1-privacy.md): the privacy notice and grievance
 * officer, consent per child and purpose, and parents' data requests with their exports.
 */
import type { PlainDate, StudentStatus } from "./school";

export const PRIVACY_PURPOSES = ["ESSENTIAL", "PHOTOS", "WHATSAPP"] as const;
export type PrivacyPurpose = (typeof PRIVACY_PURPOSES)[number];
/** The purposes a parent can switch on and off; ESSENTIAL is needed to use the app. */
export const OPTIONAL_PURPOSES = ["PHOTOS", "WHATSAPP"] as const;
export type OptionalPurpose = (typeof OPTIONAL_PURPOSES)[number];

export type ConsentAction = "GIVEN" | "DECLINED" | "WITHDRAWN";
/** A purpose's state: its latest decision, or NONE. */
export type ConsentStatus = ConsentAction | "NONE";
export type ConsentMethod = "ONLINE" | "PAPER";

export const REQUEST_TYPES = ["ACCESS", "CORRECTION", "ERASURE", "GRIEVANCE"] as const;
export type RequestType = (typeof REQUEST_TYPES)[number];
export type RequestSubject = "SELF" | "CHILD";
export const REQUEST_STATUSES = ["SUBMITTED", "IN_PROGRESS", "CLOSED"] as const;
export type RequestStatus = (typeof REQUEST_STATUSES)[number];
export type RequestResolution = "COMPLETED" | "DECLINED";
export type RequestEventKind =
  | "SUBMITTED"
  | "ASSIGNED"
  | "STAFF_REPLY"
  | "PARENT_REPLY"
  | "EXPORT_READY"
  | "EXPORT_DOWNLOADED"
  | "ERASED"
  | "CLOSED";
export type ExportStatus = "READY" | "EXPIRED" | "REPLACED" | "ERASED";

/** Limits shared with the API. */
export const NOTICE_MAX = 20_000;
export const CHANGE_SUMMARY_MAX = 500;
export const REQUEST_DETAILS_MAX = 2_000;
export const REPLY_MAX = 2_000;
/** Days the school gives itself to answer a request (its policy). */
export const REQUEST_DUE_DAYS = 30;
/** Days an export can be downloaded. */
export const EXPORT_KEEP_DAYS = 7;

/* ------------------------------------------------------------------ notice */

export type GrievanceOfficer = { name: string; email: string; phone: string; updatedAt: string | null };
export type GrievanceOfficerRequest = { name: string; email: string; phone: string };

export type NoticeVersion = {
  version: number;
  publishedAt: string;
  publishedByName: string | null;
  changeSummary: string | null;
};

export type PrivacyNotice = {
  version: number;
  current: boolean;
  bodyEn: string;
  bodyHi: string;
  changeSummary: string | null;
  publishedAt: string;
  publishedByName: string | null;
  grievanceOfficer: GrievanceOfficer;
};

export type NoticeAdmin = {
  officer: GrievanceOfficer | null;
  current: PrivacyNotice | null;
  draftEn: string;
  draftHi: string;
  draftIsTemplate: boolean;
  versions: NoticeVersion[];
};

export type PublishNoticeRequest = { bodyEn: string; bodyHi: string; changeSummary: string | null };

export type PublicNotice = {
  schoolName: string;
  schoolCode: string;
  version: number;
  currentVersion: number;
  publishedAt: string;
  changeSummary: string | null;
  bodyEn: string;
  bodyHi: string;
  grievanceOfficer: GrievanceOfficer;
  versions: NoticeVersion[];
};

/* ------------------------------------------------------------------ consent */

export type PurposeState = {
  purpose: PrivacyPurpose;
  status: ConsentStatus;
  at: string | null;
  noticeVersion: number | null;
  method: ConsentMethod | null;
  /** True when the decision was taken against the current notice version. */
  current: boolean;
};

export type ConsentEntry = {
  id: string;
  purpose: PrivacyPurpose;
  action: ConsentAction;
  method: ConsentMethod;
  noticeVersion: number;
  givenByName: string;
  recordedByName: string | null;
  paperReference: string | null;
  signedOn: PlainDate | null;
  at: string;
};

export type ChildConsent = {
  studentId: string;
  fullName: string;
  className: string | null;
  sectionName: string | null;
  status: StudentStatus;
  needsConsent: boolean;
  purposes: PurposeState[];
  history: ConsentEntry[];
};

export type ParentPrivacy = { notice: PrivacyNotice | null; needsConsent: boolean; children: ChildConsent[] };

export type ConsentChoice = { studentId: string; photos: boolean; whatsapp: boolean };
export type AcceptConsentRequest = { noticeVersion: number; acceptEssential: boolean; choices: ConsentChoice[] };

export type StudentConsent = {
  studentId: string;
  fullName: string;
  admissionNo: string;
  status: StudentStatus;
  noticeVersion: number | null;
  purposes: PurposeState[];
  history: ConsentEntry[];
};

export type PaperConsentRequest = {
  givenByName: string;
  signedOn: PlainDate;
  paperReference: string | null;
  photos: boolean;
  whatsapp: boolean;
};

export type ConsentRow = {
  studentId: string;
  fullName: string;
  admissionNo: string;
  className: string | null;
  sectionName: string | null;
  status: StudentStatus;
  purposes: PurposeState[];
};

export type ConsentPage = {
  items: ConsentRow[];
  page: number;
  size: number;
  total: number;
  noticeVersion: number | null;
  activeStudents: number;
  essentialGiven: number;
};

/* ------------------------------------------------------------------ requests */

export type PrivacyStaffRef = { id: string | null; name: string };

export type DataRequestRow = {
  id: string;
  type: RequestType;
  subject: RequestSubject;
  status: RequestStatus;
  resolution: RequestResolution | null;
  studentId: string | null;
  studentName: string | null;
  admissionNo: string | null;
  requesterName: string;
  assignedTo: PrivacyStaffRef | null;
  createdAt: string;
  dueOn: PlainDate;
  daysLeft: number;
  overdue: boolean;
  lastActivityAt: string;
  exportStatus: ExportStatus | null;
};

export type RequestCounts = { open: number; submitted: number; inProgress: number; closed: number; overdue: number };

export type DataRequestPage = {
  items: DataRequestRow[];
  page: number;
  size: number;
  total: number;
  counts: RequestCounts;
};

export type DataRequestQuery = {
  status?: "OPEN" | RequestStatus;
  type?: RequestType;
  q?: string;
  page?: number;
  size?: number;
};

export type DataExport = {
  id: string;
  fileName: string;
  sizeBytes: number;
  status: ExportStatus;
  createdByName: string;
  createdAt: string;
  expiresAt: string;
  deletedAt: string | null;
  downloadCount: number;
  lastDownloadedAt: string | null;
};

export type RequestEvent = {
  id: string;
  kind: RequestEventKind;
  actorName: string | null;
  byRequester: boolean;
  body: string | null;
  at: string;
};

export type DataRequestDetail = {
  id: string;
  type: RequestType;
  subject: RequestSubject;
  status: RequestStatus;
  resolution: RequestResolution | null;
  details: string;
  studentId: string | null;
  studentName: string | null;
  admissionNo: string | null;
  studentStatus: StudentStatus | null;
  requesterName: string;
  assignedTo: PrivacyStaffRef | null;
  createdAt: string;
  dueOn: PlainDate;
  daysLeft: number;
  overdue: boolean;
  closingNote: string | null;
  closedAt: string | null;
  closedByName: string | null;
  erasedAt: string | null;
  canErase: boolean;
  /** The file that can be downloaded now, if any. */
  export: DataExport | null;
  exports: DataExport[];
  events: RequestEvent[];
};

export type DataRequestForm = {
  type: RequestType;
  subject: RequestSubject;
  studentId: string | null;
  details: string | null;
};

export type CloseRequestForm = { resolution: RequestResolution; note: string | null };
