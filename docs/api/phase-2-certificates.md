# Phase 2 API: certificates and ID cards

Same conventions as [Phase 0](phase-0.md) and [Phase 1](phase-1.md): JSON under `/api`, a Bearer access token,
RFC 9457 problem details with `errors.<field>`, ids from another school in a path answer `404` and in a body answer
`400` on that field, conflicts answer `409`, dates are `"YYYY-MM-DD"` days in India (Asia/Kolkata), times are
instants, money is in **paise**, and every change is written to the audit trail.

**In this slice:** templates with placeholders per certificate type (transfer, bonafide, character, fee, study,
migration/NOC, custom; experience and salary for staff); the issue flow (requested → prepared → signed = issued →
cancelled, with corrections as new versions); serial numbers per type and academic year (`TC/2026-27/0001`); QR
verification on a public page; ID cards for students and staff with photos, a register and reissues; the family
pages (request a bonafide or fee certificate, print an issued one, see the child's ID card); reports; demo data.
Printing is the browser's print (A4 for certificates, CR80 cards 2 × 5 on A4); the QR code is drawn in the browser.

**Not in this slice:** server-made PDFs, e-signatures, posting the lost-card fee to the fee ledger (the fee is
noted on the card), CBSE/board portal uploads.

## Permissions and roles

| Permission | Who has it (seeded roles) | Allows |
| --- | --- | --- |
| `certificates.read` (new) | School Admin, Principal, Front office | the register, templates, settings, ID cards, photos, reports |
| `certificates.issue` (new) | School Admin, Principal, Front office | prepare, edit, cancel, correct; save templates and settings; issue and reissue cards; upload photos |
| `certificates.sign` (new) | School Admin, Principal | sign (which issues) |
| `dashboard.view` | everyone | `/api/me/certificates` (their own children) |
| (none) | anyone | `GET /api/public/verify/{token}` |

Migration `V20__certificates.sql` appends the permissions to the matching stored roles of existing schools
(idempotent). A certificate is **issued** only when a signatory signs it.

## Types and placeholders

`type`: `TRANSFER` (TC), `BONAFIDE` (BC), `CHARACTER` (CC), `FEE` (FC), `STUDY` (SC), `MIGRATION` (MC),
`CUSTOM` (CERT); for staff `EXPERIENCE` (EXP) and `SALARY` (SAL). The letters are the default serial prefixes.

Student placeholders: `{student} {firstName} {admissionNo} {class} {section} {dob} {gender} {relation} {father}
{mother} {guardian} {admissionDate} {academicYear} {address} {previousSchool} {purpose} {from} {to} {conduct}
{reason} {dateOfLeaving}`; a transfer certificate adds `{promotionStatus} {duesCleared} {workingDays}
{daysPresent}` (attendance this year); a fee certificate adds `{feesPaid} {receipts}` (receipts issued in the
period). Staff: `{staff} {firstName} {employeeCode} {designation} {department} {dateOfJoining} {dateOfLeaving}
{purpose} {conduct} {reason}`; a salary certificate adds `{salaryMonth} {grossSalary} {netSalary}` (from payroll:
the current structure's gross and the latest payslip's net). Common, filled on signing: `{school} {date}
{serialNo}`. A date value (`"2026-10-11"`) prints as `11 Oct 2026`; a blank value prints as `—`.

## Settings and templates — `/api/certificates`

| Method | Path | Permission | Success |
| --- | --- | --- | --- |
| GET | `/settings` | `certificates.read` | `200 {saved, headerName, headerAddress, affiliationNo, logoText, signatoryName, signatoryDesignation, tcDuesCheck, cardValidMonths, lostCardFeePaise}` |
| PUT | `/settings` | `certificates.issue` | `200` same; `cardValidMonths` 1–60, `lostCardFeePaise` 0–10,000,000 |
| GET | `/templates` | `certificates.read` | `200 TemplateView[]`, one per type: `{type, title, body, serialPrefix, staff, placeholders, custom, nextSerial}` |
| GET | `/templates/{type}` | `certificates.read` | `200 TemplateView` |
| PUT | `/templates/{type}` | `certificates.issue` | `200`; body `{title, body, serialPrefix}` (`serialPrefix` 1–8 of `A-Z0-9-`) |

Until a school saves its own wording, each type has a default. The header falls back to the school profile's name
and address. `tcDuesCheck` (default on) notes fees still due on a transfer certificate as a warning, never a block.

## Certificates — `/api/certificates`

| Method | Path | Permission | Success |
| --- | --- | --- | --- |
| GET | `/` | `certificates.read` | `200 {items: Row[], page, size, total}`; filters `status, type, studentId, staffUserId, issuedFrom, issuedTo, page, size≤200` |
| POST | `/` | `certificates.issue` | `201 Detail` (PREPARED); body `{type, studentId \| staffUserId, purpose?, from?, to?, fields?}` |
| GET | `/{id}` | `certificates.read` | `200 Detail` |
| PUT | `/{id}` | `certificates.issue` | `200 Detail`; body `{title, purpose?, fields}`; a REQUESTED one becomes PREPARED; `409` once signed |
| POST | `/{id}/sign` | `certificates.sign` | `200 Detail` ISSUED: serial number, frozen `body`, `verifyToken`; `409` unless PREPARED |
| POST | `/{id}/cancel` | `certificates.issue` | `200 Detail` CANCELLED; body `{reason}`; `409` if already cancelled |
| POST | `/{id}/correct` | `certificates.issue` | `201 Detail`: the old one cancelled ("Corrected: …"), a new PREPARED version N+1 with `supersedesId`; `409` unless ISSUED or already corrected |

```ts
type Row = { id, type, title, status: "REQUESTED"|"PREPARED"|"ISSUED"|"CANCELLED", subject: Subject,
  serialNo: string|null, versionNo, purpose, requestedVia: "OFFICE"|"FAMILY", requestedByName, requestedAt,
  issuedOn: string|null, signedByName: string|null };
type Subject = { type: "STUDENT"|"STAFF", id, name, firstName, code /* admission no or employee code */,
  detail /* "Class 5 A" or designation */, active };
type Detail = Row & { academicYear, supersedesId, supersededById, fields: Record<string,string>, placeholders: string[],
  body /* frozen once signed, else a preview from the template */, school: {name, address, affiliationNo, logoText,
  city, phone}, signatory: {name, designation}, verifyToken, verifyPath /* "/verify/<token>" */, duesPaise,
  preparedByName, preparedAt, signedAt, cancelledByName, cancelledAt, cancelReason, editable };
```

- Fields are prefilled from the student (students module), the class (academics), the guardians, attendance, fee
  receipts and dues (fees), the staff profile and payroll; `fields` in the body override them. Keys are letters and
  digits (`400 errors.fields`), at most 60.
- Signing a **transfer certificate** marks an ACTIVE student as `TRANSFERRED` through the students module (left on
  `{dateOfLeaving}`, reason `{reason}`) and publishes `TransferCertificateIssued`.
- Serial numbers come from a per-type, per-academic-year counter under a row lock. The year is the school's current
  academic year (`2026-27`), else April–March of the signing day.

## Public verification — `GET /api/public/verify/{token}`

No sign-in. `200 {schoolName, type, title, serialNo, firstName, issuedOn, valid, status}`; nothing else about the
student. `404` for an unknown or malformed token; `429` after 60 lookups an hour from one IP
(`akshara.certificates.verify-per-ip`, `verify-window`). The token is 24 random letters and digits, made on signing;
the web prints `<origin>/verify/<token>` as a QR code.

## ID cards — `/api/certificates/id-cards`

| Method | Path | Permission | Success |
| --- | --- | --- | --- |
| GET | `/` | `certificates.read` | `200 {school, items: Card[], page, size, total}`; `subjectType=STUDENT\|STAFF`, `sectionId` (students of a section, in roll order), `all=true` includes cancelled cards |
| POST | `/` | `certificates.issue` | `201 Card`; body `{subjectType, studentId \| staffUserId, bloodGroup?, emergencyPhone?, validUntil?}`; `409` when a card is in use |
| POST | `/batch` | `certificates.issue` | `201 {issued, skipped, cards}`; body `{sectionId}` or `{staff: true}`, `validUntil?`: everyone without a card in use |
| GET | `/{id}` | `certificates.read` | `200 Card` |
| POST | `/{id}/reissue` | `certificates.issue` | `201 Card` (issue number + 1, the old card CANCELLED); body `{reason, lostFeePaise?, feeNote?, bloodGroup?, emergencyPhone?}`; `409` if not in use |
| GET | `/photos/{subjectType}/{personId}` | `certificates.read` | `200 {fileId, name}` or `404` |
| POST | `/photos/{subjectType}/{personId}` | `certificates.issue` | `201 {fileId, name}`; multipart `file` (JPEG/PNG/WEBP ≤ 5 MB), replaces the old photo |

```ts
type Card = { id, subject: Subject, cardNo /* "ID/2026-27/0001" */, issueNo, status: "ACTIVE"|"CANCELLED",
  bloodGroup, emergencyPhone /* full; screens mask it, the printed card carries it */, validFrom, validUntil,
  photoFileId, replacesId, reissueReason, lostFeePaise, feeNote, issuedByName, createdAt, cancelledAt };
```

The blood group comes from the student record when not given; the emergency number from the primary guardian's
phone (students) or the staff profile's emergency contact (staff). Both are kept on the card only; no other
module's table is changed. Validity defaults to `cardValidMonths` from the issue day. Photos are stored in the file
store under this module (`certificates/photo_student|photo_staff/<personId>`) and served by `GET /api/files/{id}`
to certificate readers, the person, and a student's parents.

## Reports — `/api/certificates/reports` (`certificates.read`)

| Path | Filters | Answer |
| --- | --- | --- |
| `/issued`, `/issued.csv` | `from, to` (≤ 400 days, default last month), `type` | `{from, to, total, byType: [{type, count}], rows: Row[]}` — issued and later-cancelled ones in the period |
| `/pending`, `/pending.csv` | — | `{requested, prepared, rows}` oldest first |
| `/id-cards-pending`, `/id-cards-pending.csv` | `sectionId` or `staff=true` | `{sectionId, total, rows: [{subject, sectionId, sectionLabel}]}` |

CSV files are UTF-8 with a byte order mark and CRLF line ends; text that could run as a formula is defused.

## Families — `/api/me/certificates` (`dashboard.view`)

| Method | Path | Success |
| --- | --- | --- |
| GET | `/` | `200 {children: [{studentId, name, className, certificates: Row[], idCard: Card\|null}], requestableTypes: ["BONAFIDE","FEE"]}` |
| POST | `/requests` | `201 Detail` (REQUESTED, via FAMILY); body `{studentId, type: BONAFIDE\|FEE, purpose, from?, to?}`; another family's child `400 errors.studentId` |
| GET | `/{id}` | `200 Detail` of one of their children's certificates (issued ones print); `404` otherwise |
| GET | `/id-cards` | `200 Card[]` of the children's cards in use, emergency number masked, no register fields |

## Events

`TransferCertificateIssued {tenantId, certificateId, studentId, serialNo, issuedOn, leftOn}` after a transfer
certificate is signed (the student is already marked as left).

## Audit

`certificate_settings.updated`, `certificate_template.updated`, `certificate.requested`, `certificate.prepared`,
`certificate.updated`, `certificate.signed`, `certificate.cancelled`, `certificate.corrected`, `id_card.issued`,
`id_card.batch_issued`, `id_card.reissued`, `id_card.photo_uploaded`.

## Data

Schema `certificates`: `settings`, `template`, `serial_counter`, `certificate`, `id_card`; every table has
`tenant_id` with the `tenant_isolation` policy. `certificates.tenant_of_verify_token(token)` is a security-definer
function that finds the school of a token for the public page. No rows are ever deleted; cancellations keep the row.

## Demo data

`DemoCertificatesData`: the header and signatory, the bonafide wording saved, bonafide certificates issued for
Arjun Sharma and Sneha Iyengar, a transfer certificate for Laila Ahmed (who is thereby marked as transferred), and
ID cards for everyone in Class 5 A.
