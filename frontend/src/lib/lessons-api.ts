import { apiFetch } from "./api";
import { sendForm } from "./files-api";
import type {
  AnswerRequest,
  AttemptReview,
  CoverageDetail,
  CoverageQuery,
  CoverageReport,
  CopyResult,
  LearnerAttempt,
  LessonsOptions,
  LessonsSettings,
  MarkRequest,
  MaterialQuery,
  MaterialRequest,
  MaterialView,
  PlanDetail,
  PlanQuery,
  PlanRequest,
  PlanRow,
  QuestionImportResult,
  QuestionQuery,
  QuestionRequest,
  QuestionView,
  RandomQuestionsRequest,
  Study,
  SyllabusCopyRequest,
  SyllabusDetail,
  SyllabusImportResult,
  SyllabusRequest,
  SyllabusRow,
  TestDetail,
  TestIntro,
  TestQuestionRequest,
  TestRequest,
  TestResults,
  TestRow,
  TopicOption,
} from "./types";

/* Endpoint helpers for docs/api/phase-2-lessons.md. */

const id = (value: string) => encodeURIComponent(value);

/** "?a=1&b=x" from the set values only (empty strings, null, undefined and false are left out). Exported for tests. */
export function lessonsQuery(params: Record<string, string | number | boolean | null | undefined>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === "" || value === false) continue;
    search.set(key, String(value));
  }
  const text = search.toString();
  return text ? `?${text}` : "";
}

const test = (testId: string) => `/api/lessons/tests/${id(testId)}`;
const plan = (planId: string) => `/api/lessons/plans/${id(planId)}`;

export const lessonsPaths = {
  coverageCsv: (query: CoverageQuery) => `/api/lessons/coverage.csv${lessonsQuery(query)}`,
};

export const lessonsApi = {
  /* settings and pickers */
  settings: () => apiFetch<LessonsSettings>("/api/lessons/settings"),
  saveSettings: (body: LessonsSettings) => apiFetch<LessonsSettings>("/api/lessons/settings", { method: "PUT", body }),
  options: (yearId?: string | null) => apiFetch<LessonsOptions>(`/api/lessons/options${lessonsQuery({ yearId })}`),
  topics: (classId: string, subjectId: string, yearId?: string | null) =>
    apiFetch<TopicOption[]>(`/api/lessons/topics${lessonsQuery({ yearId, classId, subjectId })}`),

  /* syllabus */
  syllabi: (yearId?: string | null) => apiFetch<SyllabusRow[]>(`/api/lessons/syllabi${lessonsQuery({ yearId })}`),
  syllabus: (syllabusId: string) => apiFetch<SyllabusDetail>(`/api/lessons/syllabi/${id(syllabusId)}`),
  createSyllabus: (body: SyllabusRequest) => apiFetch<SyllabusDetail>("/api/lessons/syllabi", { method: "POST", body }),
  updateSyllabus: (syllabusId: string, body: SyllabusRequest) =>
    apiFetch<SyllabusDetail>(`/api/lessons/syllabi/${id(syllabusId)}`, { method: "PUT", body }),
  deleteSyllabus: (syllabusId: string) => apiFetch<void>(`/api/lessons/syllabi/${id(syllabusId)}`, { method: "DELETE" }),
  copySyllabi: (body: SyllabusCopyRequest) => apiFetch<CopyResult>("/api/lessons/syllabi/copy", { method: "POST", body }),
  importSyllabi: (csv: string, dryRun: boolean, yearId?: string | null) =>
    apiFetch<SyllabusImportResult>(`/api/lessons/syllabi/import${lessonsQuery({ dryRun: String(dryRun), yearId })}`, {
      method: "POST",
      body: { csv },
    }),

  /* lesson plans */
  plans: (query: PlanQuery = {}) => apiFetch<PlanRow[]>(`/api/lessons/plans${lessonsQuery(query)}`),
  queue: (yearId?: string | null) => apiFetch<PlanRow[]>(`/api/lessons/plans/queue${lessonsQuery({ yearId })}`),
  plan: (planId: string) => apiFetch<PlanDetail>(plan(planId)),
  createPlan: (body: PlanRequest) => apiFetch<PlanDetail>("/api/lessons/plans", { method: "POST", body }),
  updatePlan: (planId: string, body: PlanRequest) => apiFetch<PlanDetail>(plan(planId), { method: "PUT", body }),
  deletePlan: (planId: string) => apiFetch<void>(plan(planId), { method: "DELETE" }),
  submitPlan: (planId: string) => apiFetch<PlanDetail>(`${plan(planId)}/submit`, { method: "POST" }),
  approvePlan: (planId: string, comment: string | null) =>
    apiFetch<PlanDetail>(`${plan(planId)}/approve`, { method: "POST", body: { comment } }),
  returnPlan: (planId: string, comment: string) =>
    apiFetch<PlanDetail>(`${plan(planId)}/return`, { method: "POST", body: { comment } }),
  markTaught: (planId: string, taughtOn: string) =>
    apiFetch<PlanDetail>(`${plan(planId)}/taught`, { method: "POST", body: { taughtOn } }),
  markUntaught: (planId: string) => apiFetch<PlanDetail>(`${plan(planId)}/untaught`, { method: "POST" }),

  /* coverage */
  coverage: (query: CoverageQuery = {}) => apiFetch<CoverageReport>(`/api/lessons/coverage${lessonsQuery(query)}`),
  coverageDetail: (sectionId: string, subjectId: string, yearId?: string | null) =>
    apiFetch<CoverageDetail>(`/api/lessons/coverage/detail${lessonsQuery({ yearId, sectionId, subjectId })}`),

  /* study material */
  material: (query: MaterialQuery = {}) => apiFetch<MaterialView[]>(`/api/lessons/material${lessonsQuery(query)}`),
  createMaterial: (body: MaterialRequest) => apiFetch<MaterialView>("/api/lessons/material", { method: "POST", body }),
  updateMaterial: (materialId: string, body: MaterialRequest) =>
    apiFetch<MaterialView>(`/api/lessons/material/${id(materialId)}`, { method: "PUT", body }),
  deleteMaterial: (materialId: string) => apiFetch<void>(`/api/lessons/material/${id(materialId)}`, { method: "DELETE" }),
  addMaterialFile: (materialId: string, file: File) => {
    const form = new FormData();
    form.append("file", file, file.name);
    return sendForm<MaterialView>(`/api/lessons/material/${id(materialId)}/files`, form);
  },
  removeMaterialFile: (materialId: string, fileId: string) =>
    apiFetch<MaterialView>(`/api/lessons/material/${id(materialId)}/files/${id(fileId)}`, { method: "DELETE" }),

  /* question bank */
  questions: (query: QuestionQuery = {}) => apiFetch<QuestionView[]>(`/api/lessons/questions${lessonsQuery(query)}`),
  createQuestion: (body: QuestionRequest) => apiFetch<QuestionView>("/api/lessons/questions", { method: "POST", body }),
  updateQuestion: (questionId: string, body: QuestionRequest) =>
    apiFetch<QuestionView>(`/api/lessons/questions/${id(questionId)}`, { method: "PUT", body }),
  deleteQuestion: (questionId: string) => apiFetch<void>(`/api/lessons/questions/${id(questionId)}`, { method: "DELETE" }),
  importQuestions: (csv: string, dryRun: boolean) =>
    apiFetch<QuestionImportResult>(`/api/lessons/questions/import${lessonsQuery({ dryRun: String(dryRun) })}`, {
      method: "POST",
      body: { csv },
    }),

  /* online tests */
  tests: (yearId?: string | null, mine = false) => apiFetch<TestRow[]>(`/api/lessons/tests${lessonsQuery({ yearId, mine })}`),
  test: (testId: string) => apiFetch<TestDetail>(test(testId)),
  createTest: (body: TestRequest) => apiFetch<TestDetail>("/api/lessons/tests", { method: "POST", body }),
  updateTest: (testId: string, body: TestRequest) => apiFetch<TestDetail>(test(testId), { method: "PUT", body }),
  deleteTest: (testId: string) => apiFetch<void>(test(testId), { method: "DELETE" }),
  setTestQuestions: (testId: string, questions: TestQuestionRequest[]) =>
    apiFetch<TestDetail>(`${test(testId)}/questions`, { method: "PUT", body: { questions } }),
  addRandomQuestions: (testId: string, body: RandomQuestionsRequest) =>
    apiFetch<TestDetail>(`${test(testId)}/questions/random`, { method: "POST", body }),
  publishTest: (testId: string) => apiFetch<TestDetail>(`${test(testId)}/publish`, { method: "POST" }),
  unpublishTest: (testId: string) => apiFetch<TestDetail>(`${test(testId)}/unpublish`, { method: "POST" }),
  closeTest: (testId: string) => apiFetch<TestDetail>(`${test(testId)}/close`, { method: "POST" }),
  results: (testId: string) => apiFetch<TestResults>(`${test(testId)}/results`),
  attempt: (testId: string, attemptId: string) => apiFetch<AttemptReview>(`${test(testId)}/attempts/${id(attemptId)}`),
  mark: (testId: string, attemptId: string, marks: MarkRequest[]) =>
    apiFetch<AttemptReview>(`${test(testId)}/attempts/${id(attemptId)}/marks`, { method: "PUT", body: { marks } }),
  release: (testId: string) => apiFetch<TestResults>(`${test(testId)}/release`, { method: "POST" }),

  /* students and parents */
  myStudy: () => apiFetch<Study>("/api/me/study"),
  childStudy: (studentId: string) => apiFetch<Study>(`/api/me/children/${id(studentId)}/study`),
  myTest: (testId: string) => apiFetch<TestIntro>(`/api/me/tests/${id(testId)}`),
  startAttempt: (testId: string) => apiFetch<LearnerAttempt>(`/api/me/tests/${id(testId)}/attempts`, { method: "POST" }),
  myAttempt: (attemptId: string) => apiFetch<LearnerAttempt>(`/api/me/attempts/${id(attemptId)}`),
  saveAnswer: (attemptId: string, answerId: string, body: AnswerRequest) =>
    apiFetch<LearnerAttempt>(`/api/me/attempts/${id(attemptId)}/answers/${id(answerId)}`, { method: "PUT", body }),
  submitAttempt: (attemptId: string) => apiFetch<LearnerAttempt>(`/api/me/attempts/${id(attemptId)}/submit`, { method: "POST" }),
};
