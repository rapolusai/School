# Phase 3: government reporting

UDISE+ export, APAAR ID linking, CBSE OASIS data, CBSE registration list, scheme enrolment and the RTE 25% lottery.
Migration `V25__govreport.sql`, package `com.akshara.govreport`.

Everything that mirrors a government portal (column order, code values, income limits, categories) is a best-effort
template and is labelled **verify against the current portal template**. The code lists are editable per school and are
effective-dated, so a change by the authority is a data change, not a release. Nothing here calls an external service:
the school downloads a file and uploads it to the portal itself. Aadhaar numbers are never stored; the APAAR ID
(12 digits) and PEN are the only identifiers kept.

All endpoints are tenant scoped (row level security on every table). Reads need `govreport.read`, writes
`govreport.manage`. Principal has both, Front Office read, School Admin both. The family endpoints need only
`dashboard.view` and are limited to the caller's own children.

## Code lists

`GET /api/govreport/code-lists?list=` returns the codes (with `inForce` for today). Lists: SOCIAL_CATEGORY, RELIGION,
MOTHER_TONGUE, CWSN_TYPE, SCHOOL_CATEGORY, MANAGEMENT, RTE_INCOME_LIMIT (per state). `POST` adds a code,
`PUT /code-lists/{id}` edits label, portal value, order, dates and whether it is in use. A code is unique per
(list, code, effective-from). Defaults are seeded lazily, once per school, under a per-school advisory lock.

## School profile

`GET` / `PUT /api/govreport/profile`: UDISE code (11 digits), board, affiliation number and validity, school
category, management, and the facility counts and yes/no flags the UDISE+ school section asks for.
`GET /api/govreport/reports/completeness` reports how many of the required fields are filled.

## Student fields

| Endpoint | Purpose |
| --- | --- |
| `GET /api/govreport/students?sectionId=` | grid: APAAR ID, PEN, social category, religion, mother tongue, BPL, CWSN type, RTE admitted, previous school UDISE, with the missing fields per row |
| `PUT /api/govreport/students` | bulk save of edited rows (all or nothing, row errors keyed by student and field) |
| `GET /api/govreport/students/template.csv` | CSV template of the current records |
| `POST /api/govreport/students/import?dryRun=` | CSV import; `dryRun=true` validates without saving |
| `GET /api/govreport/students/completeness` | per section completeness |
| `PUT /api/me/govreport/children/{studentId}` | parent fills APAAR ID, PEN and category for their own child |

APAAR linking: when the office has entered an APAAR ID the record is locked against the parent; a parent can fill
an empty one once. An APAAR ID belongs to one student of the school: saving it for a second student (in the grid,
twice in one save, or from the family app) is `409` on that field, and the import reports it on the row. A student
may appear once in a bulk save (`400 entries[i].studentId`). Migration V28 kept the first-entered of any existing
duplicates and cleared (and unlocked) the others, recording each cleared ID in the audit log as
`govreport_fields.apaar_duplicate_cleared`. Each record notes whether it was last updated by the office, an import or the family.

## Exports (`/api/govreport/exports`)

`udise/preview`, `udise.xlsx` (one sheet per UDISE+ table), `udise/{table}.csv`, `oasis/preview`, `oasis.xlsx`,
`cbse-registration/classes`, `cbse-registration/preview`, `cbse-registration.xlsx`. Previews return the rows with
per-row issues; downloads are audited as `govreport_export.downloaded`. Class 9 and 11 registration and Class 10 and
12 board lists use the school's own student records. Teacher counts use keyword heuristics on designations, so
verify them before upload.

## RTE 25% lottery (`/api/govreport/rte`)

| Endpoint | Purpose |
| --- | --- |
| `GET/POST /intakes`, `GET/PUT /intakes/{id}` | intake per year and class: seats, state, income limit |
| `POST /intakes/{id}/close` | stop taking applications |
| `GET /intakes/{id}/candidates` | applications with screening result |
| `POST /intakes/{id}/applications` | add an application (links to an admission by composite FK) |
| `PUT /applications/{id}`, `POST /applications/{id}/withdraw` | edit or withdraw (409 once a lottery has run) |
| `POST /intakes/{id}/lottery` | run the lottery |
| `GET /runs/{runId}` | run with its allotments |

`applicationsCloseOn` is optional; a new or changed closing date before today (India time) is
`400 errors.applicationsCloseOn` (an unchanged date that has since passed can be saved again).

Screening checks the state income limit in force and the document flags. The lottery is a pure function: eligible
applications are ordered by id, grouped into distance bands 1 to 4 (the band is entered as the 1 km, 3 km and so on
range) and drawn with a seeded `java.util.Random`, so the same seed reproduces the result. Runs and allotments are
immutable; a re-run is a new run and needs a reason. After a run the intake is frozen for adding, screening or
updating (409).

## Schemes

`GET/POST/PUT/DELETE /api/govreport/schemes` (scholarship and entitlement enrolment per student, with
`schemes.csv`).

## Reports (`/api/govreport/reports`)

`completeness`, `enrolment`, `rte`, `schemes`, each also as `.csv`.

## Errors

400 for validation (field keyed, including a scheme `appliedOn` in the future), 403 without the permission, 404 for
ids of another school, 409 for duplicate codes, an APAAR ID already recorded for another student, locked APAAR, or
changes after a lottery.

## Audit actions

`govreport_profile.updated`, `govreport_fields.saved|imported|family_updated`, `govreport_code.added|updated`,
`govreport_scheme.enrolled|updated|removed`, `govreport_export.downloaded`, `rte_intake.created|updated|closed`,
`rte_application.added|screened|withdrawn`, `rte_lottery.run`.

## Events published

None. Demo data adds RTE applications and fields for the demo school.
