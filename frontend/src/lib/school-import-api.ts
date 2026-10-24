import { sendForm } from "./files-api";
import { downloadXlsx } from "./reports-api";

/* Endpoint helpers for docs/api/phase-3-bulk-import.md (the school import). */

/** The API's limits: file size (the same 5 MB as every upload), students and staff per file. */
export const SCHOOL_IMPORT_MAX_BYTES = 5 * 1024 * 1024;
export const SCHOOL_IMPORT_MAX_STUDENTS = 5_000;
export const SCHOOL_IMPORT_MAX_STAFF = 500;

export type SchoolImportSheetKind = "STUDENTS" | "STAFF" | "IGNORED";
export type SchoolImportSectionStatus = "EXISTS" | "NEW_SECTION" | "NEW_CLASS" | "MISSING";

/** A problem; `row` is null for a problem with the sheet itself. */
export type SchoolImportProblem = { row: number | null; column: string | null; message: string };

export type SchoolImportSheet = {
  name: string;
  kind: SchoolImportSheetKind;
  className: string | null;
  sectionName: string | null;
  section: SchoolImportSectionStatus | null;
  rows: number;
  validRows: number;
  invalidRows: number;
  problems: SchoolImportProblem[];
  problemsNotShown: number;
  ignoredColumns: string[];
};

export type SchoolImportResult = {
  dryRun: boolean;
  committed: boolean;
  createMissing: boolean;
  ready: boolean;
  studentRows: number;
  staffRows: number;
  validRows: number;
  invalidRows: number;
  sheetProblems: number;
  studentsCreated: number;
  staffCreated: number;
  toCreate: { classes: string[]; sections: string[]; departments: string[] };
  sheets: SchoolImportSheet[];
};

export type WorkbookProblem = "type" | "size" | "empty";

/** Why the API would refuse this file, judged from its name and size; null when it looks fine. Pure. */
export function workbookProblem(file: { name: string; size: number }): WorkbookProblem | null {
  if (!file.name.toLowerCase().endsWith(".xlsx")) return "type";
  if (file.size === 0) return "empty";
  if (file.size > SCHOOL_IMPORT_MAX_BYTES) return "size";
  return null;
}

/** Problems to fix in a result: rows with a problem plus problems with whole sheets. */
export function problemCount(result: SchoolImportResult): number {
  return result.invalidRows + result.sheetProblems;
}

export const schoolImportApi = {
  downloadTemplate: () => downloadXlsx("/api/school-import/template.xlsx", "school-import-template.xlsx"),
  downloadSample: () => downloadXlsx("/api/school-import/sample.xlsx", "school-import-sample.xlsx"),
  /** Checks (dryRun) or imports a workbook. */
  upload: (file: File, dryRun: boolean, createMissing: boolean) => {
    const form = new FormData();
    form.append("file", file, file.name);
    return sendForm<SchoolImportResult>(
      `/api/school-import?dryRun=${dryRun}&createMissing=${createMissing}`,
      form,
    );
  },
};
