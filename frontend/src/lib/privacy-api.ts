import { apiFetch, getAccessToken, parseErrorResponse, refreshSession, toApiError } from "./api";
import type {
  AcceptConsentRequest,
  CloseRequestForm,
  ConsentPage,
  DataRequestDetail,
  DataRequestForm,
  DataRequestPage,
  DataRequestQuery,
  DataRequestRow,
  GrievanceOfficer,
  GrievanceOfficerRequest,
  NoticeAdmin,
  OptionalPurpose,
  PaperConsentRequest,
  ParentPrivacy,
  PrivacyNotice,
  PublicNotice,
  PublishNoticeRequest,
  PrivacyStaffRef,
  StudentConsent,
} from "./types";

/* Data protection endpoints (docs/api/phase-1-privacy.md). Kept apart from api.ts so the slices merge cleanly. */

const id = (value: string) => encodeURIComponent(value);

/** "?a=1&b=x" from the values that are set; empty strings are skipped. */
export function privacyQuery(params: Record<string, string | number | undefined | null>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === "") continue;
    search.set(key, String(value));
  }
  const text = search.toString();
  return text ? `?${text}` : "";
}

const request = (requestId: string) => `/api/privacy/requests/${id(requestId)}`;
const myRequest = (requestId: string) => `/api/me/privacy/requests/${id(requestId)}`;

export const privacyApi = {
  /* Staff: notice and grievance officer */
  noticeAdmin: () => apiFetch<NoticeAdmin>("/api/privacy/notice"),
  noticeVersion: (version: number) => apiFetch<PrivacyNotice>(`/api/privacy/notice/versions/${version}`),
  publishNotice: (body: PublishNoticeRequest) =>
    apiFetch<PrivacyNotice>("/api/privacy/notice", { method: "POST", body }),
  updateOfficer: (body: GrievanceOfficerRequest) =>
    apiFetch<GrievanceOfficer>("/api/privacy/grievance-officer", { method: "PUT", body }),

  /* Staff: consent */
  consents: (query: { q?: string; page?: number; size?: number } = {}) =>
    apiFetch<ConsentPage>(`/api/privacy/consents${privacyQuery(query)}`),
  studentConsents: (studentId: string) => apiFetch<StudentConsent>(`/api/privacy/students/${id(studentId)}/consents`),
  recordPaperConsent: (studentId: string, body: PaperConsentRequest) =>
    apiFetch<StudentConsent>(`/api/privacy/students/${id(studentId)}/consents`, { method: "POST", body }),

  /* Staff: the request queue */
  requests: (query: DataRequestQuery = {}) =>
    apiFetch<DataRequestPage>(`/api/privacy/requests${privacyQuery(query)}`),
  request: (requestId: string) => apiFetch<DataRequestDetail>(request(requestId)),
  staff: () => apiFetch<PrivacyStaffRef[]>("/api/privacy/staff"),
  assign: (requestId: string, assigneeId: string) =>
    apiFetch<DataRequestDetail>(`${request(requestId)}/assign`, { method: "POST", body: { assigneeId } }),
  reply: (requestId: string, body: string) =>
    apiFetch<DataRequestDetail>(`${request(requestId)}/replies`, { method: "POST", body: { body } }),
  createExport: (requestId: string) =>
    apiFetch<DataRequestDetail>(`${request(requestId)}/export`, { method: "POST" }),
  erase: (requestId: string, confirmAdmissionNo: string) =>
    apiFetch<DataRequestDetail>(`${request(requestId)}/erase`, { method: "POST", body: { confirmAdmissionNo } }),
  close: (requestId: string, body: CloseRequestForm) =>
    apiFetch<DataRequestDetail>(`${request(requestId)}/close`, { method: "POST", body }),

  /* Parents */
  mine: () => apiFetch<ParentPrivacy>("/api/me/privacy"),
  accept: (body: AcceptConsentRequest) => apiFetch<ParentPrivacy>("/api/me/privacy/consent", { method: "POST", body }),
  change: (studentId: string, purpose: OptionalPurpose, given: boolean) =>
    apiFetch<ParentPrivacy>(`/api/me/privacy/children/${id(studentId)}/consents/${purpose}`, {
      method: "PUT",
      body: { given },
    }),
  myRequests: () => apiFetch<DataRequestRow[]>("/api/me/privacy/requests"),
  myRequest: (requestId: string) => apiFetch<DataRequestDetail>(myRequest(requestId)),
  submit: (body: DataRequestForm) =>
    apiFetch<DataRequestDetail>("/api/me/privacy/requests", { method: "POST", body }),
  myReply: (requestId: string, body: string) =>
    apiFetch<DataRequestDetail>(`${myRequest(requestId)}/replies`, { method: "POST", body: { body } }),

  /* Anyone: a school's notice */
  publicNotice: (schoolCode: string, version?: number) =>
    apiFetch<PublicNotice>(`/api/public/schools/${id(schoolCode)}/privacy-notice${privacyQuery({ version })}`, {
      auth: false,
    }),
};

/** Paths of the ZIP downloads: staff and the parent who asked. */
export const privacyDownloads = {
  staff: (requestId: string) => `${request(requestId)}/export`,
  mine: (requestId: string) => `${myRequest(requestId)}/export`,
};

/**
 * Downloads a data export with the signed-in user's token (a plain link would not carry it):
 * fetches the ZIP, then saves it under `filename` through a temporary object URL.
 */
export async function downloadExport(path: string, filename: string): Promise<void> {
  const send = (token: string | null) =>
    fetch(path, {
      headers: { Accept: "application/zip", ...(token ? { Authorization: `Bearer ${token}` } : {}) },
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
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  link.remove();
  // Some browsers read the object URL after click() returns; free it a little later.
  setTimeout(() => URL.revokeObjectURL(url), 10_000);
}
