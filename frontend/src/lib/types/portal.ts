/** Types mirroring docs/api/phase-1-portal.md (the parent and student app). Keep in sync with the contract. */

import type { AttendanceStatus } from "./attendance";
import type { PlainDate } from "./school";

export const CHILD_LEAVE_STATUSES = ["PENDING", "APPROVED", "REJECTED", "CANCELLED"] as const;
export type ChildLeaveStatus = (typeof CHILD_LEAVE_STATUSES)[number];

/** Approved leave of one student on one day: the register pre-fills `prefill` (LEAVE, or HALF_DAY). */
export type ApprovedLeave = { requestId: string; halfDay: boolean; prefill: AttendanceStatus };

/** A child's leave request (absence note), as the parent, the student or a teacher sees it. */
export type ChildLeave = {
  id: string;
  studentId: string;
  studentName: string | null;
  admissionNo: string | null;
  rollNo: number | null;
  sectionId: string;
  sectionLabel: string | null;
  fromDate: PlainDate;
  toDate: PlainDate;
  halfDay: boolean;
  /** Mondays to Saturdays that are not whole-school holidays (a half day counts as one). */
  schoolDays: number;
  reason: string;
  status: ChildLeaveStatus;
  requestedByName: string | null;
  /** ISO-8601 instant */
  createdAt: string;
  decidedByName: string | null;
  decidedAt: string | null;
  decisionComment: string | null;
  cancelledByName: string | null;
  cancelledAt: string | null;
  /** The parent may withdraw it (waiting, or approved and not started). */
  canCancel: boolean;
  /** The teacher may approve or reject it. */
  canDecide: boolean;
};

/** One child's requests; a parent may apply for days between `earliest` and `latest`. */
export type FamilyLeave = {
  studentId: string;
  studentName: string;
  sectionLabel: string | null;
  classTeacherName: string | null;
  today: PlainDate;
  earliest: PlainDate;
  latest: PlainDate;
  /** False for a student (read only) and for a child who is not in a class this year. */
  canApply: boolean;
  /** Latest dates first. */
  requests: ChildLeave[];
};

/** A teacher's list: waiting requests (oldest first) and those closed in the last 30 days. */
export type LeaveInbox = { pending: ChildLeave[]; recent: ChildLeave[]; wholeSchool: boolean };

export type ChildLeaveApplication = { fromDate: PlainDate; toDate: PlainDate; halfDay: boolean; reason: string };

/** A whole-school holiday in a child's attendance month. */
export type AttendanceHoliday = { date: PlainDate; title: string };

/** A day of approved leave in a child's attendance month. */
export type LeaveDay = { date: PlainDate; halfDay: boolean };
