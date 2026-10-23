/** Types mirroring docs/api/phase-3-hostel.md. Plain dates are "YYYY-MM-DD"; instants are ISO-8601; money is paise. */

export const HOSTEL_TYPES = ["BOYS", "GIRLS", "STAFF"] as const;
export type HostelType = (typeof HOSTEL_TYPES)[number];

export const ROOM_TYPES = ["SINGLE", "DOUBLE", "TRIPLE", "DORMITORY"] as const;
export type RoomType = (typeof ROOM_TYPES)[number];

export type AllotmentStatus = "ACTIVE" | "VACATED";

export const MEALS = ["BREAKFAST", "LUNCH", "SNACKS", "DINNER"] as const;
export type Meal = (typeof MEALS)[number];

export const PASS_KINDS = ["OUTING", "HOME_LEAVE"] as const;
export type PassKind = (typeof PASS_KINDS)[number];
export type HostelPassSource = "PARENT_APP" | "WARDEN";
export type HostelPassStatus = "REQUESTED" | "APPROVED" | "OUT" | "RETURNED" | "REJECTED" | "CANCELLED";

export const ROLL_CALL_STATUSES = ["PRESENT", "ON_LEAVE", "ABSENT"] as const;
export type RollCallStatus = (typeof ROLL_CALL_STATUSES)[number];

export type AlertKind = "ABSENT_AT_ROLL_CALL" | "LATE_RETURN";

export const HOSTEL_COMPLAINT_CATEGORIES = ["PLUMBING", "ELECTRICAL", "FURNITURE", "CLEANING", "OTHER"] as const;
export type HostelComplaintCategory = (typeof HOSTEL_COMPLAINT_CATEGORIES)[number];
export const HOSTEL_COMPLAINT_STATUSES = ["OPEN", "IN_PROGRESS", "RESOLVED"] as const;
export type HostelComplaintStatus = (typeof HOSTEL_COMPLAINT_STATUSES)[number];

export type HostelStaffRef = { id: string; name: string; designation: string | null };
export type HostelStudentRef = { id: string; fullName: string; admissionNo: string; className: string | null };

export type HostelView = {
  id: string;
  name: string;
  type: HostelType;
  wardenUserId: string | null;
  wardenName: string | null;
  address: string | null;
  active: boolean;
  blocks: number;
  rooms: number;
  beds: number;
  occupied: number;
  occupancyPercent: number;
  canManage: boolean;
  canWarden: boolean;
};

export type HostelRequest = {
  name: string;
  type: HostelType;
  wardenUserId: string | null;
  address: string | null;
  active?: boolean;
};

export type BlockView = { id: string; hostelId: string; name: string; floors: number; rooms: number };
export type HostelBlockRequest = { name: string; floors: number };

export type RoomView = {
  id: string;
  hostelId: string;
  blockId: string;
  blockName: string | null;
  floor: number;
  number: string;
  capacity: number;
  type: RoomType;
  monthlyFeePaise: number;
  active: boolean;
  beds: number;
  occupied: number;
};

export type RoomRequest = {
  floor: number;
  number: string;
  capacity: number;
  type: RoomType;
  monthlyFeePaise: number;
  active?: boolean;
};

export type BedsRequest = { labels: string[] };

export type BedView = {
  id: string;
  label: string;
  allotmentId: string | null;
  studentId: string | null;
  studentName: string | null;
  className: string | null;
  since: string | null;
};

export type RoomCard = {
  id: string;
  blockId: string;
  blockName: string | null;
  floor: number;
  number: string;
  capacity: number;
  type: RoomType;
  monthlyFeePaise: number;
  active: boolean;
  beds: BedView[];
  freeBeds: number;
  openComplaints: number;
};

export type OccupancyBoard = {
  hostelId: string;
  hostelName: string;
  type: HostelType;
  beds: number;
  occupied: number;
  free: number;
  occupancyPercent: number;
  blocks: BlockView[];
  rooms: RoomCard[];
};

export type HostelDetail = { hostel: HostelView; blocks: BlockView[]; rooms: RoomView[] };

export type AllotmentView = {
  id: string;
  studentId: string;
  studentName: string | null;
  admissionNo: string | null;
  className: string | null;
  hostelId: string;
  hostelName: string | null;
  roomId: string;
  roomNumber: string | null;
  blockName: string | null;
  bedId: string;
  bedLabel: string | null;
  fromDate: string;
  toDate: string | null;
  guardianConsent: boolean;
  status: AllotmentStatus;
  vacateReason: string | null;
  allottedByName: string | null;
  vacatedByName: string | null;
  monthlyFeePaise: number;
};

export type AllotRequest = { studentId: string; bedId: string; fromDate: string; guardianConsent: boolean };
export type VacateRequest = { reason: string; toDate: string | null };
export type MoveRequest = { bedId: string; fromDate: string | null };

export type BoarderView = {
  studentId: string;
  studentName: string | null;
  admissionNo: string | null;
  className: string | null;
  allotmentId: string;
  hostelId: string;
  hostelName: string | null;
  blockName: string | null;
  roomNumber: string | null;
  bedLabel: string | null;
  since: string;
  guardianConsent: boolean;
  guardianName: string | null;
  guardianPhoneMasked: string | null;
};

export type StudentBoarding = { studentId: string; boarder: boolean; current: AllotmentView | null; history: AllotmentView[] };

/* ------------------------------------------------------------------ mess */

export type MenuSlot = { dayOfWeek: number; meal: Meal; dish: string; veg: boolean };
export type WeeklyMenu = { hostelId: string; hostelName: string; items: MenuSlot[]; canEdit: boolean };
export type MenuRequest = { items: { dayOfWeek: number; meal: Meal; dish: string; veg: boolean }[] };

export type MealCountView = { meal: Meal; headCount: number; markedByName: string | null };
export type MessDay = { hostelId: string; date: string; boarders: number; counts: MealCountView[] };
export type MessAttendanceRequest = { counts: { meal: Meal; headCount: number }[] };

/* ------------------------------------------------------------------ passes */

export type HostelPassView = {
  id: string;
  studentId: string;
  studentName: string | null;
  admissionNo: string | null;
  className: string | null;
  hostelId: string;
  hostelName: string | null;
  kind: PassKind;
  source: HostelPassSource;
  requestedByName: string | null;
  fromDate: string;
  toDate: string;
  returnBy: string;
  reason: string;
  status: HostelPassStatus;
  decidedByName: string | null;
  decidedAt: string | null;
  decisionNote: string | null;
  outAt: string | null;
  outByName: string | null;
  inAt: string | null;
  inByName: string | null;
  late: boolean;
  createdAt: string;
  canCancel: boolean;
  canDecide: boolean;
  canMarkOut: boolean;
  canMarkIn: boolean;
};

export type HostelPassRequest = { kind: PassKind; fromDate: string; toDate: string | null; reason: string };
export type WardenPassRequest = HostelPassRequest & { studentId: string };
export type HostelDecisionRequest = { note: string | null };

/* ------------------------------------------------------------------ roll call */

export type RollCallRow = {
  studentId: string;
  studentName: string | null;
  admissionNo: string | null;
  className: string | null;
  roomNumber: string | null;
  bedLabel: string | null;
  status: RollCallStatus | null;
  note: string | null;
  passId: string | null;
  suggestedOnLeave: boolean;
};

export type RollCallSheet = {
  hostelId: string;
  hostelName: string;
  night: string;
  taken: boolean;
  takenByName: string | null;
  takenAt: string | null;
  present: number;
  onLeave: number;
  absent: number;
  rows: RollCallRow[];
  canMark: boolean;
};

export type RollCallRequest = { entries: { studentId: string; status: RollCallStatus; note: string | null }[] };

export type RollCallNight = {
  id: string;
  night: string;
  takenByName: string | null;
  takenAt: string;
  present: number;
  onLeave: number;
  absent: number;
  absentees: string[];
};
export type RollCallRegister = { hostelId: string; hostelName: string; from: string; to: string; nights: RollCallNight[] };

export type BoarderNights = { studentId: string; studentName: string | null; className: string | null; present: number; onLeave: number; absent: number };
export type RollCallSummary = { hostelId: string; hostelName: string; month: string; nightsTaken: number; boarders: BoarderNights[] };

export type HostelAlertView = {
  id: string;
  hostelId: string;
  hostelName: string | null;
  studentId: string;
  studentName: string | null;
  className: string | null;
  kind: AlertKind;
  day: string;
  message: string;
  resolved: boolean;
  resolvedByName: string | null;
  resolvedAt: string | null;
  resolutionNote: string | null;
  createdAt: string;
};
export type ResolveRequest = { note: string | null };

/* ------------------------------------------------------------------ complaints */

export type ComplaintView = {
  id: string;
  hostelId: string;
  hostelName: string | null;
  roomId: string;
  roomNumber: string | null;
  blockName: string | null;
  category: HostelComplaintCategory;
  description: string;
  status: HostelComplaintStatus;
  raisedByName: string | null;
  resolutionNote: string | null;
  resolvedAt: string | null;
  createdAt: string;
};
export type HostelComplaintRequest = { roomId: string; category: HostelComplaintCategory; description: string };
export type HostelComplaintStatusRequest = { status: HostelComplaintStatus; note: string | null };

/* ------------------------------------------------------------------ the family app */

export type FamilyHostel = {
  studentId: string;
  studentName: string;
  className: string | null;
  boarder: boolean;
  hostelName: string | null;
  blockName: string | null;
  roomNumber: string | null;
  bedLabel: string | null;
  wardenName: string | null;
  nextPass: HostelPassView | null;
  lastRollCallNight: string | null;
  lastRollCallStatus: RollCallStatus | null;
  canRequest: boolean;
  passes: HostelPassView[];
};

/* ------------------------------------------------------------------ reports */

export type HostelOccupancy = {
  hostelId: string;
  hostelName: string;
  type: HostelType;
  wardenName: string | null;
  rooms: number;
  beds: number;
  occupied: number;
  free: number;
  occupancyPercent: number;
};
export type HostelOccupancyReport = {
  asOf: string;
  beds: number;
  occupied: number;
  free: number;
  occupancyPercent: number;
  hostels: HostelOccupancy[];
  rows: BoarderView[];
};

export type HostelRollCallReportRow = { night: string; hostelId: string; hostelName: string | null; present: number; onLeave: number; absent: number; absentees: string[] };
export type HostelRollCallReport = { from: string; to: string; nights: number; present: number; onLeave: number; absent: number; rows: HostelRollCallReportRow[] };

export type HostelNamedCount = { name: string; count: number };
export type HostelOutingsReport = { month: string; total: number; outings: number; homeLeaves: number; late: number; byStatus: HostelNamedCount[]; rows: HostelPassView[] };

export type BoarderDues = {
  studentId: string;
  studentName: string | null;
  admissionNo: string | null;
  className: string | null;
  hostelName: string | null;
  roomNumber: string | null;
  netPaise: number;
  paidPaise: number;
  balancePaise: number;
  overduePaise: number;
};
export type BoarderDuesReport = { asOf: string; boarders: number; balancePaise: number; overduePaise: number; rows: BoarderDues[] };
