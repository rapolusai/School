/** Types mirroring docs/api/phase-1-timetable-homework.md (timetable). Keep in sync with the contract. */

import type { PlainDate } from "./school";

export const WEEK_DAYS = ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"] as const;
export type WeekDay = (typeof WEEK_DAYS)[number];

export type TimetableYearRef = { id: string; name: string; startsOn: PlainDate; endsOn: PlainDate } | null;

/** A teaching period (numbered) or a break (number null). Times are "HH:mm" in India. */
export type PeriodView = { number: number | null; label: string; startsAt: string; endsAt: string; breakTime: boolean };

export type BellSchedule = {
  workingDays: WeekDay[];
  /** When on, Saturdays follow `saturday`; otherwise every working day follows `weekday`. */
  saturdaySchedule: boolean;
  weekday: PeriodView[];
  saturday: PeriodView[];
  weekdayPeriods: number;
  saturdayPeriods: number;
};

export type PeriodRow = { label: string; startsAt: string; endsAt: string; breakTime: boolean };

export type BellScheduleRequest = {
  workingDays: WeekDay[];
  saturdaySchedule: boolean;
  weekday: PeriodRow[];
  saturday?: PeriodRow[];
};

export type AssignmentView = {
  id: string;
  sectionId: string;
  sectionLabel: string;
  classId: string;
  subjectId: string;
  subjectName: string;
  teacherId: string;
  teacherName: string;
  periodsPerWeek: number;
  scheduled: number;
};

export type AssignmentRequest = { sectionId: string; subjectId: string; teacherId: string; periodsPerWeek: number };
export type AssignmentUpdate = { teacherId: string; periodsPerWeek: number };

export type ClashEntry = {
  sectionId: string;
  sectionLabel: string;
  subjectId: string;
  subjectName: string;
  teacherId: string | null;
  teacherName: string | null;
};

/** TEACHER: one teacher in two sections at once. SECTION: two subjects in one period of a section. */
export type Clash = {
  kind: "TEACHER" | "SECTION";
  day: WeekDay;
  period: number;
  teacherId: string | null;
  teacherName: string | null;
  sectionId: string | null;
  sectionLabel: string | null;
  entries: ClashEntry[];
};

export type AssignmentSaved = { assignment: AssignmentView; periodsMoved: number; clashes: Clash[] };

/** A subject placed more often in a week than its assignment allows: a warning, not an error. */
export type WeeklyWarning = {
  sectionId: string;
  sectionLabel: string;
  subjectId: string;
  subjectName: string;
  scheduled: number;
  periodsPerWeek: number;
};

export type SlotView = {
  day: WeekDay;
  period: number;
  subjectId: string;
  subjectName: string;
  teacherId: string | null;
  teacherName: string | null;
  room: string | null;
};

export type SubjectLoad = {
  subjectId: string;
  subjectName: string;
  assignmentId: string | null;
  teacherId: string | null;
  teacherName: string | null;
  /** null when no teacher is assigned to the subject yet. */
  periodsPerWeek: number | null;
  scheduled: number;
};

/** Another section's period of one of this section's teachers (for live clash warnings). */
export type Booking = {
  teacherId: string;
  day: WeekDay;
  period: number;
  sectionId: string;
  sectionLabel: string;
  subjectName: string;
};

export type SectionTimetable = {
  sectionId: string;
  label: string;
  classId: string;
  className: string;
  sectionName: string;
  classTeacherName: string | null;
  academicYear: TimetableYearRef;
  bells: BellSchedule;
  slots: SlotView[];
  subjects: SubjectLoad[];
  busy: Booking[];
  clashes: Clash[];
  warnings: WeeklyWarning[];
  canEdit: boolean;
};

export type CellRequest = {
  day: WeekDay;
  period: number;
  subjectId: string;
  teacherId: string | null;
  room?: string | null;
};

export type SectionSummary = {
  sectionId: string;
  label: string;
  classId: string;
  className: string;
  sectionName: string;
  classTeacherName: string | null;
  scheduled: number;
  cells: number;
  clashes: number;
};

export type SectionsOverview = {
  academicYear: TimetableYearRef;
  bells: BellSchedule;
  sections: SectionSummary[];
  clashes: number;
  canEdit: boolean;
};

export type ClashReport = {
  academicYear: TimetableYearRef;
  sectionsChecked: number;
  periodsChecked: number;
  clashes: Clash[];
  warnings: WeeklyWarning[];
};

export type TeacherSummary = { id: string; name: string; periodsPerWeek: number; scheduled: number; subjects: string[] };

export type TeacherSlot = {
  day: WeekDay;
  period: number;
  sectionId: string;
  sectionLabel: string;
  subjectId: string;
  subjectName: string;
  room: string | null;
};

export type TeacherTimetable = {
  teacherId: string;
  teacherName: string;
  academicYear: TimetableYearRef;
  bells: BellSchedule;
  slots: TeacherSlot[];
  periodsPerWeek: number;
  clashes: Clash[];
};

/** CLASS: a regular class. SUBSTITUTION: covering for an absent teacher. COVERED: the regular teacher is away. */
export type DayEntry = {
  kind: "CLASS" | "SUBSTITUTION" | "COVERED";
  sectionId: string;
  sectionLabel: string;
  subjectId: string;
  subjectName: string;
  teacherName: string | null;
  room: string | null;
  substituteName: string | null;
  absentTeacherName: string | null;
};

export type DayPeriod = {
  number: number | null;
  label: string;
  startsAt: string;
  endsAt: string;
  breakTime: boolean;
  entries: DayEntry[];
};

export type TeacherDay = {
  date: PlainDate;
  day: WeekDay;
  workingDay: boolean;
  teacherId: string;
  teacherName: string;
  absent: boolean;
  periods: DayPeriod[];
};

export type FreeTeacher = { id: string; name: string; periodsThatDay: number; teachesSubject: boolean };

export type FreeTeachers = { date: PlainDate | null; day: WeekDay; period: number; teachers: FreeTeacher[] };

export type SubstituteRef = { id: string; teacherId: string; teacherName: string };

export type AffectedPeriod = {
  period: number;
  label: string;
  startsAt: string;
  endsAt: string;
  sectionId: string;
  sectionLabel: string;
  subjectId: string;
  subjectName: string;
  room: string | null;
  substitute: SubstituteRef | null;
  /** Free teachers, best first: those who teach the subject, then the least busy that day. */
  suggestions: FreeTeacher[];
};

export type AbsenceView = {
  id: string;
  teacherId: string;
  teacherName: string;
  reason: string | null;
  periods: AffectedPeriod[];
};

export type SubstitutionDay = {
  date: PlainDate;
  day: WeekDay;
  workingDay: boolean;
  absences: AbsenceView[];
  periodsToCover: number;
  periodsCovered: number;
  teachers: { id: string; name: string }[];
};

export type AbsenceRequest = { date: PlainDate; teacherId: string; reason?: string | null };
export type SubstitutionRequest = { date: PlainDate; sectionId: string; period: number; teacherId: string };

/** A student's section timetable, with today's day (substitutions applied). */
export type FamilyTimetable = {
  studentId: string;
  studentName: string;
  sectionId: string | null;
  sectionLabel: string | null;
  academicYear: TimetableYearRef;
  bells: BellSchedule;
  slots: SlotView[];
  today: PlainDate;
  todayDay: WeekDay;
  workingDay: boolean;
  todayPeriods: DayPeriod[];
};
