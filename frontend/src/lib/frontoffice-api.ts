import { apiFetch, pathSegment } from "./api";
import { sendForm } from "./files-api";
import { reportQuery, type ReportParams } from "./reports-api";
import type {
  BlockedView,
  BlockRequest,
  CallRequest,
  CallView,
  CheckInRequest,
  CloseRequest,
  ConvertRequest,
  CourierRequest,
  CourierView,
  DecisionRequest,
  EnquiriesReport,
  EnquiryRequest,
  EnquiryView,
  FamilyPickup,
  FollowUpQueue,
  FollowUpsReport,
  GatePassView,
  GateScreen,
  OfficePassRequest,
  OfficeStaffRef,
  PassesReport,
  PickupPersonRequest,
  PickupPersonView,
  PickupRequest,
  ReleaseRequest,
  StudentPickupPersons,
  StudentRef,
  VisitorDay,
  VisitorRequest,
  VisitorsReport,
  VisitorView,
} from "./types";

/* Endpoint helpers for docs/api/phase-2-frontoffice.md. */

const id = pathSegment;
const base = "/api/frontoffice";

export type RangeParams = { from?: string; to?: string };

const query = (params: Record<string, string | null | undefined>) => reportQuery(params as ReportParams);

/** The JSON path and the CSV path (which Excel opens) of each front office report. */
export const frontOfficeReportPaths = {
  visitors: (p: RangeParams) => `${base}/reports/visitors${query(p)}`,
  visitorsCsv: (p: RangeParams) => `${base}/reports/visitors.csv${query(p)}`,
  passes: (p: RangeParams) => `${base}/reports/gate-passes${query(p)}`,
  passesCsv: (p: RangeParams) => `${base}/reports/gate-passes.csv${query(p)}`,
  enquiries: (p: RangeParams) => `${base}/reports/enquiries${query(p)}`,
  enquiriesCsv: (p: RangeParams) => `${base}/reports/enquiries.csv${query(p)}`,
  followUps: () => `${base}/reports/follow-ups`,
  followUpsCsv: () => `${base}/reports/follow-ups.csv`,
};

export const frontOfficeApi = {
  /* visitors */
  day: (date?: string) => apiFetch<VisitorDay>(`${base}/visitors${query({ date })}`),
  register: (date: string) => apiFetch<VisitorView[]>(`${base}/visitors/register${query({ date })}`),
  visitor: (visitorId: string) => apiFetch<VisitorView>(`${base}/visitors/${id(visitorId)}`),
  staff: () => apiFetch<OfficeStaffRef[]>(`${base}/staff`),
  addVisitor: (body: VisitorRequest) => apiFetch<VisitorView>(`${base}/visitors`, { method: "POST", body }),
  updateVisitor: (visitorId: string, body: VisitorRequest) =>
    apiFetch<VisitorView>(`${base}/visitors/${id(visitorId)}`, { method: "PUT", body }),
  checkIn: (visitorId: string, body: CheckInRequest) =>
    apiFetch<VisitorView>(`${base}/visitors/${id(visitorId)}/check-in`, { method: "POST", body }),
  checkOut: (visitorId: string) =>
    apiFetch<VisitorView>(`${base}/visitors/${id(visitorId)}/check-out`, { method: "POST" }),
  visitorPhoto: (visitorId: string, file: Blob, name: string) => {
    const form = new FormData();
    form.append("file", file, name);
    return sendForm<VisitorView>(`${base}/visitors/${id(visitorId)}/photo`, form);
  },
  blocklist: () => apiFetch<BlockedView[]>(`${base}/blocklist`),
  block: (body: BlockRequest) => apiFetch<BlockedView>(`${base}/blocklist`, { method: "POST", body }),
  updateBlock: (blockId: string, body: BlockRequest) =>
    apiFetch<BlockedView>(`${base}/blocklist/${id(blockId)}`, { method: "PUT", body }),
  unblock: (blockId: string) => apiFetch<void>(`${base}/blocklist/${id(blockId)}`, { method: "DELETE" }),

  /* gate passes (the office and the gate) */
  passes: (date?: string) => apiFetch<GatePassView[]>(`${base}/gate-passes${query({ date })}`),
  pass: (passId: string) => apiFetch<GatePassView>(`${base}/gate-passes/${id(passId)}`),
  students: (q: string) => apiFetch<StudentRef[]>(`${base}/students${query({ q })}`),
  raisePass: (body: OfficePassRequest) => apiFetch<GatePassView>(`${base}/gate-passes`, { method: "POST", body }),
  approve: (passId: string, body: DecisionRequest) =>
    apiFetch<GatePassView>(`${base}/gate-passes/${id(passId)}/approve`, { method: "POST", body }),
  reject: (passId: string, body: DecisionRequest) =>
    apiFetch<GatePassView>(`${base}/gate-passes/${id(passId)}/reject`, { method: "POST", body }),
  gate: () => apiFetch<GateScreen>(`${base}/gate`),
  release: (passId: string, body: ReleaseRequest) =>
    apiFetch<GatePassView>(`${base}/gate-passes/${id(passId)}/release`, { method: "POST", body }),
  pickupPersons: (studentId: string) =>
    apiFetch<StudentPickupPersons>(`${base}/students/${id(studentId)}/pickup-persons`),

  /* the family app */
  myPasses: () => apiFetch<GatePassView[]>("/api/me/gate-passes"),
  childPickup: (studentId: string) => apiFetch<FamilyPickup>(`/api/me/children/${id(studentId)}/pickup`),
  requestPickup: (studentId: string, body: PickupRequest) =>
    apiFetch<GatePassView>(`/api/me/children/${id(studentId)}/gate-passes`, { method: "POST", body }),
  cancelPickup: (studentId: string, passId: string) =>
    apiFetch<GatePassView>(`/api/me/children/${id(studentId)}/gate-passes/${id(passId)}/cancel`, { method: "POST" }),
  addPerson: (studentId: string, body: PickupPersonRequest) =>
    apiFetch<PickupPersonView>(`/api/me/children/${id(studentId)}/pickup-persons`, { method: "POST", body }),
  updatePerson: (studentId: string, personId: string, body: PickupPersonRequest) =>
    apiFetch<PickupPersonView>(`/api/me/children/${id(studentId)}/pickup-persons/${id(personId)}`, {
      method: "PUT",
      body,
    }),
  removePerson: (studentId: string, personId: string) =>
    apiFetch<void>(`/api/me/children/${id(studentId)}/pickup-persons/${id(personId)}`, { method: "DELETE" }),
  personPhoto: (studentId: string, personId: string, file: Blob, name: string) => {
    const form = new FormData();
    form.append("file", file, name);
    return sendForm<PickupPersonView>(`/api/me/children/${id(studentId)}/pickup-persons/${id(personId)}/photo`, form);
  },

  /* calls and enquiries */
  calls: (date?: string) => apiFetch<CallView[]>(`${base}/calls${query({ date })}`),
  logCall: (body: CallRequest) => apiFetch<CallView>(`${base}/calls`, { method: "POST", body }),
  updateCall: (callId: string, body: CallRequest) =>
    apiFetch<CallView>(`${base}/calls/${id(callId)}`, { method: "PUT", body }),
  closeCall: (callId: string, body: CloseRequest) =>
    apiFetch<CallView>(`${base}/calls/${id(callId)}/close`, { method: "POST", body }),
  enquiries: (date?: string) => apiFetch<EnquiryView[]>(`${base}/enquiries${query({ date })}`),
  enquiry: (enquiryId: string) => apiFetch<EnquiryView>(`${base}/enquiries/${id(enquiryId)}`),
  logEnquiry: (body: EnquiryRequest) => apiFetch<EnquiryView>(`${base}/enquiries`, { method: "POST", body }),
  updateEnquiry: (enquiryId: string, body: EnquiryRequest) =>
    apiFetch<EnquiryView>(`${base}/enquiries/${id(enquiryId)}`, { method: "PUT", body }),
  closeEnquiry: (enquiryId: string, body: CloseRequest) =>
    apiFetch<EnquiryView>(`${base}/enquiries/${id(enquiryId)}/close`, { method: "POST", body }),
  reopenEnquiry: (enquiryId: string, followUpOn: string | null) =>
    apiFetch<EnquiryView>(`${base}/enquiries/${id(enquiryId)}/reopen`, { method: "POST", body: { followUpOn } }),
  convert: (enquiryId: string, body: ConvertRequest) =>
    apiFetch<EnquiryView>(`${base}/enquiries/${id(enquiryId)}/convert`, { method: "POST", body }),
  followUps: () => apiFetch<FollowUpQueue>(`${base}/follow-ups`),

  /* the courier register */
  courier: (p: RangeParams) => apiFetch<CourierView[]>(`${base}/courier${query(p)}`),
  logCourier: (body: CourierRequest) => apiFetch<CourierView>(`${base}/courier`, { method: "POST", body }),
  updateCourier: (itemId: string, body: CourierRequest) =>
    apiFetch<CourierView>(`${base}/courier/${id(itemId)}`, { method: "PUT", body }),
  removeCourier: (itemId: string) => apiFetch<void>(`${base}/courier/${id(itemId)}`, { method: "DELETE" }),

  /* reports */
  visitorsReport: (p: RangeParams) => apiFetch<VisitorsReport>(frontOfficeReportPaths.visitors(p)),
  passesReport: (p: RangeParams) => apiFetch<PassesReport>(frontOfficeReportPaths.passes(p)),
  enquiriesReport: (p: RangeParams) => apiFetch<EnquiriesReport>(frontOfficeReportPaths.enquiries(p)),
  followUpsReport: () => apiFetch<FollowUpsReport>(frontOfficeReportPaths.followUps()),
};
