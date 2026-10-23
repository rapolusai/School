import { apiFetch, pathSegment } from "./api";
import { downloadXlsx, reportQuery, type ReportParams } from "./reports-api";
import type {
  ApplicationPick,
  BulkResult,
  CodeListType,
  CodeRequest,
  CodeView,
  CompletenessReport,
  EnrolmentReport,
  ExportPreview,
  FamilyFieldsRequest,
  FamilyGovView,
  GovProfile,
  GovImportResult,
  IntakeDetail,
  IntakeRequest,
  IntakeView,
  LotteryRequest,
  NewCodeRequest,
  ProfileFields,
  RegistrationClass,
  RteApplicationView,
  RteReport,
  LotteryRunDetail,
  SchemeReport,
  GovSchemeRequest,
  SchemeView,
  ScreeningRequest,
  SectionGrid,
  StudentFieldsEntry,
} from "./types";

/* Endpoint helpers for docs/api/phase-3-govreport.md. */

const id = pathSegment;
const q = (params: ReportParams) => reportQuery(params);

export type ExportParams = { yearId?: string; asOf?: string; classId?: string };

/** The JSON and file paths of the exports and reports. */
export const govPaths = {
  udisePreview: (p: ExportParams) =>
    `/api/govreport/exports/udise/preview${q(p)}`,
  udiseXlsx: (p: ExportParams) => `/api/govreport/exports/udise.xlsx${q(p)}`,
  udiseCsv: (table: string, p: ExportParams) =>
    `/api/govreport/exports/udise/${id(table)}.csv${q(p)}`,
  oasisPreview: (p: ExportParams) =>
    `/api/govreport/exports/oasis/preview${q(p)}`,
  oasisXlsx: (p: ExportParams) => `/api/govreport/exports/oasis.xlsx${q(p)}`,
  registrationPreview: (p: ExportParams) =>
    `/api/govreport/exports/cbse-registration/preview${q(p)}`,
  registrationXlsx: (p: ExportParams) =>
    `/api/govreport/exports/cbse-registration.xlsx${q(p)}`,
  template: () => "/api/govreport/students/template.csv",
  schemesCsv: (p: { yearId?: string; schemeCode?: string }) =>
    `/api/govreport/schemes.csv${q(p)}`,
  completeness: (p: { yearId?: string }) =>
    `/api/govreport/reports/completeness${q(p)}`,
  completenessCsv: (p: { yearId?: string }) =>
    `/api/govreport/reports/completeness.csv${q(p)}`,
  enrolment: (p: { yearId?: string; asOf?: string }) =>
    `/api/govreport/reports/enrolment${q(p)}`,
  enrolmentCsv: (p: { yearId?: string; asOf?: string }) =>
    `/api/govreport/reports/enrolment.csv${q(p)}`,
  rte: (p: { yearId?: string }) => `/api/govreport/reports/rte${q(p)}`,
  rteCsv: (p: { yearId?: string }) => `/api/govreport/reports/rte.csv${q(p)}`,
  schemes: (p: { yearId?: string }) => `/api/govreport/reports/schemes${q(p)}`,
  schemesReportCsv: (p: { yearId?: string }) =>
    `/api/govreport/reports/schemes.csv${q(p)}`,
};

export const govreportApi = {
  codes: (list?: CodeListType) =>
    apiFetch<CodeView[]>(`/api/govreport/code-lists${q({ list })}`),
  addCode: (body: NewCodeRequest) =>
    apiFetch<CodeView>("/api/govreport/code-lists", { method: "POST", body }),
  updateCode: (codeId: string, body: CodeRequest) =>
    apiFetch<CodeView>(`/api/govreport/code-lists/${id(codeId)}`, {
      method: "PUT",
      body,
    }),

  profile: (yearId?: string) =>
    apiFetch<GovProfile>(`/api/govreport/profile${q({ yearId })}`),
  saveProfile: (body: Partial<ProfileFields>, yearId?: string) =>
    apiFetch<GovProfile>(`/api/govreport/profile${q({ yearId })}`, {
      method: "PUT",
      body,
    }),

  grid: (sectionId: string, yearId?: string) =>
    apiFetch<SectionGrid>(`/api/govreport/students${q({ sectionId, yearId })}`),
  saveGrid: (entries: StudentFieldsEntry[]) =>
    apiFetch<BulkResult>("/api/govreport/students", {
      method: "PUT",
      body: { entries },
    }),
  importCsv: (csv: string, dryRun: boolean) =>
    apiFetch<GovImportResult>(
      `/api/govreport/students/import${q({ dryRun })}`,
      { method: "POST", body: { csv } },
    ),
  completeness: (yearId?: string) =>
    apiFetch<CompletenessReport>(govPaths.completeness({ yearId })),

  schemes: (yearId?: string, schemeCode?: string) =>
    apiFetch<SchemeView[]>(
      `/api/govreport/schemes${q({ yearId, schemeCode })}`,
    ),
  enrol: (body: GovSchemeRequest) =>
    apiFetch<SchemeView>("/api/govreport/schemes", { method: "POST", body }),
  updateScheme: (
    schemeId: string,
    body: Omit<GovSchemeRequest, "studentId" | "academicYearId" | "schemeCode">,
  ) =>
    apiFetch<SchemeView>(`/api/govreport/schemes/${id(schemeId)}`, {
      method: "PUT",
      body,
    }),
  removeScheme: (schemeId: string) =>
    apiFetch<void>(`/api/govreport/schemes/${id(schemeId)}`, {
      method: "DELETE",
    }),

  udise: (p: ExportParams) => apiFetch<ExportPreview>(govPaths.udisePreview(p)),
  oasis: (p: ExportParams) => apiFetch<ExportPreview>(govPaths.oasisPreview(p)),
  registrationClasses: () =>
    apiFetch<RegistrationClass[]>(
      "/api/govreport/exports/cbse-registration/classes",
    ),
  registration: (p: ExportParams) =>
    apiFetch<ExportPreview>(govPaths.registrationPreview(p)),
  download: (path: string, fallbackName: string) =>
    downloadXlsx(path, fallbackName),

  intakes: (yearId?: string) =>
    apiFetch<IntakeView[]>(`/api/govreport/rte/intakes${q({ yearId })}`),
  createIntake: (body: IntakeRequest) =>
    apiFetch<IntakeView>("/api/govreport/rte/intakes", {
      method: "POST",
      body,
    }),
  updateIntake: (intakeId: string, body: IntakeRequest) =>
    apiFetch<IntakeView>(`/api/govreport/rte/intakes/${id(intakeId)}`, {
      method: "PUT",
      body,
    }),
  closeIntake: (intakeId: string) =>
    apiFetch<IntakeView>(`/api/govreport/rte/intakes/${id(intakeId)}/close`, {
      method: "POST",
    }),
  intake: (intakeId: string) =>
    apiFetch<IntakeDetail>(`/api/govreport/rte/intakes/${id(intakeId)}`),
  candidates: (intakeId: string) =>
    apiFetch<ApplicationPick[]>(
      `/api/govreport/rte/intakes/${id(intakeId)}/candidates`,
    ),
  addApplication: (intakeId: string, applicationId: string) =>
    apiFetch<RteApplicationView>(
      `/api/govreport/rte/intakes/${id(intakeId)}/applications`,
      {
        method: "POST",
        body: { applicationId },
      },
    ),
  screen: (applicationId: string, body: ScreeningRequest) =>
    apiFetch<RteApplicationView>(
      `/api/govreport/rte/applications/${id(applicationId)}`,
      { method: "PUT", body },
    ),
  withdraw: (applicationId: string) =>
    apiFetch<RteApplicationView>(
      `/api/govreport/rte/applications/${id(applicationId)}/withdraw`,
      { method: "POST" },
    ),
  runLottery: (intakeId: string, body: LotteryRequest) =>
    apiFetch<LotteryRunDetail>(
      `/api/govreport/rte/intakes/${id(intakeId)}/lottery`,
      { method: "POST", body },
    ),
  run: (runId: string) =>
    apiFetch<LotteryRunDetail>(`/api/govreport/rte/runs/${id(runId)}`),

  enrolmentReport: (p: { yearId?: string; asOf?: string }) =>
    apiFetch<EnrolmentReport>(govPaths.enrolment(p)),
  rteReport: (p: { yearId?: string }) => apiFetch<RteReport>(govPaths.rte(p)),
  schemeReport: (p: { yearId?: string }) =>
    apiFetch<SchemeReport>(govPaths.schemes(p)),

  mine: () => apiFetch<FamilyGovView>("/api/me/govreport"),
  saveMine: (studentId: string, body: FamilyFieldsRequest) =>
    apiFetch<FamilyGovView>(`/api/me/govreport/children/${id(studentId)}`, {
      method: "PUT",
      body,
    }),
};
