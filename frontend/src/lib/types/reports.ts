/** Types mirroring docs/api/phase-1-reports.md (staff dashboard and reports). Keep in sync with the contract. */

import type { ApplicationSource, ApplicationStage } from "./admissions";
import type { AttendanceCounts, AttendanceStatus } from "./attendance";
import type { CollectionTotal } from "./fees";
import type { PlainDate } from "./school";
import type { StaffTodaySummary } from "./staff";

/* ------------------------------------------------------------------ dashboard */

export type DashboardStudents = { onRoll: number; admittedThisMonth: number; admittedThisYear: number };

export type AttendanceDayPoint = {
  date: PlainDate;
  sectionsMarked: number;
  counts: AttendanceCounts;
  /** null when no section was marked that day */
  presentPercent: number | null;
};

export type OwnSectionToday = {
  sectionId: string;
  label: string;
  students: number;
  marked: boolean;
  canMark: boolean;
  counts: AttendanceCounts;
  presentPercent: number | null;
};

export type AttendancePeriod = {
  from: PlainDate;
  to: PlainDate;
  schoolDays: number;
  daysMarked: number;
  counts: AttendanceCounts;
  presentPercent: number | null;
};

/** wholeSchool false: a class teacher's own sections only (ownSections). */
export type DashboardAttendance = {
  date: PlainDate;
  wholeSchool: boolean;
  holiday: string | null;
  sectionCount: number;
  sectionsMarked: number;
  students: number;
  counts: AttendanceCounts;
  presentPercent: number | null;
  /** null before the current academic year is set up */
  month: AttendancePeriod | null;
  /** The last 30 school days, oldest first. */
  trend: AttendanceDayPoint[];
  ownSections: OwnSectionToday[];
};

export type FeeDayTotal = { date: PlainDate; amountPaise: number; receiptCount: number };

export type DashboardFees = {
  today: CollectionTotal;
  thisMonth: CollectionTotal;
  outstandingPaise: number;
  overduePaise: number;
  overdueStudents: number;
  /** Every day of this month up to today. */
  byDay: FeeDayTotal[];
};

export type FunnelStage = {
  stage: ApplicationStage;
  /** got to this stage or a later one */
  reached: number;
  /** in this stage now */
  current: number;
  fromPrevious: number | null;
  fromEnquiry: number | null;
};

export type FollowUp = {
  id: string;
  childName: string;
  classId: string;
  className: string | null;
  stage: ApplicationStage;
  followUpOn: PlainDate;
};

export type FollowUps = { date: PlainDate; dueToday: number; overdue: number; upcoming: number; items: FollowUp[] };

export type DashboardAdmissions = {
  openEnquiries: number;
  inProgress: number;
  offersPending: number;
  admittedThisYear: number;
  upcomingSlots: number;
  funnel: FunnelStage[];
  followUps: FollowUps;
};

export type CircularWaiting = { id: string; title: string; createdByName: string | null; submittedAt: string | null };

export type SubstitutionToday = {
  period: number | null;
  label: string | null;
  startsAt: string | null;
  endsAt: string | null;
  sectionLabel: string | null;
  subjectName: string | null;
  room: string | null;
  absentTeacherName: string | null;
};

export type MyDay = {
  date: PlainDate;
  workingDay: boolean;
  absent: boolean;
  classes: number;
  substitutions: SubstitutionToday[];
};

/** GET /api/dashboard. A card is null when the person lacks the permission behind it. */
export type DashboardSummary = {
  date: PlainDate;
  academicYearName: string | null;
  students: DashboardStudents | null;
  attendance: DashboardAttendance | null;
  fees: DashboardFees | null;
  admissions: DashboardAdmissions | null;
  staff: StaffTodaySummary | null;
  leave: { waitingForMe: number } | null;
  circulars: { pendingApproval: number; items: CircularWaiting[] } | null;
  homework: { waitingForReview: number } | null;
  timetable: { clashes: number; warnings: number; periodsToCover: number; periodsCovered: number } | null;
  myDay: MyDay | null;
};

/* ------------------------------------------------------------------ reports */

export type Absentee = {
  studentId: string;
  fullName: string;
  admissionNo: string;
  rollNo: number | null;
  classId: string;
  className: string;
  sectionId: string;
  sectionName: string;
  sectionLabel: string;
  status: Extract<AttendanceStatus, "ABSENT" | "LEAVE">;
  daysInARow: number;
  markedByName: string | null;
};

export type AbsenteesReport = {
  date: PlainDate;
  holiday: string | null;
  classId: string | null;
  includeLeave: boolean;
  sectionCount: number;
  sectionsMarked: number;
  absent: number;
  onLeave: number;
  rows: Absentee[];
};

export type SectionAttendanceLine = {
  sectionId: string;
  classId: string;
  className: string;
  sectionName: string;
  label: string;
  students: number;
  daysMarked: number;
  counts: AttendanceCounts;
  presentPercent: number | null;
};

export type ClassAttendanceLine = {
  classId: string;
  className: string;
  students: number;
  counts: AttendanceCounts;
  presentPercent: number | null;
  sections: SectionAttendanceLine[];
};

export type SectionAttendanceReport = {
  from: PlainDate;
  to: PlainDate;
  classId: string | null;
  schoolDays: number;
  holidays: number;
  classes: ClassAttendanceLine[];
  students: number;
  daysMarked: number;
  counts: AttendanceCounts;
  presentPercent: number | null;
};

export type CompletionLine = {
  sectionId: string | null;
  sectionLabel: string | null;
  classId: string | null;
  className: string | null;
  subjectId: string | null;
  subjectName: string | null;
  homework: number;
  online: number;
  expected: number;
  submitted: number;
  late: number;
  reviewed: number;
  needsRedo: number;
  waiting: number;
  completionPercent: number | null;
};

export type HomeworkCompletionReport = {
  from: PlainDate;
  to: PlainDate;
  classId: string | null;
  subjectId: string | null;
  rows: CompletionLine[];
  total: CompletionLine;
};

export type FunnelLine = {
  classId: string | null;
  className: string | null;
  source: ApplicationSource | null;
  total: number;
  applied: number;
  assessed: number;
  offered: number;
  admitted: number;
  rejected: number;
  withdrawn: number;
  conversionPercent: number | null;
};

export type Funnel = {
  total: number;
  stages: FunnelStage[];
  open: number;
  rejected: number;
  withdrawn: number;
  conversionPercent: number | null;
  byClass: FunnelLine[];
  bySource: FunnelLine[];
};

export type FunnelReport = {
  /** null: every academic year */
  academicYearId: string | null;
  academicYearName: string | null;
  classId: string | null;
  source: ApplicationSource | null;
  from: PlainDate | null;
  to: PlainDate | null;
  funnel: Funnel;
};

export type LeaveTypeRef = { id: string; name: string; code: string; lossOfPay: boolean; active: boolean };

export type StaffLeaveLine = {
  userId: string;
  name: string;
  employeeCode: string | null;
  departmentId: string | null;
  departmentName: string | null;
  active: boolean;
  /** Approved days by type, in the order of LeaveTakenReport.types. */
  days: number[];
  total: number;
  lossOfPay: number;
  pending: number;
};

export type LeaveTakenReport = {
  academicYearId: string | null;
  academicYearName: string | null;
  departmentId: string | null;
  leaveTypeId: string | null;
  types: LeaveTypeRef[];
  staff: StaffLeaveLine[];
  typeTotals: number[];
  total: number;
  lossOfPay: number;
  pending: number;
};
