package com.akshara.transport;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.persistence.EntityManager;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.shared.ApiException;
import com.akshara.shared.Ids;
import com.akshara.shared.TenantContext;
import com.akshara.staff.StaffService.StaffRow;
import com.akshara.transport.TransportForms.CrewForm;
import com.akshara.transport.TransportForms.CrewUpdateForm;
import com.akshara.transport.TransportForms.FeeZoneForm;
import com.akshara.transport.TransportForms.SettingsForm;
import com.akshara.transport.TransportForms.VehicleForm;
import com.akshara.transport.TransportViews.CrewView;
import com.akshara.transport.TransportViews.DeviceTokenInfo;
import com.akshara.transport.TransportViews.DeviceTokenView;
import com.akshara.transport.TransportViews.DocumentView;
import com.akshara.transport.TransportViews.FeeZoneView;
import com.akshara.transport.TransportViews.RouteRef;
import com.akshara.transport.TransportViews.SettingsView;
import com.akshara.transport.TransportViews.StaffOption;
import com.akshara.transport.TransportViews.VehicleView;

/**
 * The transport setup: alert settings, fee zones, vehicles (with their document expiry dates and GPS device tokens)
 * and the drivers and attendants. Every change is audited.
 */
@Service
@Transactional
public class TransportSetupService {

    /** Where a GPS device posts its positions; {@code {vehicleId}} is the vehicle's id. */
    public static final String DEVICE_ENDPOINT = "/api/public/transport/vehicles/{vehicleId}/positions";
    public static final String DEVICE_HEADER = "X-Device-Token";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final TransportSettingsRepository settings;
    private final FeeZoneRepository zones;
    private final VehicleRepository vehicles;
    private final CrewMemberRepository crew;
    private final RouteRepository routes;
    private final StopRepository stops;
    private final AllocationRepository allocations;
    private final TransportPeople people;
    private final TransportCharges charges;
    private final AuditService audit;
    private final EntityManager entityManager;

    TransportSetupService(TransportSettingsRepository settings, FeeZoneRepository zones, VehicleRepository vehicles,
            CrewMemberRepository crew, RouteRepository routes, StopRepository stops, AllocationRepository allocations,
            TransportPeople people, TransportCharges charges, AuditService audit, EntityManager entityManager) {
        this.settings = settings;
        this.zones = zones;
        this.vehicles = vehicles;
        this.crew = crew;
        this.routes = routes;
        this.stops = stops;
        this.allocations = allocations;
        this.people = people;
        this.charges = charges;
        this.audit = audit;
        this.entityManager = entityManager;
    }

    // ------------------------------------------------------------------ settings

    @Transactional(readOnly = true)
    public SettingsView settings() {
        TenantContext.require();
        return settings.findFirstBy().map(TransportSetupService::view)
                .orElse(new SettingsView(TransportSettings.DEFAULT_BOARDED, TransportSettings.DEFAULT_DROPPED,
                        TransportSettings.DEFAULT_APPROACHING, TransportSettings.DEFAULT_DELAY));
    }

    public SettingsView updateSettings(SettingsForm form, Actor actor) {
        TransportSettings s = ensureSettings();
        s.update(form.boardedAlerts(), form.droppedAlerts(), form.approachingAlerts(), form.delayAlerts());
        settings.saveAndFlush(s);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("boardedAlerts", form.boardedAlerts());
        details.put("droppedAlerts", form.droppedAlerts());
        details.put("approachingAlerts", form.approachingAlerts());
        details.put("delayAlerts", form.delayAlerts());
        audit.record(actor, "transport_settings.updated", "transport_settings", s.getId(), details);
        return view(s);
    }

    private static SettingsView view(TransportSettings s) {
        return new SettingsView(s.isBoardedAlerts(), s.isDroppedAlerts(), s.isApproachingAlerts(), s.isDelayAlerts());
    }

    /** The settings row of the current school, made with the defaults on first use. */
    private TransportSettings ensureSettings() {
        UUID tenantId = TenantContext.require();
        entityManager.createNativeQuery("insert into transport.settings (id, tenant_id, boarded_alerts, "
                + "dropped_alerts, approaching_alerts, delay_alerts, created_at, updated_at, version) "
                + "values (?1, ?2, ?3, ?4, ?5, ?6, now(), now(), 0) on conflict (tenant_id) do nothing")
                .setParameter(1, Ids.newId())
                .setParameter(2, tenantId)
                .setParameter(3, TransportSettings.DEFAULT_BOARDED)
                .setParameter(4, TransportSettings.DEFAULT_DROPPED)
                .setParameter(5, TransportSettings.DEFAULT_APPROACHING)
                .setParameter(6, TransportSettings.DEFAULT_DELAY)
                .executeUpdate();
        return settings.findFirstBy().orElseThrow();
    }

    // ------------------------------------------------------------------ fee zones

    @Transactional(readOnly = true)
    public List<FeeZoneView> zones() {
        TenantContext.require();
        Map<UUID, Long> stopsPerZone = stops.findAll().stream().filter(s -> s.getFeeZoneId() != null)
                .collect(Collectors.groupingBy(Stop::getFeeZoneId, Collectors.counting()));
        return zones.ordered().stream().map(z -> view(z, stopsPerZone.getOrDefault(z.getId(), 0L))).toList();
    }

    public FeeZoneView createZone(FeeZoneForm form, Actor actor) {
        TenantContext.require();
        String name = form.name().trim();
        checkZone(form, name, null);
        FeeZone zone = zones.saveAndFlush(new FeeZone(name, form.minKm(), form.maxKm(), form.annualFeePaise()));
        audit.record(actor, "transport_zone.created", "transport_zone", zone.getId(), zoneDetails(zone));
        return view(zone, 0);
    }

    /** Changing the fee re-prices the unpaid bus dues of the students on the zone's stops. */
    public FeeZoneView updateZone(UUID id, FeeZoneForm form, Actor actor) {
        TenantContext.require();
        FeeZone zone = zones.findById(id).orElseThrow(() -> ApiException.notFound("Fee zone"));
        String name = form.name().trim();
        checkZone(form, name, id);
        long before = zone.getAnnualFeePaise();
        zone.update(name, form.minKm(), form.maxKm(), form.annualFeePaise());
        zones.saveAndFlush(zone);
        Map<String, Object> details = zoneDetails(zone);
        List<Stop> zoneStops = stops.findByFeeZoneId(id);
        if (before != zone.getAnnualFeePaise() && !zoneStops.isEmpty()) {
            Set<UUID> students = allocations.activeAtStops(zoneStops.stream().map(Stop::getId).toList()).stream()
                    .map(Allocation::getStudentId).collect(Collectors.toSet());
            charges.refresh(students);
            details.put("studentsRepriced", students.size());
        }
        audit.record(actor, "transport_zone.updated", "transport_zone", zone.getId(), details);
        return view(zone, zoneStops.size());
    }

    public void deleteZone(UUID id, Actor actor) {
        TenantContext.require();
        FeeZone zone = zones.findById(id).orElseThrow(() -> ApiException.notFound("Fee zone"));
        if (stops.existsByFeeZoneId(id)) {
            throw ApiException.conflict("Stops use the zone " + zone.getName()
                    + ". Move them to another zone first.", "id");
        }
        zones.delete(zone);
        zones.flush();
        audit.record(actor, "transport_zone.deleted", "transport_zone", id, Map.of("name", zone.getName()));
    }

    private void checkZone(FeeZoneForm form, String name, UUID id) {
        if (form.maxKm() != null && form.maxKm().compareTo(form.minKm()) <= 0) {
            throw ApiException.badRequest("The upper distance must be more than the lower one.", "maxKm");
        }
        if (zones.withName(name).stream().anyMatch(z -> !z.getId().equals(id))) {
            throw ApiException.conflict("A fee zone with this name already exists.", "name");
        }
    }

    private static Map<String, Object> zoneDetails(FeeZone z) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("name", z.getName());
        details.put("minKm", z.getMinKm());
        details.put("maxKm", z.getMaxKm());
        details.put("annualFeePaise", z.getAnnualFeePaise());
        return details;
    }

    private static FeeZoneView view(FeeZone z, long stopCount) {
        return new FeeZoneView(z.getId(), z.getName(), z.getMinKm(), z.getMaxKm(), z.getAnnualFeePaise(), stopCount);
    }

    // ------------------------------------------------------------------ vehicles

    @Transactional(readOnly = true)
    public List<VehicleView> vehicles() {
        TenantContext.require();
        Context c = context();
        return vehicles.ordered().stream().map(v -> view(v, c)).toList();
    }

    @Transactional(readOnly = true)
    public VehicleView vehicle(UUID id) {
        TenantContext.require();
        Vehicle v = vehicles.findById(id).orElseThrow(() -> ApiException.notFound("Vehicle"));
        return view(v, context());
    }

    public VehicleView createVehicle(VehicleForm form, Actor actor) {
        TenantContext.require();
        Vehicle.Details details = details(form);
        checkRegistration(details.registrationNo(), null);
        Vehicle v = vehicles.saveAndFlush(new Vehicle(details));
        audit.record(actor, "transport_vehicle.created", "transport_vehicle", v.getId(), vehicleDetails(v));
        return view(v, context());
    }

    public VehicleView updateVehicle(UUID id, VehicleForm form, Actor actor) {
        TenantContext.require();
        Vehicle v = vehicles.findById(id).orElseThrow(() -> ApiException.notFound("Vehicle"));
        Vehicle.Details details = details(form);
        checkRegistration(details.registrationNo(), id);
        if (details.status() == VehicleStatus.RETIRED && v.getStatus() != VehicleStatus.RETIRED) {
            List<Route> using = routes.activeWithVehicle(id);
            if (!using.isEmpty()) {
                throw ApiException.conflict("Route " + using.getFirst().getName()
                        + " uses this vehicle. Give the route another vehicle before retiring it.", "status");
            }
        }
        v.update(details);
        vehicles.saveAndFlush(v);
        audit.record(actor, "transport_vehicle.updated", "transport_vehicle", v.getId(), vehicleDetails(v));
        return view(v, context());
    }

    /**
     * Makes a new token for the vehicle's GPS device and returns it once; only its SHA-256 is kept. Any earlier token
     * stops working.
     */
    public DeviceTokenView issueDeviceToken(UUID id, Actor actor) {
        TenantContext.require();
        Vehicle v = vehicles.findById(id).orElseThrow(() -> ApiException.notFound("Vehicle"));
        if (v.getStatus() == VehicleStatus.RETIRED) {
            throw ApiException.conflict("A retired vehicle takes no positions.", "status");
        }
        byte[] random = new byte[32];
        RANDOM.nextBytes(random);
        String token = "akt_" + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        String hint = token.substring(token.length() - 4);
        Instant now = Instant.now();
        v.issueDeviceToken(sha256(token), hint, now);
        vehicles.saveAndFlush(v);
        audit.record(actor, "transport_vehicle.device_token_issued", "transport_vehicle", v.getId(),
                Map.of("registrationNo", v.getRegistrationNo(), "hint", hint));
        return new DeviceTokenView(v.getId(), token, hint, now,
                DEVICE_ENDPOINT.replace("{vehicleId}", v.getId().toString()), DEVICE_HEADER);
    }

    public void revokeDeviceToken(UUID id, Actor actor) {
        TenantContext.require();
        Vehicle v = vehicles.findById(id).orElseThrow(() -> ApiException.notFound("Vehicle"));
        if (!v.hasDeviceToken()) {
            return;
        }
        v.revokeDeviceToken();
        vehicles.saveAndFlush(v);
        audit.record(actor, "transport_vehicle.device_token_revoked", "transport_vehicle", v.getId(),
                Map.of("registrationNo", v.getRegistrationNo()));
    }

    static String sha256(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** "ts09 ub  4410" → "TS09 UB 4410". */
    static String normalizeRegistration(String value) {
        return value.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static Vehicle.Details details(VehicleForm f) {
        return new Vehicle.Details(normalizeRegistration(f.registrationNo()), f.type(), f.seats(),
                blankToNull(f.gpsDeviceId()), f.insuranceExpiry(), f.fitnessExpiry(), f.permitExpiry(),
                f.pollutionExpiry(), f.status() == null ? VehicleStatus.ACTIVE : f.status());
    }

    private void checkRegistration(String registrationNo, UUID id) {
        vehicles.findByRegistrationNo(registrationNo).filter(v -> !v.getId().equals(id)).ifPresent(v -> {
            throw ApiException.conflict("A vehicle with this registration number already exists.", "registrationNo");
        });
    }

    private static Map<String, Object> vehicleDetails(Vehicle v) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("registrationNo", v.getRegistrationNo());
        details.put("type", v.getVehicleType());
        details.put("seats", v.getSeats());
        details.put("status", v.getStatus());
        details.put("insuranceExpiry", v.getInsuranceExpiry());
        details.put("fitnessExpiry", v.getFitnessExpiry());
        details.put("permitExpiry", v.getPermitExpiry());
        details.put("pollutionExpiry", v.getPollutionExpiry());
        return details;
    }

    /** The routes and crew the vehicle and crew views refer to. */
    record Context(List<Route> routes, Map<UUID, CrewMember> crewById, LocalDate today) {
    }

    Context context() {
        return new Context(routes.ordered(), crew.findAll().stream()
                .collect(Collectors.toMap(CrewMember::getId, Function.identity())), TransportRules.today());
    }

    static List<DocumentView> documents(Vehicle v, LocalDate today) {
        List<DocumentView> list = new ArrayList<>();
        list.add(document(DocumentKind.INSURANCE, v.getInsuranceExpiry(), today));
        list.add(document(DocumentKind.FITNESS, v.getFitnessExpiry(), today));
        list.add(document(DocumentKind.PERMIT, v.getPermitExpiry(), today));
        list.add(document(DocumentKind.POLLUTION, v.getPollutionExpiry(), today));
        return list;
    }

    static DocumentView document(DocumentKind kind, LocalDate expiresOn, LocalDate today) {
        return new DocumentView(kind, expiresOn, TransportRules.documentState(expiresOn, today),
                TransportRules.daysLeft(expiresOn, today));
    }

    /** True when the crew member may not drive today: a driver whose licence is expired or missing. */
    static boolean licenceInvalid(CrewMember m, LocalDate today) {
        return m.getKind() == CrewKind.DRIVER
                && TransportRules.invalid(TransportRules.documentState(m.getLicenceExpiry(), today));
    }

    VehicleView view(Vehicle v, Context c) {
        List<DocumentView> documents = documents(v, c.today());
        List<Route> using = c.routes().stream().filter(r -> v.getId().equals(r.getVehicleId())).toList();
        List<String> flags = new ArrayList<>();
        if (v.getStatus() != VehicleStatus.RETIRED) {
            if (TransportRules.invalid(TransportRules.documentState(v.getFitnessExpiry(), c.today()))) {
                flags.add("FITNESS_INVALID");
            }
            for (Route r : using) {
                if (!r.isActive()) {
                    continue;
                }
                CrewMember driver = r.getDriverId() == null ? null : c.crewById().get(r.getDriverId());
                if (driver == null) {
                    addOnce(flags, "NO_DRIVER");
                } else if (licenceInvalid(driver, c.today())) {
                    addOnce(flags, "DRIVER_LICENCE_INVALID");
                }
            }
            if (documents.stream().anyMatch(d -> d.state() == DocumentState.EXPIRED)) {
                flags.add("DOCUMENT_EXPIRED");
            }
            if (documents.stream().anyMatch(d -> d.state() == DocumentState.EXPIRING)) {
                flags.add("DOCUMENT_EXPIRING");
            }
        }
        return new VehicleView(v.getId(), v.getRegistrationNo(), v.getVehicleType(), v.getSeats(), v.getGpsDeviceId(),
                v.getInsuranceExpiry(), v.getFitnessExpiry(), v.getPermitExpiry(), v.getPollutionExpiry(),
                v.getStatus(), documents, flags,
                v.hasDeviceToken() ? new DeviceTokenInfo(v.getDeviceTokenHint(), v.getDeviceTokenIssuedAt()) : null,
                using.stream().map(r -> new RouteRef(r.getId(), r.getName())).toList(), v.getLastPositionAt());
    }

    private static void addOnce(List<String> flags, String flag) {
        if (!flags.contains(flag)) {
            flags.add(flag);
        }
    }

    // ------------------------------------------------------------------ crew

    @Transactional(readOnly = true)
    public List<CrewView> crew() {
        TenantContext.require();
        Map<UUID, StaffRow> staff = people.staff();
        Context c = context();
        return crew.findAll().stream().map(m -> view(m, staff.get(m.getUserId()), c))
                .sorted((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(nullToEmpty(a.name()), nullToEmpty(b.name())))
                .toList();
    }

    /** Active staff for the "add a driver or attendant" picker; {@code crew} marks those already added. */
    @Transactional(readOnly = true)
    public List<StaffOption> staffOptions(String query) {
        TenantContext.require();
        Set<UUID> crewUsers = crew.findAll().stream().map(CrewMember::getUserId).collect(Collectors.toSet());
        return people.searchStaff(query, 20).stream()
                .map(r -> new StaffOption(r.userId(), r.name(), r.employeeCode(), r.designation(), r.roles(),
                        crewUsers.contains(r.userId())))
                .toList();
    }

    public CrewView addCrew(CrewForm form, Actor actor) {
        TenantContext.require();
        Map<UUID, StaffRow> staff = people.staff();
        StaffRow row = staff.get(form.userId());
        if (!TransportPeople.active(row)) {
            throw ApiException.badRequest("Pick a member of staff who works at the school.", "userId");
        }
        if (crew.existsByUserId(form.userId())) {
            throw ApiException.conflict(row.name() + " is already a driver or attendant.", "userId");
        }
        String licenceNo = licence(form.kind(), form.licenceNo(), form.licenceExpiry());
        CrewMember m = crew.saveAndFlush(new CrewMember(form.userId(), form.kind(), licenceNo, form.licenceExpiry(),
                form.policeVerifiedOn()));
        audit.record(actor, "transport_crew.added", "transport_crew", m.getId(), crewDetails(m, row));
        return view(m, row, context());
    }

    public CrewView updateCrew(UUID id, CrewUpdateForm form, Actor actor) {
        TenantContext.require();
        CrewMember m = crew.findById(id).orElseThrow(() -> ApiException.notFound("Crew member"));
        String licenceNo = licence(form.kind(), form.licenceNo(), form.licenceExpiry());
        if (form.kind() != CrewKind.DRIVER && m.getKind() == CrewKind.DRIVER) {
            routes.activeWithCrew(List.of(id)).stream().filter(r -> id.equals(r.getDriverId())).findFirst()
                    .ifPresent(r -> {
                        throw ApiException.conflict("They drive route " + r.getName()
                                + ". Give the route another driver first.", "kind");
                    });
        }
        m.update(form.kind(), licenceNo, form.licenceExpiry(), form.policeVerifiedOn());
        crew.saveAndFlush(m);
        StaffRow row = people.staff().get(m.getUserId());
        audit.record(actor, "transport_crew.updated", "transport_crew", m.getId(), crewDetails(m, row));
        return view(m, row, context());
    }

    public void removeCrew(UUID id, Actor actor) {
        TenantContext.require();
        CrewMember m = crew.findById(id).orElseThrow(() -> ApiException.notFound("Crew member"));
        List<Route> active = routes.activeWithCrew(List.of(id));
        if (!active.isEmpty()) {
            throw ApiException.conflict("They are on route " + active.getFirst().getName()
                    + ". Take them off the route first.", "id");
        }
        StaffRow row = people.staff().get(m.getUserId());
        // Inactive routes that still name them lose them (the database sets the link to null).
        crew.delete(m);
        crew.flush();
        audit.record(actor, "transport_crew.removed", "transport_crew", id, crewDetails(m, row));
    }

    private static String licence(CrewKind kind, String licenceNo, java.time.LocalDate expiry) {
        String no = blankToNull(licenceNo);
        no = no == null ? null : no.toUpperCase(Locale.ROOT);
        if (kind == CrewKind.DRIVER) {
            if (no == null) {
                throw ApiException.badRequest("Enter the driving licence number.", "licenceNo");
            }
            if (expiry == null) {
                throw ApiException.badRequest("Enter the date the licence expires.", "licenceExpiry");
            }
        }
        return no;
    }

    private static Map<String, Object> crewDetails(CrewMember m, StaffRow row) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("name", row == null ? null : row.name());
        details.put("userId", m.getUserId());
        details.put("kind", m.getKind());
        details.put("licenceExpiry", m.getLicenceExpiry());
        details.put("policeVerifiedOn", m.getPoliceVerifiedOn());
        return details;
    }

    CrewView view(CrewMember m, StaffRow row, Context c) {
        List<String> flags = new ArrayList<>();
        DocumentState licenceState = m.getKind() == CrewKind.DRIVER
                ? TransportRules.documentState(m.getLicenceExpiry(), c.today()) : null;
        if (licenceState != null && TransportRules.invalid(licenceState)) {
            flags.add("LICENCE_INVALID");
        } else if (licenceState == DocumentState.EXPIRING) {
            flags.add("LICENCE_EXPIRING");
        }
        if (m.getPoliceVerifiedOn() == null) {
            flags.add("POLICE_VERIFICATION_MISSING");
        }
        boolean active = TransportPeople.active(row);
        if (!active) {
            flags.add("LEFT");
        }
        List<RouteRef> on = c.routes().stream()
                .filter(r -> m.getId().equals(r.getDriverId()) || m.getId().equals(r.getAttendantId()))
                .map(r -> new RouteRef(r.getId(), r.getName())).toList();
        return new CrewView(m.getId(), m.getUserId(), row == null ? null : row.name(),
                row == null ? null : row.employeeCode(), row == null ? null : row.designation(),
                row == null ? null : row.mobile(), active, m.getKind(), m.getLicenceNo(), m.getLicenceExpiry(),
                licenceState, m.getKind() == CrewKind.DRIVER ? TransportRules.daysLeft(m.getLicenceExpiry(), c.today())
                        : null,
                m.getPoliceVerifiedOn(), on, flags);
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /** Names of crew members by crew id, for route views. */
    Map<UUID, String> crewNames(Map<UUID, CrewMember> crewById) {
        Map<UUID, StaffRow> staff = crewById.isEmpty() ? Map.of() : people.staff();
        Map<UUID, String> names = new HashMap<>();
        crewById.forEach((id, m) -> {
            StaffRow row = staff.get(m.getUserId());
            names.put(id, row == null ? null : row.name());
        });
        return names;
    }
}
