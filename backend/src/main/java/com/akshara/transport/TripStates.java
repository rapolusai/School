package com.akshara.transport;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.akshara.transport.TransportPeople.StudentInfo;
import com.akshara.transport.TransportViews.DelayView;
import com.akshara.transport.TransportViews.PositionView;
import com.akshara.transport.TransportViews.StopRef;
import com.akshara.transport.TransportViews.TripCounts;

/**
 * Works out the state of a trip from what is stored: its stops in trip order, the students travelling that day (each
 * student's allocation that covers the day, on this route), their latest marks, and how far the bus has got.
 */
@Component
class TripStates {

    /** Everything about one trip that the trip, live and family views need. */
    record TripState(Trip trip, Route route, List<Stop> stops, int reached, Map<UUID, Allocation> riders,
            Map<UUID, StudentInfo> students, Map<UUID, TripEvent> marks, List<TripEvent> events) {

        /** Index in trip order of a stop, or -1. */
        int indexOf(UUID stopId) {
            for (int i = 0; i < stops.size(); i++) {
                if (stops.get(i).getId().equals(stopId)) {
                    return i;
                }
            }
            return -1;
        }

        Optional<Stop> next() {
            OptionalInt next = RouteMath.next(reached, stops.size());
            return next.isPresent() ? Optional.of(stops.get(next.getAsInt())) : Optional.empty();
        }

        Optional<Stop> reachedStop() {
            return reached >= 0 ? Optional.of(stops.get(reached)) : Optional.empty();
        }

        BoardingStatus mark(UUID studentId) {
            TripEvent e = marks.get(studentId);
            return e == null ? null : e.getKind().boarding();
        }

        /** Students allocated to the stop, by name. */
        List<StudentInfo> studentsAt(UUID stopId) {
            return riders.values().stream().filter(a -> stopId.equals(a.getStopId()))
                    .map(a -> students.get(a.getStudentId()))
                    .filter(s -> s != null)
                    .sorted(Comparator.comparing(StudentInfo::name, String.CASE_INSENSITIVE_ORDER))
                    .toList();
        }

        TripCounts counts() {
            int boarded = 0;
            int dropped = 0;
            int absent = 0;
            for (UUID id : riders.keySet()) {
                BoardingStatus s = mark(id);
                if (s == BoardingStatus.BOARDED) {
                    boarded++;
                } else if (s == BoardingStatus.DROPPED) {
                    dropped++;
                } else if (s == BoardingStatus.ABSENT) {
                    absent++;
                }
            }
            int total = riders.size();
            return new TripCounts(total, boarded, dropped, absent, total - boarded - dropped - absent);
        }

        DelayView delay() {
            return trip.getDelayMinutes() == null ? null
                    : new DelayView(trip.getDelayMinutes(), trip.getDelayReason(), trip.getDelayedAt());
        }

        PositionView position(Instant now) {
            if (trip.getLastPositionAt() == null) {
                return null;
            }
            List<RouteMath.StopPoint> points = stops.stream().map(Stop::point).toList();
            Optional<RouteMath.Nearest> nearest = RouteMath.nearest(points, trip.getLastLatitude().doubleValue(),
                    trip.getLastLongitude().doubleValue());
            StopRef nearestStop = nearest.map(n -> stops.get(n.index())).map(s -> new StopRef(s.getId(), s.getName()))
                    .orElse(null);
            return new PositionView(trip.getLastLatitude(), trip.getLastLongitude(), trip.getLastAccuracyM(),
                    trip.getLastPositionAt(), trip.getLastPositionSource(),
                    RouteMath.minutesAgo(trip.getLastPositionAt(), now),
                    RouteMath.stale(trip.getLastPositionAt(), now), nearestStop,
                    nearest.map(n -> Math.round(n.meters())).orElse(null));
        }
    }

    private final StopRepository stops;
    private final AllocationRepository allocations;
    private final TripEventRepository events;
    private final TransportPeople people;

    TripStates(StopRepository stops, AllocationRepository allocations, TripEventRepository events,
            TransportPeople people) {
        this.stops = stops;
        this.allocations = allocations;
        this.events = events;
        this.people = people;
    }

    TripState load(Trip trip, Route route) {
        List<Stop> ordered = RouteMath.tripOrder(stops.findByRouteIdOrderBySeq(route.getId()), trip.getKind());
        Map<UUID, Allocation> riders = riders(route.getId(), trip.getTripDate());
        List<TripEvent> log = events.ofTrip(trip.getId());
        Map<UUID, TripEvent> marks = new HashMap<>();
        for (TripEvent e : log) {
            if (e.getStudentId() != null) {
                marks.put(e.getStudentId(), e);
            }
        }
        Map<UUID, StudentInfo> students = people.students(riders.keySet());
        // Students who left the school stay in the log but no longer appear on the trip.
        Map<UUID, Allocation> active = new LinkedHashMap<>();
        riders.forEach((id, a) -> {
            StudentInfo s = students.get(id);
            if (s != null && s.active()) {
                active.put(id, a);
            }
        });
        int reached = -1;
        if (trip.getReachedStopId() != null) {
            for (int i = 0; i < ordered.size(); i++) {
                if (ordered.get(i).getId().equals(trip.getReachedStopId())) {
                    reached = i;
                }
            }
        }
        return new TripState(trip, route, ordered, reached, active, students, marks, log);
    }

    /**
     * The students travelling on the route on the day, with the allocation that puts them there: of each student's
     * allocations covering the day, the one that started last, if it is on this route and still has its stop.
     */
    Map<UUID, Allocation> riders(UUID routeId, LocalDate day) {
        Set<UUID> candidates = allocations.coveringOnRoute(routeId, day).stream().map(Allocation::getStudentId)
                .collect(Collectors.toSet());
        if (candidates.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<Allocation>> byStudent = allocations.ofStudents(candidates).stream()
                .collect(Collectors.groupingBy(Allocation::getStudentId));
        Map<UUID, Allocation> result = new LinkedHashMap<>();
        for (Map.Entry<UUID, List<Allocation>> e : byStudent.entrySet()) {
            TransportRules.covering(e.getValue(), Allocation::period, day)
                    .filter(a -> a.getRouteId().equals(routeId) && a.getStopId() != null)
                    .ifPresent(a -> result.put(e.getKey(), a));
        }
        return result;
    }

    /** The marks of every student on the trips, for listing. */
    static List<TripEvent> studentEvents(List<TripEvent> log) {
        List<TripEvent> list = new ArrayList<>();
        for (TripEvent e : log) {
            if (e.getStudentId() != null) {
                list.add(e);
            }
        }
        return list;
    }
}
