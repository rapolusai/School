# Phase 3 API: hostel

Same conventions as [Phase 0](phase-0.md) and [Phase 1](phase-1.md): JSON under `/api`, a Bearer access token,
RFC 9457 problem details with `errors.<field>`, ids from another school in a path answer `404` and in a body answer
`400` on that field, conflicts answer `409`, dates are `"YYYY-MM-DD"` days in India (Asia/Kolkata), times are
instants, money is paise, and every change is written to the audit trail with readable labels (`audit.action.*`,
`audit.entity.*`).

**In this slice:** the Warden role; hostels (boys, girls, staff) with blocks, floors, rooms (capacity, type, monthly
fee) and beds; a warden per hostel who sees only their own hostels; bed allotment with dates, guardian consent,
vacating with a reason and a room-change history; the occupancy board; the boarder list by class and a boarder flag
for the student page; the week's mess menu and daily mess head counts; outing and home-leave passes asked for by a
parent from the family app or raised by the warden, approved or refused by the warden (the parent is told through
the notifications outbox), marked out and back in with late returns flagged; the night roll call on a phone with an
alert and a simulated parent message for each absentee, a register and a monthly summary; complaints per room; four
reports with CSV; the family app's "Hostel" card and pass page; demo data (`warden@demo.akshara.test`).

**Not in this slice:** a hostel fee head in the fees module (see *Events*), real SMS, visitor passes for hostel
guests, laundry or inventory, and biometric roll call.

## Permissions and roles

| Permission | Who has it (seeded roles) | Allows |
| --- | --- | --- |
| `hostel.read` (new) | School Admin, Principal | reading every hostel, board, list, register and report |
| `hostel.manage` (new) | School Admin | setting up hostels, blocks, rooms, beds and wardens; allotting, moving and vacating beds; everything a warden may do, for every hostel |
| `hostel.warden` (new) | Warden | the hostels the signed-in user is warden of: passes, roll call, mess attendance and menu, alerts, complaints |
| `child.view` | Parent | `/api/me/children/{studentId}/hostel...` for their own children |

New role **`WARDEN`** ("Warden"): `dashboard.view`, `leave.request`, `notices.read`, `hostel.warden`. Migration
`V22__hostel.sql` adds the role to every existing school and appends the permissions to the matching built-in roles
(idempotent, like `V19`). Signup now creates twelve standard roles.

"read" below is `hostel.read` or `hostel.manage`; "warden" is `hostel.warden` or `hostel.manage`; "read or warden"
is any of the three. A warden holding only `hostel.warden` gets `404` for a hostel they are not the warden of and
`403` when acting on one they may only see.

Personal data: a boarder's guardian phone is **masked** (`98765•••01`) in every list; no identity numbers are kept.

## Hostels, blocks, rooms and beds

| Method | Path | Permission | Success |
| --- | --- | --- | --- |
| GET | `/api/hostel/hostels` | read or warden | `200 HostelView[]` (a warden sees only their own) |
| GET | `/api/hostel/hostels/{id}` | read or warden | `200 { hostel, blocks: BlockView[], rooms: RoomView[] }` |
| GET | `/api/hostel/staff` | `hostel.manage` | `200 {id, name, designation}[]`: users who may be wardens |
| GET | `/api/hostel/students?q=` | `hostel.manage` | `200 {id, fullName, admissionNo, className}[]`: up to 20 active students matching the name or admission number |
| POST | `/api/hostel/hostels` | `hostel.manage` | `201 HostelView`; body `HostelForm` |
| PUT | `/api/hostel/hostels/{id}` | `hostel.manage` | `200 HostelView` |
| POST | `/api/hostel/hostels/{id}/blocks` | `hostel.manage` | `201 BlockView`; body `{name, floors?: 1..30}` |
| PUT | `/api/hostel/hostels/{id}/blocks/{blockId}` | `hostel.manage` | `200 BlockView` |
| DELETE | `/api/hostel/hostels/{id}/blocks/{blockId}` | `hostel.manage` | `204`; `409` while it has rooms |
| POST | `/api/hostel/hostels/{id}/blocks/{blockId}/rooms` | `hostel.manage` | `201 RoomView`; body `RoomForm` |
| PUT | `/api/hostel/hostels/{id}/rooms/{roomId}` | `hostel.manage` | `200 RoomView` |
| DELETE | `/api/hostel/hostels/{id}/rooms/{roomId}` | `hostel.manage` | `204`; `409` while a bed is taken |
| POST | `/api/hostel/hostels/{id}/rooms/{roomId}/beds` | `hostel.manage` | `201 BedView[]`; body `{labels: string[]}`; `400 errors.labels` on a repeated label (case-insensitive) or beyond the capacity |
| DELETE | `/api/hostel/hostels/{id}/beds/{bedId}` | `hostel.manage` | `204`; `409` while taken |
| GET | `/api/hostel/hostels/{id}/occupancy` | read or warden | `200 OccupancyBoard` |

```ts
type HostelForm = { name; type: "BOYS"|"GIRLS"|"STAFF"; wardenUserId?; address?; active?: boolean };
type HostelView = { id, name, type, wardenUserId, wardenName, address, active, blocks, rooms, beds, occupied,
  occupancyPercent, canManage, canWarden };
type RoomForm = { floor?: 0..30; number; capacity: 1..40; type: "SINGLE"|"DOUBLE"|"TRIPLE"|"DORMITORY";
  monthlyFeePaise?: >= 0; active?: boolean };
type RoomView = { id, hostelId, blockId, blockName, floor, number, capacity, type, monthlyFeePaise, active, beds, occupied };
type BedView = { id, label, allotmentId, studentId, studentName, className, since };
type OccupancyBoard = { hostelId, hostelName, type, beds, occupied, free, occupancyPercent, blocks: BlockView[],
  rooms: (RoomView & { beds: BedView[], freeBeds, openComplaints })[] };
```

- `wardenUserId` must be a member of staff of the school (`400 errors.wardenUserId`); the Warden role gives them
  `hostel.warden`. One person may be warden of several hostels. A second hostel with the same name answers `409`.
- Audit: `hostel.created/updated`, `hostel_block.created/updated/removed`, `hostel_room.created/updated/removed`,
  `hostel_bed.added/removed` (on the room).

## Allotments

| Method | Path | Permission | Success |
| --- | --- | --- | --- |
| POST | `/api/hostel/allotments` | `hostel.manage` | `201 AllotmentView`; body `{studentId, bedId, fromDate, guardianConsent}` |
| POST | `/api/hostel/allotments/{id}/vacate` | `hostel.manage` | `200 AllotmentView`; body `{reason, toDate?}` (default today) |
| POST | `/api/hostel/allotments/{id}/move` | `hostel.manage` | `200 AllotmentView` (the new one); body `{bedId, fromDate?}` |
| GET | `/api/hostel/boarders?hostelId=` | read or warden | `200 BoarderView[]` ordered by class, then name |
| GET | `/api/hostel/students/{studentId}/boarding` | read or warden, or `students.read` | `200 { studentId, boarder, current: AllotmentView \| null, history: AllotmentView[] }` |

```ts
type AllotmentView = { id, studentId, studentName, admissionNo, className, hostelId, hostelName, blockName, roomId,
  roomNumber, bedId, bedLabel, fromDate, toDate, status: "ACTIVE"|"VACATED"|"MOVED", guardianConsent, vacateReason,
  movedFromAllotmentId, allottedByName, createdAt };
type BoarderView = AllotmentView & { guardianName, guardianPhoneMasked };
```

- `guardianConsent` is recorded with the allotment (the web form insists on it). `fromDate` is within a year back
  and 120 days ahead (`400 errors.fromDate`). A student already boarding answers `409`; a taken bed answers `409`;
  a bed in a room not in use answers `400 errors.bedId`.
- A move closes the old allotment as `MOVED` and opens a new one in the new bed from `fromDate` (after the current
  one started); the history on the student page shows both. Vacating sets `toDate` and the reason.
- Audit: `hostel_allotment.allotted`, `hostel_allotment.vacated`, `hostel_allotment.moved`.

## Mess

| Method | Path | Permission | Success |
| --- | --- | --- | --- |
| GET | `/api/hostel/hostels/{id}/menu` | read or warden | `200 { hostelId, hostelName, items: MenuSlot[], canEdit }` |
| PUT | `/api/hostel/hostels/{id}/menu` | warden | `200` menu; body `{items: {dayOfWeek: 1..7, meal, dish?, veg?}[]}`; a blank dish clears the slot |
| GET | `/api/hostel/hostels/{id}/mess-attendance?date=` | read or warden | `200 { hostelId, date, boarders, counts: {meal, headCount, markedByName}[] }` |
| PUT | `/api/hostel/hostels/{id}/mess-attendance/{date}` | warden | `200` day; body `{counts: {meal, headCount: 0..5000}[]}`; the date is today or within the last 7 days |

Meals are `BREAKFAST`, `LUNCH`, `SNACKS`, `DINNER`. Audit: `hostel_menu.updated`, `hostel_mess.marked` (on the
hostel).

## Outing and home-leave passes

| Method | Path | Permission | Success |
| --- | --- | --- | --- |
| GET | `/api/hostel/passes?hostelId=&from=&to=` | read or warden | `200 PassView[]`, newest first (default: the last 30 days and everything open) |
| GET | `/api/hostel/passes/{id}` | read or warden | `200 PassView` |
| POST | `/api/hostel/passes` | warden | `201 PassView`; body `{studentId, kind, fromDate, toDate?, reason}` (source `WARDEN`) |
| POST | `/api/hostel/passes/{id}/approve` | warden | `200 PassView`; body `{note?}`; `409` unless REQUESTED |
| POST | `/api/hostel/passes/{id}/reject` | warden | `200 PassView`; body `{note}` (required); `409` unless REQUESTED |
| POST | `/api/hostel/passes/{id}/out` | warden | `200 PassView`; `409` unless APPROVED |
| POST | `/api/hostel/passes/{id}/in` | warden | `200 PassView`; `409` unless OUT; `late` when after `returnBy` |
| GET | `/api/me/children/{studentId}/hostel` | `child.view` | `200 FamilyHostel` (`boarder: false` for a day scholar) |
| GET | `/api/me/children/{studentId}/hostel-passes` | `child.view` | `200 PassView[]` |
| POST | `/api/me/children/{studentId}/hostel-passes` | `child.view` | `201 PassView`; body `{kind, fromDate, toDate?, reason}` (source `PARENT_APP`); `409` when the child is not a boarder |
| POST | `/api/me/children/{studentId}/hostel-passes/{id}/cancel` | `child.view` | `200 PassView`; `409` unless REQUESTED |

```ts
type PassView = { id, studentId, studentName, admissionNo, className, hostelId, hostelName,
  kind: "OUTING"|"HOME_LEAVE", source: "PARENT_APP"|"WARDEN", requestedByName, fromDate, toDate, returnBy, reason,
  status: "REQUESTED"|"APPROVED"|"OUT"|"RETURNED"|"REJECTED"|"CANCELLED", decidedByName, decidedAt, decisionNote,
  outAt, outByName, inAt, inByName, late, createdAt, canCancel, canDecide, canMarkOut, canMarkIn };
type FamilyHostel = { studentId, studentName, className, boarder, hostelName, blockName, roomNumber, bedLabel,
  wardenName, nextPass: PassView | null, lastRollCallNight, lastRollCallStatus, canRequest, passes: PassView[] };
```

- Date rules (`HostelRules`, mirrored in the web form): `fromDate` is today or within 60 days; an outing is one
  day (`toDate` is ignored) and the boarder is back by **18:00** that day; home leave ends on or after it starts,
  within 30 days, back by **20:00** on the last day. Errors land on `errors.fromDate` / `errors.toDate`.
- A pass overlapping an open pass of the same boarder answers `409`. A parent sees only their own children
  (`404` otherwise).
- Approving or rejecting enqueues `hostel.pass_decided` to the primary guardian through the notifications outbox
  (`GET /api/messages?relatedId={passId}`), in the school's alert language. A return after `returnBy` sets `late`
  and opens a `LATE_RETURN` alert.
- Audit: `hostel_pass.requested/approved/rejected/cancelled/out/in`.

## Night roll call and alerts

| Method | Path | Permission | Success |
| --- | --- | --- | --- |
| GET | `/api/hostel/hostels/{id}/roll-call?night=` | read or warden | `200 RollCallSheet` for the night (default today): every boarder with the saved mark, or `suggestedOnLeave` when away on a pass |
| PUT | `/api/hostel/hostels/{id}/roll-call/{night}` | warden | `200 RollCallSheet`; body `{entries: {studentId, status, note?}[]}`; every current boarder exactly once (`400 errors.entries`); the night is today or within the last 7 days |
| GET | `/api/hostel/hostels/{id}/roll-call/register?from=&to=` | read or warden | `200 { hostelId, hostelName, from, to, nights: RollCallNight[] }` (default: the last 30 nights) |
| GET | `/api/hostel/hostels/{id}/roll-call/summary?month=YYYY-MM` | read or warden | `200 { hostelId, hostelName, month, nightsTaken, boarders: {studentId, studentName, className, present, onLeave, absent}[] }` |
| GET | `/api/hostel/alerts` | read or warden | `200 AlertView[]`: the open alerts of the hostels in scope |
| POST | `/api/hostel/alerts/{id}/resolve` | warden | `200 AlertView`; body `{note?}`; `409` when already resolved |

Statuses are `PRESENT`, `ON_LEAVE`, `ABSENT`. Taking a roll call again for the same night replaces the marks. Each
`ABSENT` mark opens an `ABSENT_AT_ROLL_CALL` alert (once per boarder and night) and enqueues
`hostel.roll_call_absent` to the primary guardian. Audit: `hostel_roll_call.taken` (with the counts),
`hostel_alert.resolved`.

## Complaints

| Method | Path | Permission | Success |
| --- | --- | --- | --- |
| GET | `/api/hostel/complaints?hostelId=&status=` | read or warden | `200 ComplaintView[]`, open ones first |
| POST | `/api/hostel/complaints` | warden | `201 ComplaintView`; body `{roomId, category, description}` |
| POST | `/api/hostel/complaints/{id}/status` | warden | `200 ComplaintView`; body `{status, note?}`; `RESOLVED` needs a note (`400 errors.note`); `409` when already resolved |

Categories: `PLUMBING`, `ELECTRICAL`, `FURNITURE`, `CLEANING`, `OTHER`; statuses `OPEN`, `IN_PROGRESS`, `RESOLVED`.
Audit: `hostel_complaint.raised`, `hostel_complaint.updated`.

## Reports (`hostel.read`)

| Path | Query | Answer |
| --- | --- | --- |
| `/api/hostel/reports/occupancy` | | `{ asOf, beds, occupied, free, occupancyPercent, hostels: HostelOccupancy[], rows: BoarderView[] }` |
| `/api/hostel/reports/roll-call` | `from?`, `to?` | `{ from, to, nights, present, onLeave, absent, rows: {night, hostelId, hostelName, present, onLeave, absent, absentees}[] }` |
| `/api/hostel/reports/outings` | `month=YYYY-MM` | `{ month, total, outings, homeLeaves, late, byStatus: {name, count}[], rows: PassView[] }` |
| `/api/hostel/reports/fee-dues` | | `{ asOf, boarders, balancePaise, overduePaise, rows: {studentId, studentName, admissionNo, className, hostelName, roomNumber, netPaise, paidPaise, balancePaise, overduePaise}[] }` from the fees module |

Each has a `.csv` twin (UTF-8 with BOM, rupees with two decimals) and the hub group "Hostel" on the web links them
with print.

## Events

Published in the committing transaction, in `com.akshara.hostel` (public):

- `HostelAllotted(tenantId, allotmentId, previousAllotmentId, studentId, hostelId, hostelName, roomId, roomNumber,
  monthlyFeePaise, fromDate)` on an allotment and on a room change (`previousAllotmentId` set).
- `HostelVacated(tenantId, allotmentId, studentId, hostelId, roomId, toDate, reason)` on vacating.

The fees module has no hostel fee head yet (`FeeHeadKind` does not know one), so the room's monthly fee is not
billed automatically; the boarder fee dues report reads the student's existing dues. When fees gains a hostel head
it listens to these two events to add and stop the monthly charge.

## Tables (`hostel` schema, all with `tenant_id` and the `tenant_isolation` RLS policy)

`hostel`, `block`, `room`, `bed`, `allotment`, `menu_item`, `mess_attendance`, `pass`, `roll_call`,
`roll_call_entry`, `alert`, `complaint`. Allotments, passes, roll calls and alerts are never deleted (no `delete`
grant); the rest may be removed only while empty.

## Demo data

`DemoHostelData` (called from `DemoDataSeeder`) adds a boys' and a girls' hostel with blocks, rooms and beds, the
warden `warden@demo.akshara.test` (Vikram Pillai, role Warden) for both, twelve boarders including Arjun Sharma whom
the demo parent sees in the family app, this week's menu, yesterday's mess counts, last night's roll call with one
absentee (and its alert and parent message), one pending outing pass and one open plumbing complaint.
