# Phase 1 API: staff records, staff attendance and leave

Same conventions as [Phase 0](phase-0.md) and [Phase 1](phase-1.md): JSON under `/api`, a Bearer access token,
RFC 9457 problem details with `errors.<field>`, ids from another school in a path answer `404` and in a body answer
`400` on that field, conflicts answer `409`, dates are `"YYYY-MM-DD"` days in India (Asia/Kolkata), times are
instants (ISO-8601, shown in India time), and every change is written to the audit trail.

**In this slice:** staff profiles linked one-to-one to sign-ins, departments and their heads, the staff directory,
recording that someone left (their sign-in is disabled; nothing is deleted), self check-in and check-out, the
admin's daily staff attendance sheet, the monthly report (with CSV), leave types, balances per academic year, leave
requests with approval by the department head or the School Admin/Principal, and the `StaffLeaveApproved` event.

**Not in this slice:** salary, bank details, Aadhaar or PAN (never stored), payroll, holidays (see working days
below), biometric/RFID devices and GPS (see "Devices later"), and accrual during the year (a type's yearly quota is
credited at the start of each academic year).

## Who is staff

Anyone whose sign-in has at least one role other than Parent and Student. The staff id in every path is the
**user id**. A staff member without a profile is listed with `profileComplete: false` ("profile incomplete") until
someone with `staff.manage` fills it in. A person is `LEFT` once a leaving date is recorded or their sign-in is
disabled; otherwise `ACTIVE`.

## Permissions

| Permission | Who has it (seeded roles) | Allows |
| --- | --- | --- |
| `staff.read` (new) | School Admin, Principal | the directory, profiles, departments, the daily sheet, reports |
| `staff.manage` (new) | School Admin | adding staff, editing profiles, recording leaving, departments, leave types and balances |
| `leave.request` (new) | School Admin, Principal, Teacher, Accountant, Front office | own balances and requests, applying, cancelling own requests, own check-in/out; deciding requests routed to them as department head |
| `leave.approve` (new) | School Admin, Principal | deciding (and cancelling) any request except their own |
| `staff_attendance.manage` (new) | School Admin | marking and correcting the daily sheet |

Migration `V8__staff.sql` appends these codes to the matching roles of every existing school (idempotent). Parent and
Student have none of them.

## Staff — `/api/staff`

| Method | Path | Permission | Success |
| --- | --- | --- | --- |
| GET | `?departmentId=&designation=&role=&status=ACTIVE\|LEFT&incomplete=&q=&page=0&size=25` | `staff.read` | `200 StaffPage` by name; `q` matches name, email or employee code; `size` ≤ 100 |
| GET | `/designations` | `staff.read` | `200 string[]` in use, for the filter |
| GET | `/roles` | `staff.read` or `staff.manage` | `200 {code, name}[]`: roles a staff member can have |
| GET | `/{userId}` | `staff.read` | `200 StaffDetail` |
| POST | `` | `staff.manage` | `201 StaffDetail`: creates the sign-in **and** the profile in one transaction |
| PUT | `/{userId}/profile` | `staff.manage` | `200 StaffDetail`: creates a missing profile or changes it |
| POST | `/{userId}/leaving` | `staff.manage` | `200 StaffDetail`: records leaving, disables the sign-in |
| GET | `/{userId}/leave?yearId=` | `staff.read` or `leave.approve` | `200 StaffLeave` (default the current year) |
| GET | `/{userId}/attendance?month=YYYY-MM` | `staff.read` or `staff_attendance.manage` | `200 PersonMonth` |

```ts
type EmploymentType = "PERMANENT" | "CONTRACT" | "PART_TIME" | "PROBATION";
type ProfileFields = { employeeCode: string;        // unique in the school, any case; letters, digits, / . _ -
  designation: string; departmentId: string | null; employmentType: EmploymentType; dateOfJoining: string;
  mobile: string;                                   // Indian mobile, +91/0/spaces accepted, stored as 10 digits
  qualifications?: string | null;
  emergencyContactName?: string | null; emergencyContactMobile?: string | null };   // both or neither
type CreateStaff = ProfileFields & { name: string; email: string;
  password: string;                                 // set by the admin, at least 10 characters
  roles: string[] };                                // staff roles only: PARENT/STUDENT answer 400 errors.roles
type Leaving = { leftOn: string; reason: string };  // not in the future, not before joining

type StaffRow = { userId: string; name: string; email: string; roles: string[]; employeeCode: string | null;
  designation: string | null; department: { id: string; name: string } | null;
  employmentType: EmploymentType | null; dateOfJoining: string | null; dateOfLeaving: string | null;
  mobile: string | null;                            // masked: "98480•••02"
  status: "ACTIVE" | "LEFT"; profileComplete: boolean;
  today: "PRESENT" | "ABSENT" | "HALF_DAY" | "ON_LEAVE" | null };
type StaffPage = { items: StaffRow[]; page: number; size: number; total: number;
  incompleteProfiles: number };                     // active staff without a profile, whatever the filters
type StaffDetail = { userId: string; name: string; email: string; roles: string[]; status: "ACTIVE" | "LEFT";
  accountActive: boolean; lastLoginAt: string | null; profileComplete: boolean;
  profile: (Omit<ProfileFields, "departmentId"> & { department: { id: string; name: string } | null;
    dateOfLeaving: string | null; leavingReason: string | null }) | null;   // full mobile numbers
  leaveApprover: { routing: "DEPARTMENT_HEAD" | "SCHOOL"; departmentHead: { id: string; name: string } | null } };
```

Adding staff: a taken email is `409 errors.email`, a taken employee code `409 errors.employeeCode`; either way
nothing is saved (the sign-in created first is rolled back). The password is hashed before the transaction starts.
Recording leaving: `409` for one's own record, a missing profile or someone who already left. It disables the
sign-in through identity's `UserService.disableUser` (login then fails with `401` and refresh tokens are revoked;
an access token already issued lasts until it expires, at most 15 minutes), removes them as department head, and
cancels their pending leave and approved leave that starts after the leaving day.

### Departments — `/api/staff/departments`

| Method | Path | Permission | Success |
| --- | --- | --- | --- |
| GET | `` | `staff.read`, `staff.manage` or `staff_attendance.manage` | `200 Department[]` by name |
| POST | `` | `staff.manage` | `201 Department`; `409 errors.name` for a taken name (any case) |
| PUT | `/{id}` | `staff.manage` | `200 Department` |
| DELETE | `/{id}` | `staff.manage` | `204`; `409` while staff are assigned |

```ts
type DepartmentFields = { name: string; headUserId: string | null };   // head: an active staff member, else 400
type Department = { id: string; name: string; head: { id: string; name: string } | null; staffCount: number };
```

## Leave — `/api/leave`

| Method | Path | Permission | Success |
| --- | --- | --- | --- |
| GET | `/types?all=` | `leave.request`, `leave.approve`, `staff.read` or `staff.manage` | `200 LeaveType[]` (active only unless `all=true`) |
| POST | `/types` | `staff.manage` | `201 LeaveType`; `409 errors.name` / `errors.code` |
| POST | `/types/standard` | `staff.manage` | `200 LeaveType[]`: adds the usual types the school lacks (below) |
| PUT | `/types/{id}` | `staff.manage` | `200 LeaveType` |
| DELETE | `/types/{id}` | `staff.manage` | `204`; `409` when requests or balances use it (switch it off instead) |
| GET | `/me?yearId=` | `leave.request` | `200 MyLeave` (default the current year) |
| POST | `/preview` | `leave.request` | `200 Preview`; same `400`s as applying, nothing saved |
| POST | `/requests` | `leave.request` | `201 LeaveRequest` |
| POST | `/requests/{id}/approve` | `leave.request` or `leave.approve` | `200 LeaveRequest`; body `{comment?}` |
| POST | `/requests/{id}/reject` | `leave.request` or `leave.approve` | `200 LeaveRequest`; body `{comment}` required |
| POST | `/requests/{id}/cancel` | `leave.request` or `leave.approve` | `200 LeaveRequest`; body `{comment?}` |
| GET | `/inbox?all=` | `leave.request` or `leave.approve` | `200 LeaveRequest[]` waiting for the caller, oldest first |
| PUT | `/balances` | `staff.manage` | `200 Balance`: sets one person's opening balance and allowance |

```ts
type LeaveType = { id: string; name: string; code: string;           // code: letters/digits, stored upper case
  yearlyQuota: number; carryForwardCap: number;                     // whole or half days, 0–366
  halfDayAllowed: boolean; lossOfPay: boolean; active: boolean; inUse: boolean };
type Balance = { leaveTypeId: string; leaveTypeName: string; code: string; lossOfPay: boolean;
  halfDayAllowed: boolean; active: boolean; opening: number; accrued: number; taken: number; pending: number;
  available: number | null;                                          // opening + accrued − taken; null: loss of pay
  setByHand: boolean };
type LeaveApplication = { leaveTypeId: string; fromDate: string; toDate: string; halfDay: boolean; reason: string };
type LeaveRequest = { id: string; userId: string; userName: string; employeeCode: string | null;
  departmentName: string | null; leaveTypeId: string; leaveTypeName: string; leaveTypeCode: string;
  lossOfPay: boolean; academicYearId: string; academicYearName: string; fromDate: string; toDate: string;
  halfDay: boolean; days: number; reason: string; status: "PENDING" | "APPROVED" | "REJECTED" | "CANCELLED";
  routing: "DEPARTMENT_HEAD" | "SCHOOL"; approverName: string | null;
  decidedByName: string | null; decidedAt: string | null; decisionComment: string | null;
  cancelledByName: string | null; cancelledAt: string | null; cancelComment: string | null; createdAt: string;
  canCancel: boolean; canDecide: boolean;                            // for the caller
  available: number | null };                                        // requester's balance, in the inbox only
type MyLeave = { year: Year | null; years: Year[]; approver: StaffDetail["leaveApprover"]; balances: Balance[];
  requests: LeaveRequest[]; waitingForMe: number };
type StaffLeave = { userId: string; year: Year | null; years: Year[]; balances: Balance[]; requests: LeaveRequest[] };
type Preview = { year: Year; fromDate: string; toDate: string; halfDay: boolean; workingDays: string[];
  nonWorkingDays: number; days: number; balance: Balance; availableAfter: number | null; enough: boolean;
  overlaps: boolean };
type Year = { id: string; name: string; startsOn: string; endsOn: string; current: boolean };
type BalanceFields = { userId: string; leaveTypeId: string; academicYearId?: string | null;   // default current
  opening: number; accrued: number };                                // whole or half days, 0–999; not loss of pay
```

Standard types (`/types/standard`): Casual leave CL 12/year, half days, no carry-forward; Sick leave SL 10/year, half
days, carry up to 20; Earned leave EL 15/year, carry up to 30; Maternity leave ML 156 working days (26 weeks without
Sundays); Paternity leave PL 15; Loss of pay LOP (no balance, half days). Schools change or add types freely.

### Rules

- **Working days** are counted in one place, `WorkingDayCalendar` (`SundayOffCalendar` today: Monday to Saturday).
  A request costs its working days, or 0.5 for a half day (`halfDay` needs `fromDate == toDate` and a type that
  allows half days: `400 errors.halfDay`). A range with no working day is `400 errors.toDate`. When the school
  calendar (holidays) exists, it provides a `@Primary WorkingDayCalendar` bean and leave and reports follow it.
- A request lies inside one academic year (`400 errors.fromDate` when no year covers it, `errors.toDate` when it runs
  past the year end), at most 200 calendar days. Past dates are allowed (sick leave is often applied for afterwards).
- **Balances** per person, type and academic year: opening = last year's closing (opening + accrued − taken)
  carried forward, never below 0 or above the type's cap (0 in the school's first year); accrued = the yearly quota
  (0 for a year that ended before the person joined); taken = approved days. A balance set by hand
  (`PUT /balances`) replaces opening and accrued for that year and feeds the next year's carry-forward.
- **Refusals:** more days than `available − pending` is `409 errors.leaveTypeId` (loss of pay is never refused);
  dates overlapping one's own pending or approved request are `409 errors.fromDate`. Applying and approving lock the
  person (Postgres advisory lock), so two requests sent at once cannot both pass.
- **Routing:** to the head of the requester's department when it has an active head who is not the requester
  (`DEPARTMENT_HEAD`), else to the school (`SCHOOL`: anyone with `leave.approve`). If the head leaves, what waited
  for them goes to the school. `leave.approve` holders may decide any request (`inbox?all=true` lists them all);
  nobody decides their own (`403`). Approving re-checks the balance (`409`); rejecting needs a comment
  (`400 errors.comment`); a decided request is `409`.
- **Cancelling:** the requester while pending and before approved leave starts (`409` after); the approver any time.
  Cancelling approved leave gives the days back.
- **Approved leave marks attendance:** each working day becomes `ON_LEAVE` (a half day `HALF_DAY`) with source
  `LEAVE`, keeping check-in times already recorded. Cancelling removes those days, or makes them `PRESENT` where the
  person had checked in.

### Event

`com.akshara.staff.StaffLeaveApproved` is published (Spring application event, inside the approving transaction)
when leave is approved, for the timetable slice to find substitutes:

```java
record StaffLeaveApproved(UUID tenantId, UUID requestId, UUID userId, UUID leaveTypeId, LocalDate fromDate,
        LocalDate toDate, boolean halfDay, BigDecimal days, List<LocalDate> workingDays, UUID approvedById,
        Instant at)
```

`StaffLeaveCancelled(tenantId, requestId, userId, fromDate, toDate, cancelledById, at)` follows when approved leave
is cancelled. Listeners that must not act on a rolled-back approval use `@TransactionalEventListener`.

## Staff attendance — `/api/staff-attendance`

| Method | Path | Permission | Success |
| --- | --- | --- | --- |
| GET | `/me/today` | `leave.request` | `200 MyDay` |
| POST | `/me/check-in` | `leave.request` | `200 MyDay`; body `{note?}` (≤ 200); `409` when already checked in or on full-day leave |
| POST | `/me/check-out` | `leave.request` | `200 MyDay`; `409` before checking in or when already checked out |
| GET | `/days/{date}` | `staff.read` or `staff_attendance.manage` | `200 DaySheet` |
| PUT | `/days/{date}` | `staff_attendance.manage` | `200 SaveResult`; marks or corrects the listed people |
| GET | `/month?month=YYYY-MM&departmentId=` | `staff.read` or `staff_attendance.manage` | `200 MonthReport` |
| GET | `/month.csv?month=YYYY-MM&departmentId=` | `staff.read` or `staff_attendance.manage` | `200 text/csv`, `staff-attendance-2026-10.csv` |
| GET | `/today` | `staff.read` or `staff_attendance.manage` | `200 TodaySummary` for the dashboard |

```ts
type StaffStatus = "PRESENT" | "ABSENT" | "HALF_DAY" | "ON_LEAVE";    // report letters P A H L
type MyDay = { date: string; workingDay: boolean; status: StaffStatus | null; checkInAt: string | null;
  checkOutAt: string | null; checkInNote: string | null; checkOutNote: string | null; onLeave: boolean;
  canCheckIn: boolean; canCheckOut: boolean };
type DayEntry = { userId: string; status: "PRESENT" | "ABSENT" | "HALF_DAY";
  checkIn?: string | null; checkOut?: string | null };              // "HH:mm" India time; absent: no times
type SaveDay = { entries: DayEntry[] };                             // at most 500
type DaySheet = { date: string; today: string; workingDay: boolean; canEdit: boolean;
  counts: { present: number; halfDay: number; absent: number; onLeave: number }; notMarked: number;
  rows: { userId: string; name: string; employeeCode: string | null; designation: string | null;
    departmentName: string | null; onRoll: boolean; status: StaffStatus | null;
    source: "SELF" | "ADMIN" | "LEAVE" | null; checkIn: string | null; checkOut: string | null;   // "HH:mm"
    checkInAt: string | null; checkOutAt: string | null; checkInNote: string | null; checkOutNote: string | null;
    onLeaveRequest: boolean;                                        // only cancelling the leave changes it
    markedByName: string | null; updatedByName: string | null; editedAt: string | null }[] };
type SaveResult = { sheet: DaySheet; marked: number; corrected: number };
type MonthReport = { month: string; departmentId: string | null; workingDays: number;
  days: { date: string; workingDay: boolean; counts: DaySheet["counts"] }[];
  staff: { userId: string; name: string; employeeCode: string | null; departmentName: string | null;
    active: boolean; marks: ("P" | "A" | "H" | "L" | null)[]; counts: DaySheet["counts"];
    notMarked: number;                                              // working days to today with no mark
    daysWorked: number }[];                                         // present + half days / 2
  totals: DaySheet["counts"] };
type TodaySummary = { date: string; workingDay: boolean; activeStaff: number; present: number; halfDay: number;
  onLeave: number; absent: number; notMarked: number; checkedIn: number };
type PersonMonth = { userId: string; month: string; counts: DaySheet["counts"]; daysWorked: number;
  days: { date: string; workingDay: boolean; status: StaffStatus | null; source: string | null;
    checkInAt: string | null; checkOutAt: string | null }[] };
```

Check-in and check-out use the server's clock in India time, once a day each; a day the admin marked absent becomes
present on check-in. The sheet lists everyone on the staff that day (joined by then, not left before it) plus anyone
marked on it. It refuses future dates and dates more than a year back (`400 errors.date`), `ON_LEAVE` (only approved
leave sets it), times on an absent day, a check-out without a check-in or before it, and times in the future
(`400 errors.entries`); changing a day of approved leave is `409`. A time sent unchanged to the minute keeps the
exact recorded instant.

### Devices later

There is no GPS, biometric or RFID check-in. A device would plug in as one more `AttendanceSource` (for example
`DEVICE`): a small authenticated endpoint (per-school device key, not a user token) receiving
`{employeeCode, at}` punches, calling the same check-in/check-out rules in `StaffAttendanceService` for the matching
staff profile, with the first punch of the day as check-in and the last as check-out. The daily sheet and reports
need no change.

## Audit actions

`staff.created`, `staff.profile_created`, `staff.profile_updated` (names of changed fields only), `staff.left`,
`user.disabled`, `department.created|updated|deleted`, `leave_type.created|updated|deleted`,
`leave_request.created|approved|rejected|cancelled`, `leave_balance.set`, `staff_attendance.checked_in|checked_out`
(entity `staff_attendance`), `staff_attendance.marked|corrected` (entity `staff_attendance_day`, id = the date;
corrections list `{userId, employeeCode, from, to}`). Details never hold mobile numbers or emergency contacts.

## Data

New schema `staff`: `department`, `staff_profile`, `leave_type`, `leave_balance` (hand-set balances only),
`leave_request`, `attendance`. Every table has `tenant_id`, the `tenant_isolation` row-level security policy and
composite `(tenant_id, …)` foreign keys; they are listed in `RowLevelSecurityCoverageIT`. Academic years with leave
requests or hand-set balances cannot be deleted.

## Demo data

The demo school gets departments (Science, Languages and Secondary have heads; Primary and Administration go to the
principal), profiles for every demo staff member, six more teachers (`anjali.deshmukh@`, `rahul.verma@`,
`fatima.shaikh@`, `kiran.joshi@`, `sandeep.kulkarni@`, `priyanka.menon@demo.akshara.test`, same demo password), the
standard leave types, approved/pending/rejected/cancelled requests (Ravi Kumar's pending request waits for the
principal; Rahul Verma's for Anjali Deshmukh), staff attendance for the last 20 working days, and today's check-ins
for everyone except Ravi Kumar.
