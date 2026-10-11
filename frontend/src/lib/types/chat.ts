/**
 * Types mirroring docs/api/phase-2-chat.md (parent-teacher messages and meetings). Instants are ISO strings, plain
 * dates "YYYY-MM-DD" and times of day "HH:mm:ss". No response carries a phone number or an email address.
 */

import type { FileRef } from "./homework";
import type { WeekDay } from "./timetable";

export type ChatSide = "PARENT" | "STAFF";
export type ReadOnlyReason = "CLOSED" | "ERASED" | "STUDENT_LEFT" | "STAFF_LEFT" | "PARENT_UNLINKED";

export type ChatStudentRef = { id: string; name: string | null; classLabel: string | null };

export type ChatMessage = {
  id: string;
  side: ChatSide;
  mine: boolean;
  senderName: string | null;
  body: string | null;
  attachments: FileRef[];
  deleted: boolean;
  erased: boolean;
  createdAt: string;
  readAt: string | null;
  deletableUntil: string | null;
  canDelete: boolean;
};

export type ChatPreview = { text: string | null; mine: boolean; deleted: boolean; attachments: number; at: string };

/** The teacher's hours for messages: open now, or when they answer next; away until a day with a note. */
export type ChatAvailability = {
  openNow: boolean;
  nextOpenAt: string | null;
  days: WeekDay[];
  from: string;
  to: string;
  awayUntil: string | null;
  awayNote: string | null;
};

export type ConversationRow = {
  id: string;
  student: ChatStudentRef;
  /** The parent (for staff) or the teacher (for parents). */
  withName: string | null;
  withDetail: string | null;
  unread: number;
  last: ChatPreview | null;
  lastMessageAt: string | null;
  readOnly: boolean;
  readOnlyReason: ReadOnlyReason | null;
  away: boolean;
};

export type ConversationDetail = {
  id: string;
  mySide: ChatSide;
  student: ChatStudentRef;
  parentName: string | null;
  staffName: string | null;
  staffDetail: string | null;
  readOnly: boolean;
  readOnlyReason: ReadOnlyReason | null;
  closedByName: string | null;
  closeReason: string | null;
  closedAt: string | null;
  staffAvailability: ChatAvailability | null;
  messages: ChatMessage[];
  hasOlder: boolean;
  reportedByMe: boolean;
  serverTime: string;
};

export type ConversationChanges = {
  id: string;
  readOnly: boolean;
  readOnlyReason: ReadOnlyReason | null;
  messages: ChatMessage[];
  serverTime: string;
};

export type ChatUnread = { unread: number; conversations: number };

export type ChatContact = {
  studentId: string;
  studentName: string | null;
  classLabel: string | null;
  personId: string;
  personName: string | null;
  detail: string | null;
  conversationId: string | null;
  awayUntil: string | null;
};

export type ChatContacts = {
  sections: { id: string; label: string }[];
  sectionId: string | null;
  contacts: ChatContact[];
};

export type ChatSent = { message: ChatMessage; staffAvailability: ChatAvailability | null };

export type ChatAway = { away: boolean; returnsOn: string | null; note: string | null };
export type ChatAwayRequest = { returnsOn: string | null; note: string | null };

export type ChatSettings = {
  availableDays: WeekDay[];
  availableFrom: string;
  availableTo: string;
  notifyNewMessages: boolean;
  ptmRemindersEnabled: boolean;
};
export type ChatSettingsRequest = Omit<ChatSettings, "ptmRemindersEnabled">;

export type ChatReportStatus = "OPEN" | "REVIEWED";

export type ChatReport = {
  id: string;
  conversationId: string;
  student: ChatStudentRef;
  parentName: string | null;
  staffName: string | null;
  reportedByName: string | null;
  reporterSide: ChatSide;
  reason: string;
  status: ChatReportStatus;
  createdAt: string;
  reviewedByName: string | null;
  reviewedAt: string | null;
  reviewNote: string | null;
};

export type OversightFilter = "all" | "reported" | "closed";

export type OversightRow = {
  id: string;
  student: ChatStudentRef;
  parentName: string | null;
  staffName: string | null;
  messageCount: number;
  lastMessageAt: string | null;
  closed: boolean;
  readOnly: boolean;
  readOnlyReason: ReadOnlyReason | null;
  openReports: number;
  createdAt: string;
};

export type OversightPage = { items: OversightRow[]; page: number; size: number; total: number; openReports: number };

export type OversightMessage = {
  id: string;
  side: ChatSide;
  senderName: string | null;
  body: string | null;
  attachments: FileRef[];
  deleted: boolean;
  deletedAt: string | null;
  erased: boolean;
  createdAt: string;
  readAt: string | null;
};

export type OversightDetail = {
  id: string;
  student: ChatStudentRef;
  parentName: string | null;
  staffName: string | null;
  startedBy: ChatSide;
  closed: boolean;
  readOnly: boolean;
  readOnlyReason: ReadOnlyReason | null;
  closedByName: string | null;
  closeReason: string | null;
  closedAt: string | null;
  createdAt: string;
  messages: OversightMessage[];
  reports: ChatReport[];
};

/* ------------------------------------------------------------------ parent-teacher meetings */

export type MeetingMode = "IN_PERSON" | "VIDEO";
export type PtmAttendance = "ATTENDED" | "NO_SHOW";
export type PtmEventStatus = "SCHEDULED" | "CANCELLED";
export type PtmTeacherKind = "CLASS_TEACHER" | "SUBJECT_TEACHER";
export type PtmBookedBy = "PARENT" | "SCHOOL";

export type PtmSectionRef = { id: string; label: string };
export type PtmBreak = { from: string; to: string };
export type PtmStudentRef = { id: string; name: string | null; classLabel: string | null; sectionId: string | null };

export type PtmBooking = {
  id: string;
  slotId: string;
  startsAt: string;
  endsAt: string;
  teacherId: string;
  teacherName: string | null;
  student: PtmStudentRef;
  parentName: string | null;
  bookedBy: PtmBookedBy;
  bookedByName: string | null;
  attendance: PtmAttendance | null;
  privateNote: string | null;
  parentNote: string | null;
  markedAt: string | null;
  markedByName: string | null;
  createdAt: string;
};

export type PtmSlot = { id: string; startsAt: string; endsAt: string; blocked: boolean; booking: PtmBooking | null };

export type PtmTeacherSchedule = {
  ptmTeacherId: string;
  teacherId: string;
  name: string | null;
  kind: PtmTeacherKind;
  subjects: string | null;
  availableFrom: string;
  availableTo: string;
  booked: number;
  free: number;
  slots: PtmSlot[];
};

export type PtmEventSection = {
  id: string;
  label: string;
  classTeacherId: string | null;
  classTeacherName: string | null;
  students: number;
  studentsBooked: number;
};

export type PtmEventSummary = {
  id: string;
  title: string;
  date: string;
  startsAt: string;
  endsAt: string;
  slotMinutes: number;
  mode: MeetingMode;
  location: string | null;
  status: PtmEventStatus;
  sections: PtmSectionRef[];
  teachers: number;
  slots: number;
  booked: number;
  attended: number;
  noShow: number;
  past: boolean;
};

export type PtmEventDetail = {
  id: string;
  title: string;
  date: string;
  startsAt: string;
  endsAt: string;
  slotMinutes: number;
  breaks: PtmBreak[];
  mode: MeetingMode;
  location: string | null;
  videoLink: string | null;
  includeSubjectTeachers: boolean;
  cutoffHours: number;
  note: string | null;
  status: PtmEventStatus;
  cancelledByName: string | null;
  cancelReason: string | null;
  cancelledAt: string | null;
  createdByName: string | null;
  createdAt: string;
  past: boolean;
  sections: PtmEventSection[];
  teachers: PtmTeacherSchedule[];
  remindersEnabled: boolean;
};

export type PtmEventRequest = {
  title: string;
  date: string;
  startsAt: string;
  endsAt: string;
  slotMinutes: number;
  breaks: PtmBreak[];
  mode: MeetingMode;
  location: string | null;
  videoLink: string | null;
  sectionIds: string[];
  includeSubjectTeachers: boolean;
  cutoffHours: number;
  note: string | null;
};

/** A teacher's own meeting: the event and their schedule at it. */
export type PtmMyEvent = {
  event: PtmEventSummary;
  videoLink: string | null;
  note: string | null;
  cancelReason: string | null;
  cutoffHours: number;
  schedule: PtmTeacherSchedule;
};

export type PtmMarkRequest = {
  attendance: PtmAttendance | null;
  privateNote: string | null;
  parentNote: string | null;
};

export type PtmFamilySlot = { id: string; startsAt: string; endsAt: string; available: boolean; bookableUntil: string };

export type PtmFamilyBooking = {
  id: string;
  slotId: string;
  startsAt: string;
  endsAt: string;
  bookedBy: PtmBookedBy;
  canChange: boolean;
  changeUntil: string;
  attendance: PtmAttendance | null;
  parentNote: string | null;
};

export type PtmFamilyTeacher = {
  teacherId: string;
  name: string | null;
  detail: string | null;
  slots: PtmFamilySlot[];
  booking: PtmFamilyBooking | null;
};

export type PtmFamilyEvent = {
  id: string;
  title: string;
  date: string;
  startsAt: string;
  endsAt: string;
  slotMinutes: number;
  mode: MeetingMode;
  location: string | null;
  videoLink: string | null;
  note: string | null;
  status: PtmEventStatus;
  cancelReason: string | null;
  cutoffHours: number;
  past: boolean;
  teachers: PtmFamilyTeacher[];
};

export type PtmChild = { studentId: string; name: string | null; classLabel: string | null; events: PtmFamilyEvent[] };

export type PtmFamily = { children: PtmChild[]; remindersEnabled: boolean; serverTime: string };

export type PtmSettings = { remindersEnabled: boolean };

export type PtmSectionAttendance = {
  sectionId: string | null;
  label: string;
  students: number;
  booked: number;
  attended: number;
  noShow: number;
  notMarked: number;
};

export type PtmTeacherAttendance = {
  teacherId: string;
  name: string | null;
  slots: number;
  booked: number;
  attended: number;
  noShow: number;
  notMarked: number;
};

export type PtmAttendanceSummary = {
  eventId: string;
  title: string;
  date: string;
  status: PtmEventStatus;
  sections: PtmSectionAttendance[];
  teachers: PtmTeacherAttendance[];
  total: PtmSectionAttendance;
};
