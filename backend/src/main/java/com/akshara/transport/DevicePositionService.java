package com.akshara.transport;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;
import com.akshara.transport.TransportForms.PositionForm;
import com.akshara.transport.TransportViews.DeviceAck;

/**
 * Positions from a vehicle's GPS device. The device signs in with nothing but its token, so no school is selected:
 * the database says which school the vehicle belongs to when the token's hash matches (a function that works without a
 * school), and the position is then recorded as that school, in its own transaction. Wrong tokens are counted per IP
 * and refused for a while after too many; each vehicle may post a few positions a minute.
 */
@Service
public class DevicePositionService {

    private final VehicleRepository vehicles;
    private final TripService trips;
    private final DeviceRateLimiter limiter;
    private final TransactionTemplate tx;

    DevicePositionService(VehicleRepository vehicles, TripService trips, DeviceRateLimiter limiter,
            PlatformTransactionManager transactions) {
        this.vehicles = vehicles;
        this.trips = trips;
        this.limiter = limiter;
        this.tx = new TransactionTemplate(transactions);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public DeviceAck post(UUID vehicleId, String token, PositionForm form, String ip) {
        limiter.checkFailures(ip);
        if (token == null || token.isBlank() || token.length() > 200) {
            limiter.recordFailure(ip);
            throw ApiException.unauthorized("Send the vehicle's device token in the X-Device-Token header.");
        }
        String hash = TransportSetupService.sha256(token.trim());
        Optional<UUID> school = TenantContext.runAs(null, () -> tx.execute(s -> vehicles.deviceTenant(vehicleId,
                hash)));
        if (school == null || school.isEmpty()) {
            limiter.recordFailure(ip);
            throw ApiException.unauthorized("This device token is not valid for the vehicle.");
        }
        limiter.takePosition(vehicleId);
        return TenantContext.runAs(school.get(), () -> tx.execute(s -> trips.devicePosition(vehicleId, form)));
    }
}
