package com.akshara.transport;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Where a bus is on its route, worked out from positions and the route's stops without maps or any outside service.
 * Pure functions, so the rules are easy to test and read.
 *
 * <p>Stops are taken in trip order (the morning trip runs them in stop order, the afternoon trip in reverse). A
 * position within {@link #AT_STOP_METERS} of a stop with coordinates means the bus has reached that stop; progress
 * only moves forward. When the bus reaches a stop, the next stop's families are told the bus is approaching.
 */
public final class RouteMath {

    /** A position this close to a stop (in metres) counts as being at the stop. */
    public static final double AT_STOP_METERS = 300;

    /** With no position for longer than this, the bus is shown as "no recent position". */
    public static final Duration STALE_AFTER = Duration.ofMinutes(5);

    /** Mean radius of the Earth in metres (IUGG). */
    private static final double EARTH_RADIUS_M = 6_371_008.8;

    /** A stop in trip order; {@code latitude} and {@code longitude} are null when the stop has no coordinates. */
    public record StopPoint(Double latitude, Double longitude) {

        boolean located() {
            return latitude != null && longitude != null;
        }
    }

    /** The stop nearest a position: its index in trip order and how far away it is. */
    public record Nearest(int index, double meters) {
    }

    private RouteMath() {
    }

    /** Great-circle distance in metres between two points (haversine). */
    public static double distanceMeters(double lat1, double lng1, double lat2, double lng2) {
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double dPhi = Math.toRadians(lat2 - lat1);
        double dLambda = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dPhi / 2) * Math.sin(dPhi / 2)
                + Math.cos(phi1) * Math.cos(phi2) * Math.sin(dLambda / 2) * Math.sin(dLambda / 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    /** The stop with coordinates nearest the position; empty when no stop has coordinates. */
    public static Optional<Nearest> nearest(List<StopPoint> stops, double latitude, double longitude) {
        Nearest best = null;
        for (int i = 0; i < stops.size(); i++) {
            StopPoint s = stops.get(i);
            if (!s.located()) {
                continue;
            }
            double d = distanceMeters(latitude, longitude, s.latitude(), s.longitude());
            if (best == null || d < best.meters()) {
                best = new Nearest(i, d);
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * The furthest stop reached (index in trip order, -1 for none yet) after a position: the nearest stop when the
     * position is within {@link #AT_STOP_METERS} of it and it is further along than {@code reached}; otherwise
     * {@code reached} unchanged. A bus never goes back to an earlier stop.
     */
    public static int advance(int reached, List<StopPoint> stops, double latitude, double longitude) {
        Optional<Nearest> nearest = nearest(stops, latitude, longitude);
        if (nearest.isPresent() && nearest.get().meters() <= AT_STOP_METERS && nearest.get().index() > reached) {
            return nearest.get().index();
        }
        return reached;
    }

    /**
     * The stop whose families should hear the bus is approaching after progress moved from {@code before} to
     * {@code after}: the stop following the newly reached one. Empty when progress did not move or the bus reached
     * the last stop. The first stop never gets this alert, as no stop comes before it.
     */
    public static OptionalInt approaching(int before, int after, int stopCount) {
        if (after <= before || after + 1 >= stopCount) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(after + 1);
    }

    /** The next stop after the furthest one reached (index in trip order); empty once the last stop is reached. */
    public static OptionalInt next(int reached, int stopCount) {
        int next = reached + 1;
        return next < stopCount ? OptionalInt.of(next) : OptionalInt.empty();
    }

    /** True when there is no position, or the latest one is older than {@link #STALE_AFTER}. */
    public static boolean stale(Instant lastPositionAt, Instant now) {
        return lastPositionAt == null || Duration.between(lastPositionAt, now).compareTo(STALE_AFTER) > 0;
    }

    /** Whole minutes since the position (never negative); null without a position. */
    public static Long minutesAgo(Instant lastPositionAt, Instant now) {
        if (lastPositionAt == null) {
            return null;
        }
        return Math.max(0, Duration.between(lastPositionAt, now).toMinutes());
    }

    /** The stops in the order a trip of this kind runs them: as given for MORNING, reversed for AFTERNOON. */
    public static <T> List<T> tripOrder(List<T> stopsInSeqOrder, TripKind kind) {
        List<T> list = new ArrayList<>(stopsInSeqOrder);
        if (kind == TripKind.AFTERNOON) {
            Collections.reverse(list);
        }
        return list;
    }
}
