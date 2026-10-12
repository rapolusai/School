# Phase 2 API: front office

Same conventions as [Phase 0](phase-0.md) and [Phase 1](phase-1.md): JSON under `/api`, a Bearer access token,
RFC 9457 problem details with `errors.<field>`, ids from another school in a path answer `404` and in a body answer
`400` on that field, conflicts answer `409`, dates are `"YYYY-MM-DD"` days in India (Asia/Kolkata), times are
instants, and every change is written to the audit trail with readable labels (`audit.action.*`, `audit.entity.*`).

**In this slice:** the Security role; the visitor log (check in and out, a photo from the phone camera, badge
numbers, a printable slip, expected visitors, an audited blocklist, a printable daily register); early pickup gate
passes with a one-time password sent to the parent and entered by the guard; the pickup persons a parent authorises;
the call log and enquiry log with a follow-up queue and conversion of an admission enquiry into an application;
the postal and courier register; four reports with CSV; the family app's "Early pickup" page; dashboard tiles and a
class teacher's early-pickups card; demo data (`security@demo.akshara.test`).

**Not in this slice:** real SMS (the OTP goes through the notifications outbox like every other message), app push
notifications (the `EarlyPickupApproved` event is ready for them), visitor pre-registration by parents, and
parcel tracking with carriers.

## Permissions and roles

| Permission | Who has it (seeded roles) | Allows |
| --- | --- | --- |
| `frontoffice.read` (new) | School Admin, Principal, Front office | reading the visitor log, register, blocklist, passes, calls, enquiries, follow-ups, courier register and reports |
| `frontoffice.manage` (new) | School Admin, Front office | everything else under `/api/frontoffice`, and standing in at the gate |
| `gate.pass` (new) | School Admin, Front office, Security | the gate screen: visitors in and out with a photo, releasing a child against the OTP |
| `child.view` | Parent | `/api/me/...` early pickup and pickup persons for their own children |

New role **`SECURITY`** ("Security"): `dashboard.view`, `leave.request`, `notices.read`, `gate.pass`. Migration
`V19__frontoffice.sql` adds the role to every existing school and appends the permissions to the matching roles
(idempotent). Converting an enquiry needs `frontoffice.manage` **and** `admissions.manage`.

Personal data: phone numbers are kept (to call back and to match the blocklist) and **masked in every list**
(`98765•••01`); a visitor's full number is in the detail view only. Only the *kind* of identity document a visitor
showed is kept (`idType`), never its number. An OTP is stored as a salted SHA-256 hash and appears in no response.

## Visitors

| Method | Path | Permission | Success |
| --- | --- | --- | --- |
| GET | `/api/frontoffice/visitors?date=` | read or gate | `200 VisitorDay` for the day (default today) |
| GET | `/api/frontoffice/visitors/register?date=` | read | `200 VisitorView[]` for the printed register |
| GET | `/api/frontoffice/visitors/{id}` | read or gate | `200 VisitorView` with the full `phone` |
| GET | `/api/frontoffice/staff` | read or gate | `200 {id, name, designation}[]`: who a visitor may come to meet |
| POST | `/api/frontoffice/visitors` | gate | `201 VisitorView`; body `VisitorForm` |
| PUT | `/api/frontoffice/visitors/{id}` | `frontoffice.manage` | `200 VisitorView`; details only, never the times |
| POST | `/api/frontoffice/visitors/{id}/check-in` | gate | `200 VisitorView`; body `{badgeNo?, override?}`; `409` when not EXPECTED |
| POST | `/api/frontoffice/visitors/{id}/check-out` | gate | `200 VisitorView`; `409` when not inside |
| POST | `/api/frontoffice/visitors/{id}/photo` | gate | `200 VisitorView`; multipart `file` (JPEG, PNG or WEBP) |
| GET | `/api/frontoffice/blocklist` | read or gate | `200 BlockedView[]` |
| POST | `/api/frontoffice/blocklist` | `frontoffice.manage` | `201 BlockedView`; body `{name, phone?, reason}` |
| PUT | `/api/frontoffice/blocklist/{id}` | `frontoffice.manage` | `200 BlockedView` |
| DELETE | `/api/frontoffice/blocklist/{id}` | `frontoffice.manage` | `204` (audited `visitor_block.removed`) |

"read or gate" is `frontoffice.read`, `frontoffice.manage` or `gate.pass`; "gate" is `gate.pass` or
`frontoffice.manage`.

```ts
type VisitorForm = { name; phone?; purpose: "MEETING"|"ADMISSION"|"FEES"|"DELIVERY"|"VENDOR"|"INTERVIEW"|"OFFICIAL"|"OTHER";
  purposeNote?; toMeetUserId?; toMeetName?; peopleCount?: 1..50 (default 1);
  idType?: "NONE"|"AADHAAR"|"VOTER_ID"|"DRIVING_LICENCE"|"PAN"|"PASSPORT"|"COMPANY_ID"|"OTHER";
  badgeNo?; expectedOn?: date; override?: boolean };
type VisitorView = { id, name, phoneMasked, phone (detail only), purpose, purposeNote, toMeetUserId, toMeetName,
  peopleCount, idType, badgeNo, photoFileId, status: "EXPECTED"|"IN"|"OUT", expectedOn, checkedInAt, checkedOutAt,
  addedByName, checkedInByName, checkedOutByName, blocked: {id, reason} | null };
type VisitorDay = { date, visitors: VisitorView[], inside: number, expected: VisitorView[] };
```

- Without `expectedOn` the visitor is checked in now; with it (today or later, else `400 errors.expectedOn`) they
  are EXPECTED for that day and the gate checks them in when they arrive.
- The blocklist is matched by phone, or by name when the entry has no phone. A match on a check-in answers
  `409` with type `urn:akshara:frontoffice:blocked` and the reason in `detail`. The office (`frontoffice.manage`)
  may send `override: true`; the audit record then carries `blocklistOverridden` and the reason. The gate cannot
  override.
- The photo is taken with `<input type="file" accept="image/*" capture>` and shrunk in the browser to about 640 px
  before upload; it is stored through the files module and downloaded with `GET /api/files/{photoFileId}` by
  anyone holding `frontoffice.read` or `gate.pass`.
- Audit: `visitor.expected`, `visitor.checked_in`, `visitor.checked_out`, `visitor.updated`, `visitor.photo_added`;
  `visitor_block.added/updated/removed`. Visitor rows are never deleted.

## Early pickup gate passes

A parent asks in the app (or the office raises the pass on a phone call); the office approves; a six-digit OTP goes
to the primary guardian's phone through the notifications outbox (`frontoffice.gate_pass_otp`, related
`gate_pass/{passId}`); the collector reads it out at the gate; the guard enters it; the child is released. The OTP
is **never shown to staff**: not in any view, not in the message log's list (the body is readable in
`GET /api/messages/{id}` as for every message, which is how a tester gets it).

| Method | Path | Permission | Success |
| --- | --- | --- | --- |
| GET | `/api/frontoffice/gate-passes?date=` | read | `200 GatePassView[]` for the day (default today), waiting first |
| GET | `/api/frontoffice/gate-passes/{id}` | read or gate | `200 GatePassView` |
| GET | `/api/frontoffice/students?q=` | `frontoffice.manage` | `200 StudentRef[]` (active students, name or admission number, at least 2 characters) |
| POST | `/api/frontoffice/gate-passes` | `frontoffice.manage` | `201 GatePassView`; body `PickupRequest & {studentId}` (source OFFICE, still needs approval) |
| POST | `/api/frontoffice/gate-passes/{id}/approve` | `frontoffice.manage` | `200 GatePassView`; body `{note?}`; generates and sends the OTP |
| POST | `/api/frontoffice/gate-passes/{id}/reject` | `frontoffice.manage` | `200 GatePassView`; body `{note}` (required) |
| GET | `/api/frontoffice/gate` | gate | `200 GateScreen`: today's approved passes, visitors inside and expected |
| POST | `/api/frontoffice/gate-passes/{id}/release` | gate | `200 GatePassView`; body `{otp, collectedByName}` |
| GET | `/api/frontoffice/students/{studentId}/pickup-persons` | read or gate | `200 {studentId, studentName, className, persons: PickupPersonView[]}` |

Parent app (`child.view`, own children only; another child's id answers `404`):

| Method | Path | Success |
| --- | --- | --- |
| GET | `/api/me/gate-passes` | `200 GatePassView[]` across the parent's children, latest first |
| GET | `/api/me/children/{studentId}/pickup` | `200 {studentId, studentName, className, canRequest, passes, persons}` |
| POST | `/api/me/children/{studentId}/gate-passes` | `201 GatePassView`; body `PickupRequest` |
| POST | `/api/me/children/{studentId}/gate-passes/{id}/cancel` | `200 GatePassView`; only REQUESTED or APPROVED |
| POST | `/api/me/children/{studentId}/pickup-persons` | `201 PickupPersonView`; body `{name, relation, phone}`; at most 5 (`409`) |
| PUT | `/api/me/children/{studentId}/pickup-persons/{id}` | `200 PickupPersonView` |
| DELETE | `/api/me/children/{studentId}/pickup-persons/{id}` | `204` |
| POST | `/api/me/children/{studentId}/pickup-persons/{id}/photo` | `200 PickupPersonView`; multipart `file` |

```ts
type PickupRequest = { pickupAt: instant (today, not in the past); reason; collectorKind: "SELF"|"OTHER";
  collectorName?; collectorPhone? };   // both required for OTHER
type GatePassView = { id, studentId, studentName, admissionNo, className, source: "PARENT_APP"|"OFFICE",
  requestedByName, pickupAt, reason, collectorKind, collectorName, collectorPhoneMasked,
  status: "REQUESTED"|"APPROVED"|"RELEASED"|"REJECTED"|"CANCELLED"|"EXPIRED"|"LOCKED",
  otpExpiresAt, attemptsLeft, guardianName, guardianPhoneMasked, approvedByName, approvedAt, decisionNote,
  releasedByName, releasedAt, collectedByName, createdAt, canCancel, canDecide, canRelease };
```

- One open pass (REQUESTED or APPROVED) per student at a time (`409 "Pass already open"`); the student must be
  active and have a primary guardian with a phone (`409`).
- The OTP is six digits from a secure random source, **valid for four hours** after approval; a release after that
  answers `409 "OTP expired"` and the pass becomes EXPIRED (audited `gate_pass.expired`).
- A wrong OTP answers `409` with type `urn:akshara:frontoffice:wrong-otp` and `errors.otp`, counts a try
  (`attemptsLeft` goes down even though the request failed) and is audited `gate_pass.wrong_otp`. The **fifth
  wrong try locks the pass** (`409`, type `urn:akshara:frontoffice:pass-locked`, status LOCKED, audited
  `gate_pass.locked`); the office raises a new one.
- Approving publishes `EarlyPickupApproved {tenantId, passId, studentId, studentName, sectionId,
  classTeacherUserId, pickupAt, at}` in the same transaction. The class teacher is told in the app through the
  dashboard card below; cancelling, rejecting or releasing marks any unsent OTP message as skipped.
- Audit: `gate_pass.requested/approved/rejected/cancelled/expired/wrong_otp/locked/released`,
  `pickup_person.added/updated/removed/photo_added`.

## Calls, enquiries and follow-ups

| Method | Path | Permission | Success |
| --- | --- | --- | --- |
| GET | `/api/frontoffice/calls?date=` | read | `200 CallView[]` for the day; without `date` the latest 50 |
| POST | `/api/frontoffice/calls` | `frontoffice.manage` | `201 CallView`; body `CallForm` |
| PUT | `/api/frontoffice/calls/{id}` | `frontoffice.manage` | `200 CallView` |
| POST | `/api/frontoffice/calls/{id}/close` | `frontoffice.manage` | `200 CallView`; body `{outcome?}`; `409` when already done |
| GET | `/api/frontoffice/enquiries?date=` | read | `200 EnquiryView[]` for the day; without `date` the latest 50 |
| GET | `/api/frontoffice/enquiries/{id}` | read | `200 EnquiryView` |
| POST | `/api/frontoffice/enquiries` | `frontoffice.manage` | `201 EnquiryView`; body `EnquiryForm` |
| PUT | `/api/frontoffice/enquiries/{id}` | `frontoffice.manage` | `200 EnquiryView` |
| POST | `/api/frontoffice/enquiries/{id}/close` | `frontoffice.manage` | `200 EnquiryView`; body `{outcome}` (required) |
| POST | `/api/frontoffice/enquiries/{id}/reopen` | `frontoffice.manage` | `200 EnquiryView`; body `{followUpOn?}`; only CLOSED |
| POST | `/api/frontoffice/enquiries/{id}/convert` | `frontoffice.manage` + `admissions.manage` | `200 EnquiryView` with `applicationId`; body `ConvertForm` |
| GET | `/api/frontoffice/follow-ups` | read | `200 {today, items: FollowUp[], dueToday, overdue}` |

```ts
type CallForm = { direction: "INCOMING"|"OUTGOING"; callerName; phone?; about; outcome?; followUpOn?;
  assignedToUserId?; calledAt? (default now, not in the future) };
type EnquiryForm = { source: "WALK_IN"|"PHONE"; topic: "ADMISSION"|"FEES"|"GENERAL"; name; phone?; childName?;
  className?; details; followUpOn?; assignedToUserId?; enquiredAt? };
type ConvertForm = { classId; academicYearId; dateOfBirth; firstName?; lastName?; relation?; phone? };
type FollowUp = { kind: "CALL"|"ENQUIRY", id, name, about, followUpOn, daysOverdue, assignedToName, phoneMasked };
```

- An enquiry is OPEN, FOLLOW_UP (it has a follow-up date), CLOSED (with an outcome) or CONVERTED. Only an
  ADMISSION enquiry converts (`409`); the application is created in the admissions module with the child's name
  and the parent's phone from the enquiry unless given, and the enquiry keeps `applicationId`.
- The follow-up queue lists open calls and enquiries with a follow-up date on or before today, overdue first, and
  feeds the dashboard tile and the "Follow-ups" tab of the enquiries page.
- Audit: `call.logged/updated/closed`, `enquiry.logged/updated/closed/reopened/converted`.

## Postal and courier register

| Method | Path | Permission | Success |
| --- | --- | --- | --- |
| GET | `/api/frontoffice/courier?from=&to=` | read | `200 CourierView[]` (default: this month), newest first |
| POST | `/api/frontoffice/courier` | `frontoffice.manage` | `201 CourierView`; body `CourierForm` |
| PUT | `/api/frontoffice/courier/{id}` | `frontoffice.manage` | `200 CourierView` |
| DELETE | `/api/frontoffice/courier/{id}` | `frontoffice.manage` | `204` (audited `courier.removed`) |

```ts
type CourierForm = { direction: "INWARD"|"OUTWARD"; item; sender; receiver; itemDate; carrier?; trackingNo?;
  receivedBy?; note? };
```

## Reports

All under `/api/frontoffice/reports`, permission `frontoffice.read`; `from`/`to` default to this month; the CSV
twins answer `text/csv` with a BOM and a `Content-Disposition` attachment.

| Path | Answer |
| --- | --- |
| `/visitors[.csv]?from&to` | `{from, to, total, people, byDay: {date, count, people}[], byPurpose: {name, count}[], rows: VisitorView[]}` |
| `/gate-passes[.csv]?from&to` | `{from, to, total, byDay: {date, requested, approved, released, rejected, cancelled, expired, locked}[], rows}` |
| `/enquiries[.csv]?from&to` | `{from, to, total, bySource, byTopic, byOutcome, rows: EnquiryView[]}` |
| `/follow-ups[.csv]` | `{asOf, overdue, rows: FollowUp[]}`: everything whose follow-up date has passed |

The web reports hub lists them under a new "Front office" group for `frontoffice.read`.

## Dashboard

`GET /api/dashboard` gains `frontOffice`: `{passesToApprove, followUpsDue, followUpsOverdue, passesAtGate,
visitorsInside, visitorsToday, classPickups}`. Each count is `null` for someone without the permission behind it
(`frontoffice.read` for the first three, `gate.pass` or `frontoffice.manage` for the gate and visitor counts).
`classPickups` lists today's approved or released passes of the sections the person is class teacher of
(`{passId, studentName, className, pickupAt, collector, status}`), so the class teacher is told in the app; the
card is `null` when nothing applies.

## Web

Staff: `/app/frontoffice` (visitors today), `/register`, `/blocklist`, `/passes`, `/gate` (the guard's phone
screen), `/enquiries` (follow-ups, enquiries and calls tabs), `/courier`, `/visitors/{id}` (printable slip);
reports at `/app/reports/frontoffice-visitors|passes|enquiries|follow-ups`. Family: `/app/family/pickup`
("Early pickup" and "Pickup persons") plus a card on the family home. Navigation: "Front office" for
`frontoffice.read`, "Gate" for `gate.pass`, "Early pickup" for parents.

## Demo data

`DemoFrontOfficeData` adds the guard **Ramesh Yadav** (`security@demo.akshara.test`, employee code AKS-501, on the
payroll), three visitors (two inside), one blocklist entry, Arjun Sharma's two pickup persons and an approved
pass for today collected by Rajesh Sharma, enquiries and calls with two follow-ups due today and one overdue, and
two courier items.

## Tests

Unit: `OtpTest`, `FrontOfficeTextTest` (masking). Integration: `VisitorIT`, `GatePassIT` (request, approve, OTP from
the outbox, wrong tries and lock, release, parent cancel, pickup persons and photo), `EnquiryIT` (calls, enquiries,
follow-ups, convert, courier, reports), `RowLevelSecurityCoverageIT` (the seven tables), `DemoDataSeederIT`.
Vitest: visitor form with the camera fallback, the gate screen, the family pickup request, nav and dashboard
tiles. Playwright: `e2e/tests/frontoffice.spec.ts`.
