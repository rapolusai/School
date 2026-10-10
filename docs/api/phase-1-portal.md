# Phase 1 API: the parent and student app

Same conventions as [Phase 0](phase-0.md) and [Phase 1](phase-1.md): JSON under `/api`, a Bearer access token,
RFC 9457 problem details with `errors.<field>`, ids from another school answer `404`, `409` for conflicts, dates are
`"YYYY-MM-DD"` school days in India (Asia/Kolkata), and every change is written to the audit trail.

**In this slice:** a mobile-first home and menu for parents and students (one child at a time, with today, this
month's attendance, homework, fees, leave, notices and what is coming up), a child's leave requests (absence notes)
that the class teacher approves or rejects, approved leave pre-filling the register and stopping absence alerts, a
student's own month of attendance, and an installable web app (manifest, icons, a service worker for the static
shell and an offline page).

**Not in this slice:** web push notifications (see [ADR 0005](../adr/0005-installable-parent-app.md)), offline data,
exam results (Phase 2), and file attachments on leave notes (a doctor's note is described in the reason).

## Permissions

No new permissions; migration `V12__portal.sql` adds one table and changes no role.

| Permission | Who has it (seeded roles) | Allows |
| --- | --- | --- |
| `child.view` | Parent, School Admin | a parent's own child's leave requests: list, apply, withdraw. Any other student is `404`, also for a School Admin who is not that child's guardian |
| `dashboard.view` | every role | a student's own leave requests (read only) and own month of attendance |
| `attendance.read` | School Admin, Principal, Teacher | the leave requests of the sections the caller can read (a teacher: the sections they are class teacher of) |
| `attendance.mark` | Teacher | approving or rejecting requests of their own sections; another section is `403` |
| `attendance.manage` | School Admin, Principal | approving or rejecting requests of every section |

## Leave requests (absence notes)

| Method | Path | Permission | Success |
| --- | --- | --- | --- |
| GET | `/api/me/children/{studentId}/leave-requests` | `child.view` | `200 FamilyLeave` for the caller's own child, newest first |
| POST | `/api/me/children/{studentId}/leave-requests` | `child.view` | `201 ChildLeave` (`PENDING`) |
| POST | `/api/me/children/{studentId}/leave-requests/{id}/cancel` | `child.view` | `200 ChildLeave` (`CANCELLED`) |
| GET | `/api/me/leave-requests` | `dashboard.view` | `200 FamilyLeave` of the signed-in student (`canApply` false); `404` when the sign-in is not a student's |
| GET | `/api/attendance/leave-requests` | `attendance.read` | `200 LeaveInbox` |
| POST | `/api/attendance/leave-requests/{id}/approve` | `attendance.mark` or `attendance.manage` | `200 ChildLeave` (`APPROVED`); body optional |
| POST | `/api/attendance/leave-requests/{id}/reject` | `attendance.mark` or `attendance.manage` | `200 ChildLeave` (`REJECTED`); `comment` required |

```ts
type ChildLeaveStatus = "PENDING" | "APPROVED" | "REJECTED" | "CANCELLED";

// POST /api/me/children/{studentId}/leave-requests
type ChildLeaveApplication = { fromDate: string; toDate: string; halfDay?: boolean; reason: string };  // reason ≤ 500
// POST .../approve and .../reject
type Decision = { comment?: string | null };   // ≤ 500; shown to the parent

type ChildLeave = { id: string; studentId: string; studentName: string; admissionNo: string; rollNo: number | null;
  sectionId: string; sectionLabel: string | null; fromDate: string; toDate: string; halfDay: boolean;
  schoolDays: number;            // Mondays to Saturdays that are not whole-school holidays; a half day counts as 1
  reason: string; status: ChildLeaveStatus; requestedByName: string | null; createdAt: string;
  decidedByName: string | null; decidedAt: string | null; decisionComment: string | null;
  cancelledByName: string | null; cancelledAt: string | null;
  canCancel: boolean;            // for the caller: a parent, while it waits or before approved leave starts
  canDecide: boolean };          // for the caller: staff who may decide, while it waits

type FamilyLeave = { studentId: string; studentName: string; sectionLabel: string | null;
  classTeacherName: string | null; today: string;
  earliest: string; latest: string;   // the dates a parent may apply for
  canApply: boolean;                  // false for a student, a child not in a class this year, or no current year
  requests: ChildLeave[] };

type LeaveInbox = { pending: ChildLeave[];   // oldest first
  recent: ChildLeave[];                      // decided or withdrawn in the last 30 days, at most 50
  wholeSchool: boolean };                    // true with attendance.manage
```

### Rules

- **Who.** A parent applies only for a child whose guardian record is linked to their sign-in; anyone else's child,
  in this school or another, is `404`. The request belongs to the section the child is in this year when the parent
  applies: its class teacher, or anyone with `attendance.manage`, decides it.
- **Dates.** `toDate` on or after `fromDate` (`400 errors.toDate`), at most 31 calendar days (`400 errors.toDate`),
  starting no more than 30 days ago (`400 errors.fromDate`; parents often write after the child was away), inside the
  current academic year (`400`), and covering at least one school day (`400 errors.fromDate` "Those days are Sundays
  or school holidays, so no leave is needed."). A half day is one day (`400 errors.halfDay`). A blank reason is
  `400 errors.reason`.
- **One note per day.** A request that overlaps a waiting or approved request of the same child is
  `409 errors.fromDate` ("There is already a pending request for …"). Applications for one child are serialised, so
  two sent at once cannot both pass.
- **Not at school.** A child who has left, or is not in a class this year, is `409`.
- **Deciding.** Only a waiting request can be approved or rejected (`409` "This request is already approved.").
  Rejecting needs a comment (`400 errors.comment`).
- **Withdrawing.** The parent can withdraw a waiting request at any time, and approved leave until the day it
  starts (`409` "This leave has started. Ask the class teacher if the dates need to change."). A decided or withdrawn
  request cannot be withdrawn again (`409`).
- **Students** see their own requests read only; their parents apply.

### The register and absence alerts

- `GET /api/attendance/registers/{sectionId}/{date}` entries carry
  `approvedLeave: { requestId: string; halfDay: boolean; prefill: "LEAVE" | "HALF_DAY" } | null` for a student with
  approved leave that day. The web app starts an **unmarked** register with that mark; the teacher can still change
  it, and a register already saved keeps its marks. "Mark all present" keeps approved leave.
- Saving a register queues **no absence alert** for a student who is on approved leave that day, even if they are
  marked absent.
- Approving a request **cancels absence alerts already queued** for its days up to today (the outbox marks them
  skipped, "On approved leave"); the count is in the audit details as `alertsCancelled`.

## A child's month of attendance

| Method | Path | Permission | Success |
| --- | --- | --- | --- |
| GET | `/api/me/children/{studentId}/attendance?month=YYYY-MM` | `child.view` | `200 ChildAttendance` (existing; new fields below) |
| GET | `/api/me/attendance?month=YYYY-MM` | `dashboard.view` | `200 ChildAttendance` of the signed-in student; `404` when the sign-in is not a student's |

`ChildAttendance` ([attendance](phase-1-attendance.md)) gains fields; existing clients are unaffected:

```ts
type ChildAttendance = { /* existing fields */
  today: string;                              // today in India
  todayStatus: Status | null;                 // today's mark, if the register is saved
  todayHoliday: string | null;                // the whole-school holiday today, if any
  todayLeave: { requestId: string; halfDay: boolean; prefill: Status } | null;   // approved leave today
  holidays: { date: string; title: string }[];   // whole-school holidays in the month
  leaveDays: { date: string; halfDay: boolean }[] };   // days of approved leave in the month
```

## Events

Spring application events in `com.akshara.attendance`, published inside the transaction that applies for or decides
the request; nothing listens yet. A notifications slice can use them for in-app or push messages without changing
this module (listeners that must not act on a rolled-back change use `@TransactionalEventListener`).

- `ChildLeaveRequested(tenantId, requestId, studentId, sectionId, fromDate, toDate, halfDay, requestedById, at)`
- `ChildLeaveDecided(tenantId, requestId, studentId, sectionId, status, fromDate, toDate, halfDay, requestedById,
  decidedById, at)` with `status` `APPROVED` or `REJECTED`

## Audit

`child_leave.requested`, `child_leave.approved` (with `alertsCancelled`), `child_leave.rejected` and
`child_leave.cancelled` (with `wasApproved`), entity type `child_leave`. Details hold the student id, the section
label, the dates and `halfDay`; never the child's name, the reason (which may be medical) or the comment.

## Data

`attendance.leave_request` (V12): the dates, half day, reason, status, and who requested, decided and withdrew it and
when (with their names, so history still reads correctly after a sign-in is removed). It has `tenant_id`, the
`tenant_isolation` row-level security policy, grants for the runtime role only, and composite `(tenant_id, id)` foreign
keys to the student (deleted with the student), section, academic year and user accounts. School setup counts leave
requests when deciding whether a section or year is still in use.

## The web app

- **Home** (`/app/dashboard`) for anyone whose roles are all Parent or Student: a child switcher (parents with more
  than one child; the last child chosen is remembered on the device), the child's card with today's status and fees
  due with **Pay fees** (the existing sandbox payment), today's timetable, this month's attendance with absences,
  homework due and recently reviewed, leave, notices (unread first) and upcoming calendar entries. A student sees
  the same for themselves, without fees.
- **Menu** (bottom bar on phones, rail or sidebar on larger screens): Home, Attendance (`/app/family/attendance`, a
  month calendar), Homework, Fees (`/app/family/fees`, parents only), Notice board, Calendar, Leave
  (`/app/family/leave`). Someone who is also staff keeps the staff menu.
- **Teachers** find "Leave requests" on the attendance page (`/app/attendance/leave-requests`), with the number waiting.
- **Installable:** `/manifest.webmanifest` (name, icons, theme colours, `start_url` `/app/dashboard`), a service worker
  `/sw.js` registered in production builds only, and `/offline.html`. Phones that have not installed the app see an
  "Add to home screen" tip on the home page until they hide it. See [ADR 0005](../adr/0005-installable-parent-app.md).

## Demo data

The demo school seeds two requests: Diya Sharma's two days of leave for a family wedding, applied by Anitha Sharma and
approved by the principal, Lakshmi Iyer; and Arjun Sharma's half day for a dentist appointment, waiting for his class
teacher, Ravi Kumar.
