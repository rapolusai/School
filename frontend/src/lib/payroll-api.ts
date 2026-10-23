import { apiFetch, pathSegment } from "./api";
import { downloadXlsx, reportQuery } from "./reports-api";
import type {
  AdjustmentRequest,
  BankAdvice,
  ComponentFields,
  MyPayslipRow,
  NewComponentRequest,
  PaidRequest,
  PayProfileFull,
  PayProfileRequest,
  PayProfileView,
  PayrollDepartmentRef,
  PayrollRegister,
  PayrollSettingsRequest,
  PayrollSettingsView,
  PayrollStatutorySummary,
  PayrollYearToDate,
  PayslipView,
  PayStaffDetail,
  PayStaffList,
  PtSlabsRequest,
  PtSlabsView,
  RunDetail,
  RunList,
  SalaryComponentView,
  SalaryStructureRequest,
  StructureView,
  TdsOverrideRequest,
} from "./types";

/* Endpoint helpers for docs/api/phase-2-payroll.md. */

const id = pathSegment;

export type RegisterParams = { month?: string; departmentId?: string };
export type YearParams = { financialYear?: string };

/** The files of an approved month and the reports' CSV downloads (opened with the signed-in user's token). */
export const payrollPaths = {
  bankFile: (runId: string) => `/api/payroll/runs/${id(runId)}/bank-file.csv`,
  ecr: (runId: string) => `/api/payroll/runs/${id(runId)}/ecr.txt`,
  esi: (runId: string) => `/api/payroll/runs/${id(runId)}/esi.csv`,
  ptCsv: (runId: string) => `/api/payroll/runs/${id(runId)}/professional-tax.csv`,
  tdsCsv: (runId: string) => `/api/payroll/runs/${id(runId)}/tds.csv`,
  register: (p: RegisterParams) => `/api/payroll/reports/register${reportQuery(p)}`,
  registerCsv: (p: RegisterParams) => `/api/payroll/reports/register.csv${reportQuery(p)}`,
  yearToDate: (p: YearParams) => `/api/payroll/reports/year-to-date${reportQuery(p)}`,
  yearToDateCsv: (p: YearParams) => `/api/payroll/reports/year-to-date.csv${reportQuery(p)}`,
  statutory: (p: YearParams) => `/api/payroll/reports/statutory${reportQuery(p)}`,
  statutoryCsv: (p: YearParams) => `/api/payroll/reports/statutory.csv${reportQuery(p)}`,
};

/**
 * Downloads a payroll file (CSV or text) with the signed-in user's token and saves it under the server's file name.
 * The same authenticated download the reports' Excel button uses; the server sets the file type.
 */
export function downloadPayrollFile(path: string, fallbackName: string): Promise<string> {
  return downloadXlsx(path, fallbackName);
}

export const payrollApi = {
  settings: () => apiFetch<PayrollSettingsView>("/api/payroll/settings"),
  saveSettings: (body: PayrollSettingsRequest) =>
    apiFetch<PayrollSettingsView>("/api/payroll/settings", { method: "PUT", body }),
  ptSlabs: (state: string) => apiFetch<PtSlabsView>(`/api/payroll/settings/pt-slabs/${id(state)}`),
  savePtSlabs: (state: string, body: PtSlabsRequest) =>
    apiFetch<PtSlabsView>(`/api/payroll/settings/pt-slabs/${id(state)}`, { method: "PUT", body }),
  resetPtSlabs: (state: string) =>
    apiFetch<PtSlabsView>(`/api/payroll/settings/pt-slabs/${id(state)}`, { method: "DELETE" }),

  components: () => apiFetch<SalaryComponentView[]>("/api/payroll/components"),
  addStandardComponents: () => apiFetch<SalaryComponentView[]>("/api/payroll/components/standard", { method: "POST" }),
  createComponent: (body: NewComponentRequest) =>
    apiFetch<SalaryComponentView>("/api/payroll/components", { method: "POST", body }),
  updateComponent: (componentId: string, body: ComponentFields) =>
    apiFetch<SalaryComponentView>(`/api/payroll/components/${id(componentId)}`, { method: "PUT", body }),

  staff: () => apiFetch<PayStaffList>("/api/payroll/staff"),
  person: (userId: string) => apiFetch<PayStaffDetail>(`/api/payroll/staff/${id(userId)}`),
  fullProfile: (userId: string) => apiFetch<PayProfileFull>(`/api/payroll/staff/${id(userId)}/pay-profile/full`),
  saveProfile: (userId: string, body: PayProfileRequest) =>
    apiFetch<PayProfileView>(`/api/payroll/staff/${id(userId)}/pay-profile`, { method: "PUT", body }),
  createStructure: (userId: string, body: SalaryStructureRequest) =>
    apiFetch<StructureView>(`/api/payroll/staff/${id(userId)}/structures`, { method: "POST", body }),
  deleteStructure: (structureId: string) =>
    apiFetch<void>(`/api/payroll/structures/${id(structureId)}`, { method: "DELETE" }),

  runs: () => apiFetch<RunList>("/api/payroll/runs"),
  createRun: (month: string) => apiFetch<RunDetail>("/api/payroll/runs", { method: "POST", body: { month } }),
  run: (runId: string) => apiFetch<RunDetail>(`/api/payroll/runs/${id(runId)}`),
  recalculate: (runId: string) => apiFetch<RunDetail>(`/api/payroll/runs/${id(runId)}/recalculate`, { method: "POST" }),
  deleteRun: (runId: string) => apiFetch<void>(`/api/payroll/runs/${id(runId)}`, { method: "DELETE" }),
  addAdjustment: (runId: string, body: AdjustmentRequest) =>
    apiFetch<RunDetail>(`/api/payroll/runs/${id(runId)}/adjustments`, { method: "POST", body }),
  removeAdjustment: (runId: string, adjustmentId: string) =>
    apiFetch<RunDetail>(`/api/payroll/runs/${id(runId)}/adjustments/${id(adjustmentId)}`, { method: "DELETE" }),
  overrideTds: (runId: string, payslipId: string, body: TdsOverrideRequest) =>
    apiFetch<RunDetail>(`/api/payroll/runs/${id(runId)}/payslips/${id(payslipId)}/tds`, { method: "PUT", body }),
  clearTdsOverride: (runId: string, payslipId: string) =>
    apiFetch<RunDetail>(`/api/payroll/runs/${id(runId)}/payslips/${id(payslipId)}/tds`, { method: "DELETE" }),
  approve: (runId: string) => apiFetch<RunDetail>(`/api/payroll/runs/${id(runId)}/approve`, { method: "POST" }),
  markPaid: (runId: string, body: PaidRequest) =>
    apiFetch<RunDetail>(`/api/payroll/runs/${id(runId)}/paid`, { method: "POST", body }),
  runPayslips: (runId: string) => apiFetch<PayslipView[]>(`/api/payroll/runs/${id(runId)}/payslips`),
  payslip: (payslipId: string) => apiFetch<PayslipView>(`/api/payroll/payslips/${id(payslipId)}`),
  bankAdvice: (runId: string) => apiFetch<BankAdvice>(`/api/payroll/runs/${id(runId)}/bank-advice`),

  myPayslips: () => apiFetch<MyPayslipRow[]>("/api/me/payslips"),
  myPayslip: (payslipId: string) => apiFetch<PayslipView>(`/api/me/payslips/${id(payslipId)}`),

  register: (p: RegisterParams) => apiFetch<PayrollRegister>(payrollPaths.register(p)),
  yearToDate: (p: YearParams) => apiFetch<PayrollYearToDate>(payrollPaths.yearToDate(p)),
  statutory: (p: YearParams) => apiFetch<PayrollStatutorySummary>(payrollPaths.statutory(p)),
  departments: () => apiFetch<PayrollDepartmentRef[]>("/api/payroll/reports/departments"),
};
