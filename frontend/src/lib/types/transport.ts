/* Transport (docs/api/phase-2-transport.md): vehicles, crew, routes and stops, fee zones, allocations, trips, the
   family view and reports. Dates are "YYYY-MM-DD", times of day "HH:mm" (the API may add ":ss"), instants ISO. Phone
   numbers arrive masked. */

export const VEHICLE_TYPES = ["BUS", "MINI_BUS", "VAN", "CAR"] as const;
export type VehicleType = (typeof VEHICLE_TYPES)[number];
export const VEHICLE_STATUSES = ["ACTIVE", "MAINTENANCE", "RETIRED"] as const;
export type VehicleStatus = (typeof VEHICLE_STATUSES)[number];
export type DocumentKind = "INSURANCE" | "FITNESS" | "PERMIT" | "POLLUTION" | "LICENCE";
export type DocumentState = "VALID" | "EXPIRING" | "EXPIRED" | "MISSING";
export const CREW_KINDS = ["DRIVER", "ATTENDANT"] as const;
export type CrewKind = (typeof CREW_KINDS)[number];
export type AllocationStatus = "ACTIVE" | "ENDED";
export type TripKind = "MORNING" | "AFTERNOON";
export type TripStatus = "RUNNING" | "COMPLETED";
export type BoardingStatus = "BOARDED" | "DROPPED" | "ABSENT";
export type TripEventKind = BoardingStatus | "DELAYED";
export type PositionSource = "PHONE" | "DEVICE";

// ------------------------------------------------------------------ setup

export type TransportSettings = {
  boardedAlerts: boolean;
  droppedAlerts: boolean;
  approachingAlerts: boolean;
  delayAlerts: boolean;
};

export type FeeZone = {
  id: string;
  name: string;
  minKm: number;
  maxKm: number | null;
  annualFeePaise: number;
  /** stops in the zone */
  stops: number;
};

export type FeeZoneRequest = { name: string; minKm: number; maxKm: number | null; annualFeePaise: number };

export type VehicleDocument = { kind: DocumentKind; expiresOn: string | null; state: DocumentState; daysLeft: number | null };

export type RouteRef = { id: string; name: string };

export type DeviceTokenInfo = { hint: string; issuedAt: string };

/** FITNESS_INVALID, DRIVER_LICENCE_INVALID, NO_DRIVER, DOCUMENT_EXPIRING, DOCUMENT_EXPIRED */
export type Vehicle = {
  id: string;
  registrationNo: string;
  type: VehicleType;
  seats: number;
  gpsDeviceId: string | null;
  insuranceExpiry: string | null;
  fitnessExpiry: string | null;
  permitExpiry: string | null;
  pollutionExpiry: string | null;
  status: VehicleStatus;
  documents: VehicleDocument[];
  flags: string[];
  deviceToken: DeviceTokenInfo | null;
  routes: RouteRef[];
  lastPositionAt: string | null;
};

export type VehicleRequest = {
  registrationNo: string;
  type: VehicleType;
  seats: number;
  gpsDeviceId: string | null;
  insuranceExpiry: string | null;
  fitnessExpiry: string | null;
  permitExpiry: string | null;
  pollutionExpiry: string | null;
  status: VehicleStatus;
};

/** Shown once: only its hash is kept. */
export type DeviceToken = {
  vehicleId: string;
  token: string;
  hint: string;
  issuedAt: string;
  endpoint: string;
  header: string;
};

/** LICENCE_INVALID, LICENCE_EXPIRING, POLICE_VERIFICATION_MISSING, LEFT */
export type CrewMember = {
  id: string;
  userId: string;
  name: string;
  employeeCode: string | null;
  designation: string | null;
  mobile: string | null;
  staffActive: boolean;
  kind: CrewKind;
  licenceNo: string | null;
  licenceExpiry: string | null;
  licenceState: DocumentState | null;
  licenceDaysLeft: number | null;
  policeVerifiedOn: string | null;
  routes: RouteRef[];
  flags: string[];
};

export type CrewRequest = {
  userId: string;
  kind: CrewKind;
  licenceNo: string | null;
  licenceExpiry: string | null;
  policeVerifiedOn: string | null;
};

export type CrewUpdateRequest = Omit<CrewRequest, "userId">;

export type StaffOption = {
  userId: string;
  name: string;
  employeeCode: string | null;
  designation: string | null;
  roles: string[];
  /** already a driver or attendant */
  crew: boolean;
};

// ------------------------------------------------------------------ routes

export type VehicleRef = { id: string; registrationNo: string; type: VehicleType; seats: number; status: VehicleStatus };
export type CrewRef = { id: string; userId: string; name: string; kind: CrewKind };
export type ZoneRef = { id: string; name: string; annualFeePaise: number };

export type TransportStop = {
  id: string;
  seq: number;
  name: string;
  landmark: string | null;
  pickupTime: string | null;
  dropTime: string | null;
  latitude: number | null;
  longitude: number | null;
  feeZone: ZoneRef | null;
  students: number;
};

/** NO_VEHICLE, NO_DRIVER, NO_ATTENDANT, OVER_CAPACITY, VEHICLE_UNAVAILABLE, FITNESS_INVALID, DRIVER_LICENCE_INVALID, NO_STOPS */
export type RouteSummary = {
  id: string;
  name: string;
  active: boolean;
  runsMorning: boolean;
  runsAfternoon: boolean;
  vehicle: VehicleRef | null;
  driver: CrewRef | null;
  attendant: CrewRef | null;
  stopCount: number;
  allocated: number;
  seats: number | null;
  firstPickup: string | null;
  flags: string[];
};

export type RouteDetail = RouteSummary & { stops: TransportStop[] };

export type RouteRequest = {
  name: string;
  vehicleId: string | null;
  driverId: string | null;
  attendantId: string | null;
  runsMorning: boolean;
  runsAfternoon: boolean;
  active: boolean;
};

export type StopRequest = {
  /** an existing stop to keep (its students stay), or null for a new one */
  id: string | null;
  name: string;
  landmark: string | null;
  pickupTime: string | null;
  dropTime: string | null;
  latitude: number | null;
  longitude: number | null;
  feeZoneId: string | null;
};

// ------------------------------------------------------------------ allocations

export type TransportStudentRef = { id: string; name: string; admissionNo: string | null; classLabel: string | null };
export type StopRef = { id: string; name: string };

export type Allocation = {
  id: string;
  student: TransportStudentRef;
  route: RouteRef;
  stop: StopRef;
  feeZone: ZoneRef | null;
  startDate: string;
  endDate: string | null;
  status: AllocationStatus;
  /** covers today */
  current: boolean;
  overCapacityReason: string | null;
  endReason: string | null;
  createdByName: string | null;
  createdAt: string;
  endedByName: string | null;
  endedAt: string | null;
};

export type AllocationRequest = {
  studentId: string;
  routeId: string;
  stopId: string;
  startDate: string;
  endDate: string | null;
  overCapacityReason: string | null;
};

export type BulkAllocationRequest = {
  classId: string;
  sectionId: string | null;
  routeId: string;
  stopId: string;
  startDate: string;
  endDate: string | null;
  studentIds: string[] | null;
  overCapacityReason: string | null;
};

export type BulkAllocationResult = {
  allocated: number;
  skipped: { studentId: string; name: string; reason: string }[];
};

export type AllocationBrief = {
  id: string;
  routeId: string;
  routeName: string;
  stopName: string;
  startDate: string;
  endDate: string | null;
};

export type TransportStudentOption = {
  id: string;
  name: string;
  admissionNo: string | null;
  classLabel: string | null;
  allocation: AllocationBrief | null;
};

// ------------------------------------------------------------------ trips

export type BusPosition = {
  latitude: number;
  longitude: number;
  accuracyM: number | null;
  at: string;
  source: PositionSource;
  minutesAgo: number | null;
  /** no position for 5 minutes */
  stale: boolean;
  nearestStop: StopRef | null;
  nearestMeters: number | null;
};

export type TripDelay = { minutes: number; reason: string; at: string };

export type TripStudent = {
  id: string;
  name: string;
  admissionNo: string | null;
  classLabel: string | null;
  status: BoardingStatus | null;
  markedAt: string | null;
};

export type TripStop = {
  id: string;
  seq: number;
  name: string;
  landmark: string | null;
  time: string | null;
  reached: boolean;
  next: boolean;
  students: TripStudent[];
};

export type TripCounts = { total: number; boarded: number; dropped: number; absent: number; pending: number };

export type Trip = {
  id: string;
  route: RouteRef;
  date: string;
  kind: TripKind;
  status: TripStatus;
  vehicleRegistrationNo: string | null;
  startedAt: string;
  startedByName: string | null;
  endedAt: string | null;
  endedByName: string | null;
  delay: TripDelay | null;
  position: BusPosition | null;
  reachedStop: StopRef | null;
  nextStop: StopRef | null;
  counts: TripCounts;
  stops: TripStop[];
};

export type TripBrief = { id: string; status: TripStatus; startedAt: string; endedAt: string | null };

export type MyRoute = {
  routeId: string;
  name: string;
  vehicleRegistrationNo: string | null;
  /** DRIVER, ATTENDANT or MANAGER */
  role: string;
  runsMorning: boolean;
  runsAfternoon: boolean;
  morning: TripBrief | null;
  afternoon: TripBrief | null;
  students: number;
};

export type MyTrips = { today: string; routes: MyRoute[] };

export type PositionRequest = {
  latitude: number;
  longitude: number;
  accuracyM: number | null;
  speedKmh: number | null;
  recordedAt?: string | null;
};

export type LiveTrip = {
  id: string;
  kind: TripKind;
  status: TripStatus;
  startedAt: string;
  endedAt: string | null;
  position: BusPosition | null;
  reachedStop: StopRef | null;
  nextStop: StopRef | null;
  counts: TripCounts;
  delay: TripDelay | null;
};

export type LiveRoute = { route: RouteRef; vehicleRegistrationNo: string | null; stops: StopRef[]; trips: LiveTrip[] };
export type LiveBoard = { today: string; routes: LiveRoute[] };

// ------------------------------------------------------------------ families

export type FamilyBusPosition = { at: string; minutesAgo: number | null; stale: boolean; nearestStop: StopRef | null };

export type FamilyBusStop = {
  id: string;
  seq: number;
  name: string;
  time: string | null;
  reached: boolean;
  next: boolean;
  /** the child's stop */
  mine: boolean;
};

export type FamilyBusEvent = {
  kind: TripEventKind;
  at: string;
  stopName: string | null;
  minutes: number | null;
  note: string | null;
};

export type FamilyTrip = {
  kind: TripKind;
  status: "NOT_STARTED" | "RUNNING" | "COMPLETED";
  startedAt: string | null;
  endedAt: string | null;
  childStatus: BoardingStatus | null;
  childMarkedAt: string | null;
  position: FamilyBusPosition | null;
  nextStop: StopRef | null;
  /** stops the bus still has to reach up to the child's stop, while running */
  stopsAway: number | null;
  delay: TripDelay | null;
  stops: FamilyBusStop[];
  events: FamilyBusEvent[];
};

export type FamilyBusAllocation = {
  routeName: string;
  stopName: string;
  landmark: string | null;
  pickupTime: string | null;
  dropTime: string | null;
  startDate: string;
  endDate: string | null;
  vehicleRegistrationNo: string | null;
  vehicleType: VehicleType | null;
};

export type ChildBus = {
  studentId: string;
  name: string;
  classLabel: string | null;
  allocation: FamilyBusAllocation | null;
  trips: FamilyTrip[];
};

export type MyTransport = { today: string; children: ChildBus[] };

// ------------------------------------------------------------------ reports

export type RosterRow = {
  studentId: string;
  name: string;
  admissionNo: string | null;
  classLabel: string | null;
  guardianName: string | null;
  /** masked */
  guardianPhone: string | null;
  startDate: string;
  endDate: string | null;
};

export type RosterStop = {
  stopId: string;
  seq: number;
  name: string;
  pickupTime: string | null;
  dropTime: string | null;
  students: RosterRow[];
};

export type RosterRoute = {
  routeId: string;
  name: string;
  vehicleRegistrationNo: string | null;
  seats: number | null;
  driverName: string | null;
  attendantName: string | null;
  students: number;
  stops: RosterStop[];
};

export type RouteStudentsReport = { date: string; students: number; routes: RosterRoute[] };

export type DocumentRow = {
  /** VEHICLE (its registration number) or DRIVER (their name) */
  subjectType: "VEHICLE" | "DRIVER";
  subjectId: string;
  subject: string;
  document: DocumentKind;
  expiresOn: string | null;
  state: DocumentState;
  daysLeft: number | null;
};

export type DocumentsReport = { asOf: string; withinDays: number; rows: DocumentRow[] };

export type RouteFees = {
  routeId: string | null;
  routeName: string | null;
  students: number;
  billedPaise: number;
  collectedPaise: number;
  outstandingPaise: number;
  overduePaise: number;
};

export type TransportFeesReport = {
  academicYearId: string;
  academicYearName: string;
  asOf: string;
  routes: RouteFees[];
  totals: RouteFees;
};

export type BoardingRow = {
  at: string;
  routeId: string;
  routeName: string;
  tripKind: TripKind;
  kind: TripEventKind;
  studentId: string | null;
  studentName: string | null;
  admissionNo: string | null;
  classLabel: string | null;
  stopName: string | null;
  minutes: number | null;
  note: string | null;
  byName: string | null;
};

export type BoardingReport = {
  date: string;
  boarded: number;
  dropped: number;
  absent: number;
  delays: number;
  rows: BoardingRow[];
};
