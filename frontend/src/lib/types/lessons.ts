/* Types mirroring docs/api/phase-2-lessons.md (syllabus, lesson plans, coverage, study material, question bank and
 * online tests). */

import type { FileRef } from "./homework";
import type { ImportRowError, PlainDate } from "./school";

/** "2026-10": a month of the year (a syllabus topic's target month). */
export type YearMonthText = string;

export const PLAN_STATUSES = ["DRAFT", "SUBMITTED", "APPROVED", "RETURNED", "TAUGHT"] as const;
export type PlanStatus = (typeof PLAN_STATUSES)[number];

export const QUESTION_TYPES = ["MCQ_SINGLE", "MCQ_MULTI", "TRUE_FALSE", "SHORT_ANSWER", "FILL_BLANK"] as const;
export type QuestionType = (typeof QUESTION_TYPES)[number];

export const DIFFICULTIES = ["EASY", "MEDIUM", "HARD"] as const;
export type Difficulty = (typeof DIFFICULTIES)[number];

export const BLOOM_LEVELS = ["REMEMBER", "UNDERSTAND", "APPLY", "ANALYSE", "EVALUATE", "CREATE"] as const;
export type BloomLevel = (typeof BLOOM_LEVELS)[number];

export type ShowAnswers = "WITH_RESULTS" | "NEVER";
export type TestStatus = "DRAFT" | "PUBLISHED";
/** A staff test's state: published but not open yet is SCHEDULED. */
export type TestState = "DRAFT" | "SCHEDULED" | "OPEN" | "CLOSED";
/** A test as a student or parent sees it. */
export type StudyTestState = "UPCOMING" | "OPEN" | "CLOSED";
export type StudentTestStatus = "NOT_STARTED" | "IN_PROGRESS" | "SUBMITTED" | "MARKING";

/** Limits shared with the API (LessonsForms). */
export const LESSONS_LIMITS = {
  title: 200,
  units: 40,
  topicsPerUnit: 60,
  plannedPeriods: 200,
  planPeriods: 20,
  planText: 2000,
  planShortText: 1000,
  reviewComment: 1000,
  materialNotes: 5000,
  link: 1000,
  questionText: 2000,
  answerText: 1000,
  explanation: 2000,
  option: 500,
  options: 6,
  testInstructions: 2000,
  testQuestions: 100,
  duration: 300,
  attempts: 5,
  randomCount: 50,
  feedback: 500,
  answer: 1000,
  materialFiles: 5,
} as const;

/* ------------------------------------------------------------------ options and settings */

export type LessonsYear = { id: string; name: string; startsOn: PlainDate; endsOn: PlainDate; current: boolean };
export type LessonsSectionRef = { id: string; name: string; label: string };
export type LessonsSubjectRef = { id: string; name: string };
export type LessonsClassOption = {
  id: string;
  name: string;
  sections: LessonsSectionRef[];
  subjects: LessonsSubjectRef[];
};
export type TeachingRef = { sectionId: string; subjectId: string };

export type LessonsOptions = {
  yearId: string | null;
  years: LessonsYear[];
  classes: LessonsClassOption[];
  userId: string | null;
  /** approvers see and plan for the whole school */
  wholeSchool: boolean;
  canManage: boolean;
  canApprove: boolean;
  teaching: TeachingRef[];
};

export type LessonsSettings = { testNotificationsEnabled: boolean };

/* ------------------------------------------------------------------ syllabus */

export type SyllabusTopic = {
  id: string;
  position: number;
  title: string;
  plannedPeriods: number;
  targetMonth: YearMonthText | null;
  hasPlans: boolean;
};
export type SyllabusUnit = { id: string; position: number; title: string; topics: SyllabusTopic[] };

export type SyllabusRow = {
  id: string;
  yearId: string;
  classId: string;
  className: string;
  subjectId: string;
  subjectName: string;
  units: number;
  topics: number;
  plannedPeriods: number;
  updatedAt: string;
  updatedByName: string | null;
  canEdit: boolean;
};

export type SyllabusDetail = {
  id: string;
  yearId: string;
  yearName: string;
  classId: string;
  className: string;
  subjectId: string;
  subjectName: string;
  units: SyllabusUnit[];
  topics: number;
  plannedPeriods: number;
  updatedAt: string;
  updatedByName: string | null;
  version: number;
  canEdit: boolean;
};

export type TopicOption = { id: string; title: string; unitTitle: string; targetMonth: YearMonthText | null };

export type TopicRequest = { id?: string | null; title: string; plannedPeriods: number; targetMonth: YearMonthText | null };
export type UnitRequest = { id?: string | null; title: string; topics: TopicRequest[] };
export type SyllabusRequest = {
  academicYearId: string | null;
  classId: string;
  subjectId: string;
  version?: number | null;
  units: UnitRequest[];
};
export type SyllabusCopyRequest = { fromYearId: string; toYearId: string; classIds?: string[] | null };
export type CopyResult = { copied: number; skipped: number };

export type SyllabusPreviewRow = {
  row: number;
  className: string;
  subjectName: string;
  unit: string;
  topic: string;
  plannedPeriods: number;
  targetMonth: YearMonthText | null;
};
export type SyllabusImportResult = {
  dryRun: boolean;
  committed: boolean;
  totalRows: number;
  validRows: number;
  invalidRows: number;
  syllabi: number;
  errors: ImportRowError[];
  ignoredColumns: string[];
  preview: SyllabusPreviewRow[];
};

/* ------------------------------------------------------------------ lesson plans */

export type PlanRow = {
  id: string;
  sectionId: string;
  sectionLabel: string;
  subjectId: string;
  subjectName: string;
  topicId: string | null;
  topicTitle: string | null;
  title: string;
  weekStart: PlainDate;
  planDate: PlainDate | null;
  periods: number;
  status: PlanStatus;
  teacherId: string;
  teacherName: string;
  submittedAt: string | null;
  reviewComment: string | null;
  taughtOn: PlainDate | null;
  updatedAt: string;
  mine: boolean;
};

export type PlanDetail = {
  id: string;
  yearId: string;
  classId: string;
  sectionId: string;
  sectionLabel: string;
  subjectId: string;
  subjectName: string;
  topicId: string | null;
  topicTitle: string | null;
  unitTitle: string | null;
  title: string;
  weekStart: PlainDate;
  planDate: PlainDate | null;
  periods: number;
  objectives: string | null;
  teachingMethod: string | null;
  resources: string | null;
  resourceLinks: string[];
  homeworkIdea: string | null;
  assessmentNote: string | null;
  status: PlanStatus;
  teacherId: string;
  teacherName: string;
  submittedAt: string | null;
  reviewedAt: string | null;
  reviewedByName: string | null;
  reviewComment: string | null;
  taughtOn: PlainDate | null;
  updatedAt: string;
  version: number;
  canEdit: boolean;
  canSubmit: boolean;
  canReview: boolean;
  canMarkTaught: boolean;
};

export type PlanRequest = {
  academicYearId?: string | null;
  sectionId: string;
  subjectId: string;
  topicId: string | null;
  title: string | null;
  weekStart: PlainDate;
  planDate: PlainDate | null;
  periods: number;
  objectives: string | null;
  teachingMethod: string | null;
  resources: string | null;
  /** one link per line */
  resourceLinks: string | null;
  homeworkIdea: string | null;
  assessmentNote: string | null;
  version?: number | null;
};

export type PlanQuery = {
  yearId?: string;
  mine?: boolean;
  status?: PlanStatus;
  sectionId?: string;
  subjectId?: string;
  teacherId?: string;
  from?: PlainDate;
  to?: PlainDate;
};

/* ------------------------------------------------------------------ coverage */

export type CoverageRow = {
  sectionId: string;
  sectionLabel: string;
  classId: string;
  className: string;
  subjectId: string;
  subjectName: string;
  topics: number;
  taught: number;
  percent: number;
  due: number;
  overdue: number;
  expectedPercent: number;
  behind: boolean;
  lastTaughtOn: PlainDate | null;
  teacherName: string | null;
};

export type CoverageReport = {
  yearId: string | null;
  yearName: string | null;
  month: YearMonthText;
  sections: number;
  averagePercent: number | null;
  behind: number;
  rows: CoverageRow[];
};

export type CoverageTopic = {
  id: string;
  title: string;
  plannedPeriods: number;
  targetMonth: YearMonthText | null;
  taught: boolean;
  taughtOn: PlainDate | null;
  teacherName: string | null;
  overdue: boolean;
  plans: number;
};
export type CoverageUnit = { id: string; title: string; topics: CoverageTopic[] };
export type CoverageDetail = { summary: CoverageRow; units: CoverageUnit[] };

export type CoverageQuery = {
  yearId?: string;
  classId?: string;
  subjectId?: string;
  sectionId?: string;
  behindOnly?: boolean;
};

/* ------------------------------------------------------------------ study material */

export type MaterialView = {
  id: string;
  yearId: string;
  classId: string;
  className: string;
  /** null: every section of the class */
  sectionId: string | null;
  sectionLabel: string | null;
  subjectId: string;
  subjectName: string;
  topicId: string | null;
  topicTitle: string | null;
  title: string;
  notes: string | null;
  linkUrl: string | null;
  publishOn: PlainDate;
  published: boolean;
  createdByName: string | null;
  updatedAt: string;
  files: FileRef[];
  canEdit: boolean;
};

export type MaterialRequest = {
  academicYearId?: string | null;
  classId: string;
  sectionId: string | null;
  subjectId: string;
  topicId: string | null;
  title: string;
  notes: string | null;
  linkUrl: string | null;
  publishOn: PlainDate | null;
};

export type MaterialQuery = { yearId?: string; classId?: string; sectionId?: string; subjectId?: string; mine?: boolean };

/* ------------------------------------------------------------------ question bank */

export type OptionView = { id: string; text: string; correct: boolean };

export type QuestionView = {
  id: string;
  classId: string;
  className: string;
  subjectId: string;
  subjectName: string;
  topicId: string | null;
  topicTitle: string | null;
  type: QuestionType;
  difficulty: Difficulty;
  bloomLevel: BloomLevel | null;
  text: string;
  answerText: string | null;
  explanation: string | null;
  marks: number;
  shared: boolean;
  options: OptionView[];
  createdByName: string | null;
  updatedAt: string;
  canEdit: boolean;
  usedInTests: number;
  /** in a published test: cannot be changed or deleted */
  locked: boolean;
};

export type OptionRequest = { text: string; correct: boolean };
export type QuestionRequest = {
  classId: string;
  subjectId: string;
  topicId: string | null;
  type: QuestionType;
  difficulty: Difficulty;
  bloomLevel: BloomLevel | null;
  text: string;
  answerText: string | null;
  explanation: string | null;
  marks: number;
  shared: boolean;
  options: OptionRequest[] | null;
};

export type QuestionQuery = {
  classId?: string;
  subjectId?: string;
  topicId?: string;
  type?: QuestionType;
  difficulty?: Difficulty;
  q?: string;
  mine?: boolean;
};

export type QuestionPreviewRow = {
  row: number;
  className: string;
  subjectName: string;
  type: QuestionType;
  difficulty: Difficulty;
  marks: number;
  text: string;
};
export type QuestionImportResult = {
  dryRun: boolean;
  committed: boolean;
  totalRows: number;
  validRows: number;
  invalidRows: number;
  errors: ImportRowError[];
  ignoredColumns: string[];
  preview: QuestionPreviewRow[];
};

/* ------------------------------------------------------------------ online tests (staff) */

export type TestRow = {
  id: string;
  title: string;
  classId: string;
  className: string;
  subjectId: string;
  subjectName: string;
  sections: LessonsSectionRef[];
  opensAt: string;
  closesAt: string;
  durationMinutes: number;
  attemptsAllowed: number;
  state: TestState;
  questions: number;
  totalMarks: number;
  students: number;
  submitted: number;
  pendingMarking: number;
  released: boolean;
  createdByName: string | null;
  canEdit: boolean;
};

export type TestQuestionView = { id: string; questionId: string; position: number; marks: number; question: QuestionView };

export type TestDetail = {
  id: string;
  yearId: string;
  title: string;
  instructions: string | null;
  classId: string;
  className: string;
  subjectId: string;
  subjectName: string;
  sections: LessonsSectionRef[];
  opensAt: string;
  closesAt: string;
  durationMinutes: number;
  attemptsAllowed: number;
  shuffleQuestions: boolean;
  shuffleOptions: boolean;
  showAnswers: ShowAnswers;
  status: TestStatus;
  state: TestState;
  publishedAt: string | null;
  resultsReleasedAt: string | null;
  releasedByName: string | null;
  createdByName: string | null;
  questions: TestQuestionView[];
  totalMarks: number;
  attempts: number;
  version: number;
  canEdit: boolean;
};

export type TestRequest = {
  academicYearId?: string | null;
  classId: string;
  subjectId: string;
  sectionIds: string[];
  title: string;
  instructions: string | null;
  opensAt: string;
  closesAt: string;
  durationMinutes: number;
  attemptsAllowed: number;
  shuffleQuestions: boolean;
  shuffleOptions: boolean;
  showAnswers: ShowAnswers;
  version?: number | null;
};

export type TestQuestionRequest = { questionId: string; marks: number | null };
export type RandomQuestionsRequest = {
  topicId: string | null;
  difficulty: Difficulty | null;
  type: QuestionType | null;
  count: number;
};

export type AttemptRef = {
  id: string;
  attemptNo: number;
  startedAt: string;
  submittedAt: string | null;
  autoSubmitted: boolean;
  score: number | null;
  pendingMarking: number;
};

export type StudentResult = {
  studentId: string;
  studentName: string;
  admissionNo: string | null;
  rollNo: number | null;
  sectionLabel: string;
  status: StudentTestStatus;
  bestAttemptId: string | null;
  score: number | null;
  maxScore: number;
  percent: number | null;
  pendingMarking: number;
  attempts: AttemptRef[];
};

export type QuestionStat = {
  testQuestionId: string;
  position: number;
  text: string;
  type: QuestionType;
  difficulty: Difficulty;
  marks: number;
  answered: number;
  correct: number;
  averageMarks: number | null;
  percentCorrect: number | null;
  pendingMarking: number;
};

export type TestResults = {
  testId: string;
  title: string;
  state: TestState;
  released: boolean;
  resultsReleasedAt: string | null;
  totalMarks: number;
  studentCount: number;
  attempted: number;
  pendingMarking: number;
  averagePercent: number | null;
  highestPercent: number | null;
  lowestPercent: number | null;
  students: StudentResult[];
  questions: QuestionStat[];
  canEdit: boolean;
  /** why the results cannot be released yet; null when they can */
  releaseBlocker: string | null;
};

export type AnswerReview = {
  answerId: string;
  position: number;
  testQuestionId: string;
  type: QuestionType;
  text: string;
  maxMarks: number;
  options: OptionView[];
  selectedOptionIds: string[];
  response: string | null;
  answerText: string | null;
  explanation: string | null;
  marks: number | null;
  autoMarked: boolean;
  feedback: string | null;
  markedByName: string | null;
};

export type AttemptReview = {
  attemptId: string;
  testId: string;
  testTitle: string;
  studentId: string;
  studentName: string;
  sectionLabel: string;
  attemptNo: number;
  startedAt: string;
  deadlineAt: string;
  submittedAt: string | null;
  autoSubmitted: boolean;
  score: number | null;
  maxScore: number;
  pendingMarking: number;
  answers: AnswerReview[];
  canMark: boolean;
};

export type MarkRequest = { answerId: string; marks: number; feedback: string | null };

/* ------------------------------------------------------------------ students and parents */

export type StudyMaterial = {
  id: string;
  title: string;
  notes: string | null;
  linkUrl: string | null;
  subjectId: string;
  subjectName: string;
  topicTitle: string | null;
  publishOn: PlainDate;
  createdByName: string | null;
  files: FileRef[];
};

export type StudyTest = {
  id: string;
  title: string;
  subjectId: string;
  subjectName: string;
  opensAt: string;
  closesAt: string;
  durationMinutes: number;
  questions: number;
  totalMarks: number;
  attemptsAllowed: number;
  attemptsUsed: number;
  state: StudyTestState;
  inProgressAttemptId: string | null;
  canStart: boolean;
  submitted: boolean;
  released: boolean;
  /** null until the teacher releases the results */
  score: number | null;
  maxScore: number | null;
  percent: number | null;
  reviewAttemptId: string | null;
};

export type Study = {
  studentId: string;
  studentName: string;
  classLabel: string;
  today: PlainDate;
  material: StudyMaterial[];
  tests: StudyTest[];
};

export type AttemptSummary = {
  id: string;
  attemptNo: number;
  startedAt: string;
  submittedAt: string | null;
  autoSubmitted: boolean;
  score: number | null;
  maxScore: number | null;
};

export type TestIntro = { test: StudyTest; instructions: string | null; attempts: AttemptSummary[] };

export type LearnerOption = { id: string; text: string };

export type LearnerQuestion = {
  answerId: string;
  position: number;
  type: QuestionType;
  text: string;
  marks: number;
  options: LearnerOption[];
  selectedOptionIds: string[];
  response: string | null;
  answered: boolean;
};

export type LearnerReviewItem = {
  position: number;
  type: QuestionType;
  text: string;
  maxMarks: number;
  options: OptionView[];
  selectedOptionIds: string[];
  response: string | null;
  correctAnswer: string | null;
  explanation: string | null;
  marks: number | null;
  feedback: string | null;
};

export type LearnerAttempt = {
  id: string;
  testId: string;
  testTitle: string;
  subjectName: string;
  attemptNo: number;
  startedAt: string;
  deadlineAt: string;
  serverNow: string;
  /** counted by the server */
  secondsLeft: number;
  submitted: boolean;
  autoSubmitted: boolean;
  submittedAt: string | null;
  answered: number;
  total: number;
  questions: LearnerQuestion[];
  released: boolean;
  score: number | null;
  maxScore: number | null;
  review: LearnerReviewItem[];
};

export type AnswerRequest = { optionIds: string[] | null; text: string | null };
