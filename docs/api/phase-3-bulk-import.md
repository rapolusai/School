# Phase 3: school import (students and staff from one Excel file)

An existing school (2,000+ students, 30+ staff) brings everyone in at once from a single Excel workbook instead of
registering people one by one. Package `com.akshara.bulkimport`. **No migration**: the import writes the same tables
as the forms (students, enrolments, guardians, staff sign-ins and profiles, classes, sections, departments).

The workbook has:

- an **Instructions** sheet (ignored by the import),
- a **Staff** sheet, one staff member per row,
- one sheet per **class and section**, named like `Class 5 - A` (class, hyphen, section; `5 - A` also means
  `Class 5`), one student per row.

The first row of every sheet holds the column headings. Headings are matched loosely (case, spaces and punctuation do
not matter, a few other names are accepted, such as `Admission number` or `Parent mobile`), columns may be in any
order, and other columns are listed in `ignoredColumns` and never read or stored. Empty rows are skipped; a sheet
with only the headings is skipped. **Aadhaar numbers are never read or stored**: an Aadhaar column is just an ignored
column.

## Endpoints

| Method | Path | Permission | Success |
| --- | --- | --- | --- |
| GET | `/api/school-import/template.xlsx` | `students.manage` and `staff.manage` | `200` the template (`Content-Disposition: attachment; filename="school-import-template.xlsx"`) |
| GET | `/api/school-import/sample.xlsx` | `students.manage` and `staff.manage` | `200` a sample workbook with 2,000 made-up students and 30 made-up staff |
| POST | `/api/school-import?dryRun=true&createMissing=false` | `students.manage` and `staff.manage` (and `settings.manage` when `createMissing=true`) | `200 ImportResult` |
| PUT | `/api/staff/{userId}/password` | `staff.manage` | `200 StaffDetail`; body `{ password }` (10 to 200 characters) |

The School Admin has every permission needed. A role with `students.manage` but not `staff.manage` (Front office)
gets `403`, and `createMissing=true` without `settings.manage` gets `403`.

The upload is `multipart/form-data` with the workbook in the part `file`. `dryRun` defaults to `true` (check only);
`dryRun=false` checks again and imports. `createMissing` defaults to `false`.

The template has the Instructions and Staff sheets and one empty sheet for every section the school has set up (in
class order). A school with no sections yet gets two example sheets, `Class 1 - A` and `Class 1 - B`. The sample
has Classes 1 to 10 with sections A and B, 100 students each (admission numbers `SMP/0001` to `SMP/2000`), some
siblings who share a guardian, and 30 staff in Mathematics, Science, Languages, Social Studies, Primary, Computers,
Sports, Arts and Administration. Every name is from a common-names list, every mobile is in a made-up range
(`70000xxxxx` for parents, `70001xxxxx` for staff) and every email is at `example.com`. Nothing is ever sent to them.
To try it on a freshly signed-up school: add the current academic year in School setup, download the sample, upload
it with "Add classes, sections and departments that are not set up yet" ticked, check, import.

## Limits

| What | Limit | Answer |
| --- | --- | --- |
| File size | 5 MB (the same as every upload; a 2,000-student file is about 150 KB) | `413` "Files can be at most 5 MB each." |
| Students per file | 5,000 | `400 errors.file` |
| Staff per file | 500 | `400 errors.file` |
| Sheets | 250 | `400 errors.file` |
| Rows per sheet | 10,000 | `400 errors.file` |
| Columns read | the first 100; cells beyond are ignored | |
| Characters per cell | 5,000 | `400 errors.file` |

The workbook is read by a small reader written for this (`XlsxReader`, java.util.zip and StAX, no library). It reads
shared strings, inline strings, numbers, booleans, the values Excel saved for formulas (formulas are never evaluated)
and dates stored as serial numbers (cells with a date format become `YYYY-MM-DD`; the 1904 date system is honoured).
Numbers come back without a decimal point when whole, so a mobile typed as a number stays `9876543210`. Because the
file is untrusted it refuses, with `400 errors.file`: anything that is not a zip (including `.xls` and `.csv`),
more than 2,000 zip entries, entry names with `..`, a leading `/`, a backslash, a drive letter or NUL, the same entry
twice, more than 40 MB inflated in one entry or 120 MB in all (counted while inflating, whatever the zip headers say,
so a zip bomb stops early), any `<!DOCTYPE>` (so no entity expansion and no external entities), relationship
targets outside `xl/worksheets/`, and damaged XML.

File-level problems (not a workbook, too many students, no current academic year, a file with no students or staff
at all) answer `400` with `errors.file`.

## Checks

Students are checked with **exactly the rules of the CSV import** (`docs/api/phase-1.md`, "CSV import"); both use
`StudentImportService`. Staff are checked with the rules of "Add staff" (`docs/api/phase-1-staff.md`).

| Student column | Required | Rule |
| --- | --- | --- |
| Admission no | yes | up to 30 letters, digits and `/ . _ -`; unique in the school and in the file (across sheets) |
| Roll no | no | 1 to 999, unique in the section (this year and the file) |
| First name | yes | up to 100 characters |
| Last name | no | up to 100 characters |
| Date of birth | yes | in the past, before the admission date, at most 25 years before it |
| Gender | yes | `MALE`, `FEMALE`, `OTHER` or `M`, `F`, `O` |
| Guardian name | yes | up to 200 characters |
| Relation | yes | `FATHER`, `MOTHER` or `GUARDIAN` |
| Guardian mobile | yes | a 10-digit Indian mobile, with or without `+91`/`0`, spaces or hyphens |
| Guardian email | no | a valid email |
| Admission date | no | defaults to the day of the import |

The class and section come from the sheet's name. A section's capacity applies to the whole file. A guardian with
the same mobile and name as an existing guardian (or another row) is the same person, so children who share one
are siblings.

| Staff column | Required | Rule |
| --- | --- | --- |
| Name | yes | up to 200 characters |
| Email | yes | valid, unique in the school (any sign-in) and in the file |
| Mobile | yes | a 10-digit Indian mobile |
| Role | yes | one or more staff roles by name or code (`Teacher`, `Front office`, `FRONT_OFFICE`, ...), separated by `,` `;` or `/`; Parent and Student are not staff roles |
| Designation | yes | up to 100 characters |
| Department | no | an existing department, or a new one when `createMissing=true` |
| Employment type | yes | `PERMANENT`, `CONTRACT`, `PART_TIME` (or `Part time`) or `PROBATION` |
| Joining date | yes | not before 1950 and at most a year ahead |
| Employee code | yes | up to 30 letters, digits and `/ . _ -`, starting with a letter or digit; unique in the school and the file |

Dates may be Excel dates or text as `YYYY-MM-DD`, `DD-MM-YYYY`, `DD/MM/YYYY` or `DD.MM.YYYY`.

Sheet problems (reported with `row: null`): a sheet whose name is not `class - section` (unless it is empty), two
sheets for the same section, a second Staff sheet, a missing required heading, a heading twice, a section name over
20 characters for a section to be created, and a class or section that is not set up when `createMissing=false`.

## Classes, sections and departments that do not exist yet

With `createMissing=false` (the default) a sheet for a section the school has not set up has `section: "MISSING"` and
a sheet problem; a department the school does not have is a row problem. With `createMissing=true` such sheets show
`NEW_SECTION` (the class exists) or `NEW_CLASS`, the departments are listed, and `toCreate` names everything the
import will add. The import then creates them first (classes in the order of the sheets, at the end of the class
order; sections without a capacity or class teacher; departments without a head), each audited as usual
(`class.created`, `section.created`, `department.created`).

## The result

```ts
type ImportResult = {
  dryRun: boolean; committed: boolean; createMissing: boolean;
  ready: boolean;                 // rows to import and no problem at all
  studentRows: number; staffRows: number;
  validRows: number; invalidRows: number;   // rows across all sheets
  sheetProblems: number;          // problems with whole sheets
  studentsCreated: number; staffCreated: number;   // 0 until a real import commits
  toCreate: { classes: string[]; sections: string[]; departments: string[] };  // "Class 9", "Class 9 - A", "Science"
  sheets: {
    name: string; kind: "STUDENTS" | "STAFF" | "IGNORED";
    className: string | null; sectionName: string | null;          // as matched in School setup
    section: "EXISTS" | "NEW_SECTION" | "NEW_CLASS" | "MISSING" | null;
    rows: number; validRows: number; invalidRows: number;
    problems: { row: number | null; column: string | null; message: string }[];  // row = the sheet's row number
    problemsNotShown: number;
    ignoredColumns: string[];
  }[];
};
```

`column` is the heading as the template writes it (`Guardian mobile`). Messages about a value used twice name the
other place: "Also used on row 2 of Class 5 - A.". At most 200 problems are listed per sheet and 1,000 in all; the
counts are always complete and `problemsNotShown` says how many more a sheet has.

**All or nothing.** Nothing is written unless the whole file is clean. `dryRun=false` checks the file again (things
may have changed since the dry run); with any problem it writes nothing and answers `400` with title "Check the
file", `detail` "N problems need fixing. Nothing was imported." and the `ImportResult` in a `result` property. A
clean file is written in **one transaction**: classes, sections and departments, then the staff, then the students.
If anything fails, everything is rolled back. Tenant isolation is the usual one: every write and lookup goes through
the school's row-level security, and admission numbers, emails and employee codes are unique per school (two schools
may import the same file).

A 2,500-student file imports in about 6 seconds in the integration tests (PostgreSQL in a container).

## Sign-ins

- **Staff** get a sign-in (they are users with staff roles, as with "Add staff") **without a usable password**:
  the account stores a marker that no password matches (`UserAccount.NO_PASSWORD`), the login checks it against a
  dummy hash so it takes as long as any other failed login, and the answer is the usual generic `401`. The account
  is active, so the person is ACTIVE staff (attendance, leave, payroll and timetables all see them). No password is
  ever generated, stored, logged or shown, and none is in any file.
- `StaffDetail` has a new field `signInReady` (false while there is no password). The staff page shows "No sign-in
  yet" and, to `staff.manage`, a **Set password** button: the School Admin types a password (at least 10
  characters, hashed before the transaction starts) and tells the person in person. `PUT /api/staff/{userId}/password`
  answers `404` for someone who is not staff of this school and `409` for someone who has left. It ends the
  person's open sessions and is audited as `user.password_set` (details: `firstPassword`; never the password).
- **Parents** get no sign-in from the import. Give a parent a sign-in from the child's page as before
  (`POST /api/students/{id}/guardians/{guardianId}/sign-in`, `docs/api/phase-1.md`).

## Audit

| Action | Entity | Details |
| --- | --- | --- |
| `school.bulk_imported` | school | `students`, `staff`, `studentSheets`, `classesAdded`, `sectionsAdded`, `departmentsAdded`, `rowsPerSheet` (sheet name to row count) |
| `students.imported` | student | `count`, `academicYear`, `source: "xlsx"` (the same event as the CSV import) |
| `staff.imported` | staff | `count`, `departmentsAdded`, `signIn: "password not set"`, `source: "xlsx"` |
| `user.created` | user | one per staff member, as with "Add staff" (email and roles) |
| `user.password_set` | user | `firstPassword` |

The import's own events hold counts only, never names, mobiles or dates of birth.

## Web app

School setup (`/app/setup`) shows a **Bring your existing students and staff** card to anyone with
`students.manage` and `staff.manage`, with Download template, Download sample and Upload file. Upload file opens
`/app/setup/import`: pick the `.xlsx` (checked for type and the 5 MB limit before upload), tick "Add classes,
sections and departments that are not set up yet" (shown only with `settings.manage`), Check file, read the result
sheet by sheet (counts, whether each section exists or will be added, every problem by row), then Import. All text
is in English, Hindi and Telugu; the problems themselves are the server's English messages, as in the CSV import.
The CSV import (`/app/students/import`, `POST /api/students/import`) is unchanged.
