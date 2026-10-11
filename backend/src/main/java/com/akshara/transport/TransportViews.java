package com.akshara.transport;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/** Response bodies of the transport API (docs/api/phase-2-transport.md). Phone numbers are always masked. */
public final class TransportViews {

    private TransportViews() {
    }

    // ------------------------------------------------------------------ setup

    public record SettingsView(boolean boardedAlerts, boolean droppedAlerts, boolean approachingAlerts,
            boolean delayAlerts) {
    }

    public record FeeZoneView(UUID id, String name, BigDecimal minKm, BigDecimal maxKm, long annualFeePaise,
            long stops) {
    }

    public record DocumentView(DocumentKind kind, LocalDate expiresOn, DocumentState state, Long daysLeft) {
    }

    public record RouteRef(UUID id, String name) {
    }

    public record DeviceTokenInfo(String hint, Instant issuedAt) {
    }

    /**
     * A vehicle. {@code flags}: FITNESS_INVALID (fitness certificate expired or missing), DRIVER_LICENCE_INVALID (a
     * driver of one of its active routes has an expired licence), NO_DRIVER (an active route has no driver),
     * DOCUMENT_EXPIRING (a document ends within 30 days), DOCUMENT_EXPIRED.
     */
    public record VehicleView(UUID id, String registrationNo, VehicleType type, int seats, String gpsDeviceId,
            LocalDate insuranceExpiry, LocalDate fitnessExpiry, LocalDate permitExpiry, LocalDate pollutionExpiry,
            VehicleStatus status, List<DocumentView> documents, List<String> flags, DeviceTokenInfo deviceToken,
            List<RouteRef> routes, Instant lastPositionAt) {
    }

    /** A new device token, shown once; only its hash is kept. */
    public record DeviceTokenView(UUID vehicleId, String token, String hint, Instant issuedAt, String endpoint,
            String header) {
    }

    /**
     * A driver or attendant. {@code mobile} is masked. {@code flags}: LICENCE_INVALID (a driver's licence expired or
     * missing), LICENCE_EXPIRING, POLICE_VERIFICATION_MISSING, LEFT (no longer on the staff).
     */
    public record CrewView(UUID id, UUID userId, String name, String employeeCode, String designation, String mobile,
            boolean staffActive, CrewKind kind, String licenceNo, LocalDate licenceExpiry, DocumentState licenceState,
            Long licenceDaysLeft, LocalDate policeVerifiedOn, List<RouteRef> routes, List<String> flags) {
    }

    public record StaffOption(UUID userId, String name, String employeeCode, String designation, List<String> roles,
            boolean crew) {
    }

    // ------------------------------------------------------------------ routes

    public record VehicleRef(UUID id, String registrationNo, VehicleType type, int seats, VehicleStatus status) {
    }

    public record CrewRef(UUID id, UUID userId, String name, CrewKind kind) {
    }

    public record ZoneRef(UUID id, String name, long annualFeePaise) {
    }

    public record StopView(UUID id, int seq, String name, String landmark, LocalTime pickupTime, LocalTime dropTime,
            BigDecimal latitude, BigDecimal longitude, ZoneRef feeZone, long students) {
    }

    /**
     * A route. {@code allocated} counts active allocations still running today or later (the seats they take).
     * {@code flags}: NO_VEHICLE, NO_DRIVER, NO_ATTENDANT, OVER_CAPACITY, VEHICLE_UNAVAILABLE (in maintenance or
     * retired), FITNESS_INVALID, DRIVER_LICENCE_INVALID, NO_STOPS.
     */
    public record RouteSummary(UUID id, String name, boolean active, boolean runsMorning, boolean runsAfternoon,
            VehicleRef vehicle, CrewRef driver, CrewRef attendant, int stopCount, long allocated, Integer seats,
            LocalTime firstPickup, List<String> flags) {
    }

    public record RouteDetail(UUID id, String name, boolean active, boolean runsMorning, boolean runsAfternoon,
            VehicleRef vehicle, CrewRef driver, CrewRef attendant, int stopCount, long allocated, Integer seats,
            LocalTime firstPickup, List<String> flags, List<StopView> stops) {
    }

    // ------------------------------------------------------------------ allocations

    public record StudentRef(UUID id, String name, String admissionNo, String classLabel) {
    }

    public record StopRef(UUID id, String name) {
    }

    /** {@code current} is true when the allocation covers today. */
    public record AllocationView(UUID id, StudentRef student, RouteRef route, StopRef stop, ZoneRef feeZone,
            LocalDate startDate, LocalDate endDate, AllocationStatus status, boolean current,
            String overCapacityReason, String endReason, String createdByName, Instant createdAt,
            String endedByName, Instant endedAt) {
    }

    public record Skipped(UUID studentId, String name, String reason) {
    }

    public record BulkResult(int allocated, List<Skipped> skipped) {
    }

    public record AllocationBrief(UUID id, UUID routeId, String routeName, String stopName, LocalDate startDate,
            LocalDate endDate) {
    }

    public record StudentOption(UUID id, String name, String admissionNo, String classLabel,
            AllocationBrief allocation) {
    }

    // ------------------------------------------------------------------ trips

    /**
     * Where the bus is: the latest position, how long ago (stale after 5 minutes without one), and the stop with
     * coordinates nearest it.
     */
    public record PositionView(BigDecimal latitude, BigDecimal longitude, Integer accuracyM, Instant at,
            PositionSource source, Long minutesAgo, boolean stale, StopRef nearestStop, Long nearestMeters) {
    }

    public record DelayView(int minutes, String reason, Instant at) {
    }

    public record TripStudent(UUID id, String name, String admissionNo, String classLabel, BoardingStatus status,
            Instant markedAt) {
    }

    /** A stop in trip order: {@code reached} once the bus got there, {@code next} for the stop it is heading to. */
    public record TripStop(UUID id, int seq, String name, String landmark, LocalTime time, boolean reached,
            boolean next, List<TripStudent> students) {
    }

    public record TripCounts(int total, int boarded, int dropped, int absent, int pending) {
    }

    public record TripView(UUID id, RouteRef route, LocalDate date, TripKind kind, TripStatus status,
            String vehicleRegistrationNo, Instant startedAt, String startedByName, Instant endedAt,
            String endedByName, DelayView delay, PositionView position, StopRef reachedStop, StopRef nextStop,
            TripCounts counts, List<TripStop> stops) {
    }

    public record TripBrief(UUID id, TripStatus status, Instant startedAt, Instant endedAt) {
    }

    /** A route the signed-in user may run today. {@code role}: DRIVER, ATTENDANT or MANAGER. */
    public record MyRoute(UUID routeId, String name, String vehicleRegistrationNo, String role, boolean runsMorning,
            boolean runsAfternoon, TripBrief morning, TripBrief afternoon, int students) {
    }

    public record MyTrips(LocalDate today, List<MyRoute> routes) {
    }

    public record LiveTrip(UUID id, TripKind kind, TripStatus status, Instant startedAt, Instant endedAt,
            PositionView position, StopRef reachedStop, StopRef nextStop, TripCounts counts, DelayView delay) {
    }

    public record LiveRoute(RouteRef route, String vehicleRegistrationNo, List<StopRef> stops, List<LiveTrip> trips) {
    }

    public record LiveBoard(LocalDate today, List<LiveRoute> routes) {
    }

    public record DeviceAck(boolean accepted, boolean onTrip) {
    }

    // ------------------------------------------------------------------ families

    /** The bus's position as families see it: no coordinates, only the nearest stop and how long ago. */
    public record FamilyPosition(Instant at, Long minutesAgo, boolean stale, StopRef nearestStop) {
    }

    public record FamilyStop(UUID id, int seq, String name, LocalTime time, boolean reached, boolean next,
            boolean mine) {
    }

    public record FamilyEvent(TripEventKind kind, Instant at, String stopName, Integer minutes, String note) {
    }

    /**
     * Today's trip of the child's route. {@code status} is NOT_STARTED, RUNNING or COMPLETED; {@code stopsAway} (while
     * running) is how many stops the bus still has to reach up to and including the child's stop: 1 when it is at the
     * stop before, 0 once it has reached the child's stop.
     */
    public record FamilyTrip(TripKind kind, String status, Instant startedAt, Instant endedAt,
            BoardingStatus childStatus, Instant childMarkedAt, FamilyPosition position, StopRef nextStop,
            Integer stopsAway, DelayView delay, List<FamilyStop> stops, List<FamilyEvent> events) {
    }

    public record FamilyAllocation(String routeName, String stopName, String landmark, LocalTime pickupTime,
            LocalTime dropTime, LocalDate startDate, LocalDate endDate, String vehicleRegistrationNo,
            VehicleType vehicleType) {
    }

    public record ChildBus(UUID studentId, String name, String classLabel, FamilyAllocation allocation,
            List<FamilyTrip> trips) {
    }

    public record MyTransport(LocalDate today, List<ChildBus> children) {
    }

    // ------------------------------------------------------------------ reports

    public record RosterRow(UUID studentId, String name, String admissionNo, String classLabel, String guardianName,
            String guardianPhone, LocalDate startDate, LocalDate endDate) {
    }

    public record RosterStop(UUID stopId, int seq, String name, LocalTime pickupTime, LocalTime dropTime,
            List<RosterRow> students) {
    }

    public record RosterRoute(UUID routeId, String name, String vehicleRegistrationNo, Integer seats,
            String driverName, String attendantName, int students, List<RosterStop> stops) {
    }

    public record RouteStudentsReport(LocalDate date, int students, List<RosterRoute> routes) {
    }

    /** {@code subjectType}: VEHICLE (its registration number) or DRIVER (their name). */
    public record DocumentRow(String subjectType, UUID subjectId, String subject, DocumentKind document,
            LocalDate expiresOn, DocumentState state, Long daysLeft) {
    }

    public record DocumentsReport(LocalDate asOf, int withinDays, List<DocumentRow> rows) {
    }

    /** One route's transport dues for the year; {@code routeId} is null for students with bus dues and no route. */
    public record RouteFees(UUID routeId, String routeName, int students, long billedPaise, long collectedPaise,
            long outstandingPaise, long overduePaise) {
    }

    public record FeesReport(UUID academicYearId, String academicYearName, LocalDate asOf, List<RouteFees> routes,
            RouteFees totals) {
    }

    public record BoardingRow(Instant at, UUID routeId, String routeName, TripKind tripKind, TripEventKind kind,
            UUID studentId, String studentName, String admissionNo, String classLabel, String stopName,
            Integer minutes, String note, String byName) {
    }

    public record BoardingReport(LocalDate date, int boarded, int dropped, int absent, int delays,
            List<BoardingRow> rows) {
    }
}
