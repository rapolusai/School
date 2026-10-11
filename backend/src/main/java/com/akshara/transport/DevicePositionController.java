package com.akshara.transport;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.transport.TransportForms.PositionForm;
import com.akshara.transport.TransportViews.DeviceAck;

/**
 * Where a vehicle's GPS device posts its positions. No sign-in: the device sends its vehicle's token in the
 * X-Device-Token header (issued by the school on the vehicle's page). Rate limited per vehicle and, for wrong tokens,
 * per client IP. See docs/api/phase-2-transport.md.
 */
@RestController
public class DevicePositionController {

    private final DevicePositionService devices;

    DevicePositionController(DevicePositionService devices) {
        this.devices = devices;
    }

    @PostMapping("/api/public/transport/vehicles/{vehicleId}/positions")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public DeviceAck post(@PathVariable UUID vehicleId,
            @RequestHeader(name = TransportSetupService.DEVICE_HEADER, required = false) String token,
            @Valid @RequestBody PositionForm form, HttpServletRequest http) {
        return devices.post(vehicleId, token, form, http.getRemoteAddr());
    }
}
