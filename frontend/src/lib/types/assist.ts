/* Types mirroring docs/api/phase-3-assist.md (AI assistance: remark drafts, early warning, draft papers, writing help,
 * settings, usage and reports). Every draft is a simulated draft while the simulated provider is in use. */

import type { BloomLevel, Difficulty, QuestionType } from "./lessons";

export const ASSIST_FEATURES = ["REMARK", "EARLY_WARNING", "PAPER", "WRITING"] as const;
export type AssistFeature = (typeof ASSIST_FEATURES)[number];

export const REMARK_TONES = ["ENCOURAGING", "FORMAL"] as const;
export type RemarkTone = (typeof REMARK_TONES)[number];

export const RISK_LEVELS = ["HIGH", "MEDIUM", "LOW"] as const;
export type RiskLevel = (typeof RISK_LEVELS)[number];

export const WATCH_STATUSES = ["OPEN", "FOLLOWING", "RESOLVED"] as const;
export type WatchStatus = (typeof WATCH_STATUSES)[number];

export type AssistLanguage = "en" | "hi";

export type AssistWeights = {
  attendance30: number;
  attendance90: number;
  marksTrend: number;
  feeDues: number;
  homework: number;
  threshold: number;
};

export type AssistSettings = {
  enabled: boolean;
  remarksEnabled: boolean;
  earlyWarningEnabled: boolean;
  papersEnabled: boolean;
  writingEnabled: boolean;
  monthlyLimit: number;
  language: AssistLanguage;
  weights: AssistWeights;
  saved: boolean;
  updatedAt: string | null;
  provider: string;
  simulated: boolean;
  usedThisMonth: number;
  lastRunAt: string | null;
  lastRunStudents: number | null;
  lastRunListed: number | null;
};

export type AssistSettingsRequest = Pick<
  AssistSettings,
  "enabled" | "remarksEnabled" | "earlyWarningEnabled" | "papersEnabled" | "writingEnabled" | "monthlyLimit" | "language" | "weights"
>;

export type UsageRow = {
  id: string;
  at: string;
  userId: string | null;
  userName: string;
  feature: AssistFeature;
  provider: string;
  tokenEstimate: number;
  requestHash: string;
};

export type FeatureCount = { feature: AssistFeature; calls: number; tokens: number };

export type AssistUsage = { month: string; limit: number; used: number; tokens: number; byFeature: FeatureCount[]; rows: UsageRow[] };

export type AssistSubjectRef = { id: string; name: string };

export type RemarkDraft = {
  id: string;
  studentId: string;
  studentName: string;
  subjectId: string | null;
  subjectName: string | null;
  tone: RemarkTone;
  language: AssistLanguage;
  provider: string;
  simulated: boolean;
  draftText: string;
  acceptedText: string | null;
  status: "DRAFT" | "ACCEPTED";
  inputs: Record<string, string>;
  inputLines: string[];
  createdByName: string | null;
  acceptedByName: string | null;
  acceptedAt: string | null;
  updatedAt: string | null;
  version: number;
};

export type RemarkStudent = {
  studentId: string;
  fullName: string;
  rollNo: number | null;
  currentRemark: string | null;
  draft: RemarkDraft | null;
  subjectDrafts: RemarkDraft[];
};

export type RemarkSheet = {
  termId: string;
  termName: string;
  sectionId: string;
  sectionLabel: string;
  canAccept: boolean;
  language: AssistLanguage;
  subjects: AssistSubjectRef[];
  students: RemarkStudent[];
};

export type DraftRemarksRequest = {
  termId: string;
  sectionId: string;
  tone: RemarkTone;
  studentIds?: string[] | null;
  subjectId?: string | null;
};

export type RiskReason = { signal: string; points: number; value: number | null; detail: string };

export type WatchNote = { id: string; authorName: string; body: string; createdAt: string };

export type AssistPerson = { id: string; name: string };

export type WatchRow = {
  id: string;
  studentId: string;
  studentName: string;
  sectionId: string;
  classLabel: string;
  score: number;
  level: RiskLevel;
  reasons: RiskReason[];
  computedAt: string;
  status: WatchStatus;
  assignedToId: string | null;
  assignedToName: string | null;
  parentInformedAt: string | null;
  notes: number;
};

export type WatchDetail = { entry: WatchRow; notes: WatchNote[] };

export type WatchList = {
  lastRunAt: string | null;
  lastRunStudents: number | null;
  lastRunListed: number | null;
  high: number;
  medium: number;
  low: number;
  assignees: AssistPerson[];
  entries: WatchRow[];
};

export type RunResult = { at: string; students: number; listed: number };

export type BlueprintSection = { name: string; questions: number; marksEach: number };

export type Blueprint = {
  sections: BlueprintSection[];
  easyPercent: number;
  mediumPercent: number;
  hardPercent: number;
  bloomLevels: BloomLevel[];
  topicIds: string[];
};

export type PaperQuestion = {
  questionId: string;
  text: string;
  type: QuestionType;
  difficulty: Difficulty;
  bloomLevel: BloomLevel | null;
  marks: number;
  options: string[];
};

export type PaperSection = {
  name: string;
  instructions: string | null;
  marksEach: number;
  wanted: number;
  questions: PaperQuestion[];
};

export type PaperView = {
  id: string | null;
  classId: string;
  className: string;
  subjectId: string;
  subjectName: string;
  title: string;
  durationMinutes: number;
  totalMarks: number;
  instructions: string | null;
  blueprint: Blueprint;
  sections: PaperSection[];
  provider: string;
  simulated: boolean;
  warnings: string[];
  createdByName: string | null;
  updatedAt: string | null;
  version: number;
};

export type PaperRow = {
  id: string;
  title: string;
  className: string;
  subjectName: string;
  totalMarks: number;
  durationMinutes: number;
  createdByName: string | null;
  updatedAt: string | null;
};

export type PaperDraftRequest = { classId: string; subjectId: string; title: string; durationMinutes: number; blueprint: Blueprint };

export type PaperSaveRequest = PaperDraftRequest & { instructions: string | null; sections: PaperSection[]; version: number | null };

export type WritingResult = { text: string; provider: string; simulated: boolean; tokenEstimate: number };

export type AtRiskClassRow = { sectionId: string; classLabel: string; students: number; high: number; medium: number; low: number };

export type AtRiskReport = {
  computedAt: string | null;
  classId: string | null;
  total: number;
  high: number;
  medium: number;
  low: number;
  classes: AtRiskClassRow[];
  entries: WatchRow[];
};

export type AcceptedRow = { teacherName: string; month: string; drafted: number; accepted: number };

export type AcceptedReport = { from: string; to: string; drafted: number; accepted: number; rows: AcceptedRow[] };
