import { apiFetch } from "./api";
import { reportQuery, type ReportParams } from "./reports-api";
import type {
  AllergiesReport,
  AuditRequest,
  CloseAuditRequest,
  CloseVisitRequest,
  CommitteeMeeting,
  CommitteeMember,
  ComplaintRequest,
  ConsentRequest,
  DrillRequest,
  DrillsReport,
  FireDrill,
  FirstAidKit,
  GrowthMeasurement,
  GrowthRequest,
  HealthItem,
  HealthItemRequest,
  HealthPersonRef,
  Incident,
  IncidentRequest,
  InfirmaryDay,
  InfirmaryReport,
  InfirmaryVisit,
  KitCheckRequest,
  MedicalRecord,
  MedicalRecordRequest,
  Medicine,
  MedicineRequest,
  MeetingRequest,
  MemberRequest,
  PocsoCommittee,
  PocsoComplaint,
  SafetyAudit,
  SafetyOverview,
  StaffChecks,
  StaffTraining,
  StaffVerification,
  TrainingRequest,
  VerificationRequest,
  VerificationsReport,
  VisitRequest,
} from "./types";

/* Endpoint helpers for docs/api/phase-3-health.md. */

const id = (value: string) => encodeURIComponent(value);
const base = "/api/health";

export type RangeParams = { from?: string; to?: string };

const query = (params: Record<string, string | null | undefined>) => reportQuery(params as ReportParams);

/** The JSON path and the CSV path (which Excel opens) of each health and safety report. */
export const healthReportPaths = {
  infirmary: (p: RangeParams) => `${base}/reports/infirmary${query(p)}`,
  infirmaryCsv: (p: RangeParams) => `${base}/reports/infirmary.csv${query(p)}`,
  allergies: () => `${base}/reports/allergies`,
  allergiesCsv: () => `${base}/reports/allergies.csv`,
  drills: () => `${base}/reports/drills`,
  drillsCsv: () => `${base}/reports/drills.csv`,
  verifications: () => `${base}/reports/verifications`,
  verificationsCsv: () => `${base}/reports/verifications.csv`,
};

export const healthApi = {
  /* medical records (every staff read is audited with the screen it came from) */
  students: (q: string) => apiFetch<HealthPersonRef[]>(`${base}/students${query({ q })}`),
  record: (studentId: string, screen: string) =>
    apiFetch<MedicalRecord>(`${base}/students/${id(studentId)}/record${query({ screen })}`),
  saveRecord: (studentId: string, body: MedicalRecordRequest) =>
    apiFetch<MedicalRecord>(`${base}/students/${id(studentId)}/record`, { method: "PUT", body }),
  saveConsent: (studentId: string, body: ConsentRequest) =>
    apiFetch<MedicalRecord>(`${base}/students/${id(studentId)}/record/consent`, { method: "PUT", body }),
  addItem: (studentId: string, body: HealthItemRequest) =>
    apiFetch<HealthItem>(`${base}/students/${id(studentId)}/record/items`, { method: "POST", body }),
  updateItem: (studentId: string, itemId: string, body: HealthItemRequest) =>
    apiFetch<HealthItem>(`${base}/students/${id(studentId)}/record/items/${id(itemId)}`, { method: "PUT", body }),
  removeItem: (studentId: string, itemId: string) =>
    apiFetch<void>(`${base}/students/${id(studentId)}/record/items/${id(itemId)}`, { method: "DELETE" }),
  addGrowth: (studentId: string, body: GrowthRequest) =>
    apiFetch<GrowthMeasurement>(`${base}/students/${id(studentId)}/record/growth`, { method: "POST", body }),
  removeGrowth: (studentId: string, measurementId: string) =>
    apiFetch<void>(`${base}/students/${id(studentId)}/record/growth/${id(measurementId)}`, { method: "DELETE" }),

  /* the family app: allergies, conditions and consent for the parent's own child */
  childHealth: (studentId: string) => apiFetch<MedicalRecord>(`/api/me/children/${id(studentId)}/health`),
  childConsent: (studentId: string, body: ConsentRequest) =>
    apiFetch<MedicalRecord>(`/api/me/children/${id(studentId)}/health/consent`, { method: "PUT", body }),
  childAddItem: (studentId: string, body: HealthItemRequest) =>
    apiFetch<HealthItem>(`/api/me/children/${id(studentId)}/health/items`, { method: "POST", body }),
  childUpdateItem: (studentId: string, itemId: string, body: HealthItemRequest) =>
    apiFetch<HealthItem>(`/api/me/children/${id(studentId)}/health/items/${id(itemId)}`, { method: "PUT", body }),
  childRemoveItem: (studentId: string, itemId: string) =>
    apiFetch<void>(`/api/me/children/${id(studentId)}/health/items/${id(itemId)}`, { method: "DELETE" }),

  /* the infirmary */
  day: (date?: string) => apiFetch<InfirmaryDay>(`${base}/infirmary/visits${query({ date })}`),
  visit: (visitId: string) => apiFetch<InfirmaryVisit>(`${base}/infirmary/visits/${id(visitId)}`),
  people: (q: string) => apiFetch<HealthPersonRef[]>(`${base}/infirmary/people${query({ q })}`),
  addVisit: (body: VisitRequest) => apiFetch<InfirmaryVisit>(`${base}/infirmary/visits`, { method: "POST", body }),
  updateVisit: (visitId: string, body: VisitRequest) =>
    apiFetch<InfirmaryVisit>(`${base}/infirmary/visits/${id(visitId)}`, { method: "PUT", body }),
  closeVisit: (visitId: string, body: CloseVisitRequest) =>
    apiFetch<InfirmaryVisit>(`${base}/infirmary/visits/${id(visitId)}/close`, { method: "POST", body }),
  medicines: () => apiFetch<Medicine[]>(`${base}/infirmary/medicines`),
  addMedicine: (body: MedicineRequest) => apiFetch<Medicine>(`${base}/infirmary/medicines`, { method: "POST", body }),
  updateMedicine: (medicineId: string, body: MedicineRequest) =>
    apiFetch<Medicine>(`${base}/infirmary/medicines/${id(medicineId)}`, { method: "PUT", body }),
  removeMedicine: (medicineId: string) => apiFetch<void>(`${base}/infirmary/medicines/${id(medicineId)}`, { method: "DELETE" }),

  /* safety registers */
  safety: () => apiFetch<SafetyOverview>(`${base}/safety`),
  drills: () => apiFetch<FireDrill[]>(`${base}/safety/drills`),
  addDrill: (body: DrillRequest) => apiFetch<FireDrill>(`${base}/safety/drills`, { method: "POST", body }),
  updateDrill: (drillId: string, body: DrillRequest) =>
    apiFetch<FireDrill>(`${base}/safety/drills/${id(drillId)}`, { method: "PUT", body }),
  removeDrill: (drillId: string) => apiFetch<void>(`${base}/safety/drills/${id(drillId)}`, { method: "DELETE" }),
  addAudit: (body: AuditRequest) => apiFetch<SafetyAudit>(`${base}/safety/audits`, { method: "POST", body }),
  updateAudit: (auditId: string, body: AuditRequest) =>
    apiFetch<SafetyAudit>(`${base}/safety/audits/${id(auditId)}`, { method: "PUT", body }),
  closeAudit: (auditId: string, body: CloseAuditRequest) =>
    apiFetch<SafetyAudit>(`${base}/safety/audits/${id(auditId)}/close`, { method: "POST", body }),
  reopenAudit: (auditId: string) => apiFetch<SafetyAudit>(`${base}/safety/audits/${id(auditId)}/reopen`, { method: "POST" }),
  addIncident: (body: IncidentRequest) => apiFetch<Incident>(`${base}/safety/incidents`, { method: "POST", body }),
  updateIncident: (incidentId: string, body: IncidentRequest) =>
    apiFetch<Incident>(`${base}/safety/incidents/${id(incidentId)}`, { method: "PUT", body }),
  addKit: (location: string) => apiFetch<FirstAidKit>(`${base}/safety/kits`, { method: "POST", body: { location } }),
  checkKit: (kitId: string, body: KitCheckRequest) =>
    apiFetch<FirstAidKit>(`${base}/safety/kits/${id(kitId)}/check`, { method: "POST", body }),
  removeKit: (kitId: string) => apiFetch<void>(`${base}/safety/kits/${id(kitId)}`, { method: "DELETE" }),

  /* the POCSO committee */
  committee: () => apiFetch<PocsoCommittee>(`${base}/pocso/committee`),
  saveCommittee: (formedOn: string | null) =>
    apiFetch<PocsoCommittee>(`${base}/pocso/committee`, { method: "PUT", body: { formedOn } }),
  addMember: (body: MemberRequest) => apiFetch<CommitteeMember>(`${base}/pocso/committee/members`, { method: "POST", body }),
  removeMember: (memberId: string) => apiFetch<void>(`${base}/pocso/committee/members/${id(memberId)}`, { method: "DELETE" }),
  addMeeting: (body: MeetingRequest) => apiFetch<CommitteeMeeting>(`${base}/pocso/committee/meetings`, { method: "POST", body }),
  complaints: () => apiFetch<PocsoComplaint[]>(`${base}/pocso/complaints`),
  complaint: (complaintId: string) => apiFetch<PocsoComplaint>(`${base}/pocso/complaints/${id(complaintId)}`),
  addComplaint: (body: ComplaintRequest) => apiFetch<PocsoComplaint>(`${base}/pocso/complaints`, { method: "POST", body }),
  updateComplaint: (complaintId: string, body: ComplaintRequest) =>
    apiFetch<PocsoComplaint>(`${base}/pocso/complaints/${id(complaintId)}`, { method: "PUT", body }),

  /* staff police verification and training */
  staffChecks: () => apiFetch<StaffChecks>(`${base}/staff/checks`),
  staffChecksDue: () => apiFetch<StaffChecks>(`${base}/staff/checks/due`),
  saveVerification: (userId: string, body: VerificationRequest) =>
    apiFetch<StaffVerification>(`${base}/staff/${id(userId)}/verification`, { method: "PUT", body }),
  addTraining: (body: TrainingRequest) => apiFetch<StaffTraining>(`${base}/staff/trainings`, { method: "POST", body }),
  removeTraining: (trainingId: string) => apiFetch<void>(`${base}/staff/trainings/${id(trainingId)}`, { method: "DELETE" }),

  /* reports */
  infirmaryReport: (p: RangeParams) => apiFetch<InfirmaryReport>(healthReportPaths.infirmary(p)),
  allergiesReport: () => apiFetch<AllergiesReport>(healthReportPaths.allergies()),
  drillsReport: () => apiFetch<DrillsReport>(healthReportPaths.drills()),
  verificationsReport: () => apiFetch<VerificationsReport>(healthReportPaths.verifications()),
};
