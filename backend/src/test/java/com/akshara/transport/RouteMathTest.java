package com.akshara.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.OptionalInt;

import org.junit.jupiter.api.Test;

import com.akshara.transport.RouteMath.StopPoint;

/** Where the bus is on its route: distances, the nearest stop, forward-only progress and stale positions. */
class RouteMathTest {

    /** Three stops about 1.1 km apart going north, and one in the middle without coordinates. */
    static final List<StopPoint> STOPS = List.of(
            new StopPoint(17.4100, 78.4400),
            new StopPoint(null, null),
            new StopPoint(17.4200, 78.4400),
            new StopPoint(17.4300, 78.4400));

    @Test
    void distancesAreGreatCircleMetres() {
        // A thousandth of a degree of latitude is about 111 metres anywhere.
        assertThat(RouteMath.distanceMeters(17.41, 78.44, 17.411, 78.44)).isCloseTo(111.2, within(0.5));
        assertThat(RouteMath.distanceMeters(17.41, 78.44, 17.41, 78.44)).isZero();
        // Hyderabad to Bengaluru is about 500 km as the crow flies.
        assertThat(RouteMath.distanceMeters(17.385, 78.4867, 12.9716, 77.5946) / 1000).isCloseTo(500, within(10.0));
    }

    @Test
    void theNearestStopIgnoresStopsWithoutCoordinates() {
        RouteMath.Nearest nearest = RouteMath.nearest(STOPS, 17.4195, 78.4401).orElseThrow();
        assertThat(nearest.index()).isEqualTo(2);
        assertThat(nearest.meters()).isLessThan(100);
        assertThat(RouteMath.nearest(List.of(new StopPoint(null, null)), 17.41, 78.44)).isEmpty();
        assertThat(RouteMath.nearest(List.of(), 17.41, 78.44)).isEmpty();
    }

    @Test
    void progressMovesForwardOnlyWithinThreeHundredMetres() {
        // Nowhere near a stop: nothing reached.
        assertThat(RouteMath.advance(-1, STOPS, 17.4150, 78.4400)).isEqualTo(-1);
        // About 110 m from the first stop.
        assertThat(RouteMath.advance(-1, STOPS, 17.4110, 78.4400)).isZero();
        // About 330 m from the third: too far yet.
        assertThat(RouteMath.advance(0, STOPS, 17.4170, 78.4400)).isZero();
        // About 220 m from it: reached (stop 1 has no coordinates and is passed over).
        assertThat(RouteMath.advance(0, STOPS, 17.4180, 78.4400)).isEqualTo(2);
        // Back near the first stop (a detour): the bus does not go back.
        assertThat(RouteMath.advance(2, STOPS, 17.4100, 78.4400)).isEqualTo(2);
        assertThat(RouteMath.advance(2, STOPS, 17.4300, 78.4400)).isEqualTo(3);
    }

    @Test
    void approachingIsTheStopAfterTheOneJustReached() {
        assertThat(RouteMath.approaching(-1, 0, 4)).isEqualTo(OptionalInt.of(1));
        assertThat(RouteMath.approaching(0, 2, 4)).isEqualTo(OptionalInt.of(3));
        // No progress, or the last stop reached: nobody to tell.
        assertThat(RouteMath.approaching(1, 1, 4)).isEmpty();
        assertThat(RouteMath.approaching(2, 3, 4)).isEmpty();
        assertThat(RouteMath.next(-1, 4)).isEqualTo(OptionalInt.of(0));
        assertThat(RouteMath.next(2, 4)).isEqualTo(OptionalInt.of(3));
        assertThat(RouteMath.next(3, 4)).isEmpty();
        assertThat(RouteMath.next(-1, 0)).isEmpty();
    }

    @Test
    void aPositionIsStaleAfterFiveMinutes() {
        Instant now = Instant.parse("2026-10-12T02:30:00Z");
        assertThat(RouteMath.stale(null, now)).isTrue();
        assertThat(RouteMath.stale(now.minus(Duration.ofMinutes(5)), now)).isFalse();
        assertThat(RouteMath.stale(now.minus(Duration.ofMinutes(5)).minusSeconds(1), now)).isTrue();
        assertThat(RouteMath.minutesAgo(null, now)).isNull();
        assertThat(RouteMath.minutesAgo(now.minusSeconds(150), now)).isEqualTo(2);
        // A phone clock slightly ahead never shows a negative age.
        assertThat(RouteMath.minutesAgo(now.plusSeconds(90), now)).isZero();
    }

    @Test
    void afternoonTripsRunTheStopsInReverse() {
        List<String> stops = List.of("Gate", "Market", "Temple");
        assertThat(RouteMath.tripOrder(stops, TripKind.MORNING)).containsExactly("Gate", "Market", "Temple");
        assertThat(RouteMath.tripOrder(stops, TripKind.AFTERNOON)).containsExactly("Temple", "Market", "Gate");
        assertThat(stops).containsExactly("Gate", "Market", "Temple");
    }
}
