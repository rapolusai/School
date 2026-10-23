/** Types mirroring docs/api/phase-3-govreport.md. Money is in paise; plain dates are "YYYY-MM-DD". */

import type { Gender } from "./school";

export const CODE_LIST_TYPES = [
  "SOCIAL_CATEGORY",
  "RELIGION",
  "MOTHER_TONGUE",
  "CWSN_TYPE",
  "SCHOOL_CATEGORY",
  "MANAGEMENT",
  "RTE_INCOME_LIMIT",
  "SCHEME",
  "RTE_DOCUMENT",
] as const;
export type CodeListType = (typeof CODE_LIST_TYPES)[number];

export type UpdatedVia = "OFFICE" | "FAMILY" | "IMPORT";
export type IntakeStatus = "OPEN" | "LOTTERY_DONE" | "CLOSED";
export type Eligibility = "PENDING" | "ELIGIBLE" | "INELIGIBLE";
export type AllotmentOutcome = "ALLOTTED" | "WAITLISTED";
export const SCHEME_STATUSES = [
  "APPLIED",
  "APPROVED",
  "RECEIVED",
  "REJECTED",
] as const;
export type SchemeStatus = (typeof SCHEME_STATUSES)[number];
export type ExportKind =
  | "UDISE_CSV"
  | "UDISE_XLSX"
  | "OASIS_XLSX"
  | "CBSE_REGISTRATION_XLSX"
  | "SCHEME_CSV";

export type CodeView = {
  id: string;
  list: CodeListType;
  code: string;
  label: string;
  value: string | null;
  stateCode: string | null;
  effectiveFrom: string;
  effectiveTo: string | null;
  verify: boolean;
  active: boolean;
  displayOrder: number;
  inForce: boolean;
};

export type CodeRequest = {
  label: string;
  value?: string | null;
  stateCode?: string | null;
  effectiveFrom: string;
  effectiveTo?: string | null;
  verify: boolean;
  active: boolean;
  displayOrder: number;
};

export type NewCodeRequest = Omit<CodeRequest, "verify" | "active"> & {
  list: CodeListType;
  code: string;
};

export type TeacherCounts = {
  total: number;
  trained: number;
  postGraduate: number;
  graduate: number;
  other: number;
  profileIncomplete: number;
};

export type Completeness = {
  filled: number;
  total: number;
  percent: number;
  missing: string[];
};

/** The editable fields of the school's government profile. */
export type ProfileFields = {
  udiseCode: string | null;
  board: string | null;
  affiliationNo: string | null;
  affiliationValidUntil: string | null;
  schoolCategoryCode: string | null;
  managementCode: string | null;
  diseBlockCode: string | null;
  diseDistrictCode: string | null;
  stateCode: string | null;
  recognitionAuthority: string | null;
  recognitionNo: string | null;
  recognitionValidUntil: string | null;
  lowestClass: string | null;
  highestClass: string | null;
  mediumOfInstruction: string | null;
  classrooms: number | null;
  otherRooms: number | null;
  toiletsBoys: number | null;
  toiletsGirls: number | null;
  toiletsCwsn: number | null;
  drinkingWater: boolean | null;
  drinkingWaterSource: string | null;
  electricity: boolean | null;
  library: boolean | null;
  libraryBooks: number | null;
  playground: boolean | null;
  computers: number | null;
  internet: boolean | null;
  ramps: boolean | null;
  boundaryWall: boolean | null;
};

export type GovProfile = ProfileFields & {
  saved: boolean;
  academicYearId: string;
  academicYearName: string;
  schoolName: string | null;
  teachers: TeacherCounts;
  completeness: Completeness;
  updatedAt: string | null;
};

export type StudentFields = {
  apaarId: string | null;
  apaarLocked?: boolean | null;
  pen: string | null;
  socialCategory: string | null;
  religion: string | null;
  motherTongue: string | null;
  bpl: boolean | null;
  cwsnType: string | null;
  rteAdmitted: boolean | null;
  previousSchoolUdise: string | null;
};

export type StudentFieldsRow = StudentFields & {
  studentId: string;
  fullName: string;
  admissionNo: string;
  rollNo: number | null;
  gender: Gender | null;
  dateOfBirth: string | null;
  className: string | null;
  sectionName: string | null;
  apaarLocked: boolean;
  missing: string[];
  updatedVia: UpdatedVia | null;
  updatedByName: string | null;
  updatedAt: string | null;
};

export type StudentFieldsEntry = StudentFields & { studentId: string };

export type SectionGrid = {
  sectionId: string;
  sectionLabel: string;
  academicYearId: string;
  students: number;
  complete: number;
  rows: StudentFieldsRow[];
};

export type BulkResult = { saved: number; rows: StudentFieldsRow[] };
export type GovImportRowError = {
  row: number;
  column: string;
  message: string;
};
export type GovImportResult = {
  dryRun: boolean;
  committed: boolean;
  totalRows: number;
  validRows: number;
  invalidRows: number;
  saved: number;
  errors: GovImportRowError[];
};

export type SectionCompleteness = {
  sectionId: string;
  label: string;
  students: number;
  complete: number;
  percent: number;
  missingByField: Record<string, number>;
};

export type CompletenessReport = {
  academicYearId: string;
  academicYearName: string;
  students: number;
  complete: number;
  percent: number;
  rows: SectionCompleteness[];
};

export type FamilyFields = {
  studentId: string;
  fullName: string;
  className: string | null;
  sectionName: string | null;
  motherTongue: string | null;
  apaarId: string | null;
  apaarEditable: boolean;
};

export type FamilyGovView = {
  children: FamilyFields[];
  motherTongues: CodeView[];
};
export type FamilyFieldsRequest = {
  motherTongue?: string | null;
  apaarId?: string | null;
};

export type ExportCell = string | number | boolean | null;
export type ExportTable = {
  key: string;
  title: string;
  columns: string[];
  rows: ExportCell[][];
};
export type ExportPreview = {
  kind: ExportKind;
  academicYearId: string;
  academicYearName: string;
  asOf: string;
  verify: boolean;
  notes: string[];
  tables: ExportTable[];
};

export type RegistrationClass = { id: string; name: string };

export type IntakeView = {
  id: string;
  academicYearId: string;
  academicYearName: string | null;
  entryClassId: string;
  className: string | null;
  totalSeats: number;
  rteSeats: number;
  stateCode: string | null;
  incomeLimitPaise: number;
  distanceBand1Km: number;
  distanceBand2Km: number;
  distanceBand3Km: number;
  documents: string[];
  applicationsCloseOn: string | null;
  status: IntakeStatus;
  currentRunId: string | null;
  applicants: number;
  eligible: number;
  runs: number;
  createdByName: string | null;
  createdAt: string;
};

export type IntakeRequest = {
  academicYearId: string;
  entryClassId: string;
  totalSeats: number;
  rteSeats?: number | null;
  stateCode?: string | null;
  incomeLimitPaise?: number | null;
  distanceBand1Km?: number | null;
  distanceBand2Km?: number | null;
  distanceBand3Km?: number | null;
  documents?: string[] | null;
  applicationsCloseOn?: string | null;
};

export type RteApplicationView = {
  id: string;
  applicationId: string;
  childName: string;
  dateOfBirth: string | null;
  gender: Gender | null;
  guardianName: string | null;
  guardianPhone: string | null;
  stage: string | null;
  categoryCode: string | null;
  annualIncomePaise: number | null;
  incomeDeclared: boolean;
  distanceKm: number | null;
  distanceBand: number;
  documents: Record<string, boolean>;
  eligibility: Eligibility;
  ineligibleReason: string | null;
  screenedByName: string | null;
  screenedAt: string | null;
  withdrawn: boolean;
  createdAt: string;
};

export type ScreeningRequest = {
  categoryCode?: string | null;
  annualIncomePaise?: number | null;
  incomeDeclared: boolean;
  distanceKm?: number | null;
  documents?: Record<string, boolean>;
  eligibility: Eligibility;
  ineligibleReason?: string | null;
};

export type RunSummary = {
  id: string;
  runNo: number;
  seed: number;
  seats: number;
  applicants: number;
  witnessName: string;
  reason: string | null;
  ranByName: string | null;
  ranAt: string;
  current: boolean;
};

export type IntakeDetail = {
  intake: IntakeView;
  applications: RteApplicationView[];
  runs: RunSummary[];
};

export type RteAllotmentView = {
  position: number;
  outcome: AllotmentOutcome;
  waitlistNo: number | null;
  distanceBand: number;
  application: RteApplicationView;
};

export type LotteryRunDetail = {
  run: RunSummary;
  intake: IntakeView;
  schoolName: string | null;
  allotments: RteAllotmentView[];
};
export type LotteryRequest = {
  witnessName: string;
  reason?: string | null;
  seed?: number | null;
};
export type ApplicationPick = {
  applicationId: string;
  childName: string;
  className: string | null;
  stage: string;
  inIntake: boolean;
};

export type SchemeView = {
  id: string;
  studentId: string;
  fullName: string;
  admissionNo: string | null;
  className: string | null;
  sectionName: string | null;
  academicYearId: string;
  schemeCode: string;
  schemeLabel: string | null;
  status: SchemeStatus;
  amountPaise: number | null;
  referenceNo: string | null;
  appliedOn: string | null;
  notes: string | null;
  updatedAt: string | null;
};

export type GovSchemeRequest = {
  studentId: string;
  academicYearId: string;
  schemeCode: string;
  status: SchemeStatus;
  amountPaise?: number | null;
  referenceNo?: string | null;
  appliedOn?: string | null;
  notes?: string | null;
};

export type EnrolmentRow = {
  classId: string | null;
  className: string;
  boys: number;
  girls: number;
  other: number;
  total: number;
  byCategory: Record<string, number>;
};

export type EnrolmentReport = {
  academicYearId: string;
  academicYearName: string;
  asOf: string;
  categories: string[];
  rows: EnrolmentRow[];
  totals: EnrolmentRow;
};

export type RteReport = {
  academicYearId: string;
  academicYearName: string;
  seats: number;
  allotted: number;
  waitlisted: number;
  intakes: IntakeView[];
};

export type SchemeTotal = {
  schemeCode: string;
  schemeLabel: string | null;
  students: number;
  amountPaise: number;
  byStatus: Record<SchemeStatus, number>;
};

export type SchemeReport = {
  academicYearId: string;
  academicYearName: string;
  students: number;
  amountPaise: number;
  rows: SchemeTotal[];
};
