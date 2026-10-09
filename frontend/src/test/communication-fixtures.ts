import type {
  AudienceOptions,
  BoardItem,
  CalendarEntry,
  CircularDetail,
  CircularEstimate,
} from "@/lib/types";

/** Test-only data shaped like docs/api/phase-1-communication.md responses. */

export const RATES = { smsPartPaise: 20, whatsappPaise: 12, emailPaise: 0 };

export const TEACHER_OPTIONS: AudienceOptions = {
  canApprove: false,
  needsApproval: true,
  canAddressWholeSchool: false,
  classes: [{ id: "c5", name: "Class 5", sections: [{ id: "s5a", name: "A", label: "Class 5 A", own: true }] }],
  staffRoles: [],
  rates: RATES,
};

export const PRINCIPAL_OPTIONS: AudienceOptions = {
  canApprove: true,
  needsApproval: false,
  canAddressWholeSchool: true,
  classes: [
    {
      id: "c5",
      name: "Class 5",
      sections: [
        { id: "s5a", name: "A", label: "Class 5 A", own: false },
        { id: "s5b", name: "B", label: "Class 5 B", own: false },
      ],
    },
    { id: "c6", name: "Class 6", sections: [{ id: "s6a", name: "A", label: "Class 6 A", own: false }] },
  ],
  staffRoles: [
    { code: "TEACHER", name: "Teacher" },
    { code: "ACCOUNTANT", name: "Accountant" },
  ],
  rates: RATES,
};

export function estimate(overrides: Partial<CircularEstimate> = {}): CircularEstimate {
  return {
    staff: 0,
    parents: 2,
    students: 0,
    inApp: 1,
    parentsWithoutPhone: 0,
    phones: 2,
    emails: 1,
    smsParts: 1,
    unicode: false,
    channels: [],
    costPaise: 0,
    ...overrides,
  };
}

const NO_ACTIONS = { edit: false, delete: false, submit: false, approve: false, reject: false, cancel: false, withdraw: false };

export function circular(overrides: Partial<CircularDetail> = {}): CircularDetail {
  return {
    id: "n1",
    title: "Class 5 A project day",
    body: "Dear parents,\nThe science project is due on Friday.",
    category: "ACADEMIC",
    status: "DRAFT",
    source: "STAFF",
    audience: {
      wholeSchool: false,
      classes: [],
      sections: [{ id: "s5a", name: "Class 5 A" }],
      roles: [{ code: "PARENT", name: "Parent" }],
      label: "Class 5 A · Parents",
    },
    channels: [],
    scheduledAt: null,
    createdById: "u-ravi",
    createdByName: "Ravi Kumar",
    createdAt: "2026-10-09T04:00:00Z",
    submittedAt: null,
    reviewedByName: null,
    reviewedAt: null,
    reviewOutcome: null,
    reviewNote: null,
    sentAt: null,
    sentByName: null,
    withdrawnAt: null,
    withdrawnByName: null,
    withdrawReason: null,
    updatedAt: "2026-10-09T04:00:00Z",
    needsApproval: false,
    actions: { ...NO_ACTIONS },
    delivery: null,
    ...overrides,
  };
}

export function boardItem(overrides: Partial<BoardItem> = {}): BoardItem {
  return {
    id: "b1",
    title: "School closed tomorrow",
    body: "Because of heavy rain the school is closed tomorrow.",
    category: "URGENT",
    sentAt: "2026-10-08T12:30:00Z",
    sentByName: "Meera Iyer",
    calendarReminder: false,
    pinned: true,
    read: false,
    readAt: null,
    ...overrides,
  };
}

export function entry(overrides: Partial<CalendarEntry> = {}): CalendarEntry {
  return {
    id: "e1",
    kind: "HOLIDAY",
    title: "Gandhi Jayanti",
    description: null,
    startsOn: "2026-10-02",
    endsOn: "2026-10-02",
    startTime: null,
    endTime: null,
    audience: "SCHOOL",
    classes: [],
    reminderDays: null,
    reminderChannels: [],
    reminderSentAt: null,
    academicYearId: "y1",
    createdByName: "Meera Iyer",
    updatedByName: null,
    updatedAt: "2026-09-01T04:00:00Z",
    ...overrides,
  };
}
