/** Types mirroring docs/api/phase-1-staff.md (staff, leave, staff attendance). Keep in sync with the contract. */

import type { PlainDate } from "./school";

export const EMPLOYMENT_TYPES = ["PERMANENT", "CONTRACT", "PART_TIME", "PROBATION"] as const;
export type EmploymentType = (typeof EMPLOYMENT_TYPES)[number];

export type StaffMemberStatus = "ACTIVE" | "LEFT";

/** A staff member's day. ON_LEAVE comes only from approved leave. */
export const STAFF_DAY_STATUSES = ["PRESENT", "ABSENT", "HALF_DAY", "ON_LEAVE"] as const;
export type StaffDayStatus = (typeof STAFF_DAY_STATUSES)[number];
/** What the daily sheet may set. */
export const STAFF_MARKABLE_STATUSES = ["PRESENT", "HALF_DAY", "ABSENT"] as const;
export type StaffMarkableStatus = (typeof STAFF_MARKABLE_STATUSES)[number];

/** Report letters: P present, A absent, H half day, L on leave. */
export type StaffMark = "P" | "A" | "H" | "L";
export const STAFF_MARK_OF: Record<StaffDayStatus, StaffMark> = {
  PRESENT: "P",
  ABSENT: "A",
  HALF_DAY: "H",
  ON_LEAVE: "L",
};

export type StaffAttendanceSource = "SELF" | "ADMIN" | "LEAVE";

export type StaffNamedRef = { id: string; name: string };

/* ------------------------------------------------------------------ staff */

export type StaffRow = {
  userId: string;
  name: string;
  email: string;
  roles: string[];
  employeeCode: string | null;
  designation: string | null;
  department: StaffNamedRef | null;
  employmentType: EmploymentType | null;
  dateOfJoining: PlainDate | null;
  dateOfLeaving: PlainDate | null;
  /** Masked, e.g. "98480•••02" */
  mobile: string | null;
  status: StaffMemberStatus;
  profileComplete: boolean;
  today: StaffDayStatus | null;
};

export type StaffPage = {
  items: StaffRow[];
  page: number;
  size: number;
  total: number;
  /** Active staff without a profile, whatever the filters. */
  incompleteProfiles: number;
};

export type StaffDirectoryQuery = {
  departmentId?: string;
  designation?: string;
  role?: string;
  status?: StaffMemberStatus;
  incomplete?: boolean;
  q?: string;
  page?: number;
  size?: number;
};

export type StaffProfile = {
  employeeCode: string;
  designation: string;
  department: StaffNamedRef | null;
  employmentType: EmploymentType;
  dateOfJoining: PlainDate;
  dateOfLeaving: PlainDate | null;
  leavingReason: string | null;
  /** Ten digits, unmasked (profile page only). */
  mobile: string;
  qualifications: string | null;
  emergencyContactName: string | null;
  emergencyContactMobile: string | null;
};

export type LeaveRouting = "DEPARTMENT_HEAD" | "SCHOOL";

export type StaffLeaveApprover = { routing: LeaveRouting; departmentHead: StaffNamedRef | null };

export type StaffDetail = {
  userId: string;
  name: string;
  email: string;
  roles: string[];
  status: StaffMemberStatus;
  accountActive: boolean;
  lastLoginAt: string | null;
  profileComplete: boolean;
  profile: StaffProfile | null;
  leaveApprover: StaffLeaveApprover;
};

export type StaffProfileRequest = {
  employeeCode: string;
  designation: string;
  departmentId: string | null;
  employmentType: EmploymentType;
  dateOfJoining: PlainDate;
  mobile: string;
  qualifications?: string | null;
  emergencyContactName?: string | null;
  emergencyContactMobile?: string | null;
};

export type CreateStaffRequest = StaffProfileRequest & {
  name: string;
  email: string;
  password: string;
  roles: string[];
};

export type StaffLeavingRequest = { leftOn: PlainDate; reason: string };

export type StaffRoleOption = { code: string; name: string };

export type StaffDepartment = { id: string; name: string; head: StaffNamedRef | null; staffCount: number };

export type StaffDepartmentRequest = { name: string; headUserId: string | null };

/* ------------------------------------------------------------------ leave */

export type LeaveType = {
  id: string;
  name: string;
  code: string;
  yearlyQuota: number;
  carryForwardCap: number;
  halfDayAllowed: boolean;
  lossOfPay: boolean;
  active: boolean;
  inUse: boolean;
};

export type LeaveTypeRequest = {
  name: string;
  code: string;
  yearlyQuota: number;
  carryForwardCap: number;
  halfDayAllowed: boolean;
  lossOfPay: boolean;
  active?: boolean;
};

export type LeaveBalance = {
  leaveTypeId: string;
  leaveTypeName: string;
  code: string;
  lossOfPay: boolean;
  halfDayAllowed: boolean;
  active: boolean;
  opening: number;
  accrued: number;
  taken: number;
  pending: number;
  /** opening + accrued − taken; null for loss of pay (no limit). */
  available: number | null;
  setByHand: boolean;
};

export const LEAVE_STATUSES = ["PENDING", "APPROVED", "REJECTED", "CANCELLED"] as const;
export type LeaveStatus = (typeof LEAVE_STATUSES)[number];

export type StaffLeaveRequest = {
  id: string;
  userId: string;
  userName: string | null;
  employeeCode: string | null;
  departmentName: string | null;
  leaveTypeId: string;
  leaveTypeName: string | null;
  leaveTypeCode: string | null;
  lossOfPay: boolean;
  academicYearId: string;
  academicYearName: string | null;
  fromDate: PlainDate;
  toDate: PlainDate;
  halfDay: boolean;
  days: number;
  reason: string;
  status: LeaveStatus;
  routing: LeaveRouting;
  approverName: string | null;
  decidedByName: string | null;
  decidedAt: string | null;
  decisionComment: string | null;
  cancelledByName: string | null;
  cancelledAt: string | null;
  cancelComment: string | null;
  createdAt: string;
  canCancel: boolean;
  canDecide: boolean;
  /** The requester's balance of the type, in the approver inbox only. */
  available: number | null;
};

export type LeaveYear = { id: string; name: string; startsOn: PlainDate; endsOn: PlainDate; current: boolean };

export type MyLeave = {
  year: LeaveYear | null;
  years: LeaveYear[];
  approver: StaffLeaveApprover;
  balances: LeaveBalance[];
  requests: StaffLeaveRequest[];
  waitingForMe: number;
};

export type StaffLeave = {
  userId: string;
  year: LeaveYear | null;
  years: LeaveYear[];
  balances: LeaveBalance[];
  requests: StaffLeaveRequest[];
};

export type LeavePreviewRequest = { leaveTypeId: string; fromDate: PlainDate; toDate: PlainDate; halfDay: boolean };

export type LeaveApplicationRequest = LeavePreviewRequest & { reason: string };

export type LeavePreview = {
  year: LeaveYear;
  fromDate: PlainDate;
  toDate: PlainDate;
  halfDay: boolean;
  workingDays: PlainDate[];
  nonWorkingDays: number;
  days: number;
  balance: LeaveBalance;
  availableAfter: number | null;
  enough: boolean;
  overlaps: boolean;
};

export type LeaveBalanceRequest = {
  userId: string;
  leaveTypeId: string;
  academicYearId?: string | null;
  opening: number;
  accrued: number;
};

/* ------------------------------------------------------------------ staff attendance */

export type StaffDayCounts = { present: number; halfDay: number; absent: number; onLeave: number };

export type MyStaffDay = {
  date: PlainDate;
  workingDay: boolean;
  status: StaffDayStatus | null;
  checkInAt: string | null;
  checkOutAt: string | null;
  checkInNote: string | null;
  checkOutNote: string | null;
  onLeave: boolean;
  canCheckIn: boolean;
  canCheckOut: boolean;
};

export type StaffDayRow = {
  userId: string;
  name: string;
  employeeCode: string | null;
  designation: string | null;
  departmentName: string | null;
  onRoll: boolean;
  status: StaffDayStatus | null;
  source: StaffAttendanceSource | null;
  /** "HH:mm" in India time */
  checkIn: string | null;
  checkOut: string | null;
  checkInAt: string | null;
  checkOutAt: string | null;
  checkInNote: string | null;
  checkOutNote: string | null;
  /** The day comes from approved leave: only cancelling the leave changes it. */
  onLeaveRequest: boolean;
  markedByName: string | null;
  updatedByName: string | null;
  editedAt: string | null;
};

export type StaffDaySheet = {
  date: PlainDate;
  today: PlainDate;
  workingDay: boolean;
  canEdit: boolean;
  counts: StaffDayCounts;
  notMarked: number;
  rows: StaffDayRow[];
};

export type StaffDayEntry = {
  userId: string;
  status: StaffMarkableStatus;
  checkIn?: string | null;
  checkOut?: string | null;
};

export type SaveStaffDayRequest = { entries: StaffDayEntry[] };

export type SaveStaffDayResult = { sheet: StaffDaySheet; marked: number; corrected: number };

export type StaffMonthLine = {
  userId: string;
  name: string;
  employeeCode: string | null;
  departmentName: string | null;
  active: boolean;
  marks: (StaffMark | null)[];
  counts: StaffDayCounts;
  notMarked: number;
  daysWorked: number;
};

export type StaffMonthReport = {
  month: string;
  departmentId: string | null;
  workingDays: number;
  days: { date: PlainDate; workingDay: boolean; counts: StaffDayCounts }[];
  staff: StaffMonthLine[];
  totals: StaffDayCounts;
};

export type StaffTodaySummary = {
  date: PlainDate;
  workingDay: boolean;
  activeStaff: number;
  present: number;
  halfDay: number;
  onLeave: number;
  absent: number;
  notMarked: number;
  checkedIn: number;
};

export type StaffPersonMonth = {
  userId: string;
  month: string;
  counts: StaffDayCounts;
  daysWorked: number;
  days: {
    date: PlainDate;
    workingDay: boolean;
    status: StaffDayStatus | null;
    source: StaffAttendanceSource | null;
    checkInAt: string | null;
    checkOutAt: string | null;
  }[];
};
