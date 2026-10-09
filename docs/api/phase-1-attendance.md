# Phase 1 API: attendance and the notifications outbox

Same conventions as [Phase 0](phase-0.md) and [Phase 1](phase-1.md): JSON under `/api`, a Bearer access token,
RFC 9457 problem details with `errors.<field>`, ids from another school answer `404`, dates are `"YYYY-MM-DD"`
school days in India (Asia/Kolkata), and every change is written to the audit trail.

**In this slice:** day-wise registers (one mark per student per section per day), month and student reports, the
dashboard summary, a parent's view of their own child, absence alerts to parents, and the message outbox with a
simulated sender and a message log.

**Not in this slice:** period-wise (subject-by-subject) attendance, and staff check-in / staff attendance. Neither has
tables or endpoints yet. There is also no holiday calendar: any day of the current academic year that is not in the
future can be marked, Sundays included.

## Permissions

| Permission | Who has it (seeded roles) | Allows |
| --- | --- | --- |
| `attendance.read` | School Admin, Principal, Teacher | reading registers and reports. A Teacher without `attendance.manage` sees only the sections they are class teacher of |
| `attendance.mark` | School Admin, Teacher | marking the caller's **own** sections (class teacher) |
| `attendance.manage` (new) | School Admin, Principal | marking and reading **any** section |
| `messages.read` (new) | School Admin, Principal | the message log |
| `settings.manage` | School Admin | absence alert settings |
| `child.view` | School Admin, Parent | a parent's own child's attendance |

The Accountant, Front office, Parent and Student roles have no attendance permission and get `403` from
`/api/attendance`. A teacher asking for a section, student or month register outside their own sections gets `403`;
an unknown section or student (or another school's) is `404`. Migration `V5__attendance.sql` appends
`attendance.manage` and `messages.read` to the School Admin and Principal roles of every existing school.

## Attendance — `/api/attendance`

| Method | Path | Permission | Success |
| --- | --- | --- | --- |
| GET | `/sections?date=` | `attendance.read` | `200 SectionsForDay` for `date` (default today): the sections the caller can see, marked or not |
| GET | `/registers/{sectionId}/{date}` | `attendance.read` | `200 RegisterView` (unmarked registers list the students with `status: null`) |
| PUT | `/registers/{sectionId}/{date}` | `attendance.mark` or `attendance.manage` | `200 SaveResult`; creates or updates the register |
| GET | `/sections/{sectionId}/month?month=YYYY-MM` | `attendance.read` | `200 MonthRegister` (default this month) |
| GET | `/sections/{sectionId}/month.csv?month=YYYY-MM` | `attendance.read` | `200 text/csv` download, `attendance-class-5-a-2026-10.csv` |
| GET | `/students/{studentId}/summary?from=&to=` | `attendance.read` | `200 StudentSummary`; default the current year to date, at most 400 days |
| GET | `/today` | `attendance.read` | `200 TodaySummary` of the sections the caller can see, for the dashboard |
| GET | `/api/me/children/{studentId}/attendance?month=YYYY-MM` | `child.view` | `200 ChildAttendance` for the caller's own child; any other student is `404` |

```ts
type Status = "PRESENT" | "ABSENT" | "LATE" | "HALF_DAY" | "LEAVE";      // LEAVE = excused
type Counts = { present: number; absent: number; late: number; halfDay: number; leave: number };

type SaveRequest = { entries: { studentId: string; status: Status }[] };  // max 500

type SectionsForDay = { date: string; today: string; canMark: boolean;
  academicYear: { id: string; name: string; startsOn: string; endsOn: string } | null;   // null: no current year
  sections: { sectionId: string; classId: string; className: string; sectionName: string; label: string;
    classTeacherName: string | null; students: number; marked: boolean; markedByName: string | null;
    markedAt: string | null; counts: Counts; presentPercent: number | null; canMark: boolean }[] };

type RegisterView = { sectionId: string; classId: string; className: string; sectionName: string; label: string;
  date: string; academicYearName: string; marked: boolean; markedByName: string | null; markedAt: string | null;
  updatedByName: string | null; updatedAt: string | null;      // last edit after the first save, if any
  canEdit: boolean; counts: Counts; unmarked: number; presentPercent: number | null;
  entries: { studentId: string; fullName: string; admissionNo: string; rollNo: number | null;
    inSection: boolean;              // false: marked earlier, has since left the section
    status: Status | null }[] };

type SaveResult = { register: RegisterView; firstSave: boolean; changed: number;
  alertsQueued: number; alertsCancelled: number };

type MonthRegister = { sectionId: string; className: string; sectionName: string; label: string; month: string;
  days: { date: string; marked: boolean; counts: Counts; presentPercent: number | null }[];   // every day
  students: { studentId: string; fullName: string; admissionNo: string; rollNo: number | null; inSection: boolean;
    marks: ("P" | "A" | "L" | "H" | "E" | null)[];   // one per day
    daysMarked: number; counts: Counts; presentPercent: number | null }[];
  daysMarked: number; counts: Counts; presentPercent: number | null };

type StudentSummary = { studentId: string; fullName: string; admissionNo: string; className: string | null;
  sectionName: string | null; from: string; to: string; daysMarked: number; counts: Counts;
  presentPercent: number | null; absences: string[]; days: { date: string; status: Status }[] };

type TodaySummary = { date: string; academicYearName: string | null; sectionCount: number; sectionsMarked: number;
  students: number; counts: Counts; presentPercent: number | null;
  classes: { classId: string; className: string; sectionCount: number; sectionsMarked: number; counts: Counts;
    presentPercent: number | null; sections: { sectionId: string; sectionName: string; label: string;
      classTeacherName: string | null; students: number; marked: boolean; markedByName: string | null;
      markedAt: string | null; counts: Counts; presentPercent: number | null }[] }[] };

type ChildAttendance = { studentId: string; fullName: string; month: string; daysMarked: number; counts: Counts;
  presentPercent: number | null; days: { date: string; status: Status }[];
  recentAbsences: { date: string; status: "ABSENT" }[] };   // newest first, at most 5, this year
```

### Rules

- **One register per section per day**, with one entry per student enrolled in the section in the **current
  academic year** (active students, in roll-number order). The register records who marked it and when, and who
  last changed it and when.
- A save must give **every** enrolled student exactly one mark (`400 errors.entries` otherwise). Students who were
  marked earlier and have since left may be included; nobody else may (`400 errors.entries`).
- **Saving again** updates the marks. A save that changes nothing writes nothing. A save that changes something is
  audited as `attendance.updated` with `changed` and the counts `before` and `after`; the first save is
  `attendance.marked` with the counts.
- `date` in the **future**, or outside the current academic year, is `400 errors.date`. With no current year every
  date is `400 errors.date` ("Set up the current academic year").
- Two people saving the same register at the same moment: the second waits for the first (row lock); two first saves
  racing each other give one `200` and one `409`.
- **Percentages:** present and late count as a full day, a half day as half, absent and leave (excused) as not
  attended, divided by the days marked: `(P + L + H/2) / days marked × 100`, one decimal, rounded half up.
  `null` when nothing is marked. A day's percentage uses the same rule over the students marked that day.
- Month registers and CSV marks: **P** present, **A** absent, **L** late, **H** half day, **E** excused leave. The
  CSV has one row per student (roll no, admission no, name, a column per day, then days marked, each count and the
  percentage) and three total rows (`Present (P+L+H)`, `Absent (A)`, `Leave (E)`). Cells that start with `= + - @`
  get a leading apostrophe so spreadsheets never run them.

### Event

Every save that creates or changes a register publishes `com.akshara.attendance.AttendanceSaved`
`(tenantId, registerId, sectionId, date, firstSave, counts)` as a Spring application event inside the saving
transaction. Listeners that must not run on a rolled-back save should use `@TransactionalEventListener`.

## Absence alerts

- Sent the **first time a student is marked ABSENT on a day** (on the first save, or when a re-save changes a mark to
  absent). Re-saving an absent student again never sends another alert: alerts are deduplicated by student and date
  (`dedupe_key = absence:<studentId>:<date>`, unique per school).
- One alert per absent student, to the **primary** guardian's mobile number. Students whose primary guardian has no
  mobile number are skipped.
- If the student is marked anything other than absent **before the alert is sent**, the alert becomes `SKIPPED`. If
  they are marked absent again later that day, the same alert is queued again (never a second one). Once sent, an
  alert stays sent.
- Alerts are queued for whatever day is being saved, including back-filled past days. Schools entering old registers
  can switch alerts off first (the settings page says so).
- English template (exact):
  `Dear {guardian}, {student} ({class}) was marked absent at {school} on {date}. Please contact the school if this is unexpected.`
  Hindi: `प्रिय {guardian}, {student} ({class}) को {date} को {school} में अनुपस्थित दर्ज किया गया है। यदि यह अपेक्षित नहीं है, तो कृपया विद्यालय से संपर्क करें।`
  `{date}` is shown as `09 Oct 2026` (English) or `09 अक्तूबर 2026` (Hindi); `{class}` is like `Class 5 A`.
  Line breaks and control characters in names are replaced with spaces.

### Settings — `/api/notifications/settings` (`settings.manage`)

| Method | Body | Success |
| --- | --- | --- |
| GET | | `200 Settings` (the defaults until a school saves its own) |
| PUT | `Settings` | `200 Settings`; audited as `notification_settings.updated` |

```ts
type Settings = {
  absenceAlertsEnabled: boolean;                 // default true
  absenceAlertChannel: "WHATSAPP_SMS" | "SMS";   // default WHATSAPP_SMS: WhatsApp first, SMS if WhatsApp fails
  alertLanguage: "en" | "hi";                    // default en
  quietHoursEnabled: boolean;                    // default true
  quietHoursStart: string;                       // "HH:mm", default "21:00" (India time)
  quietHoursEnd: string;                         // "HH:mm", default "07:00"; must differ from the start
};
```

## Message log — `/api/messages` (`messages.read`)

| Method | Path | Success |
| --- | --- | --- |
| GET | `?from=&to=&channel=&status=&q=&relatedId=&page=0&size=25` | `200 { items: MessageRow[], page, size, total }`, newest first, `size` at most 100 |
| GET | `/{id}` | `200 MessageDetail` with the rendered text; `404` for an unknown or another school's id |

`from`/`to` are the days (India) the message was queued, both included. `q` matches the related label (for absence
alerts, `Student Name (Class 5 A)`) or the recipient's name, case-insensitively. `relatedId` is the student's id.

```ts
type Channel = "SMS" | "WHATSAPP" | "EMAIL";
type MessageStatus = "QUEUED" | "SENT" | "FAILED" | "SIMULATED" | "SKIPPED";
type MessageRow = { id: string; createdAt: string; channel: Channel;
  recipient: string;            // always masked: "98765•••01", "an•••@example.in"
  recipientName: string | null; templateKey: string; status: MessageStatus; attempts: number;
  sentAt: string | null; nextAttemptAt: string | null;    // only while QUEUED
  lastError: string | null;     // why it failed or was skipped; never contains the recipient
  relatedType: string | null; relatedId: string | null; relatedLabel: string | null };
type MessageDetail = MessageRow & { language: "en" | "hi"; body: string; fallbackChannel: Channel | null };
```

Full numbers are never returned by the API and never written to logs; only the sender sees them.

## The outbox

`notifications.message` is a transactional outbox. Modules queue messages with the public
`com.akshara.notifications.NotificationQueue` **inside their own transaction** (it requires one), so a message
exists only if the change that caused it was committed. `enqueue(templateKey, language, params, related,
recipients)` renders the text immediately (the log shows exactly what was sent) and returns per recipient whether
the message was `CREATED`, `REQUEUED` (a skipped one with the same dedupe key) or a `DUPLICATE`. `skip(dedupeKey,
reason)` cancels a message (and its fallback) that has not been sent.

The **dispatcher** (`MessageDispatcher`, scheduled by `NotificationsConfig`) runs every
`akshara.notifications.dispatch.interval` (15 s):

1. With no school selected, it asks the security-definer function
   `notifications.tenants_with_due_messages(now, limit)` for the schools that have messages due. The function returns
   only school ids, so the runtime role still cannot read any school's rows without selecting that school.
2. For each school (at most `schools-per-round`, 20), it selects that school as the tenant and takes up to
   `batch-size` (50) due messages. Each message is sent in its own short transaction with
   `select … for update skip locked`, so several API instances can run the dispatcher safely.
3. **Quiet hours** (the school's setting, India time): a message due inside the window is held, without using an
   attempt, until the window ends (for example a message queued at 22:30 goes at 07:00).
4. The first `MessageSender` (in `@Order`) that supports the channel sends it. Success marks it `SENT` or
   `SIMULATED` with `sent_at`.
5. A failure is retried after `retry-base` (1 min), doubling each time up to `retry-max` (1 h), until
   `max-attempts` (5) or a non-retryable `MessageSendException`; then the message is `FAILED`. A failed WhatsApp
   message with a fallback channel is queued once more as an SMS with the same text (`dedupe_key` + `:sms`).
   Only the provider's safe reason (or the exception type for unexpected errors) is stored in `last_error`.

Settings: `akshara.notifications.dispatch.enabled` (`NOTIFICATIONS_DISPATCH_ENABLED`, default true; false in tests
and in the `migrate` task), `interval` (`NOTIFICATIONS_DISPATCH_INTERVAL`), `batch-size`, `schools-per-round`,
`max-attempts`, `retry-base`, `retry-max`.

### Senders

The only sender today is `SimulatedMessageSender`: it accepts every channel, marks the message `SIMULATED`, sends
nothing and logs only the message id and channel. **No message leaves the system.**

A real provider is one more `@Component` implementing `MessageSender`, ordered before the simulated one
(`@Order(0)`), returning `supports(channel) == true` only for its channel and only when its configuration is
present. It receives `OutgoingMessage(id, tenantId, channel, recipient, templateKey, language, params, body)`, sends
it, and returns `SendResult.sent(providerRef)` or throws `MessageSendException(safeReason, retryable)` (rate limits
and timeouts retryable; invalid numbers or rejected templates not). It must never put the recipient or text into
logs or exception messages. Credentials come from the environment (Secrets Manager in AWS), never the repository.

- **SMS through MSG91 (India, DLT):** Indian SMS must use a DLT-registered sender id (header) and template. Register
  the absence template on the DLT portal with variables for `{guardian}`, `{student}`, `{class}`, `{school}` and
  `{date}`, then map `templateKey` + `language` to the DLT template id and the MSG91 flow/template id in the sender's
  configuration (per school when schools have their own headers). Send `params` as the flow variables rather than
  `body`, so the text matches the registered template exactly. Use the message `id` as the reference for delivery
  reports; a delivery-report webhook can later move `SENT` messages to delivered/failed.
- **WhatsApp Cloud API (Meta):** absence alerts are business-initiated, so they need an approved **utility**
  template per language. Map `templateKey` + `language` (`en`, `hi`) to the template name, send `params` as the
  body parameters to the school's phone-number id, and store the returned `wamid` as `provider_ref`. Errors such as
  "not a WhatsApp user" are non-retryable, so the dispatcher falls back to SMS.
- **Email:** the `EMAIL` channel exists in the model; an email sender (SES or SMTP relay) would use `recipient` as
  the address and `body` (or a richer template keyed by `templateKey`) as the text. Nothing queues email yet.

## Audit actions

`attendance.marked` (counts), `attendance.updated` (`changed`, `before`, `after` counts) on entity
`attendance_register`; `notification_settings.updated` (the new values) on entity `notification_settings`. Details
hold the section label, the date and counts, never names of absent students or phone numbers.

## Data

Migration `V5__attendance.sql` adds schemas `attendance` (`register`, `entry`) and `notifications` (`message`,
`settings`). Every table has `tenant_id`, the `tenant_isolation` row-level security policy (USING and WITH CHECK),
grants for the runtime role only, and composite `(tenant_id, …)` foreign keys. `attendance.register` is unique per
`(tenant_id, section_id, attendance_date)`, `attendance.entry` per `(register_id, student_id)`, and
`notifications.message.dedupe_key` per school. School setup will not delete a section or year that has registers.

## Demo data

The demo school has the last 20 school days (Monday to Saturday) marked for every section, about 93% present with a
few late, half-day and leave marks, and Arjun Sharma absent on the most recent marked day. Class 5 A is marked by its
class teacher (Ravi Kumar) and left unmarked today; the other sections are marked by the principal. The resulting
absence alerts went through the outbox and are `SIMULATED` in the message log (alerts from today wait for the end of
quiet hours if the demo is created in the evening).
