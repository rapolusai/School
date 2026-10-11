package com.akshara.transport;

import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.shared.CurrentUser;
import com.akshara.transport.TransportForms.DelayForm;
import com.akshara.transport.TransportForms.MarkForm;
import com.akshara.transport.TransportForms.PositionForm;
import com.akshara.transport.TransportForms.StartTripForm;
import com.akshara.transport.TransportViews.LiveBoard;
import com.akshara.transport.TransportViews.MyTrips;
import com.akshara.transport.TransportViews.TripView;

/**
 * Trips, run from the attendant's phone (transport.trip; a route's own crew, or anyone with transport.manage), and the
 * live board of today's trips (transport.read).
 */
@RestController
@RequestMapping("/api/transport")
public class TransportTripController {

    private final TripService trips;
    private final DeviceRateLimiter limiter;

    TransportTripController(TripService trips, DeviceRateLimiter limiter) {
        this.trips = trips;
        this.limiter = limiter;
    }

    @GetMapping("/my-trips")
    @PreAuthorize(TransportAuth.TRIP)
    public MyTrips myTrips() {
        return trips.myTrips(CurrentUser.requireId(), TransportAuth.canManage());
    }

    /** Today's trip of the route, or 204 when it has not been started. */
    @GetMapping("/routes/{routeId}/trips/today")
    @PreAuthorize(TransportAuth.TRIP)
    public ResponseEntity<TripView> today(@PathVariable UUID routeId, @RequestParam TripKind kind) {
        return trips.today(routeId, kind, CurrentUser.requireId(), TransportAuth.canManage())
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.noContent().build());
    }

    @PostMapping("/routes/{routeId}/trips")
    @PreAuthorize(TransportAuth.TRIP)
    @ResponseStatus(HttpStatus.CREATED)
    public TripView start(@PathVariable UUID routeId, @Valid @RequestBody StartTripForm form) {
        return trips.start(routeId, form.kind(), TransportAuth.actor(), TransportAuth.canManage());
    }

    @GetMapping("/trips/{id}")
    @PreAuthorize(TransportAuth.TRIP)
    public TripView trip(@PathVariable UUID id) {
        return trips.trip(id, CurrentUser.requireId(), TransportAuth.canManage());
    }

    @PutMapping("/trips/{id}/students/{studentId}")
    @PreAuthorize(TransportAuth.TRIP)
    public TripView mark(@PathVariable UUID id, @PathVariable UUID studentId, @Valid @RequestBody MarkForm form) {
        return trips.mark(id, studentId, form.status(), TransportAuth.actor(), TransportAuth.canManage());
    }

    /** The phone's position, sent every 30 seconds while the trip screen is open. */
    @PostMapping("/trips/{id}/positions")
    @PreAuthorize(TransportAuth.TRIP)
    public TripView position(@PathVariable UUID id, @Valid @RequestBody PositionForm form) {
        limiter.takePosition(id);
        return trips.position(id, form, TransportAuth.actor(), TransportAuth.canManage());
    }

    @PostMapping("/trips/{id}/delay")
    @PreAuthorize(TransportAuth.TRIP)
    public TripView delay(@PathVariable UUID id, @Valid @RequestBody DelayForm form) {
        return trips.delay(id, form, TransportAuth.actor(), TransportAuth.canManage());
    }

    @PostMapping("/trips/{id}/end")
    @PreAuthorize(TransportAuth.TRIP)
    public TripView end(@PathVariable UUID id) {
        return trips.end(id, TransportAuth.actor(), TransportAuth.canManage());
    }

    @GetMapping("/live")
    @PreAuthorize(TransportAuth.READ)
    public LiveBoard live() {
        return trips.live();
    }
}
