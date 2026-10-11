package com.akshara.transport;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Request bodies of the transport API (docs/api/phase-2-transport.md). The demo data uses the same records. */
public final class TransportForms {

    /** The highest annual bus fee a zone can have: ₹10,00,000. */
    public static final long MAX_FEE_PAISE = 100_000_000L;
    /** A registration number as painted on the vehicle: letters, digits, spaces and hyphens ("TS09 UB 4410"). */
    public static final String REGISTRATION_PATTERN = "^[A-Za-z0-9][A-Za-z0-9 -]{2,18}[A-Za-z0-9]$";
    public static final String REGISTRATION_MESSAGE =
            "Use 4 to 20 letters, digits, spaces or hyphens, for example TS09 UB 4410.";
    /** A driving licence number: letters, digits, spaces, / and -. */
    public static final String LICENCE_PATTERN = "^[A-Za-z0-9 /-]{5,30}$";
    public static final String LICENCE_MESSAGE = "Use 5 to 30 letters, digits, spaces, / or -.";
    /** The most stops a route can have. */
    public static final int MAX_STOPS = 60;
    /** The most students one bulk allocation takes. */
    public static final int MAX_BULK = 200;

    private TransportForms() {
    }

    /** Which bus messages parents get. Channel, language and quiet hours are the school's message settings. */
    public record SettingsForm(
            @NotNull Boolean boardedAlerts,
            @NotNull Boolean droppedAlerts,
            @NotNull Boolean approachingAlerts,
            @NotNull Boolean delayAlerts) {
    }

    /** A distance slab: from {@code minKm} up to {@code maxKm} (open-ended when empty) with its annual fee. */
    public record FeeZoneForm(
            @NotBlank @Size(max = 60) String name,
            @NotNull @DecimalMin("0.0") @DecimalMax("999.9") BigDecimal minKm,
            @DecimalMin("0.0") @DecimalMax("999.9") BigDecimal maxKm,
            @NotNull @Min(0) @Max(MAX_FEE_PAISE) Long annualFeePaise) {
    }

    /** A vehicle. The status is ACTIVE when not given. */
    public record VehicleForm(
            @NotBlank @Pattern(regexp = REGISTRATION_PATTERN, message = REGISTRATION_MESSAGE) String registrationNo,
            @NotNull VehicleType type,
            @NotNull @Min(1) @Max(100) Integer seats,
            @Size(max = 60) String gpsDeviceId,
            LocalDate insuranceExpiry,
            LocalDate fitnessExpiry,
            LocalDate permitExpiry,
            LocalDate pollutionExpiry,
            VehicleStatus status) {
    }

    /** A member of staff added as a driver (licence number and expiry required) or an attendant. */
    public record CrewForm(
            @NotNull UUID userId,
            @NotNull CrewKind kind,
            @Pattern(regexp = LICENCE_PATTERN, message = LICENCE_MESSAGE) String licenceNo,
            LocalDate licenceExpiry,
            LocalDate policeVerifiedOn) {
    }

    /** A crew member's details; the member of staff stays the same. */
    public record CrewUpdateForm(
            @NotNull CrewKind kind,
            @Pattern(regexp = LICENCE_PATTERN, message = LICENCE_MESSAGE) String licenceNo,
            LocalDate licenceExpiry,
            LocalDate policeVerifiedOn) {
    }

    /** A route. Both trips run and the route is active when not given. */
    public record RouteForm(
            @NotBlank @Size(max = 60) String name,
            UUID vehicleId,
            UUID driverId,
            UUID attendantId,
            Boolean runsMorning,
            Boolean runsAfternoon,
            Boolean active) {
    }

    /** A stop. {@code id} is an existing stop of the route to keep (its students stay), or empty for a new one. */
    public record StopForm(
            UUID id,
            @NotBlank @Size(max = 100) String name,
            @Size(max = 150) String landmark,
            LocalTime pickupTime,
            LocalTime dropTime,
            @DecimalMin("-90.0") @DecimalMax("90.0") BigDecimal latitude,
            @DecimalMin("-180.0") @DecimalMax("180.0") BigDecimal longitude,
            UUID feeZoneId) {
    }

    /** All the stops of a route in pickup order; stops left out are removed. */
    public record StopsForm(@NotNull @Size(max = MAX_STOPS) List<@Valid @NotNull StopForm> stops) {
    }

    /**
     * A student's place on a route and stop. When the bus is full, {@code overCapacityReason} allocates anyway (the
     * reason is kept and audited).
     */
    public record AllocationForm(
            @NotNull UUID studentId,
            @NotNull UUID routeId,
            @NotNull UUID stopId,
            @NotNull LocalDate startDate,
            LocalDate endDate,
            @Size(max = 300) String overCapacityReason) {
    }

    /**
     * The active students of a class (or one section of it) on one route and stop. {@code studentIds}, when given,
     * picks some of them. Students already on a bus are skipped and listed.
     */
    public record BulkAllocationForm(
            @NotNull UUID classId,
            UUID sectionId,
            @NotNull UUID routeId,
            @NotNull UUID stopId,
            @NotNull LocalDate startDate,
            LocalDate endDate,
            @Size(max = MAX_BULK) List<@NotNull UUID> studentIds,
            @Size(max = 300) String overCapacityReason) {
    }

    /** A new stop on the same route, or a planned end date. */
    public record AllocationUpdateForm(@NotNull UUID stopId, LocalDate endDate) {
    }

    /** Leaving transport: the last day on the bus and why. */
    public record EndAllocationForm(@NotNull LocalDate endDate, @Size(max = 300) String reason) {
    }

    public record StartTripForm(@NotNull TripKind kind) {
    }

    public record MarkForm(@NotNull BoardingStatus status) {
    }

    /** A position from the attendant's phone or the vehicle's GPS device. {@code recordedAt} is now when empty. */
    public record PositionForm(
            @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") Double latitude,
            @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") Double longitude,
            @Min(0) @Max(100_000) Integer accuracyM,
            @DecimalMin("0.0") @DecimalMax("300.0") Double speedKmh,
            Instant recordedAt) {
    }

    public record DelayForm(
            @NotNull @Min(1) @Max(600) Integer minutes,
            @NotBlank @Size(max = 300) String reason) {
    }
}
