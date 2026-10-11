# Phase 2 API: parent–teacher messages and PTM

Same conventions as [Phase 0](phase-0.md) and [Phase 1](phase-1.md): JSON under `/api`, a Bearer access token,
RFC 9457 problem details with `errors.<field>`, ids from another school in a path answer `404` and in a body answer
`400` on that field, conflicts answer `409`, dates are `"YYYY-MM-DD"` days in India (Asia/Kolkata), times of day are
`"HH:mm:ss"` (requests also take `"HH:mm"`), instants are UTC, and every change is written to the audit trail.

**In this slice (module `com.akshara.chat`, migration `V18__chat.sql`):** one-to-one conversations between a parent
and a member of staff about one student, with attachments, read receipts, unread counts, delete within ten minutes,
reporting to the Principal, school hours for messages and a teacher's "away" note; the Principal's audited oversight
(read, close, reopen, review reports); parent–teacher meetings (PTM) with slots made from the hours, breaks and slot
length, parent self-booking until a cut-off, bookings by the school, blocked slots, per-teacher hours, attendance and
notes, printable schedules by teacher and by class, optional reminders, and an attendance summary in the reports hub.

**Not in this slice:** group chats or class broadcasts (circulars cover those), real-time push (the web app polls),
video calls (a PTM only carries a link the school types), translation of messages, and students writing to staff.
The existing `/api/messages` (the notifications log, `messages.read`) is unrelated and unchanged.

## Permissions and roles

| Permission | Seeded roles | Allows |
| --- | --- | --- |
| `chat.use` (new) | School Admin, Principal, Teacher | `/api/chat` (own conversations, away note), `/api/ptm/my` (own PTM schedule) |
| `chat.oversee` (new) | School Admin, Principal | `/api/chat/oversight`, `PUT /api/chat/settings`; may write to any parent |
| `ptm.manage` (new) | School Admin, Principal | `/api/ptm` (all meetings, bookings for families, reminders, attendance report) |
| `child.view` | Parent | `/api/me/chat`, `/api/me/ptm` (their own children only) |

`V18__chat.sql` appends the codes to the matching built-in roles of every existing school (idempotent); new schools
get them from `RoleCatalog`. A school can grant `chat.use` to another staff role from the roles screen.

## Who can talk to whom

- A **parent** reaches the class teacher of each of their children and the subject teachers from the timetable, while
  those teachers are active and hold `chat.use`. Students who left the school cannot start a conversation.
- A **member of staff** reaches the parents (with a sign-in) of active students in the sections they lead (class
  teacher) or teach (timetable) this year; with `chat.oversee`, any section.
- A conversation is one parent, one member of staff and one student; starting it again returns the existing one.
- Conversations of other people answer `404`, like ids of another school.
- **No response carries a phone number or an email address.** Staff see the parent's name and the child; parents see
  the teacher's name and what they teach the child ("Class teacher · Mathematics").
- A conversation becomes **read-only** (`readOnlyReason`) when the Principal closes it (`CLOSED`), the student's data
  was erased (`ERASED`), the student left (`STUDENT_LEFT`), the teacher no longer has an active sign-in with
  `chat.use` (`STAFF_LEFT`), or the parent is no longer linked to the student (`PARENT_UNLINKED`). Sending then
  answers `409`.

## Rules

| Rule | Value |
| --- | --- |
| Message text | up to 2,000 characters; text, attachments or both |
| Attachments | up to 3 per message; same types and 5 MB limit as homework (files module) |
| Rate limit | 20 messages per person per hour; the 21st answers `429` with `errors.body` saying how long to wait |
| Delete | own message, within 10 minutes, for both sides; a "Message deleted" placeholder stays |
| Page | the newest 50 messages; older ones with `?before=<instant>` (`hasOlder`) |
| Report reason / close reason / review note | up to 500 characters; one open report per person and conversation |
| Away note | back on a day after today, at most 90 days ahead; note up to 300 characters |
| School hours (defaults) | Monday–Saturday, 08:00–18:00; whole-school holidays from the school calendar are off |

Parents may write at any time. Outside the hours, or while the teacher is away, the parent sees when the message
reaches the teacher (`staffAvailability`: `openNow`, `nextOpenAt`, the days and hours, `awayUntil`, `awayNote`).

## Conversations — `/api/chat` (`chat.use`) and `/api/me/chat` (`child.view`)

Both sides have the same endpoints; the start body differs.

| Method | Path | Success |
| --- | --- | --- |
| GET | `/conversations` | `200 ConversationRow[]`, newest message first, with `unread`, `last` (a 120-character preview) and `away` |
| GET | `/unread` | `200 {unread, conversations}` (the dashboard card and the family badge) |
| GET | `/contacts?sectionId=` | `200 Contacts`; staff: the sections they may pick (`sections`), the one shown and its parents; parents: their children's teachers |
| POST | `/conversations` | `201` new or `200` existing `ConversationDetail`; staff `{studentId, parentUserId}`, parents `{studentId, teacherUserId}` |
| GET | `/conversations/{id}?before=` | `200 ConversationDetail`; opening it (without `before`) marks the other side's messages read |
| GET | `/conversations/{id}/changes?since=` | `200 {id, readOnly, readOnlyReason, messages, serverTime}`: messages new, read or deleted since `since` (the last `serverTime`); marks new ones read |
| POST | `/conversations/{id}/messages` | `201 {message, staffAvailability}`; multipart `body` and `files` (0–3) |
| DELETE | `/conversations/{id}/messages/{messageId}` | `200 MessageView` (the placeholder); `409` after 10 minutes, `403` for the other side's message |
| POST | `/conversations/{id}/reports` | `201 ReportView`; body `{reason}` |
| GET / PUT | `/away` (staff only) | `200 {away, returnsOn, note}`; body `{returnsOn, note}`, `returnsOn: null` clears it |
| GET | `/settings` (staff only; `chat.use` or `chat.oversee`) | `200 {availableDays, availableFrom, availableTo, notifyNewMessages, ptmRemindersEnabled}` |
| PUT | `/settings` (`chat.oversee`) | `200` same; body `{availableDays, availableFrom, availableTo, notifyNewMessages}` |

```ts
type MessageView = { id; side: "PARENT" | "STAFF"; mine: boolean; senderName; body: string | null;
  attachments: { id; name; contentType; size }[]; deleted: boolean; erased: boolean; createdAt;
  readAt: string | null;          // when the other side first saw it: the read receipt of one's own message
  deletableUntil: string | null; canDelete: boolean };
type ConversationRow = { id; student: { id; name; classLabel }; withName; withDetail; unread: number;
  last: { text; mine; deleted; attachments; at } | null; lastMessageAt; readOnly; readOnlyReason; away: boolean };
```

Attachments download from `GET /api/files/{id}` for the two people in the conversation while the message is not
deleted, and for `chat.oversee`.

**Polling.** The web app asks for `/changes` of the open conversation and for the list every 15 seconds while the tab
is visible, and stops while it is hidden; when the tab comes back it asks at once. `since` overlaps by 30 seconds so
messages committed just after a look are never missed; the client merges by id.

## Notifications

New-message notices go through the notifications outbox (**simulated**: nothing leaves the system), never with the
message text: one notice per run of unread messages, template `chat.to_staff` (email to the teacher, held until
their next working hour: the school's days and hours, holidays, their away note) or `chat.to_parent` (the school's
alert channel to the parent's own number, held through quiet hours by the dispatcher). A notice still waiting is
dropped when the message is read or deleted first. `notifyNewMessages: false` turns them off; unread counts in the
app do not depend on them.

## Oversight — `/api/chat/oversight` (`chat.oversee`)

| Method | Path | Success |
| --- | --- | --- |
| GET | `/conversations?filter=all\|reported\|closed&q=&page=0&size=20` | `200 {items: OversightRow[], page, size, total, openReports}`; `q` matches student, parent or teacher name |
| GET | `/conversations/{id}` | `200 OversightDetail` with every message, **including deleted ones with their original text** (`deleted`, `deletedAt`) and all reports; **audited** as `chat_conversation.oversight_read` |
| POST | `/conversations/{id}/close` | `200 OversightDetail`; body `{reason}`; both sides can still read it |
| POST | `/conversations/{id}/reopen` | `200 OversightDetail` (`409` when it is read-only for another reason) |
| GET | `/reports?status=open\|all` | `200 ReportView[]` |
| POST | `/reports/{id}/review` | `200 ReportView`; body `{note}`; the reporter is not told |

The list itself shows names and counts only; reading a conversation is what the audit trail records.

## Parent–teacher meetings — `/api/ptm` (`ptm.manage`)

| Method | Path | Success |
| --- | --- | --- |
| GET | `/events` | `200 EventSummary[]`, latest meeting day first |
| POST | `/events` | `201 EventDetail`; `409` without a current academic year |
| GET / PUT | `/events/{id}` | `200 EventDetail`; PUT takes the same body as POST |
| POST | `/events/{id}/cancel` | `200 EventDetail`; body `{reason}`; cancels every booking and drops reminders |
| PUT | `/events/{id}/teachers/{teacherId}/hours` | `200 EventDetail`; body `{availableFrom, availableTo}` inside the meeting |
| PUT | `/slots/{id}/block` | `200 SlotView`; body `{blocked}`; `409` for a booked slot |
| POST | `/events/{id}/bookings` | `201 BookingView`; body `{slotId, studentId}`: the school books for a family (no cut-off) |
| DELETE | `/bookings/{id}` | `204` |
| PUT | `/bookings/{id}` | `200 BookingView`; body `{attendance, privateNote, parentNote}` |
| GET / PUT | `/settings` | `200 {remindersEnabled}` (off by default) |
| GET | `/reports/attendance?eventId=` | `200 AttendanceSummary` |
| GET | `/reports/attendance.csv?eventId=` | `200 text/csv`, `ptm-attendance.csv` |

Event body: `{title ≤150, date (today or later), startsAt, endsAt, slotMinutes 5–60, breaks: [{from, to}] (≤5,
inside the hours, not overlapping), mode: "IN_PERSON" | "VIDEO", location ≤200, videoLink (https://, required for
VIDEO, ≤500), sectionIds (1–100), includeSubjectTeachers, cutoffHours 0–72 (default 12), note ≤1000}`.

- **Slots** are made for the class teacher of each invited section, and for its subject teachers (timetable) when
  `includeSubjectTeachers`, from the meeting's hours less the breaks; a slot that would end after `endsAt` is not made.
  A teacher's hours can be narrowed or widened inside the meeting; slots are made again and a booked slot that would
  no longer fit answers `409`.
- **Changing a meeting:** the day cannot change once families have booked, and no booked slot may fall outside the
  new hours or classes (`409`: cancel or move those bookings first).
- **Bookings:** one booking per slot (`409` "just booked by another family"), one per child and teacher per meeting,
  and never two meetings at the same time for one child or one parent. A slot is offered to parents until
  `cutoffHours` before it starts; parents change or cancel until the same moment. The school's own bookings (by
  `ptm.manage`) skip the cut-off and parents cannot change them.
- **Marking:** attendance (`ATTENDED` / `NO_SHOW`) only on or after the meeting day (`400` before); notes any time.
  `privateNote` is for staff only; `parentNote` reaches the parent from the meeting day, like attendance.

## A teacher's own schedule — `/api/ptm/my` (`chat.use` or `ptm.manage`)

`GET /api/ptm/my` → `MyEvent[]` (meetings where the caller meets parents, upcoming first, then past ones);
`GET /events/{id}` → `MyEvent`;
`PUT /events/{id}/hours`, `PUT /slots/{id}/block` and `PUT /bookings/{id}` as above, limited to the caller's own
slots and bookings (others answer `404`).

## Families — `/api/me/ptm` (`child.view`)

| Method | Path | Success |
| --- | --- | --- |
| GET | `/api/me/ptm` | `200 {children: [{studentId, name, classLabel, events: FamilyEvent[]}], remindersEnabled, serverTime}` |
| POST | `/api/me/ptm/bookings` | `201 FamilyBooking`; body `{slotId, studentId}`; `400` for a child who is not theirs |
| PUT | `/api/me/ptm/bookings/{id}` | `200 FamilyBooking`; body `{slotId}`: move to another free slot with the same teacher |
| DELETE | `/api/me/ptm/bookings/{id}` | `204` |

A family sees only the teachers its child meets, and per slot only whether it is free (`available`,
`bookableUntil`), never who booked the others. Blocked slots are left out. Past meetings stay for 30 days so the
teacher's note can be read. The web page shows the video link's "Join" button once a slot is booked (the API returns
the link to families of the invited classes).

## Reminders

Off by default (`PUT /api/ptm/settings`). When on, the parent who booked (or, for a school booking, the student's
primary guardian) gets a reminder the day before and an hour before the slot, queued in the notifications outbox when
the slot is booked (templates `ptm.reminder` and `ptm.reminder_video`; **simulated**) and dropped when the booking is
cancelled or moved or the meeting is cancelled.

## Attendance summary (reports hub)

Per invited class: students, families that booked, came, did not come, not marked; per teacher: slots (not counting
blocked ones), booked, came, did not come, not marked. The CSV is **UTF-8 with a byte-order mark** (opens in Excel
with ₹ and Hindi intact), columns `Meeting, Date, Type (Class | Total | Teacher), Name, Students or slots, Booked,
Attended, No-show, Not marked`; text that starts with `= + - @` gets a leading apostrophe. The web app lists it in
`/app/reports` under Academics ("PTM attendance") with print.

## Web app

| Route | Who | What |
| --- | --- | --- |
| `/app/chat` | `chat.use` (oversight-only users see the oversight list) | conversations and the open thread (`?c=<id>`), new message, report |
| `/app/chat/oversight`, `/app/chat/oversight/[id]` | `chat.oversee` | conversations, reports, close and reopen |
| `/app/chat/settings` | `chat.use` / `chat.oversee` | away note; school hours (editable with `chat.oversee`) |
| `/app/ptm`, `/app/ptm/[id]` | `ptm.manage` or `chat.use` | meetings and own schedules; a meeting's details, classes and every teacher's slots |
| `/app/ptm/[id]/print?by=teacher\|class` | same | A4 schedules, one page per teacher or per class |
| `/app/reports/ptm` | `ptm.manage` | the attendance summary |
| `/app/family/chat`, `/app/family/ptm` | `child.view` | "Messages" and "PTM" in the family menu |

Cards: "Messages from parents" (unread) and "Next PTM" on the staff dashboard; "Messages" with an unread badge on the
family home. Both poll like the chat.

## Events

The module listens to the privacy module's `StudentDataErased`: inside that transaction it clears the text and
attachments of every message in the student's conversations (placeholders stay), the parent's name on them, report
reasons and the notes of the student's PTM bookings, makes the conversations read-only and drops waiting notices and
reminders. It publishes no events.

## Audit

Entities `chat_conversation`, `chat_message`, `chat_report`, `chat_settings`, `chat_away`, `ptm_event`,
`ptm_teacher`, `ptm_slot`, `ptm_booking`, `ptm_settings`. Actions: `chat_conversation.started|oversight_read|closed|
reopened|reported`, `chat_message.sent|deleted`, `chat_report.reviewed`, `chat_settings.updated`,
`chat_away.updated`, `ptm_event.created|updated|cancelled`, `ptm_teacher.hours_changed`,
`ptm_slot.blocked|unblocked`, `ptm_booking.booked|cancelled|moved|marked`, `ptm_settings.updated`. Message text is
never written to the audit trail. Labels in English and Hindi.

## Data

Schema `chat`: `settings`, `staff_away`, `conversation`, `message`, `report`, `ptm_event`, `ptm_section`,
`ptm_teacher`, `ptm_slot`, `ptm_booking`. Every table has `tenant_id`, row-level security (USING and WITH CHECK),
grants to the application role only, and composite foreign keys `(tenant_id, id)`. Unique indexes keep one
conversation per parent, staff member and student, and one active booking per slot and per child and teacher.

## Demo data

`DemoChatData`: Anitha Sharma (`parent@demo.akshara.test`) and Ravi Kumar (`teacher@`), Arjun's class teacher, wrote
to each other about Arjun over the last two days and her last message is still unread for him; a "Half-yearly
parent-teacher meeting" for Class 5 on the school calendar's PTM day (or the next Saturday once that has passed) with
reminders on, where Anitha has booked 09:30 with Ravi Kumar and the office has booked two of Arjun's classmates.
