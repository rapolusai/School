import { apiFetch } from "./api";
import type {
  AdmissionsBoard,
  AdmissionsSummary,
  AdmitRequest,
  ApplicationDetail,
  ApplicationPage,
  ApplicationQuery,
  ApplicationRequest,
  FeeRequest,
  OfferRequest,
  PublicEnquiryRequest,
  PublicSchoolInfo,
  SlotRequest,
  StaffRef,
  StageRequest,
  UpcomingSlot,
} from "./types";

/* Endpoint helpers for docs/api/phase-1-admissions.md. */

const id = (value: string) => encodeURIComponent(value);

/** "?yearId=…&q=…" from the set filters only. Exported for tests. */
export function admissionsQueryString(query: ApplicationQuery): string {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (value === undefined || value === null || value === "") continue;
    params.set(key, String(value));
  }
  const text = params.toString();
  return text ? `?${text}` : "";
}

const base = "/api/admissions";
const app = (applicationId: string) => `${base}/applications/${id(applicationId)}`;

export const admissionsApi = {
  summary: () => apiFetch<AdmissionsSummary>(`${base}/summary`),
  board: (query: Omit<ApplicationQuery, "stage" | "page" | "size"> = {}) =>
    apiFetch<AdmissionsBoard>(`${base}/board${admissionsQueryString(query)}`),
  list: (query: ApplicationQuery = {}) =>
    apiFetch<ApplicationPage>(`${base}/applications${admissionsQueryString(query)}`),
  get: (applicationId: string) => apiFetch<ApplicationDetail>(app(applicationId)),
  upcomingSlots: (days = 14) => apiFetch<UpcomingSlot[]>(`${base}/slots/upcoming?days=${days}`),
  staff: () => apiFetch<StaffRef[]>(`${base}/staff`),

  create: (body: ApplicationRequest) =>
    apiFetch<ApplicationDetail>(`${base}/applications`, { method: "POST", body }),
  update: (applicationId: string, body: ApplicationRequest) =>
    apiFetch<ApplicationDetail>(app(applicationId), { method: "PUT", body }),
  moveStage: (applicationId: string, body: StageRequest) =>
    apiFetch<ApplicationDetail>(`${app(applicationId)}/stage`, { method: "POST", body }),
  addNote: (applicationId: string, note: string) =>
    apiFetch<ApplicationDetail>(`${app(applicationId)}/notes`, { method: "POST", body: { note } }),
  recordFee: (applicationId: string, body: FeeRequest) =>
    apiFetch<ApplicationDetail>(`${app(applicationId)}/fee`, { method: "PUT", body }),
  updateOffer: (applicationId: string, body: OfferRequest) =>
    apiFetch<ApplicationDetail>(`${app(applicationId)}/offer`, { method: "PUT", body }),
  scheduleSlot: (applicationId: string, body: SlotRequest) =>
    apiFetch<ApplicationDetail>(`${app(applicationId)}/slots`, { method: "POST", body }),
  rescheduleSlot: (applicationId: string, slotId: string, body: SlotRequest) =>
    apiFetch<ApplicationDetail>(`${app(applicationId)}/slots/${id(slotId)}`, { method: "PUT", body }),
  recordOutcome: (applicationId: string, slotId: string, notes: string) =>
    apiFetch<ApplicationDetail>(`${app(applicationId)}/slots/${id(slotId)}/outcome`, {
      method: "POST",
      body: { notes },
    }),
  cancelSlot: (applicationId: string, slotId: string) =>
    apiFetch<ApplicationDetail>(`${app(applicationId)}/slots/${id(slotId)}/cancel`, { method: "POST" }),
  admit: (applicationId: string, body: AdmitRequest) =>
    apiFetch<ApplicationDetail>(`${app(applicationId)}/admit`, { method: "POST", body }),

  /* The public enquiry form: no sign-in, so no token and no refresh on 401. */
  publicInfo: (schoolCode: string) =>
    apiFetch<PublicSchoolInfo>(`/api/public/schools/${id(schoolCode)}/admission-info`, { auth: false }),
  sendEnquiry: (schoolCode: string, body: PublicEnquiryRequest) =>
    apiFetch<{ received: boolean }>(`/api/public/schools/${id(schoolCode)}/enquiries`, {
      method: "POST",
      body,
      auth: false,
    }),
};
