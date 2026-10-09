/** Types mirroring docs/api/phase-1-communication.md (circulars, notice boards, calendar). Keep in sync. */

import type { PlainDate } from "./school";

export const CIRCULAR_CATEGORIES = ["GENERAL", "ACADEMIC", "EVENT", "HOLIDAY", "FEES", "URGENT"] as const;
export type CircularCategory = (typeof CIRCULAR_CATEGORIES)[number];

export const CIRCULAR_STATUSES = ["DRAFT", "PENDING_APPROVAL", "SCHEDULED", "SENT", "WITHDRAWN"] as const;
export type CircularStatus = (typeof CIRCULAR_STATUSES)[number];

/** Extra channels; the in-app notice board is always used. */
export const CIRCULAR_CHANNELS = ["SMS", "WHATSAPP", "EMAIL"] as const;
export type CircularChannel = (typeof CIRCULAR_CHANNELS)[number];

export type CircularSource = "STAFF" | "CALENDAR";
export type ReviewOutcome = "APPROVED" | "REJECTED";
export type RecipientKind = "STAFF" | "PARENT" | "STUDENT";

/** Role codes for families; every other role code is a staff role. */
export const FAMILY_ROLES = ["PARENT", "STUDENT"] as const;

export type NamedRef = { id: string; name: string };
export type RoleRef = { code: string; name: string };

/** Who a circular is for. PARENT and STUDENT in `roles` mean those of the chosen classes and sections. */
export type AudienceRequest = {
  wholeSchool: boolean;
  classIds: string[];
  sectionIds: string[];
  roles: string[];
};

export type AudienceView = {
  wholeSchool: boolean;
  classes: NamedRef[];
  sections: NamedRef[];
  roles: RoleRef[];
  /** The audience in words, as saved with the circular. */
  label: string;
};

export type CircularRequest = {
  title: string;
  /** Plain text with line breaks; never HTML. */
  body: string;
  category: CircularCategory;
  audience: AudienceRequest;
  channels: CircularChannel[];
  /** ISO-8601 instant, or null to send on submission (or approval). */
  scheduledAt: string | null;
};

export type EstimateRequest = {
  title: string;
  body: string;
  audience: AudienceRequest;
  channels: CircularChannel[];
};

export type CircularSummary = {
  id: string;
  title: string;
  category: CircularCategory;
  status: CircularStatus;
  source: CircularSource;
  audience: AudienceView;
  channels: CircularChannel[];
  scheduledAt: string | null;
  createdByName: string | null;
  submittedAt: string | null;
  sentAt: string | null;
  updatedAt: string;
  inAppRecipients: number;
  read: number;
  reviewOutcome: ReviewOutcome | null;
};

export type CircularList = {
  items: CircularSummary[];
  counts: Record<CircularStatus, number>;
  canApprove: boolean;
};

export type CircularActions = {
  edit: boolean;
  delete: boolean;
  submit: boolean;
  approve: boolean;
  reject: boolean;
  cancel: boolean;
  withdraw: boolean;
};

export type MessageStatusCounts = Partial<Record<"QUEUED" | "SENT" | "SIMULATED" | "FAILED" | "SKIPPED", number>>;

export type CircularDelivery = {
  inApp: number;
  read: number;
  readPercent: number | null;
  staff: number;
  parents: number;
  students: number;
  kinds: { kind: RecipientKind; recipients: number; read: number }[];
  messages: { channel: CircularChannel; total: number; byStatus: MessageStatusCounts }[];
};

export type CircularDetail = {
  id: string;
  title: string;
  body: string;
  category: CircularCategory;
  status: CircularStatus;
  source: CircularSource;
  audience: AudienceView;
  channels: CircularChannel[];
  scheduledAt: string | null;
  createdById: string | null;
  createdByName: string | null;
  createdAt: string;
  submittedAt: string | null;
  reviewedByName: string | null;
  reviewedAt: string | null;
  reviewOutcome: ReviewOutcome | null;
  reviewNote: string | null;
  sentAt: string | null;
  sentByName: string | null;
  withdrawnAt: string | null;
  withdrawnByName: string | null;
  withdrawReason: string | null;
  updatedAt: string;
  /** Submitting as the caller asks for approval first. */
  needsApproval: boolean;
  actions: CircularActions;
  /** SENT and WITHDRAWN only. */
  delivery: CircularDelivery | null;
};

export type ChannelEstimate = {
  channel: CircularChannel;
  messages: number;
  /** SMS parts in all (messages × parts per SMS); messages for the other channels. */
  units: number;
  costPaise: number;
};

export type CircularEstimate = {
  staff: number;
  parents: number;
  students: number;
  inApp: number;
  parentsWithoutPhone: number;
  phones: number;
  emails: number;
  /** Parts per SMS. */
  smsParts: number;
  /** Not in the GSM alphabet (Hindi, ₹ …): SMS parts are shorter. */
  unicode: boolean;
  channels: ChannelEstimate[];
  /** Indicative only; nothing is charged. */
  costPaise: number;
};

export type SectionOption = { id: string; name: string; label: string; own: boolean };
export type ClassOption = { id: string; name: string; sections: SectionOption[] };

export type AudienceOptions = {
  canApprove: boolean;
  needsApproval: boolean;
  canAddressWholeSchool: boolean;
  classes: ClassOption[];
  /** Empty without notices.approve. */
  staffRoles: RoleRef[];
  rates: { smsPartPaise: number; whatsappPaise: number; emailPaise: number };
};

export const ACK_CHANNELS = ["SMS", "WHATSAPP_SMS"] as const;
export type AckChannel = (typeof ACK_CHANNELS)[number];

export type CommunicationSettings = {
  teacherCircularsNeedApproval: boolean;
  enquiryAckEnabled: boolean;
  enquiryAckChannel: AckChannel;
};

export type BoardItem = {
  id: string;
  title: string;
  body: string;
  category: CircularCategory;
  sentAt: string;
  sentByName: string | null;
  calendarReminder: boolean;
  pinned: boolean;
  read: boolean;
  readAt: string | null;
};

export type BoardPage = { items: BoardItem[]; page: number; size: number; total: number; unread: number };

export const ENTRY_KINDS = ["HOLIDAY", "EVENT", "EXAM", "PTM", "OTHER"] as const;
export type EntryKind = (typeof ENTRY_KINDS)[number];

export const CALENDAR_AUDIENCES = ["SCHOOL", "CLASSES", "STAFF"] as const;
export type CalendarAudience = (typeof CALENDAR_AUDIENCES)[number];

export const REMINDER_CHANNELS = ["SMS", "WHATSAPP"] as const;
export type ReminderChannel = (typeof REMINDER_CHANNELS)[number];

export type EntryRequest = {
  kind: EntryKind;
  title: string;
  description?: string | null;
  startsOn: PlainDate;
  endsOn?: PlainDate | null;
  /** "HH:mm", India time */
  startTime?: string | null;
  endTime?: string | null;
  audience: CalendarAudience;
  classIds?: string[];
  reminderDays?: number | null;
  reminderChannels?: ReminderChannel[];
};

export type CalendarEntry = {
  id: string;
  kind: EntryKind;
  title: string;
  description: string | null;
  startsOn: PlainDate;
  endsOn: PlainDate;
  startTime: string | null;
  endTime: string | null;
  audience: CalendarAudience;
  classes: NamedRef[];
  reminderDays: number | null;
  reminderChannels: ReminderChannel[];
  reminderSentAt: string | null;
  academicYearId: string | null;
  createdByName: string | null;
  updatedByName: string | null;
  updatedAt: string;
};

export type CalendarEntryList = {
  from: PlainDate;
  to: PlainDate;
  canManage: boolean;
  /** The classes an entry can be for. */
  classes: NamedRef[];
  entries: CalendarEntry[];
};

export type HolidaySuggestion = {
  date: PlainDate;
  title: string;
  group: "NATIONAL" | "FESTIVAL";
  /** Festival dates differ by state: the admin confirms them before adding. */
  needsConfirmation: boolean;
  alreadyAdded: boolean;
};

export type HolidaySuggestions = {
  academicYearId: string;
  academicYearName: string;
  from: PlainDate;
  to: PlainDate;
  items: HolidaySuggestion[];
};
