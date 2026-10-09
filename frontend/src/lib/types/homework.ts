/** Types mirroring docs/api/phase-1-timetable-homework.md (homework and files). Keep in sync with the contract. */

import type { PlainDate } from "./school";

/** A stored file; download it from /api/files/{id}. `size` is in bytes. */
export type FileRef = { id: string; name: string; contentType: string; size: number };

export type HomeworkYearRef = { id: string; name: string; startsOn: PlainDate; endsOn: PlainDate } | null;

export type HomeworkSectionRef = { id: string; label: string };

export type HomeworkOptions = {
  academicYear: HomeworkYearRef;
  today: PlainDate;
  maxAttachments: number;
  maxFileBytes: number;
  sections: { id: string; label: string; classId: string; subjects: { id: string; name: string }[] }[];
};

export type HomeworkCounts = {
  students: number;
  submitted: number;
  late: number;
  missing: number;
  reviewed: number;
  needsRedo: number;
  waiting: number;
};

export type HomeworkRow = {
  id: string;
  title: string;
  subjectId: string;
  subjectName: string;
  sections: HomeworkSectionRef[];
  assignedOn: PlainDate;
  dueOn: PlainDate;
  onlineSubmission: boolean;
  attachments: number;
  counts: HomeworkCounts;
  createdByName: string | null;
  canEdit: boolean;
};

export type HomeworkPage = { items: HomeworkRow[]; page: number; size: number; total: number };

export const HOMEWORK_WHEN = ["open", "past", "all"] as const;
export type HomeworkWhen = (typeof HOMEWORK_WHEN)[number];

export type HomeworkQuery = {
  sectionId?: string;
  subjectId?: string;
  when?: HomeworkWhen;
  page?: number;
  size?: number;
};

export type HomeworkDetail = {
  id: string;
  title: string;
  instructions: string | null;
  subjectId: string;
  subjectName: string;
  sections: HomeworkSectionRef[];
  assignedOn: PlainDate;
  dueOn: PlainDate;
  onlineSubmission: boolean;
  attachments: FileRef[];
  createdByName: string | null;
  createdAt: string;
  updatedAt: string;
  counts: HomeworkCounts;
  canEdit: boolean;
  canDelete: boolean;
  today: PlainDate;
};

export type HomeworkRequest = {
  sectionIds: string[];
  subjectId: string;
  title: string;
  instructions?: string | null;
  assignedOn?: PlainDate | null;
  dueOn: PlainDate;
  onlineSubmission: boolean;
};

export const SUBMISSION_STATUSES = ["SUBMITTED", "REVIEWED", "NEEDS_REDO"] as const;
export type SubmissionStatus = (typeof SUBMISSION_STATUSES)[number];

export type TrackerRow = {
  studentId: string;
  fullName: string;
  admissionNo: string;
  rollNo: number | null;
  sectionId: string;
  sectionLabel: string;
  /** MISSING when nothing has been submitted. */
  status: "MISSING" | SubmissionStatus;
  submissionId: string | null;
  submittedAt: string | null;
  late: boolean;
  attempts: number;
  body: string | null;
  files: FileRef[];
  grade: string | null;
  remark: string | null;
  reviewedByName: string | null;
  reviewedAt: string | null;
};

export type HomeworkTracker = { homework: HomeworkDetail; counts: HomeworkCounts; rows: TrackerRow[] };

export type ReviewRequest = { status: SubmissionStatus; grade?: string | null; remark?: string | null };

export type HomeworkSettings = { remindersEnabled: boolean };

export type SubmissionView = {
  id: string;
  body: string | null;
  files: FileRef[];
  submittedAt: string;
  late: boolean;
  attempts: number;
  status: SubmissionStatus;
  grade: string | null;
  remark: string | null;
  reviewedByName: string | null;
  reviewedAt: string | null;
};

export type StudentHomeworkRow = {
  id: string;
  title: string;
  subjectId: string;
  subjectName: string;
  assignedOn: PlainDate;
  dueOn: PlainDate;
  onlineSubmission: boolean;
  attachments: number;
  /** PENDING until something is submitted. */
  status: "PENDING" | SubmissionStatus;
  late: boolean;
  overdue: boolean;
  submittedAt: string | null;
  grade: string | null;
};

export type StudentHomework = {
  studentId: string;
  studentName: string;
  sectionId: string | null;
  sectionLabel: string | null;
  today: PlainDate;
  items: StudentHomeworkRow[];
};

export type StudentHomeworkDetail = {
  id: string;
  studentId: string;
  studentName: string;
  title: string;
  instructions: string | null;
  subjectId: string;
  subjectName: string;
  assignedOn: PlainDate;
  dueOn: PlainDate;
  onlineSubmission: boolean;
  attachments: FileRef[];
  createdByName: string | null;
  submission: SubmissionView | null;
  canSubmit: boolean;
  overdue: boolean;
  maxFiles: number;
  maxFileBytes: number;
  today: PlainDate;
};
