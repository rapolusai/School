package com.akshara.transport;

/** An entry in a trip's boarding log: a student's mark, or a delay the attendant reported. */
public enum TripEventKind {
    BOARDED, DROPPED, ABSENT, DELAYED;

    static TripEventKind of(BoardingStatus status) {
        return valueOf(status.name());
    }

    /** The student's mark this event records; null for a delay. */
    BoardingStatus boarding() {
        return this == DELAYED ? null : BoardingStatus.valueOf(name());
    }
}
