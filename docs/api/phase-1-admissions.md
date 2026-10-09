# Phase 1 API: admissions

Same conventions as [Phase 1](phase-1.md): JSON under `/api`, `Authorization: Bearer <accessToken>`,
errors as RFC 9457 problem details `{ type, title, status, detail, errors?: { field: message } }`.
Ids from another school answer `404`; another school's id inside a body answers `400` with a field
error. Dates are `"YYYY-MM-DD"` school days in India (Asia/Kolkata); moments (`scheduledAt`, `at`)
are ISO-8601 instants in UTC. Money is whole paise. Mobile numbers follow the guardian rules (10 digits
starting 6–9, optional `+91`/`91`/`0`, spaces or hyphens) and are stored as the 10 digits.

## The pipeline

```
ENQUIRY ─▶ APPLICATION ─▶ ASSESSMENT ─▶ OFFERED ─▶ ADMITTED
   │            │  └──────────────────────▲  │
   └────────────┴──────────────┴──────────┴──┴─▶ REJECTED | WITHDRAWN
```

| From | Allowed moves |
| --- | --- |
| `ENQUIRY` | `APPLICATION`, `REJECTED`, `WITHDRAWN` |
| `APPLICATION` | `ASSESSMENT`, `OFFERED` (no test needed), `REJECTED`, `WITHDRAWN` |
| `ASSESSMENT` | `OFFERED`, `REJECTED`, `WITHDRAWN` |
| `OFFERED` | `ADMITTED` (only through `/admit`), `REJECTED`, `WITHDRAWN` |
| `ADMITTED`, `REJECTED`, `WITHDRAWN` | none: the application is closed |

A refused move answers `409` with `errors.stage`. Scheduling the first test or interview moves an
`APPLICATION` to `ASSESSMENT`. Closed applications cannot be edited, but notes can still be added.
Every change adds an entry to the application's timeline (who and when) and to the audit trail.

## Permissions

| Permission | Who has it (seeded roles) | Allows |
| --- | --- | --- |
| `admissions.read` (new) | School Admin, Principal, Front office | the board, list, details, upcoming slots, summary |
| `admissions.manage` (new) | School Admin, Principal, Front office | every change, including admitting |

Migration `V4__admissions.sql` adds both codes to those roles in every existing school. Teachers,
accountants, parents and students get `403` from every `/api/admissions` endpoint. Admitting creates the
student through the students module, so a Principal with `admissions.manage` can admit without
`students.manage`.

## Staff endpoints — `/api/admissions`

| Method | Path | Permission | Body | Success |
| --- | --- | --- | --- | --- |
| GET | `/summary` | read | | `200 Summary` |
| GET | `/board?yearId&classId&source&q` | read | | `200 Board` |
| GET | `/applications?yearId&classId&stage&source&q&page=0&size=25` | read | | `200 ApplicationPage` (size ≤ 100) |
| GET | `/applications/{id}` | read | | `200 ApplicationDetail` |
| GET | `/slots/upcoming?days=14` | read | | `200 UpcomingSlot[]`, soonest first (days 1–366) |
| GET | `/staff` | read | | `200 StaffRef[]`: active people with a role other than Parent or Student |
| POST | `/applications` | manage | `ApplicationRequest` | `201 ApplicationDetail` |
| PUT | `/applications/{id}` | manage | `ApplicationRequest` (`stage`, `note` ignored) | `200 ApplicationDetail`; `409` when closed |
| POST | `/applications/{id}/stage` | manage | `StageRequest` | `200 ApplicationDetail`; `409` for a refused move |
| POST | `/applications/{id}/notes` | manage | `{ note }` (≤ 2000) | `201 ApplicationDetail` |
| PUT | `/applications/{id}/fee` | manage | `FeeRequest` | `200 ApplicationDetail`; recording again corrects it |
| PUT | `/applications/{id}/offer` | manage | `{ offeredOn, validUntil? }` | `200 ApplicationDetail`; `409` unless `OFFERED` |
| POST | `/applications/{id}/slots` | manage | `SlotRequest` | `201 ApplicationDetail`; `409` unless `APPLICATION`/`ASSESSMENT` |
| PUT | `/applications/{id}/slots/{slotId}` | manage | `SlotRequest` | `200 ApplicationDetail`; `409` unless the slot is `SCHEDULED` |
| POST | `/applications/{id}/slots/{slotId}/outcome` | manage | `{ notes }` (≤ 2000) | `200 ApplicationDetail`; the slot becomes `DONE` |
| POST | `/applications/{id}/slots/{slotId}/cancel` | manage | | `200 ApplicationDetail`; `409` unless `SCHEDULED` |
| POST | `/applications/{id}/admit` | manage | `AdmitRequest` | `200 ApplicationDetail` with `studentId` |

`q` searches the child's name, guardian names and (when it looks like a number, 4+ digits) guardian
mobiles. The list is newest first; `stageCounts` ignores the `stage` filter so tabs can show counts.
The board has one lane per pipeline stage (at most 50 cards each, `total` counts all); open lanes put the
earliest follow-up first, then the longest wait; the admitted lane shows the latest first. Rejected and
withdrawn applications are only counted (`closed`).

```ts
type Stage = "ENQUIRY" | "APPLICATION" | "ASSESSMENT" | "OFFERED" | "ADMITTED" | "REJECTED" | "WITHDRAWN";
type Source = "WALK_IN" | "WEBSITE" | "PHONE" | "REFERRAL" | "OTHER";

type GuardianInput = { name: string; relation: "MOTHER" | "FATHER" | "GUARDIAN";
  phone: string; email?: string; primary?: boolean };      // 1–4; the first is primary when none is marked
type ApplicationRequest = {
  stage?: "ENQUIRY" | "APPLICATION";       // create only; default ENQUIRY
  firstName: string; lastName?: string; dateOfBirth: string; gender?: "MALE" | "FEMALE" | "OTHER";
  previousSchool?: string; classId: string; academicYearId: string;   // the current or a later year
  source: Source; assignedToId?: string;   // a staff member (counsellor)
  followUpOn?: string;                     // today or later
  note?: string;                           // create only: the first timeline entry's note
  guardians: GuardianInput[] };
type StageRequest = { stage: Stage; note?: string;
  offeredOn?: string; offerValidUntil?: string };   // moving to OFFERED: default today and +14 days
type FeeRequest = { status: "PAID" | "WAIVED"; amountPaise?: number;   // PAID: 1 … 100000000, and a method
  method?: "CASH" | "UPI" | "CARD" | "BANK_TRANSFER"; reference?: string; paidOn: string; note?: string };
type SlotRequest = { kind: "TEST" | "INTERVIEW"; scheduledAt: string;   // in the future, within a year
  mode: "IN_PERSON" | "ONLINE"; location?: string;   // required in person
  meetingLink?: string;                               // required online, https://
  interviewerId?: string };
type AdmitRequest = { sectionId: string;   // a section of the class applied for
  admissionNo: string; rollNo?: number; admissionDate?: string;   // default today
  gender?: "MALE" | "FEMALE" | "OTHER" };  // required when the application has none

type ApplicationRow = { id: string; childName: string; classId: string; className: string;
  academicYearId: string; academicYearName: string; stage: Stage; stageChangedAt: string;
  daysInStage: number; followUpOn: string | null; source: Source; contactName: string | null;
  contactPhone: string | null;   // masked, e.g. "98•••••210"
  assignedToName: string | null; nextSlotAt: string | null; createdAt: string; nextStages: Stage[] };
type ApplicationPage = { items: ApplicationRow[]; page: number; size: number; total: number;
  stageCounts: Record<Stage, number> };
type Board = { lanes: { stage: Stage; total: number; cards: ApplicationRow[] }[]; closed: number };
type ApplicationDetail = { id: string; stage: Stage; stageChangedAt: string; daysInStage: number;
  nextStages: Stage[]; firstName: string; lastName: string | null; childName: string;
  dateOfBirth: string; gender: string | null; previousSchool: string | null;
  classId: string; className: string; academicYearId: string; academicYearName: string;
  source: Source; assignedTo: StaffRef | null; followUpOn: string | null;
  message: string | null;                          // from the public form
  consentVersion: string | null; consentAt: string | null;   // public form only
  guardians: { name: string; relation: string; phone: string; email: string | null; primary: boolean }[];
  fee: { status: "PAID" | "WAIVED"; amountPaise: number | null; method: string | null;
         reference: string | null; paidOn: string } | null;
  offer: { offeredOn: string; validUntil: string | null } | null;
  studentId: string | null;
  slots: { id: string; kind: string; scheduledAt: string; mode: string; location: string | null;
           meetingLink: string | null; interviewer: StaffRef | null;
           status: "SCHEDULED" | "DONE" | "CANCELLED"; outcomeNotes: string | null }[];
  timeline: { id: string; at: string; kind: TimelineKind; actorName: string | null;
              fromStage: Stage | null; toStage: Stage | null; note: string | null;
              details: Record<string, unknown> }[];   // newest first
  createdAt: string };
type TimelineKind = "CREATED" | "STAGE_CHANGED" | "NOTE" | "UPDATED" | "FEE_RECORDED" | "OFFER_UPDATED"
  | "SLOT_SCHEDULED" | "SLOT_RESCHEDULED" | "SLOT_OUTCOME" | "SLOT_CANCELLED";
type UpcomingSlot = { id: string; applicationId: string; childName: string; className: string;
  kind: string; scheduledAt: string; mode: string; location: string | null; meetingLink: string | null;
  interviewer: StaffRef | null };
type Summary = { openEnquiries: number; inProgress: number;   // APPLICATION + ASSESSMENT
  offersPending: number; admittedThisYear: number;              // admitted during the current academic year
  upcomingSlots: number };
type StaffRef = { id: string; name: string };
```

### Admitting

`POST /applications/{id}/admit` turns an `OFFERED` application into a student of the chosen section in
the application's academic year, with the application's guardians (an existing guardian with the same
name and mobile is reused, as on the student form). The admission number, roll number and section
capacity follow the student rules (`400`/`409` with `errors.admissionNo`, `errors.rollNo`,
`errors.sectionId`). The stage becomes `ADMITTED` and `studentId` links to the new student.

The call is idempotent: the application row is locked, and a repeated call on an admitted application
returns the same detail without creating another student. Any other stage answers `409`.

## Public enquiry form (no sign-in)

| Method | Path | Body | Success |
| --- | --- | --- | --- |
| GET | `/api/public/schools/{code}/admission-info` | | `200 PublicSchoolInfo` |
| POST | `/api/public/schools/{code}/enquiries` | `PublicEnquiry` | `201 { received: true }` |

```ts
type PublicSchoolInfo = { name: string; board: string; city: string;
  classes: string[];                                           // class names, in school order
  years: { code: "CURRENT" | "NEXT"; name: string }[];        // NEXT once the school has set it up
  consentVersion: string };
type PublicEnquiry = { parentName: string; relation: "MOTHER" | "FATHER" | "GUARDIAN"; mobile: string;
  email?: string;
  childFirstName: string; childLastName?: string; dateOfBirth: string;
  className: string;              // one of `classes`, any case
  academicYear: "CURRENT" | "NEXT"; message?: string;   // ≤ 1000
  consent: true;                  // required: the family agrees the school may use these details
  consentVersion?: string;        // echo of `consentVersion`; an older text answers 400 errors.consent
  website?: string };             // hidden honeypot: leave empty
```

- The school is found by its code (any case). An unknown or suspended school answers `404` with the
  same body as any missing school; nothing else about the school (ids, contacts, people) is revealed.
- The enquiry is stored as an `ENQUIRY` from `WEBSITE` with the parent as the primary guardian, the
  message, the consent text version and the moment of consent. The timeline entry has no person.
- A filled-in honeypot answers the same `201` but stores nothing.
- Rate limits, per API instance, in a sliding hour: 120 page loads and 20 enquiries per client IP, and
  100 enquiries per school (bot submissions count). Over the limit answers `429` "Too many requests".
  Configurable as `akshara.admissions.public-info-per-ip`, `enquiries-per-ip`, `enquiries-per-school`
  and `enquiry-window`.
- No Aadhaar or other identity number is collected.

## Events

Published as Spring application events (records in `com.akshara.admissions`); nothing is sent to
families yet.

| Event | When | Fields |
| --- | --- | --- |
| `EnquiryReceived` | a public enquiry is stored | `tenantId, applicationId, classId, academicYearId, at` |
| `ApplicationStageChanged` | any stage change, including admitting | `tenantId, applicationId, from, to, actorId, studentId, at` |

## Audit actions

`application.created|updated|stage_changed|note_added|fee_recorded|offer_updated|admitted|enquiry_received`,
`assessment_slot.scheduled|rescheduled|outcome_recorded|cancelled`. Admitting also writes `student.created`.
Details hold ids, class and year names, stages, amounts and dates; never phone numbers, emails, payment
references or dates of birth.

## Data

New schema `admissions`: `application`, `application_guardian`, `timeline_entry`, `assessment_slot`.
Every table has `tenant_id`, the `tenant_isolation` row-level security policy and composite
`(tenant_id, …)` foreign keys; deleting an application removes its guardians, timeline and slots.
A class or academic year that applications point at cannot be deleted: school setup answers `409`
("This conflicts with something that already exists").
