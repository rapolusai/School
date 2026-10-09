# Phase 1 API: timetable, homework and file attachments

Same conventions as [Phase 0](phase-0.md) and [Phase 1](phase-1.md): JSON under `/api`, a Bearer access token,
RFC 9457 problem details with `errors.<field>`, ids from another school answer `404` in a path and `400` with a field
error in a body, `409` for conflicts, dates are `"YYYY-MM-DD"` school days in India (Asia/Kolkata), days of the week
are `MONDAY`…`SUNDAY`, times are `"HH:mm"`, and every change is written to the audit trail.

**In this slice:** the bell schedule (with an optional Saturday schedule), teacher assignments, section timetables
with clash checks, teacher timetables, free teachers, substitutions for absent teachers, homework with attachments,
online submissions, review and a tracker, homework reminders to parents, and stored files.

**Not in this slice:** period-wise attendance, rotating (A/B week) timetables, automatic timetable generation, and
taking a teacher's approved leave into account automatically (absences are recorded on the substitutions page).

## Permissions

| Permission | Who has it (seeded roles) | Allows |
| --- | --- | --- |
| `timetable.read` (new) | every staff role: School Admin, Principal, Teacher, Accountant, Front office | reading the bell schedule, timetables, free teachers and the substitution sheet |
| `timetable.manage` (new) | School Admin, Principal | changing the bell schedule, assignments, section timetables and substitutions; the clash report |
| `homework.manage` (new) | School Admin, Principal, Teacher | setting and reviewing homework. A caller without `timetable.manage` is limited to the sections and subjects they teach in the timetable, and every subject of the sections they are class teacher of |
| `settings.manage` | School Admin | switching homework reminders on or off |
| `dashboard.view` | Student (and every role) | a student's own timetable and homework, and submitting it |
| `child.view` | Parent, School Admin | a parent's own child's timetable and homework (read only) |

Migration `V9__timetable_homework.sql` appends the new permissions to the matching roles of every existing school.
Homework the caller cannot see is `404`; homework they can see but not change (another teacher's in their class
section) is `403`.

## Files — `/api/files`

| Method | Path | Permission | Success |
| --- | --- | --- | --- |
| GET | `/api/files/{id}` | signed in, and allowed by the module that owns the file | `200` the bytes, `Content-Disposition: attachment; filename*=UTF-8''…`, `X-Content-Type-Options: nosniff`, `Cache-Control: no-store, private` |

Anyone the owning module does not allow, an unknown id, and another school's file are all `404`. Uploads go to the
owning module's endpoints (below) as `multipart/form-data`. Every file: at most 5 MB (`413`, `errors.<field>`), PDF,
JPEG, PNG, WEBP, DOCX, XLSX, PPTX or TXT recognised from its contents, with an extension that agrees (`400`), not empty
(`400`). Names are cleaned (no folders or special characters, at most 100 characters). See
[ADR 0004](../adr/0004-file-storage.md).

```ts
type FileRef = { id: string; name: string; contentType: string; size: number };   // bytes
```

## Timetable — `/api/timetable`

| Method | Path | Permission | Success |
| --- | --- | --- | --- |
| GET | `/bell-schedule` | `timetable.read` | `200 BellSchedule` |
| PUT | `/bell-schedule` | `timetable.manage` | `200 BellSchedule`; replaces the whole schedule |
| GET | `/assignments?sectionId=&teacherId=` | `timetable.read` | `200 AssignmentView[]` of the current year |
| POST | `/assignments` | `timetable.manage` | `201 AssignmentView` |
| PUT | `/assignments/{id}` | `timetable.manage` | `200 AssignmentSaved`; the section's periods of that subject move to the new teacher |
| DELETE | `/assignments/{id}` | `timetable.manage` | `204`; `409` while the subject has periods in the section |
| GET | `/sections` | `timetable.read` | `200 SectionsOverview` |
| GET | `/sections/{sectionId}` | `timetable.read` | `200 SectionTimetable` (`busy` only for `timetable.manage`) |
| PUT | `/sections/{sectionId}` | `timetable.manage` | `200 SectionTimetable`; replaces the section's week |
| GET | `/clashes` | `timetable.manage` | `200 ClashReport` for the whole school |
| GET | `/teachers` | `timetable.read` | `200 TeacherSummary[]`: active users with the Teacher role |
| GET | `/teachers/{teacherId}` | `timetable.read` | `200 TeacherTimetable` |
| GET | `/teachers/{teacherId}/day?date=` | `timetable.read` | `200 TeacherDay` (default today) |
| GET | `/me` | `timetable.read` | `200 TeacherTimetable` of the caller |
| GET | `/me/today?date=` | `timetable.read` | `200 TeacherDay` of the caller, substitutions included |
| GET | `/free-teachers?date=&day=&period=&subjectId=` | `timetable.read` | `200 FreeTeachers`: give `date` (absences and substitutions count) or `day` |
| GET | `/substitutions?date=` | `timetable.read` | `200 SubstitutionDay` (default today) |
| POST | `/substitutions/absences` | `timetable.manage` | `201 SubstitutionDay`; `409` if already recorded |
| DELETE | `/substitutions/absences/{id}` | `timetable.manage` | `200 SubstitutionDay`; its substitutions go too |
| PUT | `/substitutions` | `timetable.manage` | `200 SubstitutionDay`; assigns or replaces the substitute of one period |
| DELETE | `/substitutions/{id}` | `timetable.manage` | `200 SubstitutionDay` |
| GET | `/api/me/timetable` | `dashboard.view` | `200 FamilyTimetable` of the signed-in student |
| GET | `/api/me/children/{studentId}/timetable` | `child.view` | `200 FamilyTimetable` of the caller's own child; any other student is `404` |

Timetables belong to the current academic year; without one, changes answer `400` with `errors.academicYear`.

**Bell schedule rules.** 1–16 teaching periods a day; breaks (lunch, short break) have no number. Rows must be in
time order without overlapping (`400 errors["weekday[2].startsAt"]`). A Saturday schedule needs Saturday among the
working days. Removing a working day or a period that a section still uses is `409` (`errors.workingDays`,
`errors.weekday` or `errors.saturday`).

**Clash rules, checked on every save.** A teacher in two sections in the same period is refused with `409` (the
message names the teacher and the other section). A section cannot have two subjects in one period (one cell per
day and period, `400`). Each cell's day and period must be a teaching period of the bell schedule and its subject
one the class studies (`400 errors["MONDAY-3"]`). A subject placed more often than its assignment's
`periodsPerWeek` is a **warning** (`warnings`), not an error. `GET /clashes` checks every section (clashes can also
appear when an assignment moves periods to a teacher who is busy).

**Substitutes** are suggested from teachers free in that period on that date: those who teach the subject first,
then those with the fewest periods that day. A substitute must be free (`409 errors.teacherId`) and not the absent
teacher (`400`). The substitute sees the period in `GET /me/today`, and the section's students in
`/api/me/timetable` (`todayPeriods`).

```ts
type Day = "MONDAY" | "TUESDAY" | "WEDNESDAY" | "THURSDAY" | "FRIDAY" | "SATURDAY" | "SUNDAY";
type YearRef = { id: string; name: string; startsOn: string; endsOn: string } | null;
type PeriodView = { number: number | null; label: string; startsAt: string; endsAt: string; breakTime: boolean };
type BellSchedule = { workingDays: Day[]; saturdaySchedule: boolean; weekday: PeriodView[]; saturday: PeriodView[];
  weekdayPeriods: number; saturdayPeriods: number };
type BellScheduleRequest = { workingDays: Day[]; saturdaySchedule: boolean;
  weekday: { label: string; startsAt: string; endsAt: string; breakTime: boolean }[];   // max 30 rows
  saturday?: { label: string; startsAt: string; endsAt: string; breakTime: boolean }[] };

type AssignmentRequest = { sectionId: string; subjectId: string; teacherId: string; periodsPerWeek: number }; // 0–60
type AssignmentUpdate = { teacherId: string; periodsPerWeek: number };
type AssignmentView = { id: string; sectionId: string; sectionLabel: string; classId: string; subjectId: string;
  subjectName: string; teacherId: string; teacherName: string; periodsPerWeek: number; scheduled: number };
type AssignmentSaved = { assignment: AssignmentView; periodsMoved: number; clashes: Clash[] };

type SectionRequest = { slots: { day: Day; period: number; subjectId: string; teacherId: string | null;
  room?: string | null }[] };   // max 112; teacherId defaults to the subject's assigned teacher
type SlotView = { day: Day; period: number; subjectId: string; subjectName: string; teacherId: string | null;
  teacherName: string | null; room: string | null };
type ClashEntry = { sectionId: string; sectionLabel: string; subjectId: string; subjectName: string;
  teacherId: string | null; teacherName: string | null };
type Clash = { kind: "TEACHER" | "SECTION"; day: Day; period: number; teacherId: string | null;
  teacherName: string | null; sectionId: string | null; sectionLabel: string | null; entries: ClashEntry[] };
type WeeklyWarning = { sectionId: string; sectionLabel: string; subjectId: string; subjectName: string;
  scheduled: number; periodsPerWeek: number };
type SectionTimetable = { sectionId: string; label: string; classId: string; className: string; sectionName: string;
  classTeacherName: string | null; academicYear: YearRef; bells: BellSchedule; slots: SlotView[];
  subjects: { subjectId: string; subjectName: string; assignmentId: string | null; teacherId: string | null;
    teacherName: string | null; periodsPerWeek: number | null; scheduled: number }[];
  busy: { teacherId: string; day: Day; period: number; sectionId: string; sectionLabel: string;
    subjectName: string }[];          // other sections' periods of this section's teachers, for live warnings
  clashes: Clash[]; warnings: WeeklyWarning[]; canEdit: boolean };
type SectionsOverview = { academicYear: YearRef; bells: BellSchedule; clashes: number; canEdit: boolean;
  sections: { sectionId: string; label: string; classId: string; className: string; sectionName: string;
    classTeacherName: string | null; scheduled: number; cells: number; clashes: number }[] };
type ClashReport = { academicYear: YearRef; sectionsChecked: number; periodsChecked: number; clashes: Clash[];
  warnings: WeeklyWarning[] };

type TeacherSummary = { id: string; name: string; periodsPerWeek: number; scheduled: number; subjects: string[] };
type TeacherTimetable = { teacherId: string; teacherName: string; academicYear: YearRef; bells: BellSchedule;
  slots: { day: Day; period: number; sectionId: string; sectionLabel: string; subjectId: string;
    subjectName: string; room: string | null }[]; periodsPerWeek: number; clashes: Clash[] };
type DayEntry = { kind: "CLASS" | "SUBSTITUTION" | "COVERED"; sectionId: string; sectionLabel: string;
  subjectId: string; subjectName: string; teacherName: string | null; room: string | null;
  substituteName: string | null; absentTeacherName: string | null };
type DayPeriod = { number: number | null; label: string; startsAt: string; endsAt: string; breakTime: boolean;
  entries: DayEntry[] };
type TeacherDay = { date: string; day: Day; workingDay: boolean; teacherId: string; teacherName: string;
  absent: boolean; periods: DayPeriod[] };

type FreeTeacher = { id: string; name: string; periodsThatDay: number; teachesSubject: boolean };
type FreeTeachers = { date: string | null; day: Day; period: number; teachers: FreeTeacher[] };   // ranked
type AbsenceRequest = { date: string; teacherId: string; reason?: string };           // reason max 200
type SubstitutionRequest = { date: string; sectionId: string; period: number; teacherId: string };
type SubstitutionDay = { date: string; day: Day; workingDay: boolean; periodsToCover: number; periodsCovered: number;
  teachers: { id: string; name: string }[];
  absences: { id: string; teacherId: string; teacherName: string; reason: string | null;
    periods: { period: number; label: string; startsAt: string; endsAt: string; sectionId: string;
      sectionLabel: string; subjectId: string; subjectName: string; room: string | null;
      substitute: { id: string; teacherId: string; teacherName: string } | null;
      suggestions: FreeTeacher[] }[] }[] };

type FamilyTimetable = { studentId: string; studentName: string; sectionId: string | null;
  sectionLabel: string | null; academicYear: YearRef; bells: BellSchedule; slots: SlotView[]; today: string;
  todayDay: Day; workingDay: boolean; todayPeriods: DayPeriod[] };
```

## Homework — `/api/homework`

| Method | Path | Permission | Success |
| --- | --- | --- | --- |
| GET | `/options` | `homework.manage` | `200 HomeworkOptions`: the sections and subjects the caller may set homework for |
| GET | `?sectionId=&subjectId=&when=open\|past\|all&page=&size=` | `homework.manage` | `200 HomeworkPage`; `open` (default) is due today or later; size max 100 |
| POST | `` | `homework.manage` | `201 HomeworkDetail` |
| GET | `/{id}` | `homework.manage` | `200 HomeworkDetail` |
| PUT | `/{id}` | `homework.manage` | `200 HomeworkDetail`; `409` after the due date |
| DELETE | `/{id}` | `homework.manage` | `204`; `409` once anyone has submitted |
| POST | `/{id}/attachments` | `homework.manage` | `201 FileRef`; multipart part `file`; at most 5 per homework (`409`) |
| DELETE | `/{id}/attachments/{fileId}` | `homework.manage` | `204` |
| GET | `/{id}/submissions` | `homework.manage` | `200 Tracker`: every student of its sections, submitted or not |
| PUT | `/{id}/submissions/{submissionId}/review` | `homework.manage` | `200 TrackerRow` |
| GET | `/settings` | `homework.manage` or `settings.manage` | `200 { remindersEnabled: boolean }` |
| PUT | `/settings` | `settings.manage` | `200 { remindersEnabled: boolean }` |
| GET | `/api/me/homework` | `dashboard.view` | `200 StudentHomework` of the signed-in student (current year) |
| GET | `/api/me/homework/{id}` | `dashboard.view` | `200 StudentHomeworkDetail` |
| POST | `/api/me/homework/{id}/submission` | `dashboard.view` | `200 StudentHomeworkDetail`; multipart `text`, `files` (repeat), `keepFileIds` (repeat) |
| GET | `/api/me/children/{studentId}/homework` | `child.view` | `200 StudentHomework` of the caller's own child |
| GET | `/api/me/children/{studentId}/homework/{id}` | `child.view` | `200 StudentHomeworkDetail` (read only, `canSubmit: false`) |

**Rules.** `assignedOn` (default today) cannot be in the future; `dueOn` must be on or after `assignedOn` and today,
inside the current academic year. Instructions are plain text (at most 5,000 characters; HTML is shown as text).
Homework can be changed until its due date; once a student has submitted, its subject cannot change and sections
cannot be removed. Attachments can be added and removed until the due date.

**Submissions.** Only for homework with `onlineSubmission: true` (`409` otherwise). Text and/or files (at most 5
files, each checked as above); `keepFileIds` lists earlier files to keep on a resubmission. A submission after the due
date is accepted and marked `late`. A student can resubmit until a teacher reviews it (`409` after `REVIEWED`);
`NEEDS_REDO` opens it again. Statuses: `SUBMITTED`, `REVIEWED`, `NEEDS_REDO`, with an optional grade (max 10
characters) and remark (max 500).

**Reminders** go to each student's primary parent or guardian through the notifications outbox (templates
`homework.assigned` and `homework.due`, the school's alert channel and language), only while the school has them
switched on (off by default): once when homework is set for today, and once the evening before the due date (a job
at 17:00–20:00 India time) for students who have not submitted. Dedupe keys `homework-assigned:<homework>:<student>`
and `homework-due:<homework>:<student>` make each go at most once.

```ts
type HomeworkRequest = { sectionIds: string[]; subjectId: string; title: string; instructions?: string | null;
  assignedOn?: string | null; dueOn: string; onlineSubmission: boolean };      // sections 1–30, title max 200
type ReviewRequest = { status: "SUBMITTED" | "REVIEWED" | "NEEDS_REDO"; grade?: string | null; remark?: string | null };
type Counts = { students: number; submitted: number; late: number; missing: number; reviewed: number;
  needsRedo: number; waiting: number };
type HomeworkOptions = { academicYear: YearRef; today: string; maxAttachments: number; maxFileBytes: number;
  sections: { id: string; label: string; classId: string; subjects: { id: string; name: string }[] }[] };
type HomeworkRow = { id: string; title: string; subjectId: string; subjectName: string;
  sections: { id: string; label: string }[]; assignedOn: string; dueOn: string; onlineSubmission: boolean;
  attachments: number; counts: Counts; createdByName: string | null; canEdit: boolean };
type HomeworkPage = { items: HomeworkRow[]; page: number; size: number; total: number };
type HomeworkDetail = { id: string; title: string; instructions: string | null; subjectId: string;
  subjectName: string; sections: { id: string; label: string }[]; assignedOn: string; dueOn: string;
  onlineSubmission: boolean; attachments: FileRef[]; createdByName: string | null; createdAt: string;
  updatedAt: string; counts: Counts; canEdit: boolean; canDelete: boolean; today: string };
type TrackerRow = { studentId: string; fullName: string; admissionNo: string; rollNo: number | null;
  sectionId: string; sectionLabel: string; status: "MISSING" | "SUBMITTED" | "REVIEWED" | "NEEDS_REDO";
  submissionId: string | null; submittedAt: string | null; late: boolean; attempts: number; body: string | null;
  files: FileRef[]; grade: string | null; remark: string | null; reviewedByName: string | null;
  reviewedAt: string | null };
type Tracker = { homework: HomeworkDetail; counts: Counts; rows: TrackerRow[] };

type StudentHomework = { studentId: string; studentName: string; sectionId: string | null;
  sectionLabel: string | null; today: string;
  items: { id: string; title: string; subjectId: string; subjectName: string; assignedOn: string; dueOn: string;
    onlineSubmission: boolean; attachments: number;
    status: "PENDING" | "SUBMITTED" | "REVIEWED" | "NEEDS_REDO"; late: boolean; overdue: boolean;
    submittedAt: string | null; grade: string | null }[] };
type StudentHomeworkDetail = { id: string; studentId: string; studentName: string; title: string;
  instructions: string | null; subjectId: string; subjectName: string; assignedOn: string; dueOn: string;
  onlineSubmission: boolean; attachments: FileRef[]; createdByName: string | null;
  submission: { id: string; body: string | null; files: FileRef[]; submittedAt: string; late: boolean;
    attempts: number; status: "SUBMITTED" | "REVIEWED" | "NEEDS_REDO"; grade: string | null;
    remark: string | null; reviewedByName: string | null; reviewedAt: string | null } | null;
  canSubmit: boolean; overdue: boolean; maxFiles: number; maxFileBytes: number; today: string };
```

## Audit

`bell_schedule.updated`, `teacher_assignment.created|updated|deleted`, `timetable.section_saved`,
`teacher_absence.recorded|removed`, `substitution.assigned|removed`, `homework.created|updated|deleted`,
`homework.attachment_added|attachment_removed`, `homework.submitted`, `homework.reviewed`,
`homework_settings.updated`. Details hold ids, names, counts, dates and file names; never file contents, submission
text or phone numbers.

## Data

New schemas `files` (`stored_file`), `timetable` (`settings`, `period`, `teacher_assignment`, `slot`,
`teacher_absence`, `substitution`) and `homework` (`homework`, `homework_section`, `submission`, `settings`). Every
table has `tenant_id`, the `tenant_isolation` row-level security policy and composite `(tenant_id, …)` foreign keys.
Academic years with timetable or homework rows cannot be deleted. No events are published to other modules.
