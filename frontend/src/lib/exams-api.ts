import { apiFetch, pathSegment } from "./api";
import type {
  CardPeriod,
  ClassSchemeRequest,
  ClassSchemes,
  ExamAnalysis,
  ExamClassDetail,
  ExamRequest,
  ExamSettings,
  ExamSettingsRequest,
  ExamStructure,
  FamilyResults,
  GradingScheme,
  HallTickets,
  MarkSheet,
  MarkSheetRequest,
  MarksOverview,
  PaperRequest,
  ReportCards,
  SchemeRequest,
  SectionResults,
  TermRecordRequest,
  TermRecords,
  TermRequest,
  WeightsRequest,
} from "./types";

/* Endpoint helpers for docs/api/phase-2-exams.md. */

const id = pathSegment;

/** "?yearId=…" when a year is chosen, otherwise "" (the API uses the current year). Exported for tests. */
export function yearQuery(yearId: string | null | undefined): string {
  return yearId ? `?yearId=${id(yearId)}` : "";
}

/** "termId=…" or "yearId=…" for a report card. Exported for tests. */
export function periodQuery(period: CardPeriod): string {
  return "termId" in period ? `termId=${id(period.termId)}` : `yearId=${id(period.yearId)}`;
}

const examClass = (examId: string, classId: string) => `/api/exams/${id(examId)}/classes/${id(classId)}`;
const sheet = (paperId: string, sectionId: string) =>
  `/api/exams/papers/${id(paperId)}/sections/${id(sectionId)}/marks`;

export const examPaths = {
  analysisCsv: (examId: string, classId: string) => `${examClass(examId, classId)}/analysis.csv`,
};

export const examsApi = {
  /* grading */
  schemes: () => apiFetch<GradingScheme[]>("/api/exams/grading-schemes"),
  createScheme: (body: SchemeRequest) => apiFetch<GradingScheme>("/api/exams/grading-schemes", { method: "POST", body }),
  updateScheme: (schemeId: string, body: SchemeRequest) =>
    apiFetch<GradingScheme>(`/api/exams/grading-schemes/${id(schemeId)}`, { method: "PUT", body }),
  deleteScheme: (schemeId: string) => apiFetch<void>(`/api/exams/grading-schemes/${id(schemeId)}`, { method: "DELETE" }),
  restoreSchemes: () => apiFetch<GradingScheme[]>("/api/exams/grading-schemes/restore", { method: "POST" }),
  settings: () => apiFetch<ExamSettings>("/api/exams/settings"),
  saveSettings: (body: ExamSettingsRequest) => apiFetch<ExamSettings>("/api/exams/settings", { method: "PUT", body }),
  classSchemes: (yearId?: string | null) => apiFetch<ClassSchemes>(`/api/exams/class-schemes${yearQuery(yearId)}`),
  assignSchemes: (yearId: string | null, body: ClassSchemeRequest) =>
    apiFetch<ClassSchemes>(`/api/exams/class-schemes${yearQuery(yearId)}`, { method: "PUT", body }),

  /* terms and exams */
  structure: (yearId?: string | null) => apiFetch<ExamStructure>(`/api/exams/structure${yearQuery(yearId)}`),
  createTerm: (body: TermRequest) => apiFetch<ExamStructure>("/api/exams/terms", { method: "POST", body }),
  updateTerm: (termId: string, body: TermRequest) =>
    apiFetch<ExamStructure>(`/api/exams/terms/${id(termId)}`, { method: "PUT", body }),
  deleteTerm: (termId: string) => apiFetch<ExamStructure>(`/api/exams/terms/${id(termId)}`, { method: "DELETE" }),
  termWeights: (yearId: string, body: WeightsRequest) =>
    apiFetch<ExamStructure>(`/api/exams/years/${id(yearId)}/term-weights`, { method: "PUT", body }),
  examWeights: (termId: string, body: WeightsRequest) =>
    apiFetch<ExamStructure>(`/api/exams/terms/${id(termId)}/exam-weights`, { method: "PUT", body }),
  createExam: (body: ExamRequest) => apiFetch<ExamStructure>("/api/exams", { method: "POST", body }),
  updateExam: (examId: string, body: ExamRequest) =>
    apiFetch<ExamStructure>(`/api/exams/${id(examId)}`, { method: "PUT", body }),
  deleteExam: (examId: string) => apiFetch<ExamStructure>(`/api/exams/${id(examId)}`, { method: "DELETE" }),

  /* papers, date sheet and hall tickets */
  examClass: (examId: string, classId: string) => apiFetch<ExamClassDetail>(examClass(examId, classId)),
  savePapers: (examId: string, classId: string, papers: PaperRequest[]) =>
    apiFetch<ExamClassDetail>(`${examClass(examId, classId)}/papers`, { method: "PUT", body: { papers } }),
  publishDateSheet: (examId: string, classId: string) =>
    apiFetch<ExamClassDetail>(`${examClass(examId, classId)}/date-sheet/publish`, { method: "POST" }),
  withdrawDateSheet: (examId: string, classId: string) =>
    apiFetch<ExamClassDetail>(`${examClass(examId, classId)}/date-sheet/withdraw`, { method: "POST" }),
  hallTickets: (examId: string, classId: string, sectionId?: string | null) =>
    apiFetch<HallTickets>(
      `${examClass(examId, classId)}/hall-tickets${sectionId ? `?sectionId=${id(sectionId)}` : ""}`,
    ),

  /* marks */
  marks: (yearId?: string | null) => apiFetch<MarksOverview>(`/api/exams/marks${yearQuery(yearId)}`),
  sheet: (paperId: string, sectionId: string) => apiFetch<MarkSheet>(sheet(paperId, sectionId)),
  saveSheet: (paperId: string, sectionId: string, body: MarkSheetRequest) =>
    apiFetch<MarkSheet>(sheet(paperId, sectionId), { method: "PUT", body }),
  submitSheet: (paperId: string, sectionId: string, version: number | null) =>
    apiFetch<MarkSheet>(`${sheet(paperId, sectionId)}/submit`, { method: "POST", body: { version } }),
  unlockSheet: (paperId: string, sectionId: string, reason: string) =>
    apiFetch<MarkSheet>(`${sheet(paperId, sectionId)}/unlock`, { method: "POST", body: { reason } }),

  /* results */
  sectionResults: (examId: string, sectionId: string) =>
    apiFetch<SectionResults>(`/api/exams/${id(examId)}/sections/${id(sectionId)}/results`),
  publishResults: (examId: string, classId: string) =>
    apiFetch<ExamClassDetail>(`${examClass(examId, classId)}/results/publish`, { method: "POST" }),
  withdrawResults: (examId: string, classId: string, reason: string) =>
    apiFetch<ExamClassDetail>(`${examClass(examId, classId)}/results/withdraw`, { method: "POST", body: { reason } }),
  records: (termId: string, sectionId: string) =>
    apiFetch<TermRecords>(`/api/exams/terms/${id(termId)}/sections/${id(sectionId)}/records`),
  saveRecords: (termId: string, sectionId: string, entries: TermRecordRequest[]) =>
    apiFetch<TermRecords>(`/api/exams/terms/${id(termId)}/sections/${id(sectionId)}/records`, {
      method: "PUT",
      body: { entries },
    }),
  reportCards: (sectionId: string, period: CardPeriod) =>
    apiFetch<ReportCards>(`/api/exams/report-cards?sectionId=${id(sectionId)}&${periodQuery(period)}`),
  studentCard: (studentId: string, period: CardPeriod) =>
    apiFetch<ReportCards>(`/api/exams/report-cards/students/${id(studentId)}?${periodQuery(period)}`),
  analysis: (examId: string, classId: string) => apiFetch<ExamAnalysis>(`${examClass(examId, classId)}/analysis`),

  /* families */
  myResults: (yearId?: string | null) => apiFetch<FamilyResults>(`/api/me/results${yearQuery(yearId)}`),
  myCard: (period: CardPeriod) => apiFetch<ReportCards>(`/api/me/report-card?${periodQuery(period)}`),
  childResults: (studentId: string, yearId?: string | null) =>
    apiFetch<FamilyResults>(`/api/me/children/${id(studentId)}/results${yearQuery(yearId)}`),
  childCard: (studentId: string, period: CardPeriod) =>
    apiFetch<ReportCards>(`/api/me/children/${id(studentId)}/report-card?${periodQuery(period)}`),
};
