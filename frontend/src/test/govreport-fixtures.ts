import type {
  CodeListType,
  CodeView,
  ExportPreview,
  IntakeDetail,
  IntakeView,
  LotteryRunDetail,
  RteApplicationView,
  SectionGrid,
  StudentFieldsRow,
} from "@/lib/types";

/* Fixtures for the government reporting views (docs/api/phase-3-govreport.md). */

export const GOV_READER = ["dashboard.view", "govreport.read"];
export const GOV_MANAGER = [
  ...GOV_READER,
  "govreport.manage",
  "academics.read",
];

export function code(
  list: CodeListType,
  codeValue: string,
  label: string,
): CodeView {
  return {
    id: `${list}-${codeValue}`,
    list,
    code: codeValue,
    label,
    value: null,
    stateCode: null,
    effectiveFrom: "2024-04-01",
    effectiveTo: null,
    verify: true,
    active: true,
    displayOrder: 0,
    inForce: true,
  };
}

export const CODES: Partial<Record<CodeListType, CodeView[]>> = {
  SOCIAL_CATEGORY: [
    code("SOCIAL_CATEGORY", "GEN", "General"),
    code("SOCIAL_CATEGORY", "SC", "Scheduled Caste"),
  ],
  RELIGION: [code("RELIGION", "HINDU", "Hindu")],
  MOTHER_TONGUE: [
    code("MOTHER_TONGUE", "TELUGU", "Telugu"),
    code("MOTHER_TONGUE", "HINDI", "Hindi"),
  ],
  CWSN_TYPE: [code("CWSN_TYPE", "LOW_VISION", "Low vision")],
  RTE_DOCUMENT: [code("RTE_DOCUMENT", "BIRTH_CERT", "Birth certificate")],
};

export function fieldsRow(
  overrides: Partial<StudentFieldsRow> = {},
): StudentFieldsRow {
  return {
    studentId: "s1",
    fullName: "Asha Rao",
    admissionNo: "GOV-1",
    rollNo: 1,
    gender: "FEMALE",
    dateOfBirth: "2019-05-01",
    className: "Class 1",
    sectionName: "A",
    apaarId: null,
    apaarLocked: false,
    pen: null,
    socialCategory: null,
    religion: null,
    motherTongue: null,
    bpl: null,
    cwsnType: null,
    rteAdmitted: null,
    previousSchoolUdise: null,
    missing: [
      "apaarId",
      "pen",
      "socialCategory",
      "religion",
      "motherTongue",
      "bpl",
      "rteAdmitted",
    ],
    updatedVia: null,
    updatedByName: null,
    updatedAt: null,
    ...overrides,
  };
}

export function sectionGrid(
  rows: StudentFieldsRow[] = [
    fieldsRow(),
    fieldsRow({ studentId: "s2", fullName: "Bala Iyer", admissionNo: "GOV-2" }),
  ],
): SectionGrid {
  return {
    sectionId: "sec1",
    sectionLabel: "Class 1 A",
    academicYearId: "y1",
    students: rows.length,
    complete: rows.filter((r) => r.missing.length === 0).length,
    rows,
  };
}

export function udisePreview(): ExportPreview {
  return {
    kind: "UDISE_XLSX",
    academicYearId: "y1",
    academicYearName: "2026-27",
    asOf: "2026-09-30",
    verify: true,
    notes: ["Verify against the current portal template before uploading."],
    tables: [
      {
        key: "school",
        title: "School profile",
        columns: ["UDISE code", "School name"],
        rows: [["36011200912", "Akshara Demo School"]],
      },
      {
        key: "enrolment",
        title: "Enrolment",
        columns: ["Class", "Boys", "Girls", "Other", "Total"],
        rows: [
          ["Class 1", 2, 1, 0, 3],
          ["Total", 2, 1, 0, 3],
        ],
      },
    ],
  };
}

export function intake(overrides: Partial<IntakeView> = {}): IntakeView {
  return {
    id: "i1",
    academicYearId: "y1",
    academicYearName: "2026-27",
    entryClassId: "c1",
    className: "Class 1",
    totalSeats: 8,
    rteSeats: 2,
    stateCode: "TS",
    incomeLimitPaise: 20_000_000,
    distanceBand1Km: 1,
    distanceBand2Km: 3,
    distanceBand3Km: 6,
    documents: ["BIRTH_CERT"],
    applicationsCloseOn: null,
    status: "OPEN",
    currentRunId: null,
    applicants: 3,
    eligible: 2,
    runs: 0,
    createdByName: "Prem Principal",
    createdAt: "2026-04-01T04:30:00Z",
    ...overrides,
  };
}

export function rteApplication(
  overrides: Partial<RteApplicationView> = {},
): RteApplicationView {
  return {
    id: "a1",
    applicationId: "adm1",
    childName: "Meher Shaik",
    dateOfBirth: "2020-05-01",
    gender: "FEMALE",
    guardianName: "Fatima Shaik",
    guardianPhone: "XXXXXX2010",
    stage: "APPLICATION",
    categoryCode: "SC",
    annualIncomePaise: 9_000_000,
    incomeDeclared: true,
    distanceKm: 0.8,
    distanceBand: 1,
    documents: { BIRTH_CERT: true },
    eligibility: "ELIGIBLE",
    ineligibleReason: null,
    screenedByName: "Prem Principal",
    screenedAt: "2026-04-02T04:30:00Z",
    withdrawn: false,
    createdAt: "2026-04-01T04:30:00Z",
    ...overrides,
  };
}

export function intakeDetail(
  overrides: Partial<IntakeDetail> = {},
): IntakeDetail {
  return {
    intake: intake(),
    applications: [
      rteApplication(),
      rteApplication({
        id: "a2",
        applicationId: "adm2",
        childName: "Aarav Naik",
        distanceKm: 2.4,
        distanceBand: 2,
      }),
    ],
    runs: [],
    ...overrides,
  };
}

export function runDetail(): LotteryRunDetail {
  const a = rteApplication();
  const b = rteApplication({
    id: "a2",
    childName: "Aarav Naik",
    guardianName: "Pradeep Naik",
    distanceBand: 2,
  });
  const c = rteApplication({
    id: "a3",
    childName: "Lasya Gowd",
    guardianName: "Padma Gowd",
    distanceBand: 2,
  });
  return {
    run: {
      id: "r1",
      runNo: 1,
      seed: 12345,
      seats: 2,
      applicants: 3,
      witnessName: "SMC member",
      reason: null,
      ranByName: "Prem Principal",
      ranAt: "2026-05-02T05:30:00Z",
      current: true,
    },
    intake: intake({ status: "LOTTERY_DONE", currentRunId: "r1", runs: 1 }),
    schoolName: "Akshara Demo School",
    allotments: [
      {
        position: 1,
        outcome: "ALLOTTED",
        waitlistNo: null,
        distanceBand: 1,
        application: a,
      },
      {
        position: 2,
        outcome: "ALLOTTED",
        waitlistNo: null,
        distanceBand: 2,
        application: b,
      },
      {
        position: 3,
        outcome: "WAITLISTED",
        waitlistNo: 1,
        distanceBand: 2,
        application: c,
      },
    ],
  };
}
