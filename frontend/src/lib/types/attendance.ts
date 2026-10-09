/** Types mirroring docs/api/phase-1-attendance.md (attendance). Keep in sync with the contract. */

import type { PlainDate } from "./school";

export const ATTENDANCE_STATUSES = ["PRESENT", "ABSENT", "LATE", "HALF_DAY", "LEAVE"] as const;
/** LEAVE is excused leave. */
export type AttendanceStatus = (typeof ATTENDANCE_STATUSES)[number];

/** Register letters: P present, A absent, L late, H half day, E excused leave. */
export type AttendanceMark = "P" | "A" | "L" | "H" | "E";

export const MARK_OF: Record<AttendanceStatus, AttendanceMark> = {
  PRESENT: "P",
  ABSENT: "A",
  LATE: "L",
  HALF_DAY: "H",
  LEAVE: "E",
};

export type AttendanceCounts = { present: number; absent: number; late: number; halfDay: number; leave: number };

export type AttendanceYearRef = { id: string; name: string; startsOn: PlainDate; endsOn: PlainDate };

export type SectionDay = {
  sectionId: string;
  classId: string;
  className: string;
  sectionName: string;
  label: string;
  classTeacherName: string | null;
  students: number;
  marked: boolean;
  markedByName: string | null;
  /** ISO-8601 instant */
  markedAt: string | null;
  counts: AttendanceCounts;
  presentPercent: number | null;
  canMark: boolean;
};

export type SectionsForDay = {
  date: PlainDate;
  today: PlainDate;
  /** null when the school has no current academic year */
  academicYear: AttendanceYearRef | null;
  canMark: boolean;
  sections: SectionDay[];
};

export type RegisterEntry = {
  studentId: string;
  fullName: string;
  admissionNo: string;
  rollNo: number | null;
  /** false: marked earlier, has since left the section */
  inSection: boolean;
  status: AttendanceStatus | null;
};

export type RegisterView = {
  sectionId: string;
  classId: string;
  className: string;
  sectionName: string;
  label: string;
  date: PlainDate;
  academicYearName: string;
  marked: boolean;
  markedByName: string | null;
  markedAt: string | null;
  updatedByName: string | null;
  updatedAt: string | null;
  canEdit: boolean;
  counts: AttendanceCounts;
  unmarked: number;
  presentPercent: number | null;
  entries: RegisterEntry[];
};

export type SaveRegisterRequest = { entries: { studentId: string; status: AttendanceStatus }[] };

export type SaveRegisterResult = {
  register: RegisterView;
  firstSave: boolean;
  changed: number;
  alertsQueued: number;
  alertsCancelled: number;
};

export type MonthDay = { date: PlainDate; marked: boolean; counts: AttendanceCounts; presentPercent: number | null };

export type MonthStudent = {
  studentId: string;
  fullName: string;
  admissionNo: string;
  rollNo: number | null;
  inSection: boolean;
  /** One per day of the month; null where the student was not marked. */
  marks: (AttendanceMark | null)[];
  daysMarked: number;
  counts: AttendanceCounts;
  presentPercent: number | null;
};

export type MonthRegister = {
  sectionId: string;
  className: string;
  sectionName: string;
  label: string;
  /** "YYYY-MM" */
  month: string;
  days: MonthDay[];
  students: MonthStudent[];
  daysMarked: number;
  counts: AttendanceCounts;
  presentPercent: number | null;
};

export type DayMark = { date: PlainDate; status: AttendanceStatus };

export type StudentAttendanceSummary = {
  studentId: string;
  fullName: string;
  admissionNo: string;
  className: string | null;
  sectionName: string | null;
  from: PlainDate;
  to: PlainDate;
  daysMarked: number;
  counts: AttendanceCounts;
  presentPercent: number | null;
  absences: PlainDate[];
  days: DayMark[];
};

export type TodaySection = {
  sectionId: string;
  sectionName: string;
  label: string;
  classTeacherName: string | null;
  students: number;
  marked: boolean;
  markedByName: string | null;
  markedAt: string | null;
  counts: AttendanceCounts;
  presentPercent: number | null;
};

export type TodayClass = {
  classId: string;
  className: string;
  sectionCount: number;
  sectionsMarked: number;
  counts: AttendanceCounts;
  presentPercent: number | null;
  sections: TodaySection[];
};

export type AttendanceToday = {
  date: PlainDate;
  academicYearName: string | null;
  sectionCount: number;
  sectionsMarked: number;
  students: number;
  counts: AttendanceCounts;
  presentPercent: number | null;
  classes: TodayClass[];
};

export type ChildAttendance = {
  studentId: string;
  fullName: string;
  month: string;
  daysMarked: number;
  counts: AttendanceCounts;
  presentPercent: number | null;
  days: DayMark[];
  /** Newest first, at most 5, this academic year. */
  recentAbsences: DayMark[];
};
