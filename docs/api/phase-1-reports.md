# Phase 1 API: staff dashboards and reports

Same conventions as [Phase 0](phase-0.md) and [Phase 1](phase-1.md): JSON under `/api`, a Bearer access token,
RFC 9457 problem details with `errors.<field>`, ids of another school answer `404`, dates are `"YYYY-MM-DD"` days in
India (Asia/Kolkata), amounts are paise, percentages are numbers rounded to one decimal place (`null` when there is
nothing to divide by).

**In this slice:** one summary for the staff dashboards (`GET /api/dashboard`), five new reports with an Excel
export each, the web app's role dashboards with KPI tiles and charts, the reports hub `/app/reports`, and an A4 print
layout for every new report. Module `com.akshara.reports`; it reads the other modules only through their public
services (`AttendanceInsights`, `FeeInsights`, `AdmissionsInsights`, `LeaveInsights`, `HomeworkInsights`,
`StudentInsights`, each a narrow read-only query added to its own module, plus existing public services).

**Not in this slice:** parent and student dashboards and the Super Admin console (other slices), exam and result
reports (no exams module yet), scheduled or emailed reports, server-side PDF.

**No migration and no new permission.** Every report reuses the read permission of the module whose data it shows,
and the dashboard summary is opened by the existing `dashboard.view`; nothing new is stored, so there is no
`V13__reports.sql` and row-level security coverage is unchanged.

## Permissions

| Endpoint | Permission | Seeded roles that have it |
| --- | --- | --- |
| `GET /api/dashboard` | `dashboard.view` (each card has its own, below) | every role |
| attendance reports | `attendance.read` | School Admin, Principal, Teacher |
| homework completion | `homework.manage` | School Admin, Principal, Teacher |
| admissions funnel | `admissions.read` | School Admin, Principal, Front office |
| leave taken | `staff.read` | School Admin, Principal |

Scope follows the module's own screens: with `attendance.manage` the attendance figures cover the whole school,
otherwise only the sections the person is class teacher of; homework figures cover the sections and subjects the
person may manage. The web app shows the **Reports** menu item to anyone holding `attendance.read`, `fees.read`,
`admissions.read`, `staff.read`, `homework.manage` or `timetable.manage`, and the hub lists only the reports those
permissions open.

## Dashboard summary — `GET /api/dashboard`

`200 DashboardSummary`. Each card is computed only when the caller holds its permission; otherwise it is `null`.

| Card | Permission | Contents |
| --- | --- | --- |
| `students` | `students.read` | `onRoll` (active students of the current year), `admittedThisMonth`, `admittedThisYear` |
| `attendance` | `attendance.read` | today, this month and the trend (below) in the caller's scope |
| `fees` | `fees.read` | `today`, `thisMonth` (`{amountPaise, receiptCount}`), `outstandingPaise`, `overduePaise`, `overdueStudents`, `byDay` (every day of this month up to today, zeros included, issued receipts only) |
| `admissions` | `admissions.read` | `openEnquiries`, `inProgress`, `offersPending`, `admittedThisYear`, `upcomingSlots`, `funnel` (stages for applications to years that have not ended), `followUps` |
| `staff` | `staff.read` or `staff_attendance.manage` | the staff module's `TodaySummary` (present, half day, on leave, absent, not marked, checked in) |
| `leave` | `leave.approve` or `leave.request` | `waitingForMe`: leave requests the caller can decide |
| `circulars` | `notices.approve` | `pendingApproval` and up to 5 `{id, title, createdByName, submittedAt}` |
| `homework` | `homework.manage` | `waitingForReview`: this year's submissions waiting for the caller |
| `timetable` | `timetable.manage` | `clashes`, `warnings`, today's `periodsToCover` and `periodsCovered` |
| `myDay` | `timetable.read` | the caller's `classes` today, `workingDay`, `absent`, and `substitutions` they cover today |

```ts
type AttendanceCard = {
  date: string; wholeSchool: boolean;               // false: a class teacher's own sections only
  holiday: string | null;                           // title of today's whole-school holiday
  sectionCount: number; sectionsMarked: number; students: number;
  counts: AttendanceCounts; presentPercent: number | null;
  month: { from: string; to: string; schoolDays: number; daysMarked: number;
           counts: AttendanceCounts; presentPercent: number | null } | null;   // null before a current year exists
  trend: { date: string; sectionsMarked: number; counts: AttendanceCounts;
           presentPercent: number | null }[];      // the last 30 school days, oldest first; null = nobody marked
  ownSections: { sectionId: string; label: string; students: number; marked: boolean; canMark: boolean;
                 counts: AttendanceCounts; presentPercent: number | null }[];   // empty for whole school
};
```

School days are Monday to Saturday less whole-school holidays from the calendar, inside the academic year; the trend
looks back at most 120 days. Present % is the attendance module's rule: present and late count as a day, half day as
half, absent and leave as none.

Follow-ups: open applications with a follow-up date up to seven days ahead, as `dueToday`, `overdue`, `upcoming` and
the first 5 `items` (`{id, childName, classId, className, stage, followUpOn}`), the oldest first.

## Reports — `/api/reports`

Every report has a JSON endpoint and an `.xlsx` twin with the same parameters. Unknown filter ids (`classId`,
`subjectId`, `yearId`, `departmentId`, `leaveTypeId`) answer `404`; a bad range answers `400 errors.to`.

| Method | Path | Permission | Parameters (all optional) |
| --- | --- | --- | --- |
| GET | `/attendance/absentees` and `.xlsx` | `attendance.read` | `date` (today; future `400 errors.date`), `classId`, `includeLeave=false` |
| GET | `/attendance/sections` and `.xlsx` | `attendance.read` | `from` (1st of `to`'s month), `to` (today), `classId`; at most 400 days |
| GET | `/homework/completion` and `.xlsx` | `homework.manage` | `from` (`to` − 30 days), `to` (today), `classId`, `subjectId`; due dates, at most 400 days |
| GET | `/admissions/funnel` and `.xlsx` | `admissions.read` | `yearId` (none: every year), `classId`, `source`, `from`, `to` (days the applications were made) |
| GET | `/staff/leave` and `.xlsx` | `staff.read` | `yearId` (the current year), `departmentId`, `leaveTypeId` |

- **Daily absentees** `{date, holiday, classId, includeLeave, sectionCount, sectionsMarked, absent, onLeave, rows}`;
  a row is `{studentId, fullName, admissionNo, rollNo, classId, className, sectionId, sectionName, sectionLabel,
  status: "ABSENT" | "LEAVE", daysInARow, markedByName}` in class order, then roll number. `daysInARow` counts back
  over marked days with the same mark (looking back at most 60 days). No phone numbers are included.
- **Attendance by class and section** `{from, to, classId, schoolDays, holidays, classes, students, daysMarked,
  counts, presentPercent}`; `classes[]` is `{classId, className, students, counts, presentPercent, sections[]}` and a
  section is `{sectionId, classId, className, sectionName, label, students, daysMarked, counts, presentPercent}`.
  `students` is today's active roll; holidays are left out.
- **Homework completion** `{from, to, classId, subjectId, rows, total}`; a row is `{sectionId, sectionLabel, classId,
  className, subjectId, subjectName, homework, online, expected, submitted, late, reviewed, needsRedo, waiting,
  completionPercent}`. `expected` sums the section's active students over homework with online submission;
  `completionPercent` is `submitted / expected`. Homework done on paper counts in `homework` only. `total` counts each
  piece of homework once however many sections it was set for.
- **Admissions funnel** `{academicYearId, academicYearName, classId, source, from, to, funnel}`; `funnel` is `{total,
  stages, open, rejected, withdrawn, conversionPercent, byClass, bySource}`. A stage is `{stage, reached, current,
  fromPrevious, fromEnquiry}` for ENQUIRY → APPLICATION → ASSESSMENT → OFFERED → ADMITTED. **Reached** means the
  application got to that stage or a later one: one that skipped the test or interview counts as having passed it,
  and a rejected or withdrawn application counts up to the furthest stage it was in before it closed (from its
  stage history). `conversionPercent` is admitted out of all applications. `byClass` / `bySource` lines are `{classId,
  className, source, total, applied, assessed, offered, admitted, rejected, withdrawn, conversionPercent}`.
- **Leave taken** `{academicYearId, academicYearName, departmentId, leaveTypeId, types, staff, typeTotals, total,
  lossOfPay, pending}`. `types` are the columns (active types and any used this year; only the chosen one when
  filtered) as `{id, name, code, lossOfPay, active}`; a staff line is `{userId, name, employeeCode, departmentId,
  departmentName, active, days[] (aligned with types), total, lossOfPay, pending}`. Days are approved working days
  (halves allowed); `pending` is shown apart and not in the totals. Staff who left appear only if they took leave.

### Excel export

A real `.xlsx` (Office Open XML), written by a small SpreadsheetML writer on `java.util.zip` (no new dependency):

- `Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`,
  `Content-Disposition: attachment; filename="…"` and `Cache-Control: no-store`.
- File names: `absentees-<date>.xlsx`, `attendance-by-section-<from>-to-<to>.xlsx`,
  `homework-completion-<from>-to-<to>.xlsx`, `admissions-funnel-<year|all-years>.xlsx`, `leave-taken-<year>.xlsx`.
- Every sheet starts with the school name, the report title, a line with the filters and "Generated dd MMM yyyy,
  HH:mm IST by <name>", then a bold header row, the rows, and bold totals. Pages are set to A4.
- Text is always an inline string, so a value such as `=SUM(…)` is never a formula; numbers are numbers and dates
  are real dates formatted `dd mmm yyyy`. Characters XML cannot hold are dropped. Sheet names are made safe for Excel.
- The funnel workbook has three sheets (stages, by class, by source); attendance by section has two (by section, by
  class).

**Audit:** every export is recorded as `report.exported` on entity `report` (entity id = the report key:
`attendance-absentees`, `attendance-sections`, `homework-completion`, `admissions-funnel`, `staff-leave`) with
`{report, format: "xlsx", <filters>, rows}`. Only the filters and the row count are stored, never names or figures.
Viewing a report on screen is not audited.

### Print and PDF

The web app's report pages have **Print or save as PDF**, which calls the browser's print. A print stylesheet puts
the report on a named A4 page (`@page a4report { size: A4; margin: 12mm }`) black on white, hides the navigation,
filters and buttons, prints a header with the school name, report title, filters and the date and person who printed
it, always prints the desktop table (never the phone cards) and repeats the table header on each page. "Save as PDF"
in the print dialog gives the PDF.

## Web app

- `/app/dashboard`: staff (any role but parent and student) get "At a glance" above the existing cards, arranged by
  their most senior role: **leadership** (School Admin, Principal), **teacher**, **accountant**, **front office**, or
  every available tile for other roles. Tiles appear only when their card came back. Charts are SVG and CSS only:
  attendance % over the last 30 school days (gaps for unmarked days), collections by day this month (today
  highlighted) and the admissions funnel; leadership also see circulars waiting for approval and admission
  follow-ups.
- `/app/reports`: the hub, grouped Attendance, Fees, Admissions, Staff and Academics. It links the existing screens
  (month register, fee collection, outstanding, overdue, receipts, applications, staff attendance by month, timetable
  clashes) and the new pages `/app/reports/absentees`, `/attendance`, `/homework`, `/admissions` and `/leave`, each
  guarded by its permission, with filters (date ranges checked as the API does), Excel download and print.

## Events

None published or consumed.
