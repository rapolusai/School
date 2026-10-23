# Phase 3 API: health and safety

Same conventions as [Phase 0](phase-0.md) and [Phase 1](phase-1.md): JSON under `/api`, a Bearer access token,
RFC 9457 problem details with `errors.<field>`, ids from another school in a path answer `404` and in a body answer
`400` on that field, conflicts answer `409`, dates are `"YYYY-MM-DD"` days in India (Asia/Kolkata), times are
instants, and every change is written to the audit trail with readable labels (`audit.action.*`, `audit.entity.*`).

**In this slice:** the Nurse role; a student's medical record (blood group, height and weight with a growth chart,
allergies with severity, chronic conditions, medication, doctor, emergency contact from the guardians, vaccinations,
insurance with a masked policy number, parent consent); the infirmary log with a small medicine stock; the safety
registers (drills, audit checklist, incidents, first-aid kits); the POCSO committee with a committee-only complaints
register; staff police verification and training records; four reports with CSV; the family app's "Health" page and
a home card; dashboard tiles; demo data (`nurse@demo.akshara.test`). Migration `V23__health.sql`, module
`com.akshara.health`.

**Not in this slice:** doctor's prescriptions and appointment booking, a vaccination schedule by age, insurance
claims, real SMS (the sent-home message goes through the notifications outbox like every other message), and
attendance changes when a child is sent home (the `StudentSentHome` event is published for that).

## Sensitive data

This slice holds health data, so it collects the minimum and reads are restricted and audited:

- **Every staff read of a student's medical record is audited** (`medical_record.viewed`, with `details.screen`
  naming the screen that asked: `student_medical`, `infirmary`, `class_list`, or `api`), not only writes. A parent
  reading their own child's record is not audited; their writes are.
- **Every access to the POCSO complaints register is audited** (`pocso_complaint.list_viewed`,
  `pocso_complaint.viewed`); audit details never carry a complaint's text or the complainant.
- **Never store Aadhaar or any government ID number.** No field takes one; the web form says so.
- The insurance policy number is stored but only ever answered masked (`••••8812`, last four characters); a police
  verification reference number likewise (`PV/HYD/2025/4100` → `••••4100`). Phone numbers in lists are masked
  (`98765•••01`); the emergency contact's full number is on the record view only.
- Whole-school reads of medical records need `health.manage` or `attendance.manage`; a class teacher with
  `health.read` reads only the students of the sections they lead (others answer `404`, like attendance).
- Reports hold counts only; no diagnosis per student leaves the record view.

## Permissions and roles

| Permission | Who has it (seeded roles) | Allows |
| --- | --- | --- |
| `health.read` (new) | School Admin, Principal, Teacher, Nurse | reading medical records (class teachers: own sections only), the infirmary log, the medicine stock, the infirmary and allergy reports |
| `health.manage` (new) | School Admin, Nurse | keeping medical records, the infirmary log and the medicine stock; whole-school reads |
| `safety.manage` (new) | School Admin, Principal | the safety registers, the POCSO committee's membership and meetings, staff verification and training, the drills and verifications reports |
| `pocso.committee` (new) | Principal only | reading and writing the POCSO complaints register. **Not part of ALL_SCHOOL**: a School Admin is not on the committee unless added as a member |
| `leave.request` | every staff role | seeing who is on the POCSO committee |
| `child.view` | Parent | `/api/me/children/{id}/health`: the parent's own children only |

Committee access to the complaints register is `pocso.committee` **or** a membership row in
`health.pocso_member` for the signed-in user. Everyone else answers `403`.

New role **`NURSE`** ("Nurse"): `dashboard.view`, `students.read`, `academics.read`, `leave.request`,
`notices.read`, `timetable.read`, `health.read`, `health.manage`. The migration adds the role to every existing
school and appends the permissions to the matching roles (idempotent). Twelve standard roles now.

## Medical records

| Method | Path | Permission | Success |
| --- | --- | --- | --- |
| GET | `/api/health/students?q=` | read | `200 PersonRef[]`: active students in scope whose name or admission number matches |
| GET | `/api/health/students/{id}/record?screen=` | read (scope) | `200 MedicalRecordView`; **audited** with `screen` |
| PUT | `/api/health/students/{id}/record` | `health.manage` | `200 MedicalRecordView`; body `RecordForm`; a blank `insurancePolicyNo` keeps the one on file |
| PUT | `/api/health/students/{id}/record/consent` | `health.manage` | `200 MedicalRecordView`; body `{firstAid, hospital}` |
| POST | `/api/health/students/{id}/record/items` | `health.manage` | `201 ItemView`; body `ItemForm` |
| PUT | `/api/health/students/{id}/record/items/{itemId}` | `health.manage` | `200 ItemView`; the `kind` cannot change (`400 errors.kind`) |
| DELETE | `/api/health/students/{id}/record/items/{itemId}` | `health.manage` | `204` |
| POST | `/api/health/students/{id}/record/growth` | `health.manage` | `201 GrowthView`; a second measurement on the same day replaces the first |
| DELETE | `/api/health/students/{id}/record/growth/{measurementId}` | `health.manage` | `204` |

```ts
type RecordForm = { bloodGroup?: "A+"|"A-"|"B+"|"B-"|"AB+"|"AB-"|"O+"|"O-"; doctorName?; doctorPhone?;
  insuranceProvider?; insurancePolicyNo?; notes? };
type ItemForm = { kind: "ALLERGY"|"CONDITION"|"MEDICATION"|"VACCINATION"; name; detail?;
  severity?: "MILD"|"MODERATE"|"SEVERE" /* required for an allergy */; itemDate? /* required for a vaccination, not in the future for one; never before the student's date of birth */ };
type GrowthForm = { measuredOn /* not in the future, not before the student's date of birth */; heightCm?: 30..250; weightKg?: 1..250 /* at least one */ };
type MedicalRecordView = { studentId, studentName, admissionNo, className, dateOfBirth, exists, bloodGroup, doctorName,
  doctorPhone, insuranceProvider, insurancePolicyNoMasked, notes, consent: {firstAid, hospital, byName, at},
  emergencyContact: {guardianId, name, phone, phoneMasked, email} | null /* the primary guardian */,
  allergies, conditions, medications, vaccinations: ItemView[], growth: GrowthView[],
  chart: {points: {date, ageMonths, heightCm, weightKg}[], minHeight, maxHeight, minWeight, maxWeight},
  visits: VisitView[] /* latest first */, updatedAt };
type ItemView = { id, kind, name, detail, severity, itemDate, source: "PARENT"|"STAFF", addedByName };
```

### The family app

| Method | Path | Permission | Success |
| --- | --- | --- | --- |
| GET | `/api/me/children/{studentId}/health` | `child.view` | `200 MedicalRecordView` (not audited; `404` for a child that is not theirs) |
| PUT | `/api/me/children/{studentId}/health/consent` | `child.view` | `200 MedicalRecordView` |
| POST | `/api/me/children/{studentId}/health/items` | `child.view` | `201 ItemView` with `source: "PARENT"`; only `ALLERGY` and `CONDITION` (`400 errors.kind`) |
| PUT | `/api/me/children/{studentId}/health/items/{itemId}` | `child.view` | `200 ItemView`; the parent's own entries only |
| DELETE | `/api/me/children/{studentId}/health/items/{itemId}` | `child.view` | `204` |

## Infirmary

| Method | Path | Permission | Success |
| --- | --- | --- | --- |
| GET | `/api/health/infirmary/visits?date=` | read | `200 {date, visits: VisitView[], open, medicinesLow}` for the day (default today) |
| GET | `/api/health/infirmary/visits/{id}` | read | `200 VisitView` |
| GET | `/api/health/infirmary/people?q=` | `health.manage` | `200 PersonRef[]`: active students and staff (`kind: "STUDENT"|"STAFF"`) |
| POST | `/api/health/infirmary/visits` | `health.manage` | `201 VisitView`; body `VisitForm`; takes the medicine from the stock (`400 errors.medicines` when short); visits taking the same medicine at the same moment queue on its stock row, so each succeeds while the stock lasts |
| PUT | `/api/health/infirmary/visits/{id}` | `health.manage` | `200 VisitView`; `409` once closed |
| POST | `/api/health/infirmary/visits/{id}/close` | `health.manage` | `200 VisitView`; body `CloseForm`; `409` once closed; `400 errors.timeOut` in the future |
| GET | `/api/health/infirmary/medicines` | read | `200 MedicineView[]` |
| POST | `/api/health/infirmary/medicines` | `health.manage` | `201 MedicineView`; `409 errors.name` for a duplicate name |
| PUT | `/api/health/infirmary/medicines/{id}` | `health.manage` | `200 MedicineView` |
| DELETE | `/api/health/infirmary/medicines/{id}` | `health.manage` | `204`; `409` once given in a visit |

```ts
type VisitForm = { personKind: "STUDENT"|"STAFF"; studentId? /* required for a student */; staffUserId? /* for staff */;
  timeIn? /* default now */; complaint; temperatureC?: 30..45; treatment?; medicines?: {medicineId, quantity: 1..1000}[] (max 10) };
type CloseForm = { outcome: "BACK_TO_CLASS"|"SENT_HOME"|"HOSPITAL"; timeOut? /* default now */; treatment?; parentInformed? };
type VisitView = { id, personKind, studentId, staffUserId, personName, className, sectionId, timeIn, timeOut, complaint,
  temperatureC, treatment, outcome /* null while open */, parentInformed, medicines: {medicineId, name, unit, quantity}[],
  allergies: AllergyBadge[], recordedByName };
type MedicineView = { id, name, unit, quantity, reorderLevel, expiresOn, low, expired };
```

Closing a **student's** visit as `SENT_HOME` or `HOSPITAL` sends the primary guardian a message through the
notifications outbox (templates `health.sent_home` / `health.hospital`, related id = the visit, the channel from the
school's absence-alert setting, masked recipient in the log) and publishes the `StudentSentHome` event. Nothing is
sent for a staff member's visit. Deleting a visit is not possible (the table has no delete grant).

## Safety registers

All under `/api/health/safety`, permission `safety.manage`.

| Method | Path | Success |
| --- | --- | --- |
| GET | `/` | `200 {today, drills: DrillView[], audits: AuditView[], incidents: IncidentView[], kits: KitView[]}` |
| GET / POST | `/drills` | `200 DrillView[]` / `201 DrillView`; body `DrillForm`; `nextDue` defaults to 90 days on |
| PUT / DELETE | `/drills/{id}` | `200` / `204` |
| POST / PUT | `/audits[/{id}]` | `201` / `200 AuditView`; body `AuditForm` |
| POST | `/audits/{id}/close` | `200 AuditView`; body `{closedOn?, closureNote?}`; `409` when closed |
| POST | `/audits/{id}/reopen` | `200 AuditView`; `409` when open |
| POST / PUT | `/incidents[/{id}]` | `201` / `200 IncidentView`; body `IncidentForm` (no delete) |
| POST | `/kits` | `201 KitView`; body `{location}`; `409 errors.location` for a duplicate |
| POST | `/kits/{id}/check` | `200 KitView`; body `{checkedOn?, complete, earliestExpiry?, missingItems?}` |
| DELETE | `/kits/{id}` | `204` |

```ts
type DrillForm = { drillDate; kind: "FIRE"|"EVACUATION"|"EARTHQUAKE"; block; timeToClearSeconds: 1..7200; attendance; issues?; nextDue? /* after drillDate */ };
type AuditForm = { area: "ELECTRICAL"|"PLAYGROUND"|"LABS"|"TRANSPORT"|"BUILDING"|"WATER"; auditDate /* not in the future, not before 1900 */; finding; severity: "LOW"|"MEDIUM"|"HIGH"; dueOn? };
type IncidentForm = { incidentAt?; kind: "INJURY"|"BULLYING"|"OTHER"; severity: "MINOR"|"MODERATE"|"SERIOUS"; studentId?; personName?; place?; description; actions?; parentInformed? };
type Due = "OK" | "DUE_SOON" /* within 30 days */ | "OVERDUE" | "MISSING";
```

`DrillView.due`, `KitView.expiry` (earliest expiry) and `KitView.checkDue` (90 days since the last check) use
those rules; `AuditView.overdue` is an open finding past `dueOn`.

## POCSO committee

All under `/api/health/pocso`.

| Method | Path | Permission | Success |
| --- | --- | --- | --- |
| GET | `/committee` | any staff (`leave.request`) | `200 {formedOn, members: {id, userId, name, designation, role, appointedOn}[], meetings: {id, meetingDate, minutes, recordedByName}[], canReadComplaints, canManage}` |
| PUT | `/committee` | `safety.manage` | `200`; body `{formedOn?}` |
| POST | `/committee/members` | `safety.manage` | `201 MemberView`; body `{userId /* active staff */, role: "CHAIRPERSON"|"MEMBER"|"EXTERNAL"|"SECRETARY", appointedOn?}`; `409` when already a member |
| DELETE | `/committee/members/{id}` | `safety.manage` | `204` |
| POST | `/committee/meetings` | `safety.manage` | `201 MeetingView`; body `{meetingDate, minutes}` |
| GET | `/complaints` | committee | `200 ComplaintView[]` without `complainantNote`; **audited** |
| GET | `/complaints/{id}` | committee | `200 ComplaintView` with `complainantNote`; **audited** |
| POST | `/complaints` | committee | `201 ComplaintView`; body `ComplaintForm` |
| PUT | `/complaints/{id}` | committee | `200 ComplaintView`; a blank `complainantNote` keeps the one on file |

```ts
type ComplaintForm = { complaintDate; category: "ABUSE"|"HARASSMENT"|"NEGLECT"|"OTHER"; description;
  status?: "OPEN"|"UNDER_ENQUIRY"|"REFERRED"|"CLOSED"; actions?; complainantNote? /* free text, committee only */ };
```

"committee" is `pocso.committee` or a membership row; others answer `403`. Complaints are never deleted.

## Staff police verification and training

All under `/api/health/staff`, permission `safety.manage`.

| Method | Path | Success |
| --- | --- | --- |
| GET | `/checks` | `200 {asOf, rows: VerificationView[] /* every active member of staff, a MISSING row when none yet */, verificationsDue, verificationsMissing, trainingsDue}` |
| GET | `/checks/due` | the same, rows limited to those needing attention |
| PUT | `/{userId}/verification` | `200 VerificationView`; body `{status: "PENDING"|"REQUESTED"|"CLEARED"|"FLAGGED", requestedOn? /* not in the future */, clearedOn? /* required when CLEARED; not in the future */, referenceNo? /* blank keeps */, expiresOn?, note?}`; `404` for a user who is not staff here |
| POST | `/trainings` | `201 TrainingView`; body `{userId, kind: "POCSO"|"FIRST_AID", trainedOn, expiresOn? /* after trainedOn */, provider?}` |
| DELETE | `/trainings/{id}` | `204` |

`VerificationView = {userId, name, designation, active, status, requestedOn, clearedOn, referenceNoMasked, expiresOn,
note, updatedByName, due: Due, trainings: TrainingView[], pocsoTrainingDue, firstAidTrainingDue}`; `due` is
`MISSING` without a row or while PENDING/REQUESTED/FLAGGED, else by `expiresOn`. `TrainingView.needsRenewal` is true
when `expiresOn` has passed or is within 30 days; `pocsoTrainingDue` / `firstAidTrainingDue` are true when that
training was never taken or its latest record needs renewal.

## Reports

All under `/api/health/reports`; the CSV twins answer `text/csv` with a BOM and a `Content-Disposition` attachment
and defuse formula cells. Counts only: no student's diagnosis appears without opening the record (audited).

| Path | Permission | Answer |
| --- | --- | --- |
| `/infirmary[.csv]?from&to` | `health.read` | `{from, to, total, byMonth: {month, visits, students, staff, sentHome, hospital}[], byOutcome, byComplaint: {name, count}[]}`; default the last six months, max 400 days |
| `/allergies[.csv]` | `health.read` | `{asOf, students, withAllergies, severe, byClass: {sectionId, label, students, withAllergies, severe}[]}` |
| `/drills[.csv]` | `safety.manage` | `{asOf, nextDue, due: Due, rows: DrillView[]}` |
| `/verifications[.csv]` | `safety.manage` | `{asOf, due, missing, rows: VerificationView[] /* those needing attention */}` |

The web reports hub lists them under a new "Health and safety" group.

## Dashboard

`GET /api/dashboard` gains `health`: `{inInfirmary, visitsToday, verificationsDue, verificationsMissing,
trainingsDue, nextDrillDue, drillDue, openFindings}`. The infirmary counts are `null` without `health.read`; the rest
`null` without `safety.manage`; the card is `null` when nothing applies. The School Admin's and Principal's dashboard
shows "In the infirmary" and "Staff checks due" tiles (the latter also flags an overdue or soon-due drill).

## Public types for other modules

The module exposes these Spring beans and records (`com.akshara.health`), the only way in for other modules:

- **`HealthAlerts.forStudents(Collection<UUID> studentIds)`** → `Map<UUID, List<AllergyBadge>>` with
  `AllergyBadge(studentId, severity, label)`: the severity and a short label (the allergen's name) of each active
  student's allergies, nothing more. `forStudent(UUID)` for one. **The mess and transport modules call this** to
  show a badge wherever a student is served food or driven; the web shows the same badges on the infirmary log and
  the medical record. Callers must hold a permission of their own: the badge is not a medical record read and is
  not audited.
- **`InfirmaryToday.studentVisits(LocalDate day)`** and **`inInfirmaryNow()`** → `StudentVisit(visitId, studentId,
  studentName, sectionId, timeIn, timeOut, outcome)`: lets a class teacher's screen say "in the infirmary" without
  this module touching attendance.
- **`StudentSentHome`** event record `(tenantId, visitId, studentId, studentName, sectionId, outcome, at)`,
  published through `ApplicationEventPublisher` when a student's visit closes as `SENT_HOME` or `HOSPITAL`, inside
  the closing transaction. Attendance may consume it to mark a half day; nothing consumes it yet.

## Audit actions

`medical_record.viewed` (details: `studentId`, `screen`), `medical_record.updated`,
`medical_record.consent_updated`, `medical_record_item.added|updated|removed`,
`growth_measurement.added|removed`, `infirmary_visit.recorded|updated|closed`, `medicine.added|updated|removed`,
`fire_drill.recorded|updated|removed`, `safety_audit.recorded|updated|closed|reopened`,
`incident.recorded|updated`, `first_aid_kit.added|checked|removed`, `pocso_committee.updated`,
`pocso_member.added|removed`, `pocso_meeting.recorded`, `pocso_complaint.list_viewed|viewed|recorded|updated`,
`staff_verification.updated`, `staff_training.recorded|removed`. Entities: `medical_record`, `infirmary_visit`,
`medicine`, `fire_drill`, `safety_audit`, `incident`, `first_aid_kit`, `pocso_committee`, `pocso_complaint`,
`staff_verification`, `staff_training`.

## Web

Staff: `/app/health` (the infirmary log for a day; record, treat and close visits), `/app/health/medicines`,
`/app/health/students/{id}` (the medical record with the SVG growth chart and the allergy badge; linked from the
student page for `health.read`), `/app/health/safety` (drills, audit checklist, incidents, first-aid kits),
`/app/health/pocso` (committee for all staff; complaints register for the committee), `/app/health/staff`
(verification and training); reports at `/app/reports/health-infirmary|allergies|drills|verifications`.
Family: `/app/family/health` ("Health": allergies and conditions the parent keeps, consent, the school's medication
and vaccinations, infirmary visits) plus a card on the family home on a day the child was in the infirmary.
Navigation: "Health and safety" for `health.read`, `health.manage` or `safety.manage`; "Health" for parents.

## Database

Schema `health`, migration `V23__health.sql`: `medical_record`, `growth_measurement`, `record_item`, `medicine`,
`infirmary_visit`, `medicine_given`, `fire_drill`, `safety_audit`, `incident`, `first_aid_kit`, `pocso_committee`
(one per school), `pocso_member`, `pocso_meeting`, `pocso_complaint`, `staff_verification`, `staff_training`. Every
table has `tenant_id`, the `tenant_isolation` policy and composite foreign keys; `infirmary_visit`, `incident` and
`pocso_complaint` have no delete grant.

## Demo data

`DemoHealthData` adds the nurse **Mary Thomas** (`nurse@demo.akshara.test`, employee code AKS-601, on the payroll),
medical records for ten students (Diya Sharma's severe peanut allergy entered by her mother, with consent), a small
medicine stock with ORS running low, three visits yesterday (one sent home), a drill last month and an older one
whose next is due in 12 days, three open and closed audit findings, an incident, three first-aid kits (the science
lab's incomplete), the POCSO committee of three formed a year ago with minutes, police verification rows for every
member of staff (the guard's and the nurse's still requested), and POCSO and first-aid training records.

## Tests

Unit: `HealthTextTest` (masking, due rules), `GrowthChartTest`. Integration: `MedicalRecordIT` (audited reads,
masking, growth, per-section scope, other schools, the family app), `InfirmaryIT` (stock, closing, the parent's
message, the event, reports), `SafetyIT` (registers, the committee-only register with audited reads, staff checks,
allergies report), `RowLevelSecurityCoverageIT` (the sixteen tables), `AuthFlowIT` / `TenantIsolationIT` (twelve
roles), `DemoDataSeederIT`. Vitest: the medical record page and chart, the infirmary visit form and closing, the
family health page and home card, nav and the reports hub. Playwright: `e2e/tests/health.spec.ts`.
