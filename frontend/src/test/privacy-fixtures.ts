import type {
  ChildConsent,
  ConsentPage,
  DataRequestDetail,
  DataRequestPage,
  DataRequestRow,
  GrievanceOfficer,
  NoticeAdmin,
  ParentPrivacy,
  PrivacyNotice,
  PublicNotice,
  PurposeState,
} from "@/lib/types";

/** Test-only privacy data: Anitha Sharma's children Arjun (Class 5 A) and Diya (Class 2 B). */

export const PRIVACY_STAFF = ["dashboard.view", "students.read", "academics.read", "privacy.manage"];
export const PARENT = ["dashboard.view", "child.view"];

export const OFFICER: GrievanceOfficer = {
  name: "Lakshmi Iyer",
  email: "grievance@school.test",
  phone: "040 2345 6789",
  updatedAt: "2026-10-01T04:30:00Z",
};

export const NOTICE: PrivacyNotice = {
  version: 2,
  current: true,
  bodyEn: "## What we collect\nYour child's name and class.\n- Attendance\n- Fees",
  bodyHi: "## हम क्या एकत्र करते हैं\nबच्चे का नाम और कक्षा।",
  changeSummary: "Added WhatsApp messages.",
  publishedAt: "2026-10-05T04:30:00Z",
  publishedByName: "Priya Nair",
  grievanceOfficer: OFFICER,
};

export function state(purpose: PurposeState["purpose"], status: PurposeState["status"], current = true): PurposeState {
  return {
    purpose,
    status,
    at: status === "NONE" ? null : "2026-10-05T05:00:00Z",
    noticeVersion: status === "NONE" ? null : current ? 2 : 1,
    method: status === "NONE" ? null : "ONLINE",
    current: status !== "NONE" && current,
  };
}

export function child(overrides: Partial<ChildConsent> = {}): ChildConsent {
  return {
    studentId: "st1",
    fullName: "Arjun Sharma",
    className: "Class 5",
    sectionName: "A",
    status: "ACTIVE",
    needsConsent: false,
    purposes: [state("ESSENTIAL", "GIVEN"), state("PHOTOS", "GIVEN"), state("WHATSAPP", "DECLINED")],
    history: [],
    ...overrides,
  };
}

export const ARJUN = child();
export const DIYA = child({
  studentId: "st2",
  fullName: "Diya Sharma",
  className: "Class 2",
  sectionName: "B",
  purposes: [state("ESSENTIAL", "GIVEN"), state("PHOTOS", "DECLINED"), state("WHATSAPP", "GIVEN")],
});

export function parentPrivacy(overrides: Partial<ParentPrivacy> = {}): ParentPrivacy {
  return { notice: NOTICE, needsConsent: false, children: [ARJUN, DIYA], ...overrides };
}

/** Both children need consent to version 2; Arjun had agreed to version 1. */
export const NEEDS_CONSENT = parentPrivacy({
  needsConsent: true,
  children: [
    child({
      needsConsent: true,
      purposes: [state("ESSENTIAL", "GIVEN", false), state("PHOTOS", "GIVEN", false), state("WHATSAPP", "NONE")],
      history: [
        {
          id: "c1",
          purpose: "ESSENTIAL",
          action: "GIVEN",
          method: "ONLINE",
          noticeVersion: 1,
          givenByName: "Anitha Sharma",
          recordedByName: null,
          paperReference: null,
          signedOn: null,
          at: "2026-06-01T05:00:00Z",
        },
      ],
    }),
    { ...DIYA, needsConsent: true, purposes: [state("ESSENTIAL", "NONE"), state("PHOTOS", "NONE"), state("WHATSAPP", "NONE")] },
  ],
});

export function requestRow(overrides: Partial<DataRequestRow> = {}): DataRequestRow {
  return {
    id: "r1",
    type: "ACCESS",
    subject: "CHILD",
    status: "SUBMITTED",
    resolution: null,
    studentId: "st1",
    studentName: "Arjun Sharma",
    admissionNo: "AKS/2026/001",
    requesterName: "Anitha Sharma",
    assignedTo: null,
    createdAt: "2026-10-01T05:00:00Z",
    dueOn: "2026-10-31",
    daysLeft: 22,
    overdue: false,
    lastActivityAt: "2026-10-01T05:00:00Z",
    exportStatus: null,
    ...overrides,
  };
}

export function requestPage(items: DataRequestRow[]): DataRequestPage {
  return {
    items,
    page: 0,
    size: 25,
    total: items.length,
    counts: { open: 2, submitted: 1, inProgress: 1, closed: 3, overdue: 1 },
  };
}

export function requestDetail(overrides: Partial<DataRequestDetail> = {}): DataRequestDetail {
  return {
    id: "r1",
    type: "ACCESS",
    subject: "CHILD",
    status: "SUBMITTED",
    resolution: null,
    details: "Please send me a copy of Arjun's records.",
    studentId: "st1",
    studentName: "Arjun Sharma",
    admissionNo: "AKS/2026/001",
    studentStatus: "ACTIVE",
    requesterName: "Anitha Sharma",
    assignedTo: null,
    createdAt: "2026-10-01T05:00:00Z",
    dueOn: "2026-10-31",
    daysLeft: 22,
    overdue: false,
    closingNote: null,
    closedAt: null,
    closedByName: null,
    erasedAt: null,
    canErase: false,
    export: null,
    exports: [],
    events: [
      {
        id: "e1",
        kind: "SUBMITTED",
        actorName: "Anitha Sharma",
        byRequester: true,
        body: "Please send me a copy of Arjun's records.",
        at: "2026-10-01T05:00:00Z",
      },
    ],
    ...overrides,
  };
}

export const READY_EXPORT = {
  id: "x1",
  fileName: "data-export-AKS-2026-001-2026-10-09.zip",
  sizeBytes: 4096,
  status: "READY" as const,
  createdByName: "Priya Nair",
  createdAt: "2026-10-09T05:00:00Z",
  expiresAt: "2026-10-16T05:00:00Z",
  deletedAt: null,
  downloadCount: 0,
  lastDownloadedAt: null,
};

export function noticeAdmin(overrides: Partial<NoticeAdmin> = {}): NoticeAdmin {
  return {
    officer: OFFICER,
    current: NOTICE,
    draftEn: NOTICE.bodyEn,
    draftHi: NOTICE.bodyHi,
    draftIsTemplate: false,
    versions: [
      { version: 2, publishedAt: NOTICE.publishedAt, publishedByName: "Priya Nair", changeSummary: "Added WhatsApp messages." },
      { version: 1, publishedAt: "2026-06-01T04:30:00Z", publishedByName: "Priya Nair", changeSummary: null },
    ],
    ...overrides,
  };
}

export function consentPage(overrides: Partial<ConsentPage> = {}): ConsentPage {
  return {
    items: [
      {
        studentId: "st1",
        fullName: "Arjun Sharma",
        admissionNo: "AKS/2026/001",
        className: "Class 5",
        sectionName: "A",
        status: "ACTIVE",
        purposes: ARJUN.purposes,
      },
      {
        studentId: "st3",
        fullName: "Kabir Khan",
        admissionNo: "AKS/2026/003",
        className: "Class 5",
        sectionName: "A",
        status: "ACTIVE",
        purposes: [state("ESSENTIAL", "NONE"), state("PHOTOS", "NONE"), state("WHATSAPP", "NONE")],
      },
    ],
    page: 0,
    size: 25,
    total: 2,
    noticeVersion: 2,
    activeStudents: 2,
    essentialGiven: 1,
    ...overrides,
  };
}

export function publicNotice(overrides: Partial<PublicNotice> = {}): PublicNotice {
  return {
    schoolName: "Sunrise Public School",
    schoolCode: "sunrise-public",
    version: 2,
    currentVersion: 2,
    publishedAt: NOTICE.publishedAt,
    changeSummary: NOTICE.changeSummary,
    bodyEn: NOTICE.bodyEn,
    bodyHi: NOTICE.bodyHi,
    grievanceOfficer: OFFICER,
    versions: [
      { version: 2, publishedAt: NOTICE.publishedAt, publishedByName: null, changeSummary: "Added WhatsApp messages." },
      { version: 1, publishedAt: "2026-06-01T04:30:00Z", publishedByName: null, changeSummary: null },
    ],
    ...overrides,
  };
}
