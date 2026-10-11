package com.akshara.transport;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.transport.TransportForms.CrewForm;
import com.akshara.transport.TransportForms.CrewUpdateForm;
import com.akshara.transport.TransportForms.FeeZoneForm;
import com.akshara.transport.TransportForms.SettingsForm;
import com.akshara.transport.TransportForms.VehicleForm;
import com.akshara.transport.TransportViews.CrewView;
import com.akshara.transport.TransportViews.DeviceTokenView;
import com.akshara.transport.TransportViews.FeeZoneView;
import com.akshara.transport.TransportViews.SettingsView;
import com.akshara.transport.TransportViews.StaffOption;
import com.akshara.transport.TransportViews.VehicleView;

/**
 * Transport setup: alert settings, fee zones, vehicles with their GPS device tokens, and drivers and attendants.
 * Reading needs transport.read; changes need transport.manage.
 */
@RestController
@RequestMapping("/api/transport")
public class TransportSetupController {

    private final TransportSetupService setup;

    TransportSetupController(TransportSetupService setup) {
        this.setup = setup;
    }

    @GetMapping("/settings")
    @PreAuthorize(TransportAuth.READ)
    public SettingsView settings() {
        return setup.settings();
    }

    @PutMapping("/settings")
    @PreAuthorize(TransportAuth.MANAGE)
    public SettingsView updateSettings(@Valid @RequestBody SettingsForm form) {
        return setup.updateSettings(form, TransportAuth.actor());
    }

    // ------------------------------------------------------------------ fee zones

    @GetMapping("/fee-zones")
    @PreAuthorize(TransportAuth.READ)
    public List<FeeZoneView> zones() {
        return setup.zones();
    }

    @PostMapping("/fee-zones")
    @PreAuthorize(TransportAuth.MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public FeeZoneView createZone(@Valid @RequestBody FeeZoneForm form) {
        return setup.createZone(form, TransportAuth.actor());
    }

    @PutMapping("/fee-zones/{id}")
    @PreAuthorize(TransportAuth.MANAGE)
    public FeeZoneView updateZone(@PathVariable UUID id, @Valid @RequestBody FeeZoneForm form) {
        return setup.updateZone(id, form, TransportAuth.actor());
    }

    @DeleteMapping("/fee-zones/{id}")
    @PreAuthorize(TransportAuth.MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteZone(@PathVariable UUID id) {
        setup.deleteZone(id, TransportAuth.actor());
    }

    // ------------------------------------------------------------------ vehicles

    @GetMapping("/vehicles")
    @PreAuthorize(TransportAuth.READ)
    public List<VehicleView> vehicles() {
        return setup.vehicles();
    }

    @GetMapping("/vehicles/{id}")
    @PreAuthorize(TransportAuth.READ)
    public VehicleView vehicle(@PathVariable UUID id) {
        return setup.vehicle(id);
    }

    @PostMapping("/vehicles")
    @PreAuthorize(TransportAuth.MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public VehicleView createVehicle(@Valid @RequestBody VehicleForm form) {
        return setup.createVehicle(form, TransportAuth.actor());
    }

    @PutMapping("/vehicles/{id}")
    @PreAuthorize(TransportAuth.MANAGE)
    public VehicleView updateVehicle(@PathVariable UUID id, @Valid @RequestBody VehicleForm form) {
        return setup.updateVehicle(id, form, TransportAuth.actor());
    }

    /** A new token for the vehicle's GPS device, shown once. */
    @PostMapping("/vehicles/{id}/device-token")
    @PreAuthorize(TransportAuth.MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public DeviceTokenView issueDeviceToken(@PathVariable UUID id) {
        return setup.issueDeviceToken(id, TransportAuth.actor());
    }

    @DeleteMapping("/vehicles/{id}/device-token")
    @PreAuthorize(TransportAuth.MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeDeviceToken(@PathVariable UUID id) {
        setup.revokeDeviceToken(id, TransportAuth.actor());
    }

    // ------------------------------------------------------------------ crew

    @GetMapping("/crew")
    @PreAuthorize(TransportAuth.READ)
    public List<CrewView> crew() {
        return setup.crew();
    }

    /** Active staff to add as drivers or attendants. */
    @GetMapping("/staff")
    @PreAuthorize(TransportAuth.MANAGE)
    public List<StaffOption> staff(@RequestParam(required = false) String q) {
        return setup.staffOptions(q);
    }

    @PostMapping("/crew")
    @PreAuthorize(TransportAuth.MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public CrewView addCrew(@Valid @RequestBody CrewForm form) {
        return setup.addCrew(form, TransportAuth.actor());
    }

    @PutMapping("/crew/{id}")
    @PreAuthorize(TransportAuth.MANAGE)
    public CrewView updateCrew(@PathVariable UUID id, @Valid @RequestBody CrewUpdateForm form) {
        return setup.updateCrew(id, form, TransportAuth.actor());
    }

    @DeleteMapping("/crew/{id}")
    @PreAuthorize(TransportAuth.MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeCrew(@PathVariable UUID id) {
        setup.removeCrew(id, TransportAuth.actor());
    }
}
