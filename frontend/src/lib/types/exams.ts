/* Types mirroring docs/api/phase-2-exams.md (exams, marks, results and report cards). */

import type { PlainDate } from "./school";

/** Instead of marks: AB absent (counts as zero), ML medical and EX exempt (both left out of the totals). */
export const MARK_CODES = ["AB", "ML", "EX"] as const;
export type MarkCode = (typeof MARK_CODES)[number];

export type SchemeKind = "SCHOLASTIC" | "CO_SCHOLASTIC";
export type SheetStatus = "DRAFT" | "LOCKED";
/** A sheet on the progress list also shows the sheets nobody has opened yet. */
export type SheetProgress = SheetStatus | "NOT_STARTED";
export type OverallResult = "PASS" | "NEEDS_IMPROVEMENT" | "INCOMPLETE";

/** The four levels of the NEP 2020 holistic progress card, lowest first. */
export const HPC_LEVELS = ["BEGINNER", "PROGRESSING", "PROFICIENT", "ADVANCED"] as const;
export type HpcLevel = (typeof HPC_LEVELS)[number];
export const HPC_DOMAINS = ["physical", "socioEmotional", "cognitive", "language", "numeracy", "aesthetic"] as const;
export type HpcDomain = (typeof HPC_DOMAINS)[number];

export const CO_SCHOLASTIC_AREAS = ["workEducation", "artEducation", "healthPhysical", "discipline"] as const;
export type CoScholasticArea = (typeof CO_SCHOLASTIC_AREAS)[number];

/** Limits shared with the API (ExamsForms). */
export const MAX_BANDS = 20;
export const MAX_PAPERS = 40;
export const REMARK_MAX = 500;
export const REFLECTION_MAX = 1000;
export const REASON_MAX = 500;
export const INSTRUCTIONS_MAX = 2000;

export type ExamYearRef = { id: string; name: string; startsOn: PlainDate; endsOn: PlainDate; current: boolean };
export type ExamClassRef = { id: string; name: string };
export type ExamSubjectRef = { id: string; name: string; code: string | null };

/* ------------------------------------------------------------------ grading */

export type GradeBand = { minPercent: number; grade: string; gradePoint: number | null; remark: string | null };

export type GradingScheme = {
  id: string;
  code: string | null;
  name: string;
  kind: SchemeKind;
  description: string | null;
  passPercent: number;
  overallPassPercent: number;
  builtIn: boolean;
  bands: GradeBand[];
  /** classes using it this year */
  inUse: number;
  updatedAt: string;
};

export type SchemeRequest = {
  name: string;
  kind: SchemeKind;
  description: string | null;
  passPercent: number;
  overallPassPercent: number;
  bands: GradeBand[];
};

export type ExamSettings = {
  ranksEnabled: boolean;
  hpcUpToLevel: number;
  coScholasticSchemeId: string | null;
  coScholasticSchemeName: string | null;
};

export type ExamSettingsRequest = { ranksEnabled: boolean; hpcUpToLevel: number; coScholasticSchemeId: string | null };

export type ClassSchemeRow = {
  classId: string;
  className: string;
  /** read from the class name; LKG and UKG are 0 */
  level: number | null;
  schemeId: string | null;
  schemeName: string | null;
  defaultScheme: boolean;
  hpc: boolean;
};

export type ClassSchemes = {
  year: ExamYearRef | null;
  years: ExamYearRef[];
  board: string | null;
  classes: ClassSchemeRow[];
};

export type ClassSchemeRequest = { classIds: string[]; schemeId: string | null };

/* ------------------------------------------------------------------ structure */

export type ExamClassSummary = {
  examClassId: string;
  classId: string;
  className: string;
  papers: number;
  papersScheduled: number;
  dateSheetPublishedAt: string | null;
  resultsPublishedAt: string | null;
};

export type ExamSummary = {
  id: string;
  name: string;
  weight: number | null;
  instructions: string | null;
  firstPaperOn: PlainDate | null;
  lastPaperOn: PlainDate | null;
  classes: ExamClassSummary[];
};

export type ExamTerm = {
  id: string;
  name: string;
  startsOn: PlainDate;
  endsOn: PlainDate;
  weight: number | null;
  examWeightTotal: number | null;
  exams: ExamSummary[];
};

export type ExamStructure = {
  year: ExamYearRef | null;
  years: ExamYearRef[];
  terms: ExamTerm[];
  termWeightTotal: number | null;
  classes: ExamClassRef[];
  canSetup: boolean;
  canPublish: boolean;
};

export type TermRequest = { academicYearId?: string; name: string; startsOn: PlainDate; endsOn: PlainDate };
export type ExamRequest = { termId?: string; name: string; instructions: string | null; classIds: string[] };
export type WeightsRequest = { weights: { id: string; weight: number }[] };

/* ------------------------------------------------------------------ papers and date sheets */

export type ExamPaper = {
  id: string;
  subjectId: string;
  subjectName: string;
  subjectCode: string | null;
  maxTheory: number;
  maxPractical: number;
  maxInternal: number;
  maxTotal: number;
  date: PlainDate | null;
  /** "09:30" or "09:30:00" */
  startTime: string | null;
  durationMinutes: number | null;
  endTime: string | null;
  room: string | null;
  hasMarks: boolean;
};

export type DateSheetClashKind = "OVERLAP" | "HOLIDAY" | "SUNDAY";

export type DateSheetClash = { paperId: string; kind: DateSheetClashKind | string; detail: string; otherPaperId: string | null };

export type ExamClassDetail = {
  examId: string;
  examName: string;
  termId: string;
  termName: string;
  academicYearId: string;
  academicYearName: string;
  examClassId: string;
  classId: string;
  className: string;
  instructions: string | null;
  papers: ExamPaper[];
  clashes: DateSheetClash[];
  classSubjects: ExamSubjectRef[];
  dateSheetPublishedAt: string | null;
  dateSheetPublishedBy: string | null;
  resultsPublishedAt: string | null;
  resultsPublishedBy: string | null;
  canSetup: boolean;
  canPublish: boolean;
};

export type PaperRequest = {
  subjectId: string;
  maxTheory: number;
  maxPractical: number | null;
  maxInternal: number | null;
  date: PlainDate | null;
  startTime: string | null;
  durationMinutes: number | null;
  room: string | null;
};

export type ExamSchoolHeader = {
  name: string;
  code: string;
  board: string | null;
  city: string | null;
  address: string | null;
  phone: string | null;
};

export type HallTicketStudent = {
  studentId: string;
  fullName: string;
  admissionNo: string;
  rollNo: number | null;
  sectionId: string;
  sectionName: string;
  sectionLabel: string;
};

export type HallTickets = {
  school: ExamSchoolHeader;
  examId: string;
  examName: string;
  termName: string;
  academicYearName: string;
  classId: string;
  className: string;
  instructions: string | null;
  papers: ExamPaper[];
  students: HallTicketStudent[];
};

/* ------------------------------------------------------------------ marks */

export type MarkSheetRow = {
  examClassId: string;
  classId: string;
  className: string;
  paperId: string;
  subjectId: string;
  subjectName: string;
  sectionId: string;
  sectionLabel: string;
  paperDate: PlainDate | null;
  status: SheetProgress;
  students: number;
  entered: number;
  canEdit: boolean;
  updatedByName: string | null;
  updatedAt: string | null;
  resultsPublished: boolean;
};

export type ExamMarks = {
  examId: string;
  examName: string;
  termName: string;
  total: number;
  locked: number;
  draft: number;
  notStarted: number;
  sheets: MarkSheetRow[];
};

export type MarksOverview = { year: ExamYearRef | null; years: ExamYearRef[]; wholeSchool: boolean; exams: ExamMarks[] };

export type MarkRow = {
  studentId: string;
  fullName: string;
  admissionNo: string;
  rollNo: number | null;
  /** false for a student with marks who has since left the section */
  inSection: boolean;
  theory: number | null;
  practical: number | null;
  internal: number | null;
  code: MarkCode | null;
  total: number | null;
  percent: number | null;
  grade: string | null;
};

export type MarkSheet = {
  sheetId: string | null;
  /** null until the sheet is first saved */
  version: number | null;
  examId: string;
  examName: string;
  termName: string;
  examClassId: string;
  classId: string;
  className: string;
  sectionId: string;
  sectionLabel: string;
  paperId: string;
  subjectId: string;
  subjectName: string;
  maxTheory: number;
  maxPractical: number;
  maxInternal: number;
  maxTotal: number;
  paperDate: PlainDate | null;
  status: SheetStatus | null;
  updatedByName: string | null;
  updatedAt: string | null;
  submittedByName: string | null;
  submittedAt: string | null;
  unlockedByName: string | null;
  unlockedAt: string | null;
  unlockReason: string | null;
  canEdit: boolean;
  canUnlock: boolean;
  resultsPublished: boolean;
  entered: number;
  students: MarkRow[];
};

export type MarkEntryRequest = {
  studentId: string;
  theory: number | null;
  practical: number | null;
  internal: number | null;
  code: MarkCode | null;
};

export type MarkSheetRequest = { version: number | null; entries: MarkEntryRequest[] };

/* ------------------------------------------------------------------ results */

export type ExamSchemeRef = {
  id: string;
  name: string;
  passPercent: number;
  overallPassPercent: number;
  gradePoints: boolean;
  bands: GradeBand[];
};

export type ResultSubjectHead = { paperId: string; subjectId: string; subjectName: string; max: number };

export type SubjectScore = {
  subjectId: string;
  obtained: number | null;
  max: number | null;
  percent: number | null;
  grade: string | null;
  gradePoint: number | null;
  /** null when there is nothing to judge (ML, EX, or marks missing) */
  passed: boolean | null;
  code: MarkCode | null;
};

export type ResultRow = {
  studentId: string;
  fullName: string;
  admissionNo: string;
  rollNo: number | null;
  subjects: SubjectScore[];
  obtained: number | null;
  max: number | null;
  percent: number | null;
  grade: string | null;
  gpa: number | null;
  result: OverallResult;
  rank: number | null;
};

export type SectionResults = {
  examId: string;
  examName: string;
  termName: string;
  examClassId: string;
  classId: string;
  className: string;
  sectionId: string;
  sectionLabel: string;
  scheme: ExamSchemeRef;
  ranksEnabled: boolean;
  resultsPublishedAt: string | null;
  sheetsPending: number;
  subjects: ResultSubjectHead[];
  rows: ResultRow[];
};

export type Hpc = Record<HpcDomain, HpcLevel | null> & {
  teacherReflection: string | null;
  selfReflection: string | null;
  parentReflection: string | null;
};

export type CoScholastic = Record<CoScholasticArea, string | null>;

export type StudentTermRecord = {
  studentId: string;
  fullName: string;
  admissionNo: string;
  rollNo: number | null;
  coScholastic: CoScholastic;
  remark: string | null;
  hpc: Hpc | null;
};

export type TermRecords = {
  termId: string;
  termName: string;
  classId: string;
  className: string;
  sectionId: string;
  sectionLabel: string;
  coScholasticGrades: string[];
  hpcEnabled: boolean;
  canEdit: boolean;
  students: StudentTermRecord[];
};

export type TermRecordRequest = CoScholastic &
  Record<HpcDomain, HpcLevel | null> & {
    studentId: string;
    remark: string | null;
    teacherReflection: string | null;
    selfReflection: string | null;
    parentReflection: string | null;
  };

/* ------------------------------------------------------------------ report cards */

export type CardColumn = { id: string; name: string; weight: number | null };

export type CardCell = {
  columnId: string;
  obtained: number | null;
  max: number | null;
  percent: number | null;
  grade: string | null;
  code: MarkCode | null;
};

export type CardSubject = {
  subjectId: string;
  subjectName: string;
  cells: CardCell[];
  percent: number | null;
  grade: string | null;
  gradePoint: number | null;
  passed: boolean | null;
};

export type CardAttendance = {
  from: PlainDate;
  to: PlainDate;
  daysMarked: number;
  present: number;
  absent: number;
  late: number;
  halfDay: number;
  leave: number;
  presentPercent: number | null;
};

export type ReportCard = {
  studentId: string;
  fullName: string;
  admissionNo: string;
  rollNo: number | null;
  classLabel: string;
  subjects: CardSubject[];
  percent: number | null;
  grade: string | null;
  gpa: number | null;
  result: OverallResult;
  rank: number | null;
  attendance: CardAttendance | null;
  coScholastic: CoScholastic | null;
  remark: string | null;
  hpc: Hpc | null;
};

export type ReportCards = {
  school: ExamSchoolHeader;
  /** TERM: the columns are the term's exams; YEAR: the columns are the terms */
  kind: "TERM" | "YEAR";
  academicYearId: string;
  academicYearName: string;
  termId: string | null;
  termName: string | null;
  classId: string;
  className: string;
  sectionId: string;
  sectionLabel: string;
  classTeacherName: string | null;
  scheme: ExamSchemeRef;
  ranksEnabled: boolean;
  hpcEnabled: boolean;
  /** only published results count (families) */
  publishedOnly: boolean;
  columns: CardColumn[];
  cards: ReportCard[];
};

/** A term (`termId`) or the whole year (`yearId`): exactly one. */
export type CardPeriod = { termId: string } | { yearId: string };

/* ------------------------------------------------------------------ families */

export type FamilyExamResult = {
  examId: string;
  examName: string;
  termId: string;
  termName: string;
  publishedAt: string;
  percent: number | null;
  grade: string | null;
  result: OverallResult;
};

export type FamilyTerm = { id: string; name: string; published: boolean };

export type UpcomingExam = { examId: string; examName: string; termName: string; papers: ExamPaper[] };

export type FamilyResults = {
  studentId: string;
  fullName: string;
  classLabel: string | null;
  year: ExamYearRef | null;
  terms: FamilyTerm[];
  results: FamilyExamResult[];
  upcoming: UpcomingExam[];
};

/* ------------------------------------------------------------------ analysis */

export type GradeCount = { grade: string; count: number };

export type SectionStat = {
  sectionId: string;
  label: string;
  students: number;
  appeared: number;
  average: number | null;
  passed: number;
  passPercent: number | null;
};

export type SubjectStat = {
  subjectId: string;
  subjectName: string;
  max: number;
  appeared: number;
  absent: number;
  averageMarks: number | null;
  highest: number | null;
  lowest: number | null;
  average: number | null;
  passed: number;
  passPercent: number | null;
  grades: GradeCount[];
  previousAverage: number | null;
  change: number | null;
  sections: SectionStat[];
};

export type WeakSubject = { subjectName: string; percent: number | null; code: MarkCode | null };

export type BelowPass = {
  studentId: string;
  fullName: string;
  admissionNo: string;
  rollNo: number | null;
  sectionLabel: string;
  percent: number | null;
  subjects: WeakSubject[];
};

export type ExamAnalysis = {
  examId: string;
  examName: string;
  termName: string;
  academicYearId: string;
  classId: string;
  className: string;
  previousExamId: string | null;
  previousExamName: string | null;
  scheme: ExamSchemeRef;
  students: number;
  appeared: number;
  average: number | null;
  passed: number;
  passPercent: number | null;
  previousAverage: number | null;
  grades: GradeCount[];
  subjects: SubjectStat[];
  sections: SectionStat[];
  belowPass: BelowPass[];
  resultsPublishedAt: string | null;
  sheetsPending: number;
};
