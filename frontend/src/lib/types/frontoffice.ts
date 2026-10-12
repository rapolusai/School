/** Types mirroring docs/api/phase-2-frontoffice.md. Plain dates are "YYYY-MM-DD"; instants are ISO-8601. */

export const VISITOR_PURPOSES = ["MEETING", "ADMISSION", "FEES", "DELIVERY", "VENDOR", "INTERVIEW", "OFFICIAL", "OTHER"] as const;
export type VisitorPurpose = (typeof VISITOR_PURPOSES)[number];

export const ID_TYPES = ["NONE", "AADHAAR", "VOTER_ID", "DRIVING_LICENCE", "PAN", "PASSPORT", "COMPANY_ID", "OTHER"] as const;
export type IdType = (typeof ID_TYPES)[number];

export type VisitorStatus = "EXPECTED" | "IN" | "OUT";

export type OfficeStaffRef = { id: string; name: string; designation: string | null };
export type StudentRef = { id: string; fullName: string; admissionNo: string; className: string | null };
export type BlockMatch = { id: string; reason: string };

export type VisitorView = {
  id: string;
  name: string;
  phoneMasked: string;
  /** The full number, on the detail view only. */
  phone: string | null;
  purpose: VisitorPurpose;
  purposeNote: string | null;
  toMeetUserId: string | null;
  toMeetName: string | null;
  peopleCount: number;
  idType: IdType;
  badgeNo: string | null;
  photoFileId: string | null;
  status: VisitorStatus;
  expectedOn: string | null;
  checkedInAt: string | null;
  checkedOutAt: string | null;
  addedByName: string | null;
  checkedInByName: string | null;
  checkedOutByName: string | null;
  blocked: BlockMatch | null;
};

export type VisitorDay = { date: string; visitors: VisitorView[]; inside: number; expected: VisitorView[] };

export type VisitorRequest = {
  name: string;
  phone: string | null;
  purpose: VisitorPurpose;
  purposeNote: string | null;
  toMeetUserId: string | null;
  toMeetName: string | null;
  peopleCount: number;
  idType: IdType;
  badgeNo: string | null;
  expectedOn: string | null;
  override?: boolean;
};

export type CheckInRequest = { badgeNo?: string | null; override?: boolean };

export type BlockedView = {
  id: string;
  name: string;
  phoneMasked: string;
  reason: string;
  addedByName: string | null;
  createdAt: string;
};

export type BlockRequest = { name: string; phone: string | null; reason: string };

/* ------------------------------------------------------------------ gate passes */

export type PassSource = "PARENT_APP" | "OFFICE";
export type CollectorKind = "SELF" | "OTHER";
export const PASS_STATUSES = ["REQUESTED", "APPROVED", "RELEASED", "REJECTED", "CANCELLED", "EXPIRED", "LOCKED"] as const;
export type PassStatus = (typeof PASS_STATUSES)[number];

export type GatePassView = {
  id: string;
  studentId: string;
  studentName: string;
  admissionNo: string | null;
  className: string | null;
  source: PassSource;
  requestedByName: string | null;
  pickupAt: string;
  reason: string;
  collectorKind: CollectorKind;
  collectorName: string | null;
  collectorPhoneMasked: string;
  status: PassStatus;
  otpExpiresAt: string | null;
  attemptsLeft: number;
  guardianName: string | null;
  guardianPhoneMasked: string;
  approvedByName: string | null;
  approvedAt: string | null;
  decisionNote: string | null;
  releasedByName: string | null;
  releasedAt: string | null;
  collectedByName: string | null;
  createdAt: string;
  canCancel: boolean;
  canDecide: boolean;
  canRelease: boolean;
};

export type PickupRequest = {
  pickupAt: string;
  reason: string;
  collectorKind: CollectorKind;
  collectorName: string | null;
  collectorPhone: string | null;
};

export type OfficePassRequest = PickupRequest & { studentId: string };
export type DecisionRequest = { note: string | null };
export type ReleaseRequest = { otp: string; collectedByName: string };

export type PickupPersonView = {
  id: string;
  studentId: string;
  studentName: string | null;
  name: string;
  relation: string;
  phoneMasked: string;
  photoFileId: string | null;
  addedByName: string | null;
  createdAt: string;
};

export type PickupPersonRequest = { name: string; relation: string; phone: string };

export type FamilyPickup = {
  studentId: string;
  studentName: string;
  className: string | null;
  canRequest: boolean;
  passes: GatePassView[];
  persons: PickupPersonView[];
};

export type GateScreen = { date: string; passes: GatePassView[]; inside: VisitorView[]; expected: VisitorView[] };

export type StudentPickupPersons = {
  studentId: string;
  studentName: string;
  className: string | null;
  persons: PickupPersonView[];
};

/* ------------------------------------------------------------------ calls and enquiries */

export type CallDirection = "INCOMING" | "OUTGOING";
export type CallStatus = "OPEN" | "DONE";
export const ENQUIRY_SOURCES = ["WALK_IN", "PHONE"] as const;
export type EnquirySource = (typeof ENQUIRY_SOURCES)[number];
export const ENQUIRY_TOPICS = ["ADMISSION", "FEES", "GENERAL"] as const;
export type EnquiryTopic = (typeof ENQUIRY_TOPICS)[number];
export type EnquiryStatus = "OPEN" | "FOLLOW_UP" | "CLOSED" | "CONVERTED";

export type CallView = {
  id: string;
  direction: CallDirection;
  callerName: string;
  phoneMasked: string;
  about: string;
  outcome: string | null;
  followUpOn: string | null;
  assignedToUserId: string | null;
  assignedToName: string | null;
  status: CallStatus;
  loggedByName: string | null;
  calledAt: string;
};

export type CallRequest = {
  direction: CallDirection;
  callerName: string;
  phone: string | null;
  about: string;
  outcome: string | null;
  followUpOn: string | null;
  assignedToUserId: string | null;
  calledAt: string | null;
};

export type CloseRequest = { outcome: string | null };

export type EnquiryView = {
  id: string;
  source: EnquirySource;
  topic: EnquiryTopic;
  name: string;
  phoneMasked: string;
  childName: string | null;
  className: string | null;
  details: string;
  status: EnquiryStatus;
  outcome: string | null;
  followUpOn: string | null;
  assignedToUserId: string | null;
  assignedToName: string | null;
  applicationId: string | null;
  loggedByName: string | null;
  enquiredAt: string;
};

export type EnquiryRequest = {
  source: EnquirySource;
  topic: EnquiryTopic;
  name: string;
  phone: string | null;
  childName: string | null;
  className: string | null;
  details: string;
  followUpOn: string | null;
  assignedToUserId: string | null;
  enquiredAt: string | null;
};

export type ConvertRequest = {
  classId: string;
  academicYearId: string;
  dateOfBirth: string;
  firstName: string | null;
  lastName: string | null;
  relation: "FATHER" | "MOTHER" | "GUARDIAN" | null;
  phone: string | null;
};

export type FollowUpKind = "CALL" | "ENQUIRY";

export type OfficeFollowUp = {
  kind: FollowUpKind;
  id: string;
  name: string;
  about: string;
  followUpOn: string;
  daysOverdue: number;
  assignedToName: string | null;
  phoneMasked: string;
};

export type FollowUpQueue = { today: string; items: OfficeFollowUp[]; dueToday: number; overdue: number };

/* ------------------------------------------------------------------ courier */

export type CourierDirection = "INWARD" | "OUTWARD";

export type CourierView = {
  id: string;
  direction: CourierDirection;
  item: string;
  sender: string;
  receiver: string;
  itemDate: string;
  carrier: string | null;
  trackingNo: string | null;
  receivedBy: string | null;
  note: string | null;
  loggedByName: string | null;
  createdAt: string;
};

export type CourierRequest = {
  direction: CourierDirection;
  item: string;
  sender: string;
  receiver: string;
  itemDate: string;
  carrier: string | null;
  trackingNo: string | null;
  receivedBy: string | null;
  note: string | null;
};

/* ------------------------------------------------------------------ reports and the dashboard */

export type DayCount = { date: string; count: number; people: number };
export type NamedCount = { name: string; count: number };

export type VisitorsReport = {
  from: string;
  to: string;
  total: number;
  people: number;
  byDay: DayCount[];
  byPurpose: NamedCount[];
  rows: VisitorView[];
};

export type PassDay = {
  date: string;
  requested: number;
  approved: number;
  released: number;
  rejected: number;
  cancelled: number;
  expired: number;
  locked: number;
};

export type PassesReport = { from: string; to: string; total: number; byDay: PassDay[]; rows: GatePassView[] };

export type EnquiriesReport = {
  from: string;
  to: string;
  total: number;
  bySource: NamedCount[];
  byTopic: NamedCount[];
  byOutcome: NamedCount[];
  rows: EnquiryView[];
};

export type FollowUpsReport = { asOf: string; overdue: number; rows: OfficeFollowUp[] };

/** A class teacher's in-app notice: a pupil of theirs leaves early today. */
export type ClassPickup = {
  passId: string;
  studentName: string;
  className: string | null;
  pickupAt: string;
  collector: string;
  status: PassStatus;
};

/** The front office card of GET /api/dashboard; each count is null for someone without that permission. */
export type FrontOfficeCard = {
  passesToApprove: number | null;
  followUpsDue: number | null;
  followUpsOverdue: number | null;
  passesAtGate: number | null;
  visitorsInside: number | null;
  visitorsToday: number | null;
  classPickups: ClassPickup[];
};
