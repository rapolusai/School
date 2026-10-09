# Phase 1 API: circulars, notice boards and the school calendar

Same conventions as [Phase 0](phase-0.md) and [Phase 1](phase-1.md): JSON under `/api`, a Bearer access token,
RFC 9457 problem details with `errors.<field>`, ids from another school answer `404` in paths and `400` in bodies,
dates are `"YYYY-MM-DD"` school days in India (Asia/Kolkata), times are `"HH:mm"` in India, instants are ISO-8601
UTC, and every change is written to the audit trail.

**In this slice:** circulars (announcements) from draft to sent with optional approval and scheduling, in-app notice
boards for staff, parents and students with read receipts, SMS / WhatsApp / email copies through the notifications
outbox (simulated sender), the school calendar (holidays, events, exams, PTMs) with reminders, an iCalendar
download, a holiday starter list, whole-school holidays in attendance, and a thank-you message for public admissions
enquiries.

**Not in this slice:** attachments on circulars, replies or acknowledgements from parents, push notifications (the
`CircularSent` event is there for them), recurring calendar entries, a subscribable (token-in-URL) calendar feed,
and per-state holiday lists beyond the 2026-27 starter list.

## Permissions

| Permission | Who has it (seeded roles) | Allows |
| --- | --- | --- |
| `notices.send` | School Admin, Principal, Teacher | writing circulars, the estimate, submitting, taking back, withdrawing. Without `notices.approve`: only one's own circulars, addressed only to the parents and students of the sections one is class teacher of |
| `notices.approve` (new) | School Admin, Principal | approving or sending back circulars that wait for approval; sees and manages every circular; may address the whole school, any class, section or role |
| `notices.read` (new) | everyone, Parent and Student included | the caller's notice board and the calendar entries meant for them |
| `calendar.manage` (new) | School Admin, Principal | adding, changing and removing calendar entries; the holiday starter list |
| `settings.manage` | School Admin | the communication settings |

`V7__communication.sql` appends `notices.read` to all seven built-in roles and `notices.approve` and
`calendar.manage` to School Admin and Principal of every existing school (idempotent). New permissions take effect
at the next sign-in.

## Circulars — `/api/notices`

| Method | Path | Permission | Success |
| --- | --- | --- | --- |
| GET | `?status=` | `notices.send` | `200 CircularList`: the caller's circulars (every circular with `notices.approve`), newest change first, at most 200, with counts per status |
| GET | `/audience-options` | `notices.send` | `200 AudienceOptions`: classes, sections and staff roles the caller may address; whether their circulars need approval; the indicative prices |
| POST | `/estimate` | `notices.send` | `200 Estimate` for an audience and channels, before saving (an empty audience estimates nothing) |
| POST | `` | `notices.send` | `201 CircularDetail` (a DRAFT) |
| GET | `/{id}` | `notices.send` | `200 CircularDetail`; another person's circular is `404` without `notices.approve` |
| PUT | `/{id}` | `notices.send` | `200 CircularDetail`; drafts only, else `409` |
| DELETE | `/{id}` | `notices.send` | `204`; drafts only, else `409` |
| POST | `/{id}/submit` | `notices.send` | `200`: PENDING_APPROVAL when the caller needs approval, else SCHEDULED (a future `scheduledAt`) or SENT now |
| POST | `/{id}/approve` `{note?}` | `notices.approve` | `200`: SCHEDULED or SENT (a schedule that has passed meanwhile sends now) |
| POST | `/{id}/reject` `{note}` | `notices.approve` | `200`: back to DRAFT with the note for the author |
| POST | `/{id}/cancel` | `notices.send` | `200`: a pending or scheduled circular back to DRAFT |
| POST | `/{id}/withdraw` `{reason}` | `notices.send` | `200`: SENT to WITHDRAWN; off every board; messages still waiting in the outbox become SKIPPED, ones already sent stay sent |
| GET / PUT | `/settings` | `settings.manage` | `200 Settings` |

Lifecycle: `DRAFT → (PENDING_APPROVAL →) SCHEDULED | SENT → WITHDRAWN`. Sent circulars cannot be edited (`409`).
A circular from someone without `notices.approve` waits for approval when the school's setting
`teacherCircularsNeedApproval` is on (the default). The scheduler (every minute, `akshara.communication.scheduler.*`)
sends scheduled circulars that are due, each in its own transaction with `for update skip locked`.

```ts
type Category = "GENERAL" | "ACADEMIC" | "EVENT" | "HOLIDAY" | "FEES" | "URGENT";
type Status = "DRAFT" | "PENDING_APPROVAL" | "SCHEDULED" | "SENT" | "WITHDRAWN";
type Channel = "SMS" | "WHATSAPP" | "EMAIL";            // the in-app board is always used

// Role codes are staff roles (TEACHER, ACCOUNTANT, ...) or PARENT / STUDENT. PARENT and STUDENT mean the parents or
// students of the chosen classes and sections, or of every class when none is chosen. Classes or sections without
// PARENT or STUDENT are refused (400 errors.audience), as are unknown ids and roles.
type Audience = { wholeSchool?: boolean; classIds?: string[]; sectionIds?: string[]; roles?: string[] };

type CircularRequest = { title: string /* 1-200 */; body: string /* plain text with line breaks, 1-5000 */;
  category: Category; audience: Audience; channels?: Channel[]; scheduledAt?: string | null /* future, < 1 year */ };

type CircularDetail = { id: string; title: string; body: string; category: Category; status: Status;
  source: "STAFF" | "CALENDAR";                        // CALENDAR: a reminder of a calendar entry
  audience: { wholeSchool: boolean; classes: Ref[]; sections: Ref[]; roles: { code: string; name: string }[];
    label: string };                                   // label: the audience in words when it was saved
  channels: Channel[]; scheduledAt: string | null; createdById: string | null; createdByName: string | null;
  createdAt: string; submittedAt: string | null; reviewedByName: string | null; reviewedAt: string | null;
  reviewOutcome: "APPROVED" | "REJECTED" | null; reviewNote: string | null; sentAt: string | null;
  sentByName: string | null; withdrawnAt: string | null; withdrawnByName: string | null;
  withdrawReason: string | null; updatedAt: string;
  needsApproval: boolean;                              // submitting as the caller asks for approval
  actions: { edit; delete; submit; approve; reject; cancel; withdraw: boolean };
  delivery: null | {                                   // SENT and WITHDRAWN only
    inApp: number; read: number; readPercent: number | null;       // people with the circular on their board
    staff: number; parents: number; students: number;              // parents = parents and guardians reached
    kinds: { kind: "STAFF" | "PARENT" | "STUDENT"; recipients: number; read: number }[];
    messages: { channel: Channel; total: number; byStatus: Record<"QUEUED" | "SENT" | "SIMULATED" | "FAILED" | "SKIPPED", number> }[] } };
type Ref = { id: string; name: string };

type CircularList = { items: CircularSummary[]; counts: Record<Status, number>; canApprove: boolean };
type CircularSummary = { id; title; category; status; source; audience; channels; scheduledAt; createdByName;
  submittedAt; sentAt; updatedAt; inAppRecipients: number; read: number; reviewOutcome };

type Estimate = { staff: number; parents: number; students: number; inApp: number; parentsWithoutPhone: number;
  phones: number; emails: number; smsParts: number /* per SMS */; unicode: boolean /* Hindi etc.: 70/67 chars */;
  channels: { channel: Channel; messages: number; units: number /* SMS parts */; costPaise: number }[];
  costPaise: number };                                 // indicative only; nothing is charged

type AudienceOptions = { canApprove: boolean; needsApproval: boolean; canAddressWholeSchool: boolean;
  classes: { id: string; name: string; sections: { id: string; name: string; label: string; own: boolean }[] }[];
  staffRoles: { code: string; name: string }[];        // empty without notices.approve
  rates: { smsPartPaise: number; whatsappPaise: number; emailPaise: number } };

type Settings = { teacherCircularsNeedApproval: boolean; enquiryAckEnabled: boolean;
  enquiryAckChannel: "SMS" | "WHATSAPP_SMS" };
```

Errors: `400 errors.audience` (empty, unknown, or classes without parents/students), `403 errors.audience` (a teacher
addressing anything but the parents and students of their own sections), `400 errors.scheduledAt` (in the past, more
than a year ahead, or passed before submitting), `400 errors.audience` when nobody in the audience can receive it on
submit, `400 errors.note` (reject without a note), `400 errors.reason`, `409 errors.status` for a step that does
not fit the status.

### Who a circular reaches (worked out when it is sent)

- **Parents:** every linked parent or guardian of the active students enrolled this year in the chosen classes and
  sections. Each guardian is counted once however many of their children are in the audience.
- **Students:** those students who have a sign-in (in-app only; messages go to parents).
- **Staff:** active users with the chosen roles; the whole school means every role except Parent and Student.
- A person who is both staff and a parent is on the board once, as staff.
- **In-app:** a `circular_recipient` row per person with an active sign-in; the board and read receipts use it.
- **SMS / WhatsApp:** one message per distinct guardian mobile number (staff have no number on file), each with the
  dedupe key `circular:<id>:<sms|whatsapp>:<guardianId>`; the channels are independent (no fallback between them).
- **Email:** guardian email, else the guardian's sign-in email, and staff sign-in emails; one per address.
- Template `communication.circular` (en, hi): `Circular from {school}: {title}. {summary}`; the summary is the body
  on one line, cut at 300 characters (the whole body for email). The school's alert language is used. The outbox keeps
  messages through the school's quiet hours.

## Notice board — `/api/notices/board` (`notices.read`)

| Method | Path | Success |
| --- | --- | --- |
| GET | `?page=0&size=20&unreadOnly=false` | `200 BoardPage`: sent circulars addressed to the caller; URGENT ones sent in the last 7 days pinned first, then newest first |
| GET | `/{id}` | `200 BoardItem`; `404` when not addressed to the caller or withdrawn |
| POST | `/{id}/read` | `200 BoardItem`; the first read is the read receipt, later ones change nothing |
| POST | `/read-all` | `200 { marked: number }` |

```ts
type BoardItem = { id: string; title: string; body: string; category: Category; sentAt: string;
  sentByName: string | null; calendarReminder: boolean; pinned: boolean; read: boolean; readAt: string | null };
type BoardPage = { items: BoardItem[]; page: number; size: number /* max 50 */; total: number; unread: number };
```

Reading is not audited (it is the reader's own activity; the receipt itself is the record).

## Calendar — `/api/calendar`

| Method | Path | Permission | Success |
| --- | --- | --- | --- |
| GET | `/entries?from=&to=&yearId=` | `notices.read` | `200 EntryList`; without dates the given (or current) academic year; at most 400 days |
| GET | `/upcoming?limit=5` | `notices.read` | `200 EntryView[]`: entries that have not ended, soonest first (max 20) |
| GET | `/entries/{id}` | `notices.read` | `200 EntryView`; an entry not meant for the caller is `404` |
| POST | `/entries` | `calendar.manage` | `201 EntryView` |
| POST | `/entries/bulk` `{entries: EntryRequest[]}` | `calendar.manage` | `201 EntryView[]`; 1-60 entries, all or none |
| PUT | `/entries/{id}` | `calendar.manage` | `200 EntryView` |
| DELETE | `/entries/{id}` | `calendar.manage` | `204` |
| GET | `/holiday-suggestions?yearId=` | `calendar.manage` | `200 Suggestions` for the given (or current) year; adds nothing |
| GET | `/api/calendar.ics` | `notices.read` | `200 text/calendar` download of the caller's entries, two months back to a year ahead |

```ts
type EntryKind = "HOLIDAY" | "EVENT" | "EXAM" | "PTM" | "OTHER";
type CalendarAudience = "SCHOOL" | "CLASSES" | "STAFF";
type EntryRequest = { kind: EntryKind; title: string; description?: string /* <= 2000 */;
  startsOn: string; endsOn?: string /* default startsOn; at most 120 days later */;
  startTime?: string; endTime?: string;            // endTime needs startTime; after it on a one-day entry
  audience: CalendarAudience; classIds?: string[]; // required for CLASSES
  reminderDays?: number /* 1-30 */; reminderChannels?: ("SMS" | "WHATSAPP")[] };
type EntryView = EntryRequest & { id: string; classes: Ref[]; reminderSentAt: string | null;
  academicYearId: string | null;                   // the year the entry starts in
  createdByName: string | null; updatedByName: string | null; updatedAt: string };
type EntryList = { from: string; to: string; canManage: boolean; classes: Ref[]; entries: EntryView[] };
type Suggestions = { academicYearId: string; academicYearName: string; from: string; to: string;
  items: { date: string; title: string; group: "NATIONAL" | "FESTIVAL"; needsConfirmation: boolean;
    alreadyAdded: boolean }[] };
```

**Who sees what:** staff (any role other than Parent and Student) see every entry. Parents and students see SCHOOL
entries and CLASSES entries for their children's (or their own) classes this year; STAFF entries never.

**Holiday starter list:** Republic Day (26 Jan), Independence Day (15 Aug) and Gandhi Jayanti (2 Oct) every year,
plus a 2026-27 festival list (Good Friday, Ambedkar Jayanti, Buddha Purnima, Bakrid, Muharram, Milad-un-Nabi, Raksha
Bandhan, Janmashtami, Ganesh Chaturthi, Dussehra, Diwali, Guru Nanak Jayanti, Christmas, Makar Sankranti / Pongal,
Maha Shivaratri, Id-ul-Fitr, Holi). Festival dates follow the lunar calendar and differ by state, so every one is
`needsConfirmation: true`; the web asks the admin to confirm and lets them edit each date and title. Nothing is
added until the admin adds it.

**Reminders:** an entry with `reminderDays` is sent, once, as a circular (`source: CALENDAR`) when its lead time
starts (from `startsOn - reminderDays` until it starts): to the whole school, to the parents and students of its
classes, or to all staff, on notice boards and (if chosen) by SMS / WhatsApp with template `calendar.reminder`
(`Reminder from {school}: {title} on {date}.`). Moving the start date or the lead time sends it again. Category:
HOLIDAY → HOLIDAY, EXAM → ACADEMIC, EVENT and PTM → EVENT, OTHER → GENERAL.

**iCalendar:** RFC 5545, all-day entries as `DTSTART;VALUE=DATE` with the exclusive `DTEND`, timed entries in UTC
(a start without an end lasts an hour), text escaped, lines folded at 75 octets, CRLF line ends, `UID <id>@akshara`.

### Holidays and attendance

`com.akshara.communication.SchoolCalendar` (public): `isHoliday(date)`, `holidayOn(date)`, `holidaysBetween(from,
to)` and `schoolDaysBetween(from, to)` (Monday to Saturday, less holidays). Only **whole-school HOLIDAY** entries
count; a holiday for some classes or for staff only does not close the school. Attendance changes:

- `PUT /api/attendance/registers/{sectionId}/{date}` on a holiday: `409` `{"title":"School holiday",
  "errors":{"date":"2026-10-02 is a school holiday (Gandhi Jayanti). Attendance is not marked on holidays."}}`.
- `SectionsForDay` and `RegisterView` gain `holiday: string | null`; on a holiday `canMark` / `canEdit` are false.
- `MonthRegister.days[]` gain `holiday: string | null`, and the register gains `schoolDays` (Mondays to Saturdays
  less holidays) and `holidays` (holiday days in the month). Marks made before a day was declared a holiday stay
  visible but are left out of `daysMarked`, the counts and the percentages.

## Admissions enquiry acknowledgement

When a family sends an enquiry through the public form (`EnquiryReceived`), the parent who enquired gets one
message: `Thank you for your enquiry at {school} for {child} ({class}). Our admissions team will contact you soon.`
(template `admissions.enquiry_ack`, English and Hindi, in the school's alert language), by SMS, or by WhatsApp with
SMS as the fallback when `enquiryAckChannel` is `WHATSAPP_SMS`; off when `enquiryAckEnabled` is false. It is sent
only because the form records the family's consent (`consentAt`), and its dedupe key `enquiry-ack:<applicationId>`
means a repeated event never sends it twice. Enquiries entered by staff are not acknowledged automatically.

*Why in the same transaction:* the admissions module publishes `EnquiryReceived` inside the transaction that stores
the enquiry, so the listener queues the message in that transaction, the outbox pattern the notifications module is
built on: the message exists if and only if the enquiry does, and nothing is sent for a rolled-back enquiry. Queuing
is a single insert after a lookup, with the phone number checked first, so it does not put the enquiry at risk; any
other error is logged by type only. If the event arrives outside a transaction, the listener opens a new one as that
school (`TenantContext.runAs`).

## Events

`CircularSent(tenantId, circularId, category, calendarReminder, inAppRecipients, messagesQueued, sentAt)`, published
inside the sending transaction, for a future push-notification module and dashboards.

## Audit actions

`circular.created|updated|deleted|submitted|approved|rejected|scheduled|sent|cancelled|withdrawn`,
`calendar_entry.created|updated|deleted|reminder_sent`, `communication_settings.updated`. Details hold titles,
categories, audience labels, statuses, counts and reasons; never phone numbers or email addresses. Scheduled sends
and reminders are recorded without a person.

## Data

Schema `communication`: `circular`, `circular_target` (the chosen classes, sections and roles), `circular_recipient`
(board rows and read receipts), `calendar_entry`, `calendar_entry_class`, `settings`. Every table has `tenant_id`, the
`tenant_isolation` policy and composite `(tenant_id, id)` foreign keys; no phone number is stored here. Two
`security definer` functions tell the scheduler which schools have work due without a school selected. Indicative
prices: `akshara.communication.cost.sms-part-paise` (20), `whatsapp-paise` (12), `email-paise` (0).
