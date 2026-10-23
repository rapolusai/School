import { apiFetch, pathSegment } from "./api";
import { sendForm } from "./files-api";
import { reportQuery, type ReportParams } from "./reports-api";
import type {
  CardBatchRequest,
  CardBatchResult,
  CardPhoto,
  CardRegister,
  CardRequest,
  CardsPendingReport,
  CertificateDetail,
  CertificatePage,
  CertificateSettings,
  CertificateSettingsRequest,
  CertificateStatus,
  CertificateSubjectType,
  CertificateTemplate,
  CertificateType,
  EditCertificateRequest,
  FamilyCertificates,
  FamilyRequest,
  IdCard,
  IssuedCertificatesReport,
  PendingCertificatesReport,
  PrepareRequest,
  ReissueRequest,
  TemplateRequest,
  Verification,
} from "./types";

/* Endpoint helpers for docs/api/phase-2-certificates.md. */

const id = pathSegment;

export type CertificateQuery = {
  status?: CertificateStatus;
  type?: CertificateType;
  studentId?: string;
  staffUserId?: string;
  issuedFrom?: string;
  issuedTo?: string;
  page?: number;
  size?: number;
};
export type CardQuery = { subjectType?: CertificateSubjectType; sectionId?: string; all?: boolean; page?: number; size?: number };
export type IssuedParams = { from?: string; to?: string; type?: CertificateType };
export type CardsPendingParams = { sectionId?: string; staff?: boolean };

const query = (params: Record<string, string | number | boolean | null | undefined>) =>
  reportQuery(
    Object.fromEntries(
      Object.entries(params).map(([key, value]) => [key, typeof value === "number" ? String(value) : value]),
    ) as ReportParams,
  );

/** The JSON path and the CSV path (which Excel opens) of each report. */
export const certificateReportPaths = {
  issued: (p: IssuedParams) => `/api/certificates/reports/issued${query(p)}`,
  issuedCsv: (p: IssuedParams) => `/api/certificates/reports/issued.csv${query(p)}`,
  pending: () => "/api/certificates/reports/pending",
  pendingCsv: () => "/api/certificates/reports/pending.csv",
  cardsPending: (p: CardsPendingParams) => `/api/certificates/reports/id-cards-pending${query(p)}`,
  cardsPendingCsv: (p: CardsPendingParams) => `/api/certificates/reports/id-cards-pending.csv${query(p)}`,
};

export const certificatesApi = {
  // Settings and templates
  settings: () => apiFetch<CertificateSettings>("/api/certificates/settings"),
  saveSettings: (body: CertificateSettingsRequest) =>
    apiFetch<CertificateSettings>("/api/certificates/settings", { method: "PUT", body }),
  templates: () => apiFetch<CertificateTemplate[]>("/api/certificates/templates"),
  template: (type: CertificateType) => apiFetch<CertificateTemplate>(`/api/certificates/templates/${type}`),
  saveTemplate: (type: CertificateType, body: TemplateRequest) =>
    apiFetch<CertificateTemplate>(`/api/certificates/templates/${type}`, { method: "PUT", body }),

  // The register and the flow
  list: (q: CertificateQuery = {}) => apiFetch<CertificatePage>(`/api/certificates${query(q)}`),
  prepare: (body: PrepareRequest) => apiFetch<CertificateDetail>("/api/certificates", { method: "POST", body }),
  detail: (certificateId: string) => apiFetch<CertificateDetail>(`/api/certificates/${id(certificateId)}`),
  edit: (certificateId: string, body: EditCertificateRequest) =>
    apiFetch<CertificateDetail>(`/api/certificates/${id(certificateId)}`, { method: "PUT", body }),
  sign: (certificateId: string) =>
    apiFetch<CertificateDetail>(`/api/certificates/${id(certificateId)}/sign`, { method: "POST" }),
  cancel: (certificateId: string, reason: string) =>
    apiFetch<CertificateDetail>(`/api/certificates/${id(certificateId)}/cancel`, { method: "POST", body: { reason } }),
  correct: (certificateId: string, reason: string) =>
    apiFetch<CertificateDetail>(`/api/certificates/${id(certificateId)}/correct`, { method: "POST", body: { reason } }),

  // ID cards
  cards: (q: CardQuery = {}) => apiFetch<CardRegister>(`/api/certificates/id-cards${query(q)}`),
  card: (cardId: string) => apiFetch<IdCard>(`/api/certificates/id-cards/${id(cardId)}`),
  issueCard: (body: CardRequest) => apiFetch<IdCard>("/api/certificates/id-cards", { method: "POST", body }),
  issueCards: (body: CardBatchRequest) =>
    apiFetch<CardBatchResult>("/api/certificates/id-cards/batch", { method: "POST", body }),
  reissueCard: (cardId: string, body: ReissueRequest) =>
    apiFetch<IdCard>(`/api/certificates/id-cards/${id(cardId)}/reissue`, { method: "POST", body }),
  photo: (subjectType: CertificateSubjectType, personId: string) =>
    apiFetch<CardPhoto>(`/api/certificates/id-cards/photos/${subjectType}/${id(personId)}`),
  uploadPhoto: (subjectType: CertificateSubjectType, personId: string, file: File) => {
    const form = new FormData();
    form.append("file", file);
    return sendForm<CardPhoto>(`/api/certificates/id-cards/photos/${subjectType}/${id(personId)}`, form);
  },

  // Reports
  issuedReport: (p: IssuedParams) => apiFetch<IssuedCertificatesReport>(certificateReportPaths.issued(p)),
  pendingReport: () => apiFetch<PendingCertificatesReport>(certificateReportPaths.pending()),
  cardsPendingReport: (p: CardsPendingParams) => apiFetch<CardsPendingReport>(certificateReportPaths.cardsPending(p)),

  // Families
  mine: () => apiFetch<FamilyCertificates>("/api/me/certificates"),
  request: (body: FamilyRequest) =>
    apiFetch<CertificateDetail>("/api/me/certificates/requests", { method: "POST", body }),
  myCertificate: (certificateId: string) => apiFetch<CertificateDetail>(`/api/me/certificates/${id(certificateId)}`),
  myIdCards: () => apiFetch<IdCard[]>("/api/me/certificates/id-cards"),

  // Public
  /* Anyone can scan the code: no token and no refresh on 401. */
  verify: (token: string) => apiFetch<Verification>(`/api/public/verify/${id(token)}`, { auth: false }),
};
