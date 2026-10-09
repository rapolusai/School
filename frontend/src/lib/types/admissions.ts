/** Types mirroring docs/api/phase-1-admissions.md. Keep in sync with the contract. */

import type { Gender, GuardianRelation, PlainDate } from "./school";

/** The pipeline, in board order. */
export const PIPELINE_STAGES = ["ENQUIRY", "APPLICATION", "ASSESSMENT", "OFFERED", "ADMITTED"] as const;
export const APPLICATION_STAGES = [...PIPELINE_STAGES, "REJECTED", "WITHDRAWN"] as const;
export type ApplicationStage = (typeof APPLICATION_STAGES)[number];

export const APPLICATION_SOURCES = ["WALK_IN", "WEBSITE", "PHONE", "REFERRAL", "OTHER"] as const;
export type ApplicationSource = (typeof APPLICATION_SOURCES)[number];

export const ASSESSMENT_KINDS = ["TEST", "INTERVIEW"] as const;
export type AssessmentKind = (typeof ASSESSMENT_KINDS)[number];

export const ASSESSMENT_MODES = ["IN_PERSON", "ONLINE"] as const;
export type AssessmentMode = (typeof ASSESSMENT_MODES)[number];

export type SlotStatus = "SCHEDULED" | "DONE" | "CANCELLED";

export const FEE_STATUSES = ["PAID", "WAIVED"] as const;
export type FeeStatus = (typeof FEE_STATUSES)[number];

export const PAYMENT_METHODS = ["CASH", "UPI", "CARD", "BANK_TRANSFER"] as const;
export type PaymentMethod = (typeof PAYMENT_METHODS)[number];

export type TimelineKind =
  | "CREATED"
  | "STAGE_CHANGED"
  | "NOTE"
  | "UPDATED"
  | "FEE_RECORDED"
  | "OFFER_UPDATED"
  | "SLOT_SCHEDULED"
  | "SLOT_RESCHEDULED"
  | "SLOT_OUTCOME"
  | "SLOT_CANCELLED";

export type YearChoice = "CURRENT" | "NEXT";

/** Largest application fee the API accepts: ₹10,00,000 in paise. */
export const MAX_FEE_PAISE = 100_000_000;

export type StaffRef = { id: string; name: string };

/** One application in the list or on a board card. `contactPhone` is masked by the API. */
export type ApplicationRow = {
  id: string;
  childName: string;
  classId: string;
  className: string;
  academicYearId: string;
  academicYearName: string;
  stage: ApplicationStage;
  /** ISO-8601 instant. */
  stageChangedAt: string;
  daysInStage: number;
  followUpOn: PlainDate | null;
  source: ApplicationSource;
  contactName: string | null;
  contactPhone: string | null;
  assignedToName: string | null;
  nextSlotAt: string | null;
  createdAt: string;
  nextStages: ApplicationStage[];
};

export type ApplicationPage = {
  items: ApplicationRow[];
  page: number;
  size: number;
  total: number;
  /** Per stage, ignoring the stage filter. */
  stageCounts: Record<ApplicationStage, number>;
};

export type BoardLane = { stage: ApplicationStage; total: number; cards: ApplicationRow[] };

/** The pipeline lanes; rejected and withdrawn applications are only counted. */
export type AdmissionsBoard = { lanes: BoardLane[]; closed: number };

export type ApplicationGuardian = {
  name: string;
  relation: GuardianRelation;
  /** Ten digits. */
  phone: string;
  email: string | null;
  primary: boolean;
};

export type ApplicationFee = {
  status: FeeStatus;
  amountPaise: number | null;
  method: PaymentMethod | null;
  reference: string | null;
  paidOn: PlainDate;
};

export type ApplicationOffer = { offeredOn: PlainDate; validUntil: PlainDate | null };

export type AssessmentSlot = {
  id: string;
  kind: AssessmentKind;
  scheduledAt: string;
  mode: AssessmentMode;
  location: string | null;
  meetingLink: string | null;
  interviewer: StaffRef | null;
  status: SlotStatus;
  outcomeNotes: string | null;
};

export type TimelineEntry = {
  id: string;
  at: string;
  kind: TimelineKind;
  /** Null for entries from the public enquiry form. */
  actorName: string | null;
  fromStage: ApplicationStage | null;
  toStage: ApplicationStage | null;
  note: string | null;
  details: Record<string, unknown>;
};

export type ApplicationDetail = {
  id: string;
  stage: ApplicationStage;
  stageChangedAt: string;
  daysInStage: number;
  nextStages: ApplicationStage[];
  firstName: string;
  lastName: string | null;
  childName: string;
  dateOfBirth: PlainDate;
  gender: Gender | null;
  previousSchool: string | null;
  classId: string;
  className: string;
  academicYearId: string;
  academicYearName: string;
  source: ApplicationSource;
  assignedTo: StaffRef | null;
  followUpOn: PlainDate | null;
  message: string | null;
  consentVersion: string | null;
  consentAt: string | null;
  guardians: ApplicationGuardian[];
  fee: ApplicationFee | null;
  offer: ApplicationOffer | null;
  studentId: string | null;
  slots: AssessmentSlot[];
  /** Newest first. */
  timeline: TimelineEntry[];
  createdAt: string;
};

export type UpcomingSlot = {
  id: string;
  applicationId: string;
  childName: string;
  className: string;
  kind: AssessmentKind;
  scheduledAt: string;
  mode: AssessmentMode;
  location: string | null;
  meetingLink: string | null;
  interviewer: StaffRef | null;
};

export type AdmissionsSummary = {
  openEnquiries: number;
  /** Applications and tests or interviews in progress. */
  inProgress: number;
  offersPending: number;
  admittedThisYear: number;
  upcomingSlots: number;
};

export type ApplicationQuery = {
  yearId?: string;
  classId?: string;
  stage?: ApplicationStage;
  source?: ApplicationSource;
  q?: string;
  page?: number;
  size?: number;
};

export type ApplicationGuardianInput = {
  name: string;
  relation: GuardianRelation;
  phone: string;
  email?: string | null;
  primary: boolean;
};

export type ApplicationRequest = {
  /** Only read when creating. */
  stage?: "ENQUIRY" | "APPLICATION";
  firstName: string;
  lastName?: string | null;
  dateOfBirth: PlainDate;
  gender?: Gender | null;
  previousSchool?: string | null;
  classId: string;
  academicYearId: string;
  source: ApplicationSource;
  assignedToId?: string | null;
  followUpOn?: PlainDate | null;
  /** Only read when creating. */
  note?: string | null;
  guardians: ApplicationGuardianInput[];
};

export type StageRequest = {
  stage: ApplicationStage;
  note?: string | null;
  offeredOn?: PlainDate | null;
  offerValidUntil?: PlainDate | null;
};

export type FeeRequest = {
  status: FeeStatus;
  amountPaise?: number | null;
  method?: PaymentMethod | null;
  reference?: string | null;
  paidOn: PlainDate;
  note?: string | null;
};

export type SlotRequest = {
  kind: AssessmentKind;
  scheduledAt: string;
  mode: AssessmentMode;
  location?: string | null;
  meetingLink?: string | null;
  interviewerId?: string | null;
};

export type OfferRequest = { offeredOn: PlainDate; validUntil?: PlainDate | null };

export type AdmitRequest = {
  sectionId: string;
  admissionNo: string;
  rollNo?: number | null;
  admissionDate?: PlainDate | null;
  gender?: Gender | null;
};

/** What a school's public enquiry page shows. */
export type PublicSchoolInfo = {
  name: string;
  board: string;
  city: string | null;
  classes: string[];
  years: { code: YearChoice; name: string }[];
  consentVersion: string;
};

export type PublicEnquiryRequest = {
  parentName: string;
  relation: GuardianRelation;
  mobile: string;
  email?: string | null;
  childFirstName: string;
  childLastName?: string | null;
  dateOfBirth: PlainDate;
  className: string;
  academicYear: YearChoice;
  message?: string | null;
  consent: boolean;
  consentVersion: string;
  /** Honeypot: always empty from a real browser. */
  website: string;
};
