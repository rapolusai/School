package com.akshara.govreport;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import com.akshara.govreport.GovReportTypes.AllotmentOutcome;

/**
 * The RTE lottery, as a pure function so it can be tested and reproduced: candidates are grouped by distance band
 * (the nearest band first, as the RTE neighbourhood rule asks), each band is shuffled with the seeded random
 * number generator (candidates ordered by id first, so the same seed always gives the same order whatever the
 * order of the input), and the first {@code seats} positions are allotted; the rest form the waitlist in order.
 */
final class Lottery {

    record Candidate(UUID id, int distanceBand) {
    }

    record Draw(UUID id, int position, AllotmentOutcome outcome, Integer waitlistNo, int distanceBand) {
    }

    private Lottery() {
    }

    static List<Draw> draw(List<Candidate> candidates, int seats, long seed) {
        Random random = new Random(seed);
        List<Draw> result = new ArrayList<>();
        int position = 0;
        for (int band = 1; band <= 4; band++) {
            int b = band;
            List<Candidate> inBand = new ArrayList<>(candidates.stream().filter(c -> c.distanceBand() == b)
                    .sorted(Comparator.comparing(c -> c.id().toString())).toList());
            Collections.shuffle(inBand, random);
            for (Candidate c : inBand) {
                position++;
                boolean allotted = position <= seats;
                result.add(new Draw(c.id(), position, allotted ? AllotmentOutcome.ALLOTTED : AllotmentOutcome.WAITLISTED,
                        allotted ? null : position - seats, c.distanceBand()));
            }
        }
        return result;
    }
}
