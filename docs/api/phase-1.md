# Phase 1 API: school setup and students

Same conventions as [Phase 0](phase-0.md): JSON under `/api`, `Authorization: Bearer <accessToken>`,
errors as RFC 9457 problem details `{ type, title, status, detail, errors?: { field: message } }`.

- **Ids from another school answer `404`**, exactly like ids that do not exist. Rows are filtered by
  PostgreSQL row-level security before the API sees them.
- An id **inside a request body** that is not in the caller's school (a section, a class teacher, a
  subject) answers `400` with a field error, for example `errors.sectionId`.
- `409` means the request clashes with existing data (a duplicate name, admission number or roll
  number, overlapping years, a full section, or a record still in use). `detail` says why.
- Dates are calendar dates `"YYYY-MM-DD"` (no time zone). "Today" means today in India (Asia/Kolkata).
- Mobile numbers are accepted as 10 digits starting 6–9, with optional `+91`, `91` or `0` and spaces or
  hyphens, and are stored and returned as the 10 digits.
- Every change is written to the school's audit trail (actions listed at the end).

## Permissions

| Permission | Who has it (seeded roles) | Allows |
| --- | --- | --- |
| `academics.read` (new) | School Admin, Principal, Teacher, Accountant, Front office | reading school setup |
| `settings.manage` | School Admin | changing school setup and the school profile |
| `students.read` | School Admin, Principal, Teacher, Accountant, Front office | reading student records |
| `students.manage` | School Admin, Front office (new) | admitting, editing, leaving, promoting, importing, sign-ins |
| `child.view` | School Admin, Parent | `GET /api/me/children` |

Migration `V2__academics.sql` adds `academics.read` to those roles, and `students.manage` to Front office,
in every existing school. Parents and students get `403` from every `/api/academics` and `/api/students` endpoint.

## School setup — `/api/academics`

Reads need `academics.read`; every change needs `settings.manage`.

| Method | Path | Body | Success |
| --- | --- | --- | --- |
| GET | `/years` | | `200 AcademicYear[]`, newest first |
| POST | `/years` | `YearRequest` | `201 AcademicYear` |
| PUT | `/years/{id}` | `YearRequest` (`current` ignored) | `200 AcademicYear` |
| POST | `/years/{id}/set-current` | | `200 AcademicYear`; the previous current year stops being current |
| DELETE | `/years/{id}` | | `204`; `409` for the current year or a year with enrollments |
| GET | `/classes` | | `200 ClassView[]` in `displayOrder`, then name |
| GET | `/classes/{id}` | | `200 ClassView` |
| POST | `/classes` | `{ name, displayOrder? }` | `201 ClassView`; without `displayOrder` it goes last |
| PUT | `/classes/{id}` | `{ name, displayOrder? }` | `200 ClassView` |
| DELETE | `/classes/{id}` | | `204`; `409` while it has sections |
| GET | `/classes/{id}/sections` | | `200 SectionView[]` |
| POST | `/classes/{id}/sections` | `SectionRequest` | `201 SectionView` |
| PUT | `/sections/{id}` | `SectionRequest` | `200 SectionView`; capacity cannot drop below this year's students |
| DELETE | `/sections/{id}` | | `204`; `409` once any student was ever enrolled |
| GET | `/teachers` | | `200 TeacherRef[]`: active people with the Teacher role |
| GET | `/subjects` | | `200 Subject[]` |
| POST | `/subjects` | `{ name, code? }` | `201 Subject` |
| PUT | `/subjects/{id}` | `{ name, code? }` | `200 Subject` |
| DELETE | `/subjects/{id}` | | `204`; `409` while a class teaches it |
| GET | `/classes/{id}/subjects` | | `200 SubjectRef[]` |
| PUT | `/classes/{id}/subjects` | `{ subjectIds: string[] }` (max 200) | `200 SubjectRef[]`; replaces the class's list |

```ts
type YearRequest = { name: string; startsOn: string; endsOn: string; current?: boolean };
type AcademicYear = { id: string; name: string; startsOn: string; endsOn: string; current: boolean };
type SectionRequest = { name: string; capacity?: number | null; classTeacherId?: string | null };
type TeacherRef = { id: string; name: string };
type SubjectRef = { id: string; name: string; code: string | null };
type SectionView = { id: string; classId: string; className: string; name: string;
  capacity: number | null; classTeacher: TeacherRef | null;
  studentCount: number };          // active students enrolled in the current year
type ClassView = { id: string; name: string; displayOrder: number;
  sections: SectionView[]; subjects: SubjectRef[] };
type Subject = { id: string; name: string; code: string | null; classCount: number };
```

Rules: year `name` 1–20 characters and unique; `endsOn` after `startsOn`; years may not overlap (`409`).
At most one year is current: a new year becomes current when `current` is true or when the school has
no current year yet, and the previous current year stops being current. Class name 1–40
characters, unique (ignoring case); `displayOrder` 0–999. Section name 1–20 characters, unique within
its class; `capacity` 1–500 or empty for no limit; the class teacher must have the Teacher role in
this school. Subject name 1–100 characters and code up to 20 letters, digits or hyphens, both unique.

### School profile — `/api/school/profile`

| Method | Path | Permission | Success |
| --- | --- | --- | --- |
| GET | `/api/school/profile` | any signed-in school member | `200 SchoolProfile` |
| PUT | `/api/school/profile` | `settings.manage` | `200 SchoolProfile`; body `{ address, phone, contactEmail, udiseCode }` |

```ts
type SchoolProfile = { id: string; name: string; code: string; board: string; city: string | null;
  address: string | null; phone: string | null; contactEmail: string | null; udiseCode: string | null };
```

Empty strings clear a field. `phone`: 8–15 digits with optional `+`, spaces, brackets or hyphens.
`udiseCode`: 11 digits. Name, code, board and city are set at sign-up and not changed here.

## Students — `/api/students`

Reads need `students.read`; every change needs `students.manage`.

| Method | Path | Body | Success |
| --- | --- | --- | --- |
| GET | `?yearId&classId&sectionId&status&q&page=0&size=25` | | `200 StudentPage` |
| GET | `/{id}` | | `200 StudentDetail` |
| POST | | `CreateStudent` | `201 StudentDetail` |
| PUT | `/{id}` | `UpdateStudent` | `200 StudentDetail` |
| POST | `/{id}/leave` | `{ status: "TRANSFERRED" \| "WITHDRAWN", leftOn, reason }` | `200 StudentDetail`; `409` if not active |
| POST | `/{id}/guardians` | `GuardianFields` | `201 Guardian` |
| PUT | `/{id}/guardians/{guardianId}` | `GuardianFields` | `200 Guardian` |
| DELETE | `/{id}/guardians/{guardianId}` | | `204`; `409` for the last guardian |
| POST | `/{id}/guardians/{guardianId}/sign-in` | `SignIn` | `200 Guardian` |
| POST | `/{id}/sign-in` | `SignIn` | `200 StudentDetail` |
| POST | `/promote` | `Promote` | `200 PromotionResult` |
| POST | `/import?dryRun=true` | `{ csv: string }` | `200 ImportResult` (see below) |

The list covers one academic year: `yearId`, or the current year when it is left out. `academicYearId`
in the response is that year, or `null` when the school has no current year yet (the list is then
empty). `q` matches the full name, last name or admission number (case-insensitive, max 100
characters). `page` is 0-based; `size` is 1–100. Rows are ordered by roll number when filtered to one
section, otherwise by name.

```ts
type StudentRow = { id: string; admissionNo: string; firstName: string; lastName: string | null;
  fullName: string; gender: "MALE" | "FEMALE" | "OTHER"; dateOfBirth: string;
  status: "ACTIVE" | "TRANSFERRED" | "WITHDRAWN" | "ALUMNI";
  classId: string | null; className: string | null; sectionId: string | null; sectionName: string | null;
  rollNo: number | null; guardianName: string | null; guardianPhone: string | null };  // primary contact
type StudentPage = { items: StudentRow[]; page: number; size: number; total: number;
  academicYearId: string | null };

type GuardianFields = { name: string; relation: "MOTHER" | "FATHER" | "GUARDIAN"; phone: string;
  email?: string | null; occupation?: string | null; primary?: boolean };   // primary defaults to false
type Profile = { admissionNo: string; firstName: string; lastName?: string | null;
  dateOfBirth: string; gender: "MALE" | "FEMALE" | "OTHER"; admissionDate: string;
  bloodGroup?: string | null; address?: string | null; previousSchool?: string | null;
  apaarId?: string | null };
type CreateStudent = Profile & { sectionId: string; rollNo?: number | null; guardians: GuardianFields[] };
type UpdateStudent = Profile & { sectionId?: string | null; rollNo?: number | null };
type SignIn = { mode: "CREATE"; email: string; password: string } | { mode: "LINK"; email: string };
type Promote = { fromSectionId: string; fromYearId?: string | null;   // default: current year
  toSectionId?: string | null; toYearId?: string | null; graduate?: boolean };

type Guardian = { id: string; name: string; relation: string; phone: string; email: string | null;
  occupation: string | null; primary: boolean; hasSignIn: boolean; signInEmail: string | null };
type Enrollment = { id: string; academicYearId: string; academicYearName: string; currentYear: boolean;
  classId: string; className: string; sectionId: string; sectionName: string; rollNo: number | null };
type StudentDetail = Profile & { id: string; fullName: string; status: string;
  leftOn: string | null; leavingReason: string | null; hasSignIn: boolean; signInEmail: string | null;
  currentEnrollment: Enrollment | null; guardians: Guardian[];
  siblings: { id: string; fullName: string; admissionNo: string; status: string;
    className: string | null; sectionName: string | null }[];
  enrollments: Enrollment[] };        // newest year first
type PromotionResult = { promoted: number; graduated: number;
  skipped: { studentId: string; fullName: string; reason: string }[] };
```

Student rules:

- `admissionNo`: 1–30 letters, digits and `/ . _ -`, starting with a letter or digit, unique in the school
  (ignoring case). `firstName` 1–100 characters; `lastName` optional (some students use one name).
- `dateOfBirth` in the past and before `admissionDate`. `bloodGroup` one of `A+ A- B+ B- AB+ AB- O+ O-`.
- `apaarId`: optional, 12 digits. **Aadhaar numbers are not collected or stored anywhere.**
- A new student joins the given section in the **current** academic year (`400` on `sectionId` when
  there is none). `rollNo` 1–999, unique within the section and year (`409`). A section at capacity
  answers `409`. On update, `sectionId` moves an active student within the current year.
- 1–4 guardians, at most one `primary` (the first becomes primary when none is marked). A guardian with
  the same mobile number and name as an existing one is the same person: the record is shared, and
  students who share a guardian are listed as `siblings`. A guardian left with no student is deleted.
- `leftOn` cannot be before the admission date; `reason` 1–500 characters.

Sign-ins: `CREATE` makes a new account with the Parent (guardian) or Student role and a temporary
password of at least 10 characters (`409` if the email is taken). `LINK` attaches an existing account in
this school that already has that role. A guardian or student has at most one sign-in, and an account
belongs to at most one guardian or student.

Promotion moves every **active** student enrolled in the source section and year into the target section
of a **later** year, assigning roll numbers in name order after any already used. Students already
enrolled in the target year are skipped with a reason. `graduate: true` instead marks them `ALUMNI` with
`leftOn` = the source year's end date. Target capacity is checked for the whole group first (`409`).

### CSV import

`POST /api/students/import?dryRun=true` checks a file; `dryRun=false` imports it. Body `{ csv }`, at most
2,000,000 characters and **2,000 data rows**. Students join the current academic year.

Columns (header row required; names are case-insensitive, spaces become `_`): `admission_no`,
`first_name`, `last_name`, `date_of_birth`, `gender`, `class`, `section`, `guardian_name`,
`guardian_relation`, `guardian_phone`, `guardian_email`. Optional: `admission_date` (default today),
`roll_no`. Other columns are reported in `ignoredColumns` and not stored. Dates: `YYYY-MM-DD`,
`DD-MM-YYYY`, `DD/MM/YYYY` or `DD.MM.YYYY`. Gender `M`/`F`/`O` or the full word. Class as named in setup,
or just the number (`5` → `Class 5`). Quoted fields (RFC 4180), a UTF-8 BOM and CRLF are accepted.

```ts
type ImportResult = { dryRun: boolean; committed: boolean; totalRows: number; validRows: number;
  invalidRows: number; created: number;
  errors: { row: number; column: string; message: string }[];   // row = line in the file; max 500 listed
  ignoredColumns: string[] };
```

The import is **all or nothing**: the same checks as the form, plus duplicates within the file and
section capacity for the whole file. With any bad row, `dryRun=false` writes nothing and answers `400`
(`detail` "N rows need attention. Nothing was imported.") with the `ImportResult` in a `result`
property. File-level problems (no header, a missing column, too many rows, no current year) answer
`400` with `errors.csv`.

## Parent and student views

| Method | Path | Permission | Success |
| --- | --- | --- | --- |
| GET | `/api/me/children` | `child.view` | `200 Child[]`: students whose guardian record is linked to the caller |
| GET | `/api/me/student` | `dashboard.view` | `200 Child` for the caller's own student record; `404` when not linked |

```ts
type Child = { id: string; fullName: string; admissionNo: string; status: string;
  className: string | null; sectionName: string | null; rollNo: number | null;
  classTeacherName: string | null; academicYearName: string | null };   // current year's class
```

## Audit actions added in Phase 1

`academic_year.created|updated|set_current|deleted`, `class.created|updated|deleted|subjects_updated`,
`section.created|updated|deleted`, `subject.created|updated|deleted`, `school.profile_updated`,
`student.created|updated|transferred|withdrawn|promoted|graduated|sign_in_linked`, `students.imported`,
`guardian.added|updated|removed|sign_in_linked`. Creating a sign-in also writes `user.created`.
Details hold ids, names, admission numbers and counts; they never hold phone numbers, addresses or
dates of birth.

## Data

New schemas `academics` (`academic_year`, `school_class`, `section`, `subject`, `class_subject`) and
`students` (`student`, `guardian`, `student_guardian`, `enrollment`). Every table has `tenant_id` and the
`tenant_isolation` row-level security policy; `RowLevelSecurityCoverageIT` fails the build if a future
table is missing either.

Admissions (enquiries, applications, tests and interviews, offers, admitting, the public enquiry form): see [phase-1-admissions.md](phase-1-admissions.md).
Attendance and the notifications outbox: [phase-1-attendance.md](phase-1-attendance.md).
Fees (heads, structures, concessions, dues, payments, receipts, online payments, reports): see [phase-1-fees.md](phase-1-fees.md).
Staff records, staff attendance and leave: [phase-1-staff.md](phase-1-staff.md).
Circulars, notice boards and the school calendar: [phase-1-communication.md](phase-1-communication.md).
Timetable, homework and file attachments: [phase-1-timetable-homework.md](phase-1-timetable-homework.md).
Billing (plans, subscriptions, GST invoices, renewals, suspension, platform health — the Super Admin console): see [phase-1-billing.md](phase-1-billing.md).
