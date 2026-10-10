# Phase 1 API: data protection (DPDP Act 2023)

Same conventions as [Phase 0](phase-0.md) and [Phase 1](phase-1.md): JSON under `/api`,
`Authorization: Bearer <accessToken>`, RFC 9457 problem details, `404` for ids of another school in
a path, `400` with a field error for ids of another school (or of another family) in a body, `409`
for conflicts. Dates are `"YYYY-MM-DD"` school days in India (Asia/Kolkata).

The module `com.akshara.privacy` (migration `V11__privacy.sql`, schema `privacy`) gives each school:

- a **privacy notice** in English and Hindi, versioned, with a **grievance officer** shown on it,
  readable by anyone at `/privacy/<schoolCode>`;
- **consent per child and purpose**: `ESSENTIAL` (school records, needed to use the parent app),
  `PHOTOS` and `WHATSAPP` (optional, can be withdrawn at any time); given online by the parent or
  entered by staff from a signed paper form, always against a notice version, with full history;
- **data requests** from parents (copy of data, correction, erasure, grievance) about themselves or
  one of their children, answered within 30 days, with a timeline both sides can see;
- **data export**: a ZIP of JSON files for an access request, downloadable for 7 days;
- **erasure** of a child who has left the school, keeping what the law requires.

Nothing here sends SMS, WhatsApp or email, and absence alerts and fee messages are unchanged (see
[Events](#events-for-other-modules)).

## Permissions

| Permission | Who has it (seeded roles) | Allows |
| --- | --- | --- |
| `privacy.manage` (new) | School Admin, Principal | everything under `/api/privacy`: notice, grievance officer, consent list and paper forms, the request queue, exports, erasure |
| `child.view` | Parent (and School Admin) | everything under `/api/me/privacy` for the caller's own children and requests |

`V11__privacy.sql` appends `privacy.manage` to the built-in School Admin and Principal roles of every
existing school, once (new schools get it from the role catalogue). A school can give it to a custom
role (for example an office manager). Teachers, accountants, front office, parents and students get
`403` from every `/api/privacy` endpoint.

## Public notice — `/api/public/schools/{code}/privacy-notice`

| Method | Path | Body | Success |
| --- | --- | --- | --- |
| GET | `?version=` (optional) | | `200 PublicNotice`; `404` if the school is unknown or suspended, has no notice, or the version does not exist; `429` after 120 requests per IP address per hour |

```ts
type GrievanceOfficer = { name: string; email: string; phone: string; updatedAt: string | null };
type NoticeVersion = { version: number; publishedAt: string; publishedByName: string | null; changeSummary: string | null };
type PublicNotice = {
  schoolName: string; schoolCode: string;
  version: number; currentVersion: number; publishedAt: string; changeSummary: string | null;
  bodyEn: string; bodyHi: string;                // plain text: "## " heading lines, "- " list lines
  grievanceOfficer: GrievanceOfficer;            // the officer named when that version was published
  versions: NoticeVersion[];                     // newest first; publishedByName is always null here
};
```

## Staff — `/api/privacy` (all `privacy.manage`)

### Notice and grievance officer

| Method | Path | Body | Success |
| --- | --- | --- | --- |
| GET | `/notice` | | `200 NoticeAdmin` |
| GET | `/notice/versions/{version}` | | `200 PrivacyNotice` (any version, old ones stay readable) |
| POST | `/notice` | `NoticeForm` | `201 PrivacyNotice`; `400 grievanceOfficer` until a grievance officer is saved; `400 changeSummary` when it is missing after version 1 |
| PUT | `/grievance-officer` | `OfficerForm` | `200 GrievanceOfficer` |

```ts
type OfficerForm = { name: string /* ≤200 */; email: string; phone: string /* 8–15 digits */ };
type NoticeForm = { bodyEn: string /* ≤20000 */; bodyHi: string /* ≤20000 */; changeSummary: string | null /* ≤500 */ };
type PrivacyNotice = { version: number; current: boolean; bodyEn: string; bodyHi: string;
  changeSummary: string | null; publishedAt: string; publishedByName: string | null; grievanceOfficer: GrievanceOfficer };
type NoticeAdmin = { officer: GrievanceOfficer | null; current: PrivacyNotice | null;
  draftEn: string; draftHi: string;   // the current text, or the standard template with the school's name
  draftIsTemplate: boolean; versions: NoticeVersion[] };
```

Published versions are never changed (the runtime role has no `update` on `privacy_notice`). The
standard template covers who the school is, what is collected (never Aadhaar), essential and
optional purposes, who sees the data, how long it is kept, the parent's rights, the 30-day reply and
the Data Protection Board of India; schools should check it before publishing version 1. Publishing a
new version makes every parent with an active child accept again (below).

### Consent

| Method | Path | Body | Success |
| --- | --- | --- | --- |
| GET | `/consents?q=&page=&size=` | | `200 ConsentPage`: students (search by name or admission number, as on the student list; 1–100 per page, 25 by default) with each purpose's state against the current notice |
| GET | `/students/{studentId}/consents` | | `200 StudentConsent` with the full history |
| POST | `/students/{studentId}/consents` | `PaperConsentForm` | `201 StudentConsent`; `409` when no notice is published; `400 signedOn` for a future date |

```ts
type PurposeState = { purpose: "ESSENTIAL" | "PHOTOS" | "WHATSAPP"; status: "GIVEN" | "DECLINED" | "WITHDRAWN" | "NONE";
  at: string | null; noticeVersion: number | null; method: "ONLINE" | "PAPER" | null; current: boolean };
type ConsentEntry = { id: string; purpose: string; action: "GIVEN" | "DECLINED" | "WITHDRAWN"; method: "ONLINE" | "PAPER";
  noticeVersion: number; givenByName: string; recordedByName: string | null;
  paperReference: string | null; signedOn: string | null; at: string };
type ConsentPage = { items: { studentId; fullName; admissionNo; className; sectionName; status; purposes: PurposeState[] }[];
  page; size; total; noticeVersion: number | null; activeStudents: number; essentialGiven: number };
type PaperConsentForm = { givenByName: string /* ≤200 */; signedOn: string; paperReference: string | null /* ≤100 */;
  photos: boolean; whatsapp: boolean };     // records ESSENTIAL given plus the two choices
```

### Requests

| Method | Path | Body | Success |
| --- | --- | --- | --- |
| GET | `/requests?status=&type=&q=&page=&size=` | | `200 DataRequestPage`: `status` is `OPEN` (default in the app), `SUBMITTED`, `IN_PROGRESS` or `CLOSED`; `q` matches the requesting parent's name. Open requests first by due date, then closed ones newest first; `counts` for the whole school |
| GET | `/requests/{id}` | | `200 DataRequestDetail` |
| GET | `/staff` | | `200 { id, name }[]`: active people with `privacy.manage` |
| POST | `/requests/{id}/assign` | `{ assigneeId }` | `200 DataRequestDetail`; `400 assigneeId` for anyone without `privacy.manage` |
| POST | `/requests/{id}/replies` | `{ body }` (≤2000) | `200 DataRequestDetail`; the parent sees the reply |
| POST | `/requests/{id}/export` | | `200 DataRequestDetail` with a new `export`; only for `ACCESS`; `409` if the requester has no sign-in any more; a previous ready file becomes `REPLACED` |
| GET | `/requests/{id}/export` | | `200 application/zip` (`Content-Disposition: attachment`, `Cache-Control: no-store`); `404` when no file is ready |
| POST | `/requests/{id}/erase` | `{ confirmAdmissionNo }` | `200 DataRequestDetail` (see [Erasure](#erasure)) |
| POST | `/requests/{id}/close` | `{ resolution: "COMPLETED" \| "DECLINED", note }` | `200 DataRequestDetail`; `400 note` when declining without a reason; `409` when completing an erasure request that was not erased |

Every action on a closed request is `409`. Assigning, a staff reply, making a file or erasing moves a
`SUBMITTED` request to `IN_PROGRESS`; a parent's reply does not.

```ts
type DataRequestRow = { id; type: "ACCESS" | "CORRECTION" | "ERASURE" | "GRIEVANCE"; subject: "SELF" | "CHILD";
  status: "SUBMITTED" | "IN_PROGRESS" | "CLOSED"; resolution: "COMPLETED" | "DECLINED" | null;
  studentId; studentName; admissionNo; requesterName; assignedTo: { id; name } | null;
  createdAt; dueOn: string; daysLeft: number; overdue: boolean; lastActivityAt;
  exportStatus: "READY" | "EXPIRED" | "REPLACED" | "ERASED" | null };
type DataRequestPage = { items: DataRequestRow[]; page; size; total;
  counts: { open; submitted; inProgress; closed; overdue } };
type DataRequestDetail = Omit<DataRequestRow, "lastActivityAt" | "exportStatus"> & {
  details: string; studentStatus; closingNote; closedAt; closedByName; erasedAt; canErase: boolean;
  export: DataExport | null;            // the file that can be downloaded now
  exports: DataExport[];                 // every file made, newest first
  events: { id; kind: "SUBMITTED" | "ASSIGNED" | "STAFF_REPLY" | "PARENT_REPLY" | "EXPORT_READY"
            | "EXPORT_DOWNLOADED" | "ERASED" | "CLOSED"; actorName; byRequester: boolean; body; at }[] };
type DataExport = { id; fileName; sizeBytes; status; createdByName; createdAt; expiresAt; deletedAt;
  downloadCount; lastDownloadedAt };
```

## Parents — `/api/me/privacy` (all `child.view`)

| Method | Path | Body | Success |
| --- | --- | --- | --- |
| GET | `` | | `200 ParentPrivacy` |
| POST | `/consent` | `AcceptForm` | `200 ParentPrivacy`; `409 noticeVersion` when a newer version was published meanwhile; `400 choices[i].studentId` for a child that is not theirs |
| PUT | `/children/{studentId}/consents/{PHOTOS\|WHATSAPP}` | `{ given }` | `200 ParentPrivacy`; `400 purpose` for `ESSENTIAL` (that is an erasure request); `404` for someone else's child; asking for the current state changes nothing |
| GET | `/requests` | | `200 DataRequestRow[]` (own requests, newest first, no assignee) |
| POST | `/requests` | `DataRequestForm` | `201 DataRequestDetail`; `400 studentId` for a child that is not theirs; `400 details` for a correction or grievance without details; `409` with 10 open requests |
| GET | `/requests/{id}` | | `200 DataRequestDetail`; `404` for anyone else's request |
| POST | `/requests/{id}/replies` | `{ body }` | `200 DataRequestDetail`; `409` once closed |
| GET | `/requests/{id}/export` | | `200 application/zip`; `404` for anyone else's request or when no file is ready |

```ts
type ParentPrivacy = { notice: PrivacyNotice | null; needsConsent: boolean;
  children: { studentId; fullName; className; sectionName; status; needsConsent: boolean;
              purposes: PurposeState[]; history: ConsentEntry[] }[] };
type AcceptForm = { noticeVersion: number; acceptEssential: true;
  choices: { studentId: string; photos: boolean; whatsapp: boolean }[] /* 1–20 */ };
type DataRequestForm = { type; subject: "SELF" | "CHILD"; studentId: string | null; details: string | null /* ≤2000 */ };
```

A child **needs consent** when a notice is published, the child is active, and essential consent
for the current version has not been given (online or on paper). The web app shows a parent who
needs consent the notice, an "I agree" for essential school records and an unticked choice per child
for photos and WhatsApp before any page except their privacy page (so a parent who does not agree can
still raise a request or a grievance). This is a screen, not access control: other modules' APIs
are not blocked, and if the check fails the page is shown.

## Data export

`POST /requests/{id}/export` builds the ZIP in memory with `java.util.zip` from other modules'
public services (no new dependency) and stores it in `privacy.data_export` (`bytea`, with its
SHA-256) for **7 days**. `README.txt` (English and Hindi) comes first.

- About a child: `student.json`, `guardians.json` (the requesting parent in full, other guardians by
  name and relation only), `enrollments.json`, `admission-application.json` (if any; other guardians'
  phone and email left out), `attendance.json` (per enrolled year up to today), `fees.json`,
  `fee-receipts.json`, `consents.json`.
- About the parent: `account.json`, `guardian.json`, `children.json`, `consents-given.json`,
  `data-requests.json`.

The file name is `data-export-<admission number or "parent">-<date>.zip`. Each download is counted
and audited (`by: PARENT | STAFF`) and adds `EXPORT_DOWNLOADED` to the timeline when the parent
downloads. The clean-up job (`akshara.privacy.export-cleanup`, hourly, first run 2 minutes after start;
`PRIVACY_EXPORT_CLEANUP_ENABLED`, `PRIVACY_EXPORT_CLEANUP_INTERVAL`) deletes the content of every file
past its expiry (`EXPIRED`), school by school; a file past its expiry cannot be downloaded even
before the job runs. The SECURITY DEFINER function `privacy.tenants_with_expired_exports` only tells
the job which schools have expired files.

## Erasure

`POST /requests/{id}/erase` with `{ confirmAdmissionNo }` is the second confirmation (the first is
opening the dialog): the admission number typed again (spaces and case ignored, `400
confirmAdmissionNo` otherwise). It is allowed only for an open `ERASURE` request about a child
(`409` otherwise, and `409` if already erased) and only for a student who has **left** (transferred,
withdrawn or alumni; `409 Still enrolled` otherwise). In one transaction:

- `StudentErasure.anonymiseLeftStudent` (students module, its only new public method): name becomes
  "Erased student", date of birth keeps only the year, blood group, address, previous school, APAAR
  ID, leaving reason and the student's sign-in link are cleared; links to parents are removed and a
  parent record with no other child is deleted (one shared with a sibling stays). The student row,
  admission number, status and enrolments stay so that kept records still point somewhere.
- `AdmissionRecords.anonymiseForStudent` (admissions): the application's child name, date of birth
  (year only), previous school, message, timeline notes and test/interview notes are cleared and its
  guardians deleted.
- Ready export files of that student are deleted (`ERASED`).
- The timeline gets `ERASED` with what was kept, the audit gets `data_request.erased`,
  `student.anonymised` and `application.anonymised`, and `StudentDataErased` is published.

Rows of another school are never touched: everything runs under the caller's school with row-level
security, and a request id of another school is `404`.

## Retention

| Data | Kept | Why |
| --- | --- | --- |
| Fee receipts, payments, dues (incl. the student name printed on a receipt) | until 31 March of the financial year of the receipt + **8 years**; not erased | Companies Act 2013 s.128(5) (8 years) and Income-tax Act s.44AA / Rule 6F (6 years from the end of the assessment year); the longer applies |
| Published notices, consent decisions, request timelines | as long as the school uses the service | proof of consent and of how requests were handled (DPDP Act s.6(10)); append-only for the runtime role |
| Audit log | unchanged by this slice | |
| Data export ZIPs | 7 days, then content deleted | |
| Message log (absence alerts, fee messages) | unchanged; not erased yet | follow-up through `StudentDataErased` |
| Database backups | up to 14 days after erasure | see below |

The erasure timeline entry tells the parent how many receipts are kept and until when.

## Security and backups

- `infra/terraform/rds.tf` (read only, not run): automated backups `backup_retention_period = 14`
  days in the window 20:00–21:00 UTC (01:30–02:30 IST) with point-in-time restore within that period;
  storage and Performance Insights encrypted with a customer KMS key; `rds.force_ssl`; deletion
  protection and a final snapshot; Multi-AZ by default (`db_multi_az`, off in staging); region
  ap-south-1. **Erased data stays in backups for up to 14 days**: after restoring a backup older than an
  erasure, the erasure must be repeated (the audit log lists `data_request.erased`).
- All six tables have `tenant_id`, the `tenant_isolation` policy (USING and WITH CHECK) and composite
  foreign keys; grants go to the runtime role only, without `update`/`delete` on notices, consent
  records and request events.
- Exports are served only to the requesting parent or to `privacy.manage`, with `no-store`; they hold
  no other family's phone or email. Nothing in this module stores Aadhaar; request details warn
  parents not to type Aadhaar or bank numbers.
- The public notice endpoint is rate limited per IP (`akshara.privacy.public-notice-per-ip`, 120 per
  `PT1H`) and names no staff.

## Events for other modules

Published with `ApplicationEventPublisher` inside the transaction; nothing listens yet.

- `ConsentChanged(tenantId, studentId, purpose, action, method, noticeVersion, at)` for every decision.
  Follow-up: the notifications module can skip WhatsApp for a child whose `WHATSAPP` consent is not
  `GIVEN` and fall back to SMS; communication can do the same for circulars, and photo features can
  check `PHOTOS`. Absence alerts and fee messages are sent exactly as before.
- `StudentDataErased(tenantId, studentId, requestId, unlinkedUserIds, at)`. Follow-up: notifications
  can clear names in its message log; identity can disable `unlinkedUserIds` sign-ins that no longer
  point at any child (this slice leaves accounts active).

## Audit

`privacy_notice.published`, `grievance_officer.updated`, `consent.given`, `consent.withdrawn`,
`consent.recorded`, `data_request.submitted|assigned|replied|parent_replied|closed|erased`,
`data_export.created|downloaded|expired` (expiry by "System"), `student.anonymised`,
`application.anonymised`. Details hold ids, versions, choices, counts, file names, admission numbers
and staff names; never phone numbers, addresses or the text of requests and replies.

## Demo data

`DemoPrivacyData`: grievance officer Lakshmi Iyer, notice version 1 from the template, Anitha
Sharma's online consent (Arjun: photos and WhatsApp; Diya: WhatsApp only) and one open access request
about Arjun.
