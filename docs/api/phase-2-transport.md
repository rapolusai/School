# Phase 2 API: transport

Same conventions as [Phase 0](phase-0.md) and [Phase 1](phase-1.md): JSON under `/api`, a Bearer access token,
RFC 9457 problem details with `errors.<field>`, ids from another school in a path answer `404` and in a body answer
`400` on that field, conflicts answer `409`, dates are `"YYYY-MM-DD"` days in India (Asia/Kolkata), times of day are
`"HH:MM:SS"`, instants are UTC, money is in **paise**, and every change is written to the audit trail.

**In this slice:** the Transport manager and Bus attendant roles; fee zones; vehicles with their document expiry dates
and GPS device tokens; drivers and attendants (members of staff); routes with ordered stops; students allocated to a
route and stop (one at a time or a class at once) with a capacity check; the bus fee in each student's fee dues; the
attendant's trip screen (start, share the phone's location, mark boarded / dropped / absent, report a delay, end); a
token-protected endpoint for GPS devices; the live board; parent messages (boarded, dropped, approaching, delayed)
through the simulated outbox; the family Bus page; four reports; demo data.

**Not in this slice:** maps or map tiles, route optimisation, outside GPS or SMS services (nothing leaves the system),
per-year fee zone prices, and app notifications (the events below are ready for them).

> **Owner decision needed: phone location is blocked by the web app's security headers.** `frontend/next.config.ts`
> sends `Permissions-Policy: … geolocation=() …`, which turns the browser Geolocation API off for the whole site. The
> trip screen therefore shows "Location is blocked" in a real browser and sends no phone positions; marking students,
> delays and GPS-device positions all work. The fix is one word in that header, `geolocation=(self)`. This slice did
> not change it (`next.config` is owned outside the slice); the trip screen and its tests are written for the fixed
> header (Vitest mocks the Geolocation API; the Playwright spec posts the bus's position as its GPS device instead).

## Permissions and roles

| Permission | Who has it (seeded roles) | Allows |
| --- | --- | --- |
| `transport.read` (new) | School Admin, Principal, Transport manager | routes, stops, vehicles, crew, allocations, the live board, settings, reports |
| `transport.manage` (new) | School Admin, Transport manager | every change under `/api/transport` except trips; the student and staff pickers |
| `transport.trip` (new) | School Admin, Transport manager, Bus attendant | the trip screen: their routes' trips (a manager: every route's) |
| `dashboard.view` | everyone | `GET /api/me/transport` (their own or their children's bus) |

New roles: **`TRANSPORT_MANAGER`** ("Transport manager"): `dashboard.view`, `academics.read`, `leave.request`,
`notices.read`, `timetable.read`, `transport.read`, `transport.manage`, `transport.trip`. **`BUS_ATTENDANT`** ("Bus
attendant", for drivers and attendants who run trips on a phone): `dashboard.view`, `leave.request`, `notices.read`,
`transport.trip`. Migration `V17__transport.sql` adds both roles to every existing school and appends the permissions
to School Admin and Principal (idempotent); `RoleCatalog` gives them to new schools. Teachers, parents and students
have none of the transport permissions.

## Settings and fee zones — `/api/transport`

| Method | Path | Permission | Success |
| --- | --- | --- | --- |
| GET | `/settings` | `transport.read` | `200 {boardedAlerts, droppedAlerts, approachingAlerts, delayAlerts}` |
| PUT | `/settings` | `transport.manage` | `200` same; all four required |
| GET | `/fee-zones` | `transport.read` | `200 FeeZoneView[]` by distance |
| POST | `/fee-zones` | `transport.manage` | `201 FeeZoneView`; body `{name, minKm, maxKm?, annualFeePaise}` |
| PUT | `/fee-zones/{id}` | `transport.manage` | `200 FeeZoneView` |
| DELETE | `/fee-zones/{id}` | `transport.manage` | `204`; `409` while stops use it |

- Defaults: boarded and dropped messages **on**, approaching **off** (it needs stop coordinates), delays **on**.
- A zone is a distance slab: `minKm` 0–999.9, `maxKm` empty for no upper limit and otherwise more than `minKm`
  (`400 errors.maxKm`), `annualFeePaise` 0–10,00,000 rupees. Names are unique per school, ignoring case (`409`).
- A changed fee re-prices this academic year's **unpaid** bus dues of the students on the zone's stops; paid dues stay.
  Zones have one price; a new year's price is set by editing the zone (not versioned by year).

```ts
type FeeZoneView = { id; name; minKm: number; maxKm: number | null; annualFeePaise: number; stops: number };
```

## Vehicles — `/api/transport`

| Method | Path | Permission | Success |
| --- | --- | --- | --- |
| GET | `/vehicles` | `transport.read` | `200 VehicleView[]` by registration number |
| GET | `/vehicles/{id}` | `transport.read` | `200 VehicleView` |
| POST | `/vehicles` | `transport.manage` | `201 VehicleView` |
| PUT | `/vehicles/{id}` | `transport.manage` | `200 VehicleView` |
| POST | `/vehicles/{id}/device-token` | `transport.manage` | `201 DeviceTokenView` (the token is shown **once**) |
| DELETE | `/vehicles/{id}/device-token` | `transport.manage` | `204` |

```ts
type VehicleForm = { registrationNo: string; type: "BUS" | "MINI_BUS" | "VAN" | "CAR"; seats: number /* 1–100 */;
  gpsDeviceId?: string | null; insuranceExpiry?; fitnessExpiry?; permitExpiry?; pollutionExpiry?: string | null;
  status?: "ACTIVE" | "MAINTENANCE" | "RETIRED" };                     // ACTIVE when not given
type DocumentView = { kind: "INSURANCE" | "FITNESS" | "PERMIT" | "POLLUTION"; expiresOn: string | null;
  state: "VALID" | "EXPIRING" | "EXPIRED" | "MISSING"; daysLeft: number | null };
type VehicleView = VehicleForm & { id; documents: DocumentView[]; flags: string[];
  deviceToken: { hint: string; issuedAt: string } | null; routes: { id; name }[]; lastPositionAt: string | null };
type DeviceTokenView = { vehicleId; token; hint; issuedAt; endpoint: string; header: "X-Device-Token" };
```

- Registration numbers: 4–20 letters, digits, spaces or hyphens, stored upper-case with single spaces
  (`TS09 UB 4410`); unique per school (`409 errors.registrationNo`).
- **Expiry warnings:** a document is EXPIRING from 30 days before its date (the date itself is still valid), EXPIRED
  after it and MISSING without one. Vehicle `flags`: `DOCUMENT_EXPIRING`, `DOCUMENT_EXPIRED`, `FITNESS_INVALID`
  (fitness expired or missing), `DRIVER_LICENCE_INVALID` (a driver of one of its routes), `NO_DRIVER`.
- Retiring a vehicle that an active route uses is `409 errors.status`; a retired vehicle gets no token and accepts no
  positions.
- A device token is `akt_` + 43 random characters; only its SHA-256 is stored. A new token replaces the old one at once.

## Drivers and attendants — `/api/transport`

| Method | Path | Permission | Success |
| --- | --- | --- | --- |
| GET | `/crew` | `transport.read` | `200 CrewView[]` |
| GET | `/staff?q=` | `transport.manage` | `200 StaffOption[]`: active staff by name, email or employee code (at most 20), `crew` true when already crew |
| POST | `/crew` | `transport.manage` | `201 CrewView`; body `{userId, kind, licenceNo?, licenceExpiry?, policeVerifiedOn?}` |
| PUT | `/crew/{id}` | `transport.manage` | `200 CrewView`; body without `userId` |
| DELETE | `/crew/{id}` | `transport.manage` | `204`; `409` while they are on an active route |

- Crew are **members of staff** (the staff module): the transport module keeps only their user id, kind, licence and
  police verification date, and reads the name, employee code and mobile (masked) from the staff module. Someone not
  on the staff, or of another school, is `400 errors.userId`; someone already crew is `409`.
- `kind` DRIVER needs `licenceNo` (5–30 letters, digits, spaces, `/` or `-`, stored upper-case) and `licenceExpiry`
  (`400`); for an ATTENDANT both are optional. A driver of an active route cannot become an attendant (`409 errors.kind`).
- To run trips from a phone, a crew member needs a role with `transport.trip` (Bus attendant), given on the Users page.
- Crew `flags`: `LICENCE_INVALID`, `LICENCE_EXPIRING`, `POLICE_VERIFICATION_MISSING`, `LEFT` (no longer on the staff).

## Routes and stops — `/api/transport`

| Method | Path | Permission | Success |
| --- | --- | --- | --- |
| GET | `/routes` | `transport.read` | `200 RouteSummary[]` by name |
| GET | `/routes/{id}` | `transport.read` | `200 RouteDetail` (with `stops`) |
| POST | `/routes` | `transport.manage` | `201 RouteDetail`; body `{name, vehicleId?, driverId?, attendantId?, runsMorning?, runsAfternoon?, active?}` |
| PUT | `/routes/{id}` | `transport.manage` | `200 RouteDetail` |
| PUT | `/routes/{id}/stops` | `transport.manage` | `200 RouteDetail`; body `{stops: StopForm[]}` in pickup order |

```ts
type StopForm = { id?: string | null; name: string; landmark?: string | null; pickupTime?: string | null;
  dropTime?: string | null; latitude?: number | null; longitude?: number | null; feeZoneId?: string | null };
type RouteSummary = { id; name; active; runsMorning; runsAfternoon;
  vehicle: { id; registrationNo; type; seats; status } | null; driver: CrewRef | null; attendant: CrewRef | null;
  stopCount; allocated: number; seats: number | null; firstPickup: string | null; flags: string[] };
type RouteDetail = RouteSummary & { stops: { id; seq; name; landmark; pickupTime; dropTime; latitude; longitude;
  feeZone: { id; name; annualFeePaise } | null; students: number }[] };
```

- Names are unique per school ignoring case (`409`). A route runs a morning trip, an afternoon trip or both (`400
  errors.runsMorning`); both and `active` are true when not given. The driver must be a DRIVER, the attendant cannot
  be the driver, a retired vehicle cannot be chosen (`400`). Making a route inactive while students travel on it is
  `409 errors.active`.
- **Stops** are replaced as a list (at most 60): an `id` keeps an existing stop and its students; stops left out are
  removed, which is `409` while students are allocated to them. Latitude and longitude come together or not at all
  (`400 errors["stops[i].longitude"]`). The morning trip runs the stops in this order; the **afternoon trip runs them
  in reverse** with `dropTime`.
- `allocated` counts active allocations running today or later; route `flags`: `NO_VEHICLE`, `NO_DRIVER`,
  `NO_ATTENDANT`, `OVER_CAPACITY`, `VEHICLE_UNAVAILABLE` (in maintenance or retired), `FITNESS_INVALID`,
  `DRIVER_LICENCE_INVALID`, `NO_STOPS`.

## Students on the buses — `/api/transport`

| Method | Path | Permission | Success |
| --- | --- | --- | --- |
| GET | `/allocations?routeId=&stopId=&classId=&status=ACTIVE\|ENDED\|ALL&q=` | `transport.read` | `200 AllocationView[]` (default ACTIVE) |
| GET | `/allocations/{id}` | `transport.read` | `200 AllocationView` |
| POST | `/allocations` | `transport.manage` | `201 AllocationView`; body `{studentId, routeId, stopId, startDate, endDate?, overCapacityReason?}` |
| POST | `/allocations/bulk` | `transport.manage` | `200 {allocated, skipped: {studentId, name, reason}[]}`; body `{classId, sectionId?, routeId, stopId, startDate, endDate?, studentIds?, overCapacityReason?}` |
| PUT | `/allocations/{id}` | `transport.manage` | `200 AllocationView`; body `{stopId, endDate?}` (a stop on the same route) |
| POST | `/allocations/{id}/end` | `transport.manage` | `200 AllocationView`; body `{endDate, reason?}` |
| GET | `/students?q=&classId=&sectionId=` | `transport.manage` | `200 StudentOption[]`: active students with their current allocation, if any |

- Only **active students** (the students module) on an **active route**, at a stop of that route (`400`).
- **One active allocation per student** (a partial unique index): another one is `409` with type
  `urn:akshara:problem:already-allocated` and `errors.studentId`, unless it ends before the new one starts (it is
  then closed). To move a student to another route, end the allocation and allocate again.
- **Capacity:** when the route's vehicle has as many allocations running on the start date as it has seats, a new one
  is `409` with type `urn:akshara:problem:route-full`, a detail naming the seats and `errors.overCapacityReason`. Sent
  again with `overCapacityReason`, it is allocated; the reason is kept on the allocation and in the audit trail. A
  route without a vehicle has no limit.
- Bulk: the class's (or section's) active students, or the `studentIds` picked from them (at most 200); students
  already on a bus are skipped and listed. The capacity check counts all of them.
- `endDate` is optional (the rest of the year) and never before `startDate` (`400 errors.endDate`). Ending sets the
  last day; allocations are never deleted. `current` is true when the allocation covers today.

## Transport fees

Bus fees are **student dues in the fees module**, under the school's first active fee head of kind **Transport**, so
they are collected, receipted, concessioned and paid online with the other fees. The fees module now has a
`StudentChargeSource` extension point (`com.akshara.fees`): when it works out a student's dues from the class's
published structure it asks each source for per-student charges, and `FeeDuesService.refreshStudentCharges(studentIds)`
re-prices them. The transport source charges, for each instalment of the structure, the zone fee of the allocation
that covers the instalment's **due date**, split evenly over the structure's instalments (the last one takes the
rounding). Instalments due before the student starts or after they leave are not charged. Every allocation change
and zone fee change refreshes the students concerned: unpaid dues that no longer apply are removed, paid ones stay.
Without a published structure for the class or without an active Transport head, nothing is charged (the allocation
still succeeds).

## Trips — `/api/transport` (`transport.trip`)

| Method | Path | Success |
| --- | --- | --- |
| GET | `/my-trips` | `200 {today, routes: MyRoute[]}`: the user's routes as driver or attendant (managers: every active route), with today's morning and afternoon trip if started |
| GET | `/routes/{routeId}/trips/today?kind=MORNING\|AFTERNOON` | `200 TripView`, or `204` when not started |
| POST | `/routes/{routeId}/trips` | `201 TripView`; body `{kind}` |
| GET | `/trips/{id}` | `200 TripView` |
| PUT | `/trips/{id}/students/{studentId}` | `200 TripView`; body `{status: "BOARDED" \| "DROPPED" \| "ABSENT"}` |
| POST | `/trips/{id}/positions` | `200 TripView`; body `{latitude, longitude, accuracyM?, speedKmh?, recordedAt?}` |
| POST | `/trips/{id}/delay` | `200 TripView`; body `{minutes 1–600, reason}` |
| POST | `/trips/{id}/end` | `200 TripView` |

```ts
type TripView = { id; route: { id; name }; date; kind: "MORNING" | "AFTERNOON"; status: "RUNNING" | "COMPLETED";
  vehicleRegistrationNo; startedAt; startedByName; endedAt; endedByName;
  delay: { minutes; reason; at } | null; position: PositionView | null;
  reachedStop: { id; name } | null; nextStop: { id; name } | null;
  counts: { total; boarded; dropped; absent; pending };
  stops: { id; seq; name; landmark; time; reached; next;               // in trip order
    students: { id; name; admissionNo; classLabel; status: "BOARDED" | "DROPPED" | "ABSENT" | null; markedAt }[] }[] };
type PositionView = { latitude; longitude; accuracyM; at; source: "PHONE" | "DEVICE"; minutesAgo: number | null;
  stale: boolean; nearestStop: { id; name } | null; nearestMeters: number | null };
```

- The **driver and attendant** of a route run its trips; `transport.manage` may run any route's. Any other route or
  trip is `404`. One trip per route, day and kind (`409` when already started); the route must be active (`409`) and
  run that kind (`400 errors.kind`). The trip's students are those whose allocation covers the day.
- Marks: the morning trip takes BOARDED (at the student's stop) or ABSENT (`400` for DROPPED); the afternoon trip also
  takes DROPPED. The same mark again changes nothing; a different one replaces it and cancels the earlier message if
  it has not gone out. A student not on the trip is `404`. Marks, delays, starting and ending need a RUNNING trip
  (`409`), are audited and kept in the boarding log.
- **Positions** (phone): the trip screen sends one every 30 seconds while it is open (browser Geolocation API; see the
  owner decision above). `recordedAt` defaults to now and may not be more than an hour old (`400`). At most 12 a minute
  per trip (`429`). Positions are kept in the trip log, not the audit trail.
- **Snapping, no maps:** the bus is shown at the stop with coordinates nearest its latest position
  (`nearestStop`, great-circle distance). Within **300 m** of a stop further along the trip than the last one reached,
  or when a student there is marked boarded (morning) or dropped (afternoon), that stop becomes `reachedStop` and the following one
  `nextStop`; the bus never goes back. With no position for
  **more than 5 minutes** `stale` is true and screens say "No recent position". The web shows the stops as an ordered
  list and an SVG line; no map tiles or outside services are used.
- **Approaching:** when the bus reaches a stop, the families of students at the **next** stop who are still waiting
  get the approaching message (if the school has it on). The first stop never gets it.
- A **delay** is kept on the trip, logged, and sent to the families still waiting (morning: not yet boarded or absent;
  afternoon: not yet dropped).

### GPS devices — `POST /api/public/transport/vehicles/{vehicleId}/positions` (no sign-in)

Header `X-Device-Token: <token>`; body `{latitude, longitude, accuracyM?, speedKmh?, recordedAt?}`. Answers
`202 {accepted: true, onTrip}`: the position is the vehicle's latest, and when the vehicle is on a RUNNING trip today
it also goes into that trip's log and moves the trip on like a phone's position. A missing or wrong token is `401`;
after **20 wrong tokens from one IP in 10 minutes** that IP gets `429` for the rest of the window; each vehicle may post
**12 positions a minute** (`429`). The token's SHA-256 is matched by a `security definer` database function that
returns only the vehicle's school, so the request then works as that school under row-level security. Limits are set by
`akshara.transport.positions-per-minute`, `.device-failures-per-ip` and `.device-failure-window` (in memory, per API
instance, like the sign-in limiter).

## Live board — `GET /api/transport/live` (`transport.read`)

`200 {today, routes: {route, vehicleRegistrationNo, stops: {id, name}[], trips: {id, kind, status, startedAt, endedAt,
position, reachedStop, nextStop, counts, delay}[]}[]}` for every active route. The web page refreshes every 30 seconds.

## Families — `GET /api/me/transport` (`dashboard.view`)

```ts
type MyTransport = { today; children: { studentId; name; classLabel;
  allocation: { routeName; stopName; landmark; pickupTime; dropTime; startDate; endDate;
    vehicleRegistrationNo; vehicleType } | null;
  trips: { kind; status: "NOT_STARTED" | "RUNNING" | "COMPLETED"; startedAt; endedAt;
    childStatus: "BOARDED" | "DROPPED" | "ABSENT" | null; childMarkedAt;
    position: { at; minutesAgo; stale; nearestStop } | null;          // no coordinates
    nextStop; stopsAway: number | null; delay;
    stops: { id; seq; name; time; reached; next; mine }[];
    events: { kind: "BOARDED" | "DROPPED" | "ABSENT" | "DELAYED"; at; stopName; minutes; note }[] }[] }[] };
```

A parent sees their children, a student their own record; no other child is reachable. Families never get the bus's
coordinates, other children's names or the crew's numbers. `stopsAway` (while running) counts the stops the bus still
has to reach up to the child's stop. The web page is **Bus** in the family menu (`/app/family/bus`), refreshing every 30
seconds.

## Parent messages

`transport.boarded`, `transport.dropped`, `transport.approaching` and `transport.delayed` (English and Hindi) go to the
student's primary parent or guardian through the notifications outbox on the school's alert channel and language,
held by its quiet hours like absence alerts. The outbox is **simulated**: nothing leaves the system and the message
log shows them as Simulated, with the number masked. Each is deduplicated per trip and student (per delay event for
delays). A student whose guardian has no valid mobile is marked as usual with no message.

## Reports — `/api/transport/reports` (`transport.read`)

| Path | Parameters | Returns |
| --- | --- | --- |
| `/route-students` | `routeId?`, `date?` (today) | students by route and stop with the parent's name and **masked** number; prints one route per page |
| `/documents` | `withinDays?` 0–365 (30) | vehicle documents and driving licences expired, missing or expiring within the days |
| `/fees` | `yearId?` (current), `routeId?` | per route: students, billed, collected, outstanding and overdue bus dues (from the fees module) |
| `/boarding` | `date?` (today), `routeId?` | the day's boarding log (boarded, dropped, absent, delays) with who marked it |

Each has a `.csv` path with the same parameters. Downloads are **UTF-8 CSV with a byte-order mark** and CRLF (Excel
opens ₹ and Hindi intact; no new dependency); text that a spreadsheet would run as a formula is defused; amounts are in
rupees. The web app lists them in `/app/reports` under "Transport".

## Events

- `TransportBoardingRecorded(tenantId, tripId, routeId, tripKind, studentId, stopId, status, at)`: a student marked
  boarded, dropped or absent (`stopId` null when boarding at school in the afternoon).
- `TransportTripDelayed(tenantId, tripId, routeId, tripKind, minutes, reason, at)`.

Both are published inside the transaction that records them; the parent messages are already queued by the transport
module, so listeners acting outside the database should use `@TransactionalEventListener` (after commit).

## Audit

Entities `transport_settings`, `transport_zone`, `transport_vehicle`, `transport_crew`, `transport_route`,
`transport_allocation`, `transport_trip`. Actions: `transport_settings.updated`,
`transport_zone.created|updated|deleted`, `transport_vehicle.created|updated|device_token_issued|device_token_revoked`,
`transport_crew.added|updated|removed`, `transport_route.created|updated|stops_saved`,
`transport_allocation.created|updated|ended` (with any over-capacity reason), `transport_allocations.bulk_created`,
`transport_trip.started|student_marked|delayed|ended`. Labels in English and Hindi. Positions are not audited.

## Data

Schema `transport`: `settings`, `fee_zone`, `vehicle`, `crew_member`, `route`, `stop`, `allocation`, `trip`,
`trip_event` (the boarding log, append-only), `position` (the trip log, append-only). Every table has `tenant_id`,
row-level security (USING and WITH CHECK), grants to the application role only, and composite `(tenant_id, id)`
foreign keys. Personal data kept: only ids for students and staff, a driver's licence number and expiry and a police
verification date; no Aadhaar, no phone numbers (read masked from the students and staff modules when needed).

## Web

`/app/transport` (routes), `/routes/[id]` (stops, students), `/allocations`, `/live`, `/vehicles`, `/crew`,
`/settings` (alerts and fee zones), `/app/transport/trip` (the attendant's phone screen: large buttons, students
grouped by stop in trip order, location every 30 seconds while open), `/app/family/bus`, and `/app/reports/transport-*`. Navigation:
"Transport" (`transport.read`), "Bus trips" (`transport.trip`) and the family "Bus".

## Demo data

`DemoTransportData`: zones A (0–5 km, ₹12,000), B (5–10 km, ₹16,000) and C (10 km and more, ₹20,000); three vehicles
(the mini bus's fitness certificate expires in 12 days; TS09 UB 4410 has a GPS device id); a driver and an attendant
for each of three routes around Hyderabad (staff `AKS-401`–`AKS-406` with the Bus attendant role, signing in with the
demo password; `attendant@demo.akshara.test` runs Route 1); Arjun Sharma and 15 other students on the buses from
today; and Route 1's morning trip today, with a position at Arjun's stop, Arjun boarded, one classmate absent, and the
trip ended.
