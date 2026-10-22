/** Types mirroring docs/api/phase-2-certificates.md. Money is in paise; plain dates are "YYYY-MM-DD". */

export const CERTIFICATE_TYPES = [
  "TRANSFER",
  "BONAFIDE",
  "CHARACTER",
  "FEE",
  "STUDY",
  "MIGRATION",
  "CUSTOM",
  "EXPERIENCE",
  "SALARY",
] as const;
export type CertificateType = (typeof CERTIFICATE_TYPES)[number];

/** The types issued to members of staff; every other type is for a student. */
export const STAFF_CERTIFICATE_TYPES: readonly CertificateType[] = ["EXPERIENCE", "SALARY"];

/** The types a parent may request from the family app. */
export const FAMILY_CERTIFICATE_TYPES: readonly CertificateType[] = ["BONAFIDE", "FEE"];

export const CERTIFICATE_STATUSES = ["REQUESTED", "PREPARED", "ISSUED", "CANCELLED"] as const;
export type CertificateStatus = (typeof CERTIFICATE_STATUSES)[number];

export type CertificateSubjectType = "STUDENT" | "STAFF";
export type RequestedVia = "OFFICE" | "FAMILY";
export type CardStatus = "ACTIVE" | "CANCELLED";

/* Blood groups on an ID card come from BLOOD_GROUPS in ./school. */

export type SchoolHeader = {
  name: string;
  address: string | null;
  affiliationNo: string | null;
  logoText: string | null;
  city: string | null;
  phone: string | null;
};

export type Signatory = { name: string | null; designation: string | null };

export type CertificateSettings = {
  saved: boolean;
  headerName: string | null;
  headerAddress: string | null;
  affiliationNo: string | null;
  logoText: string | null;
  signatoryName: string | null;
  signatoryDesignation: string | null;
  tcDuesCheck: boolean;
  cardValidMonths: number;
  lostCardFeePaise: number;
};

export type CertificateSettingsRequest = Omit<CertificateSettings, "saved">;

export type CertificateTemplate = {
  type: CertificateType;
  title: string;
  body: string;
  serialPrefix: string;
  staff: boolean;
  placeholders: string[];
  /** true once the school saved its own wording */
  custom: boolean;
  nextSerial: string;
};

export type TemplateRequest = { title: string; body: string; serialPrefix: string };

/** Whom a certificate or card is for: code is the admission number or employee code, detail the class or designation. */
export type CertificateSubject = {
  type: CertificateSubjectType;
  id: string;
  name: string | null;
  firstName: string | null;
  code: string | null;
  detail: string | null;
  active: boolean;
};

export type CertificateRow = {
  id: string;
  type: CertificateType;
  title: string;
  status: CertificateStatus;
  subject: CertificateSubject;
  serialNo: string | null;
  versionNo: number;
  purpose: string | null;
  requestedVia: RequestedVia;
  requestedByName: string | null;
  requestedAt: string;
  issuedOn: string | null;
  signedByName: string | null;
};

export type CertificatePage = { items: CertificateRow[]; page: number; size: number; total: number };

export type CertificateDetail = CertificateRow & {
  academicYear: string | null;
  supersedesId: string | null;
  supersededById: string | null;
  fields: Record<string, string | null>;
  placeholders: string[];
  /** frozen once signed; otherwise rendered from the template as it stands */
  body: string;
  school: SchoolHeader;
  signatory: Signatory;
  verifyToken: string | null;
  verifyPath: string | null;
  duesPaise: number | null;
  preparedByName: string | null;
  preparedAt: string | null;
  signedAt: string | null;
  cancelledByName: string | null;
  cancelledAt: string | null;
  cancelReason: string | null;
  editable: boolean;
};

export type PrepareRequest = {
  type: CertificateType;
  studentId?: string;
  staffUserId?: string;
  purpose?: string;
  from?: string;
  to?: string;
  fields?: Record<string, string>;
};

export type EditCertificateRequest = { title: string; purpose?: string; fields: Record<string, string> };

export type FamilyRequest = { studentId: string; type: CertificateType; purpose: string; from?: string; to?: string };

/** What the public verification page shows. */
export type Verification = {
  schoolName: string;
  type: CertificateType;
  title: string;
  serialNo: string;
  firstName: string | null;
  issuedOn: string;
  valid: boolean;
  status: CertificateStatus;
};

export type IdCard = {
  id: string;
  subject: CertificateSubject;
  cardNo: string;
  issueNo: number;
  status: CardStatus;
  bloodGroup: string | null;
  /** in full for the printed card; screens mask it */
  emergencyPhone: string | null;
  validFrom: string;
  validUntil: string;
  photoFileId: string | null;
  replacesId: string | null;
  reissueReason: string | null;
  lostFeePaise: number | null;
  feeNote: string | null;
  issuedByName: string | null;
  createdAt: string;
  cancelledAt: string | null;
};

export type CardRegister = { school: SchoolHeader; items: IdCard[]; page: number; size: number; total: number };

export type CardRequest = {
  subjectType: CertificateSubjectType;
  studentId?: string;
  staffUserId?: string;
  bloodGroup?: string;
  emergencyPhone?: string;
  validUntil?: string;
};

export type CardBatchRequest = { sectionId?: string; staff?: boolean; validUntil?: string };

export type CardBatchResult = { issued: number; skipped: number; cards: IdCard[] };

export type ReissueRequest = {
  reason: string;
  lostFeePaise?: number;
  feeNote?: string;
  bloodGroup?: string;
  emergencyPhone?: string;
};

export type CardPhoto = { fileId: string; name: string };

export type IssuedCertificatesReport = {
  from: string;
  to: string;
  total: number;
  byType: { type: CertificateType; count: number }[];
  rows: CertificateRow[];
};

export type PendingCertificatesReport = { requested: number; prepared: number; rows: CertificateRow[] };

export type CardsPendingReport = {
  sectionId: string | null;
  total: number;
  rows: { subject: CertificateSubject; sectionId: string | null; sectionLabel: string | null }[];
};

export type FamilyCertificates = {
  children: {
    studentId: string;
    name: string;
    className: string | null;
    certificates: CertificateRow[];
    idCard: IdCard | null;
  }[];
  requestableTypes: CertificateType[];
};
