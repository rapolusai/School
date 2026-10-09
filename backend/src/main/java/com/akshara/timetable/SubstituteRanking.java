package com.akshara.timetable;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Orders the teachers who are free in a period as substitutes: teachers of the same subject first, then whoever has
 * the fewest periods that day (their own classes plus substitutions already given), then by name.
 */
final class SubstituteRanking {

    /** A free teacher. {@code periodsThatDay} counts their classes and substitutions on the day. */
    record Candidate(UUID teacherId, String name, boolean teachesSubject, int periodsThatDay) {
    }

    static final Comparator<Candidate> ORDER = Comparator.comparing((Candidate c) -> !c.teachesSubject())
            .thenComparingInt(Candidate::periodsThatDay)
            .thenComparing(c -> c.name() == null ? "" : c.name().toLowerCase())
            .thenComparing(Candidate::teacherId);

    private SubstituteRanking() {
    }

    static List<Candidate> rank(Collection<Candidate> candidates) {
        return candidates.stream().sorted(ORDER).toList();
    }
}
