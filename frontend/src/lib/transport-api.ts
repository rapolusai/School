import { apiFetch, pathSegment } from "./api";
import { reportQuery } from "./reports-api";
import type {
  Allocation,
  AllocationRequest,
  BoardingReport,
  BoardingStatus,
  BulkAllocationRequest,
  BulkAllocationResult,
  CrewMember,
  CrewRequest,
  CrewUpdateRequest,
  DeviceToken,
  DocumentsReport,
  FeeZone,
  FeeZoneRequest,
  LiveBoard,
  MyTransport,
  MyTrips,
  PositionRequest,
  RouteDetail,
  RouteRequest,
  RouteStudentsReport,
  RouteSummary,
  StaffOption,
  StopRequest,
  TransportFeesReport,
  TransportSettings,
  TransportStudentOption,
  Trip,
  TripKind,
  Vehicle,
  VehicleRequest,
} from "./types";

/* Endpoint helpers for docs/api/phase-2-transport.md. */

const id = pathSegment;

export type AllocationQuery = {
  routeId?: string;
  stopId?: string;
  classId?: string;
  /** ACTIVE (the API's default), ENDED or ALL */
  status?: "ACTIVE" | "ENDED" | "ALL";
  q?: string;
};
export type StudentOptionQuery = { q?: string; classId?: string; sectionId?: string };
export type RouteStudentsParams = { routeId?: string; date?: string };
export type DocumentsParams = { withinDays?: number };
export type TransportFeesParams = { yearId?: string; routeId?: string };
export type BoardingParams = { date?: string; routeId?: string };

const query = (params: Record<string, string | number | undefined>) =>
  reportQuery(
    Object.fromEntries(
      Object.entries(params).map(([key, value]) => [key, typeof value === "number" ? String(value) : value]),
    ),
  );

/** The JSON path and the CSV path (which Excel opens) of each transport report. */
export const transportReportPaths = {
  routeStudents: (p: RouteStudentsParams) => `/api/transport/reports/route-students${query(p)}`,
  routeStudentsCsv: (p: RouteStudentsParams) => `/api/transport/reports/route-students.csv${query(p)}`,
  documents: (p: DocumentsParams) => `/api/transport/reports/documents${query(p)}`,
  documentsCsv: (p: DocumentsParams) => `/api/transport/reports/documents.csv${query(p)}`,
  fees: (p: TransportFeesParams) => `/api/transport/reports/fees${query(p)}`,
  feesCsv: (p: TransportFeesParams) => `/api/transport/reports/fees.csv${query(p)}`,
  boarding: (p: BoardingParams) => `/api/transport/reports/boarding${query(p)}`,
  boardingCsv: (p: BoardingParams) => `/api/transport/reports/boarding.csv${query(p)}`,
};

export const transportApi = {
  // Setup
  settings: () => apiFetch<TransportSettings>("/api/transport/settings"),
  saveSettings: (body: TransportSettings) =>
    apiFetch<TransportSettings>("/api/transport/settings", { method: "PUT", body }),
  zones: () => apiFetch<FeeZone[]>("/api/transport/fee-zones"),
  createZone: (body: FeeZoneRequest) => apiFetch<FeeZone>("/api/transport/fee-zones", { method: "POST", body }),
  updateZone: (zoneId: string, body: FeeZoneRequest) =>
    apiFetch<FeeZone>(`/api/transport/fee-zones/${id(zoneId)}`, { method: "PUT", body }),
  deleteZone: (zoneId: string) => apiFetch<void>(`/api/transport/fee-zones/${id(zoneId)}`, { method: "DELETE" }),
  vehicles: () => apiFetch<Vehicle[]>("/api/transport/vehicles"),
  vehicle: (vehicleId: string) => apiFetch<Vehicle>(`/api/transport/vehicles/${id(vehicleId)}`),
  createVehicle: (body: VehicleRequest) => apiFetch<Vehicle>("/api/transport/vehicles", { method: "POST", body }),
  updateVehicle: (vehicleId: string, body: VehicleRequest) =>
    apiFetch<Vehicle>(`/api/transport/vehicles/${id(vehicleId)}`, { method: "PUT", body }),
  issueDeviceToken: (vehicleId: string) =>
    apiFetch<DeviceToken>(`/api/transport/vehicles/${id(vehicleId)}/device-token`, { method: "POST" }),
  revokeDeviceToken: (vehicleId: string) =>
    apiFetch<void>(`/api/transport/vehicles/${id(vehicleId)}/device-token`, { method: "DELETE" }),
  crew: () => apiFetch<CrewMember[]>("/api/transport/crew"),
  staff: (q: string) => apiFetch<StaffOption[]>(`/api/transport/staff${query({ q })}`),
  addCrew: (body: CrewRequest) => apiFetch<CrewMember>("/api/transport/crew", { method: "POST", body }),
  updateCrew: (crewId: string, body: CrewUpdateRequest) =>
    apiFetch<CrewMember>(`/api/transport/crew/${id(crewId)}`, { method: "PUT", body }),
  removeCrew: (crewId: string) => apiFetch<void>(`/api/transport/crew/${id(crewId)}`, { method: "DELETE" }),

  // Routes and stops
  routes: () => apiFetch<RouteSummary[]>("/api/transport/routes"),
  route: (routeId: string) => apiFetch<RouteDetail>(`/api/transport/routes/${id(routeId)}`),
  createRoute: (body: RouteRequest) => apiFetch<RouteDetail>("/api/transport/routes", { method: "POST", body }),
  updateRoute: (routeId: string, body: RouteRequest) =>
    apiFetch<RouteDetail>(`/api/transport/routes/${id(routeId)}`, { method: "PUT", body }),
  saveStops: (routeId: string, stops: StopRequest[]) =>
    apiFetch<RouteDetail>(`/api/transport/routes/${id(routeId)}/stops`, { method: "PUT", body: { stops } }),

  // Allocations
  allocations: (q: AllocationQuery = {}) => apiFetch<Allocation[]>(`/api/transport/allocations${query(q)}`),
  allocate: (body: AllocationRequest) => apiFetch<Allocation>("/api/transport/allocations", { method: "POST", body }),
  allocateClass: (body: BulkAllocationRequest) =>
    apiFetch<BulkAllocationResult>("/api/transport/allocations/bulk", { method: "POST", body }),
  updateAllocation: (allocationId: string, body: { stopId: string; endDate: string | null }) =>
    apiFetch<Allocation>(`/api/transport/allocations/${id(allocationId)}`, { method: "PUT", body }),
  endAllocation: (allocationId: string, body: { endDate: string; reason: string | null }) =>
    apiFetch<Allocation>(`/api/transport/allocations/${id(allocationId)}/end`, { method: "POST", body }),
  students: (q: StudentOptionQuery) => apiFetch<TransportStudentOption[]>(`/api/transport/students${query(q)}`),

  // Trips
  myTrips: () => apiFetch<MyTrips>("/api/transport/my-trips"),
  /** Today's trip of the route, or undefined when it has not been started (204). */
  todayTrip: (routeId: string, kind: TripKind) =>
    apiFetch<Trip | undefined>(`/api/transport/routes/${id(routeId)}/trips/today${query({ kind })}`),
  startTrip: (routeId: string, kind: TripKind) =>
    apiFetch<Trip>(`/api/transport/routes/${id(routeId)}/trips`, { method: "POST", body: { kind } }),
  trip: (tripId: string) => apiFetch<Trip>(`/api/transport/trips/${id(tripId)}`),
  mark: (tripId: string, studentId: string, status: BoardingStatus) =>
    apiFetch<Trip>(`/api/transport/trips/${id(tripId)}/students/${id(studentId)}`, { method: "PUT", body: { status } }),
  position: (tripId: string, body: PositionRequest) =>
    apiFetch<Trip>(`/api/transport/trips/${id(tripId)}/positions`, { method: "POST", body }),
  delay: (tripId: string, body: { minutes: number; reason: string }) =>
    apiFetch<Trip>(`/api/transport/trips/${id(tripId)}/delay`, { method: "POST", body }),
  endTrip: (tripId: string) => apiFetch<Trip>(`/api/transport/trips/${id(tripId)}/end`, { method: "POST" }),
  live: () => apiFetch<LiveBoard>("/api/transport/live"),

  // Families
  mine: () => apiFetch<MyTransport>("/api/me/transport"),

  // Reports
  routeStudents: (p: RouteStudentsParams) => apiFetch<RouteStudentsReport>(transportReportPaths.routeStudents(p)),
  documents: (p: DocumentsParams) => apiFetch<DocumentsReport>(transportReportPaths.documents(p)),
  fees: (p: TransportFeesParams) => apiFetch<TransportFeesReport>(transportReportPaths.fees(p)),
  boarding: (p: BoardingParams) => apiFetch<BoardingReport>(transportReportPaths.boarding(p)),
};

/** The API's registration number rule: 4 to 20 letters, digits, spaces or hyphens ("TS09 UB 4410"). */
export const REGISTRATION_PATTERN = /^[A-Za-z0-9][A-Za-z0-9 -]{2,18}[A-Za-z0-9]$/;
/** The API's driving licence number rule. */
export const LICENCE_PATTERN = /^[A-Za-z0-9 /-]{5,30}$/;
/** How often the trip screen sends the phone's position. */
export const POSITION_INTERVAL_MS = 30_000;
/** A position older than this is "no recent position" (the API's rule). */
export const STALE_AFTER_MINUTES = 5;

/** "07:10" from the API's "07:10" or "07:10:00"; "" for none. */
export function timeOfDay(value: string | null | undefined): string {
  return value ? value.slice(0, 5) : "";
}
