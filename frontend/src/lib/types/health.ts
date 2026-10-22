/** Types mirroring docs/api/phase-3-health.md. Plain dates are "YYYY-MM-DD"; instants are ISO-8601. */

/** The blood groups a record may hold (the students module lists the same under BLOOD_GROUPS). */
export const HEALTH_BLOOD_GROUPS = ["A+", "A-", "B+", "B-", "AB+", "AB-", "O+", "O-"] as const;

export type ItemKind = "ALLERGY" | "CONDITION" | "MEDICATION" | "VACCINATION";
export const ALLERGY_SEVERITIES = ["MILD", "MODERATE", "SEVERE"] as const;
export type AllergySeverity = (typeof ALLERGY_SEVERITIES)[number];
export type ItemSource = "PARENT" | "STAFF";
export type PersonKind = "STUDENT" | "STAFF";
export const VISIT_OUTCOMES = ["BACK_TO_CLASS", "SENT_HOME", "HOSPITAL"] as const;
export type VisitOutcome = (typeof VISIT_OUTCOMES)[number];
export const DRILL_KINDS = ["FIRE", "EVACUATION", "EARTHQUAKE"] as const;
export type DrillKind = (typeof DRILL_KINDS)[number];
export const AUDIT_AREAS = ["ELECTRICAL", "PLAYGROUND", "LABS", "TRANSPORT", "BUILDING", "WATER"] as const;
export type AuditArea = (typeof AUDIT_AREAS)[number];
export const AUDIT_SEVERITIES = ["LOW", "MEDIUM", "HIGH"] as const;
export type AuditSeverity = (typeof AUDIT_SEVERITIES)[number];
export type AuditStatus = "OPEN" | "CLOSED";
export const INCIDENT_KINDS = ["INJURY", "BULLYING", "OTHER"] as const;
export type IncidentKind = (typeof INCIDENT_KINDS)[number];
export const INCIDENT_SEVERITIES = ["MINOR", "MODERATE", "SERIOUS"] as const;
export type IncidentSeverity = (typeof INCIDENT_SEVERITIES)[number];
export const COMMITTEE_ROLES = ["CHAIRPERSON", "MEMBER", "EXTERNAL", "SECRETARY"] as const;
export type CommitteeRole = (typeof COMMITTEE_ROLES)[number];
export const COMPLAINT_CATEGORIES = ["ABUSE", "HARASSMENT", "NEGLECT", "OTHER"] as const;
export type ComplaintCategory = (typeof COMPLAINT_CATEGORIES)[number];
export const COMPLAINT_STATUSES = ["OPEN", "UNDER_ENQUIRY", "REFERRED", "CLOSED"] as const;
export type ComplaintStatus = (typeof COMPLAINT_STATUSES)[number];
export const VERIFICATION_STATUSES = ["PENDING", "REQUESTED", "CLEARED", "FLAGGED"] as const;
export type VerificationStatus = (typeof VERIFICATION_STATUSES)[number];
export const TRAINING_KINDS = ["POCSO", "FIRST_AID"] as const;
export type TrainingKind = (typeof TRAINING_KINDS)[number];
/** How soon something is due: within 30 days is DUE_SOON. */
export type Due = "OK" | "DUE_SOON" | "OVERDUE" | "MISSING";

export type HealthPersonRef = { id: string; kind: PersonKind; name: string; admissionNo: string | null; label: string | null };

/* ------------------------------------------------------------------ medical record */

export type HealthItem = {
  id: string;
  kind: ItemKind;
  name: string;
  detail: string | null;
  severity: AllergySeverity | null;
  itemDate: string | null;
  source: ItemSource;
  addedByName: string | null;
};

export type GrowthMeasurement = { id: string; measuredOn: string; heightCm: number | null; weightKg: number | null; recordedByName: string | null };

export type GrowthPoint = { date: string; ageMonths: number; heightCm: number | null; weightKg: number | null };
export type GrowthSeries = {
  points: GrowthPoint[];
  minHeight: number | null;
  maxHeight: number | null;
  minWeight: number | null;
  maxWeight: number | null;
};

export type HealthConsent = { firstAid: boolean; hospital: boolean; byName: string | null; at: string | null };
export type EmergencyContact = { guardianId: string; name: string; phone: string | null; phoneMasked: string; email: string | null } | null;

export type AllergyBadge = { studentId: string; severity: AllergySeverity; label: string };

export type MedicineGiven = { medicineId: string; name: string; unit: string; quantity: number };

export type InfirmaryVisit = {
  id: string;
  personKind: PersonKind;
  studentId: string | null;
  staffUserId: string | null;
  personName: string;
  className: string | null;
  sectionId: string | null;
  timeIn: string;
  timeOut: string | null;
  complaint: string;
  temperatureC: number | null;
  treatment: string | null;
  outcome: VisitOutcome | null;
  parentInformed: boolean;
  medicines: MedicineGiven[];
  allergies: AllergyBadge[];
  recordedByName: string | null;
};

export type MedicalRecord = {
  studentId: string;
  studentName: string;
  admissionNo: string | null;
  className: string | null;
  dateOfBirth: string | null;
  exists: boolean;
  bloodGroup: string | null;
  doctorName: string | null;
  doctorPhone: string | null;
  insuranceProvider: string | null;
  /** last four characters only; the full number is never sent */
  insurancePolicyNoMasked: string | null;
  notes: string | null;
  consent: HealthConsent;
  emergencyContact: EmergencyContact;
  allergies: HealthItem[];
  conditions: HealthItem[];
  medications: HealthItem[];
  vaccinations: HealthItem[];
  growth: GrowthMeasurement[];
  chart: GrowthSeries;
  visits: InfirmaryVisit[];
  updatedAt: string | null;
};

export type MedicalRecordRequest = {
  bloodGroup: string | null;
  doctorName: string | null;
  doctorPhone: string | null;
  insuranceProvider: string | null;
  insurancePolicyNo: string | null;
  notes: string | null;
};
export type ConsentRequest = { firstAid: boolean; hospital: boolean };
export type HealthItemRequest = { kind: ItemKind; name: string; detail: string | null; severity: AllergySeverity | null; itemDate: string | null };
export type GrowthRequest = { measuredOn: string; heightCm: number | null; weightKg: number | null };

/* ------------------------------------------------------------------ infirmary */

export type Medicine = {
  id: string;
  name: string;
  unit: string;
  quantity: number;
  reorderLevel: number;
  expiresOn: string | null;
  low: boolean;
  expired: boolean;
};
export type MedicineRequest = { name: string; unit: string; quantity: number; reorderLevel: number | null; expiresOn: string | null };

export type InfirmaryDay = { date: string; visits: InfirmaryVisit[]; open: number; medicinesLow: number };

export type VisitRequest = {
  personKind: PersonKind;
  studentId: string | null;
  staffUserId: string | null;
  timeIn: string | null;
  complaint: string;
  temperatureC: number | null;
  treatment: string | null;
  medicines: { medicineId: string; quantity: number }[];
};
export type CloseVisitRequest = { outcome: VisitOutcome; timeOut: string | null; treatment: string | null; parentInformed: boolean | null };

/* ------------------------------------------------------------------ safety */

export type FireDrill = {
  id: string;
  drillDate: string;
  kind: DrillKind;
  block: string;
  timeToClearSeconds: number;
  attendance: number;
  issues: string | null;
  nextDue: string | null;
  due: Due;
  conductedByName: string | null;
};
export type DrillRequest = { drillDate: string; kind: DrillKind; block: string; timeToClearSeconds: number; attendance: number; issues: string | null; nextDue: string | null };

export type SafetyAudit = {
  id: string;
  area: AuditArea;
  auditDate: string;
  finding: string;
  severity: AuditSeverity;
  status: AuditStatus;
  dueOn: string | null;
  overdue: boolean;
  closedOn: string | null;
  closureNote: string | null;
  auditedByName: string | null;
};
export type AuditRequest = { area: AuditArea; auditDate: string; finding: string; severity: AuditSeverity; dueOn: string | null };
export type CloseAuditRequest = { closedOn: string | null; closureNote: string | null };

export type Incident = {
  id: string;
  incidentAt: string;
  kind: IncidentKind;
  severity: IncidentSeverity;
  studentId: string | null;
  personName: string | null;
  className: string | null;
  place: string | null;
  description: string;
  actions: string | null;
  parentInformed: boolean;
  reportedByName: string | null;
};
export type IncidentRequest = {
  incidentAt: string | null;
  kind: IncidentKind;
  severity: IncidentSeverity;
  studentId: string | null;
  personName: string | null;
  place: string | null;
  description: string;
  actions: string | null;
  parentInformed: boolean;
};

export type FirstAidKit = {
  id: string;
  location: string;
  checkedOn: string | null;
  complete: boolean;
  earliestExpiry: string | null;
  missingItems: string | null;
  checkedByName: string | null;
  expiry: Due;
  checkDue: boolean;
};
export type KitCheckRequest = { checkedOn: string | null; complete: boolean; earliestExpiry: string | null; missingItems: string | null };

export type SafetyOverview = { today: string; drills: FireDrill[]; audits: SafetyAudit[]; incidents: Incident[]; kits: FirstAidKit[] };

/* ------------------------------------------------------------------ POCSO */

export type CommitteeMember = { id: string; userId: string; name: string; designation: string | null; role: CommitteeRole; appointedOn: string | null };
export type CommitteeMeeting = { id: string; meetingDate: string; minutes: string; recordedByName: string | null };
export type PocsoCommittee = {
  formedOn: string | null;
  members: CommitteeMember[];
  meetings: CommitteeMeeting[];
  /** the signed-in person is on the committee (or holds pocso.committee) */
  canReadComplaints: boolean;
  canManage: boolean;
};
export type MemberRequest = { userId: string; role: CommitteeRole; appointedOn: string | null };
export type MeetingRequest = { meetingDate: string; minutes: string };

export type PocsoComplaint = {
  id: string;
  complaintDate: string;
  category: ComplaintCategory;
  description: string;
  status: ComplaintStatus;
  actions: string | null;
  /** who complained, in the detail view only */
  complainantNote: string | null;
  recordedByName: string | null;
  updatedAt: string;
};
export type ComplaintRequest = {
  complaintDate: string;
  category: ComplaintCategory;
  description: string;
  status: ComplaintStatus | null;
  actions: string | null;
  complainantNote: string | null;
};

/* ------------------------------------------------------------------ staff checks */

export type StaffTraining = {
  id: string;
  userId: string;
  name: string;
  kind: TrainingKind;
  trainedOn: string;
  expiresOn: string | null;
  provider: string | null;
  needsRenewal: boolean;
};
export type StaffVerification = {
  userId: string;
  name: string;
  designation: string | null;
  active: boolean;
  status: VerificationStatus;
  requestedOn: string | null;
  clearedOn: string | null;
  referenceNoMasked: string | null;
  expiresOn: string | null;
  note: string | null;
  updatedByName: string | null;
  due: Due;
  trainings: StaffTraining[];
  pocsoTrainingDue: boolean;
  firstAidTrainingDue: boolean;
};
export type StaffChecks = { asOf: string; rows: StaffVerification[]; verificationsDue: number; verificationsMissing: number; trainingsDue: number };
export type VerificationRequest = {
  status: VerificationStatus;
  requestedOn: string | null;
  clearedOn: string | null;
  referenceNo: string | null;
  expiresOn: string | null;
  note: string | null;
};
export type TrainingRequest = { userId: string; kind: TrainingKind; trainedOn: string; expiresOn: string | null; provider: string | null };

/* ------------------------------------------------------------------ reports and the dashboard */

export type HealthNamedCount = { name: string; count: number };
export type InfirmaryMonth = { month: string; visits: number; students: number; staff: number; sentHome: number; hospital: number };
export type InfirmaryReport = { from: string; to: string; total: number; byMonth: InfirmaryMonth[]; byOutcome: HealthNamedCount[]; byComplaint: HealthNamedCount[] };
export type ClassAllergies = { sectionId: string; label: string; students: number; withAllergies: number; severe: number };
export type AllergiesReport = { asOf: string; students: number; withAllergies: number; severe: number; byClass: ClassAllergies[] };
export type DrillsReport = { asOf: string; nextDue: string | null; due: Due; rows: FireDrill[] };
export type VerificationsReport = { asOf: string; due: number; missing: number; rows: StaffVerification[] };

/** The dashboard's health card: each count is null when the person lacks the permission behind it. */
export type HealthCard = {
  inInfirmary: number | null;
  visitsToday: number | null;
  verificationsDue: number | null;
  verificationsMissing: number | null;
  trainingsDue: number | null;
  nextDrillDue: string | null;
  drillDue: Due | null;
  openFindings: number | null;
};
