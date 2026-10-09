import { addDays } from "@/components/views/admissions/admission-time";
import { todayInIndia } from "@/lib/format";
import type { AcademicYear, ApplicationDetail, ApplicationRow, ClassView } from "@/lib/types";

/** Test-only data for the admissions views. */

export const TODAY = todayInIndia();

export const ADMISSIONS_STAFF = ["dashboard.view", "academics.read", "students.read", "admissions.read", "admissions.manage"];

export const ADMISSIONS_VIEWER = ["dashboard.view", "admissions.read"];

/** The current year always ends in the future, so the forms offer it whenever the tests run. */
export const YEAR: AcademicYear = {
  id: "y1",
  name: "2026-27",
  startsOn: addDays(TODAY, -120),
  endsOn: addDays(TODAY, 200),
  current: true,
};

export const CLASSES: ClassView[] = [
  {
    id: "c1",
    name: "LKG",
    displayOrder: 1,
    subjects: [],
    sections: [
      {
        id: "s1",
        classId: "c1",
        className: "LKG",
        name: "A",
        capacity: 30,
        classTeacher: null,
        studentCount: 12,
      },
    ],
  },
];

export function applicationRow(overrides: Partial<ApplicationRow> = {}): ApplicationRow {
  return {
    id: "a1",
    childName: "Aanya Bhatt",
    classId: "c1",
    className: "LKG",
    academicYearId: "y1",
    academicYearName: "2026-27",
    stage: "ENQUIRY",
    stageChangedAt: "2026-10-05T04:30:00Z",
    daysInStage: 4,
    followUpOn: null,
    source: "WEBSITE",
    contactName: "Meera Bhatt",
    contactPhone: "98•••••001",
    assignedToName: null,
    nextSlotAt: null,
    createdAt: "2026-10-05T04:30:00Z",
    nextStages: ["APPLICATION", "REJECTED", "WITHDRAWN"],
    ...overrides,
  };
}

export function applicationDetail(overrides: Partial<ApplicationDetail> = {}): ApplicationDetail {
  return {
    id: "a1",
    stage: "ENQUIRY",
    stageChangedAt: "2026-10-05T04:30:00Z",
    daysInStage: 4,
    nextStages: ["APPLICATION", "REJECTED", "WITHDRAWN"],
    firstName: "Aanya",
    lastName: "Bhatt",
    childName: "Aanya Bhatt",
    dateOfBirth: "2022-03-14",
    gender: "FEMALE",
    previousSchool: null,
    classId: "c1",
    className: "LKG",
    academicYearId: "y1",
    academicYearName: "2026-27",
    source: "WEBSITE",
    assignedTo: null,
    followUpOn: null,
    message: "Is there a school bus from Kothrud?",
    consentVersion: "enquiry-2026-10",
    consentAt: "2026-10-05T04:30:00Z",
    guardians: [{ name: "Meera Bhatt", relation: "MOTHER", phone: "9876502001", email: null, primary: true }],
    fee: null,
    offer: null,
    studentId: null,
    slots: [],
    timeline: [
      {
        id: "t1",
        at: "2026-10-05T04:30:00Z",
        kind: "CREATED",
        actorName: null,
        fromStage: null,
        toStage: "ENQUIRY",
        note: null,
        details: { publicForm: true },
      },
    ],
    createdAt: "2026-10-05T04:30:00Z",
    ...overrides,
  };
}
