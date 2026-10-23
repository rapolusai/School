package com.akshara.govreport;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.akshara.govreport.GovReportTypes.AllotmentOutcome;
import com.akshara.govreport.Lottery.Candidate;
import com.akshara.govreport.Lottery.Draw;

/** The lottery is seeded, reproducible whatever the input order, and the waitlist follows the draw order. */
class LotteryTest {

    private static List<Candidate> candidates(int n) {
        List<Candidate> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(new Candidate(UUID.nameUUIDFromBytes(("c" + i).getBytes()), 1 + i % 3));
        }
        return list;
    }

    @Test
    void theSameSeedGivesTheSameOrderWhateverTheInputOrder() {
        List<Candidate> a = candidates(12);
        List<Candidate> b = new ArrayList<>(a);
        Collections.reverse(b);
        List<Draw> first = Lottery.draw(a, 4, 42L);
        List<Draw> second = Lottery.draw(b, 4, 42L);
        assertThat(first).isEqualTo(second);
        assertThat(Lottery.draw(a, 4, 43L)).isNotEqualTo(first);
    }

    @Test
    void seatsAreAllottedInOrderAndTheRestAreWaitlistedInOrder() {
        List<Draw> draws = Lottery.draw(candidates(10), 3, 7L);
        assertThat(draws).hasSize(10);
        assertThat(draws).extracting(Draw::position).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        assertThat(draws.subList(0, 3)).allMatch(d -> d.outcome() == AllotmentOutcome.ALLOTTED && d.waitlistNo() == null);
        assertThat(draws.subList(3, 10)).extracting(Draw::outcome).containsOnly(AllotmentOutcome.WAITLISTED);
        assertThat(draws.subList(3, 10)).extracting(Draw::waitlistNo).containsExactly(1, 2, 3, 4, 5, 6, 7);
        assertThat(draws).extracting(Draw::id).doesNotHaveDuplicates();
    }

    @Test
    void nearerDistanceBandsComeFirst() {
        List<Draw> draws = Lottery.draw(candidates(12), 2, 1L);
        List<Integer> bands = draws.stream().map(Draw::distanceBand).toList();
        assertThat(bands).isSorted();
        assertThat(draws.subList(0, 2)).extracting(Draw::distanceBand).containsOnly(1);
    }

    @Test
    void moreSeatsThanCandidatesAllotsEveryone() {
        List<Draw> draws = Lottery.draw(candidates(3), 10, 5L);
        assertThat(draws).extracting(Draw::outcome).containsOnly(AllotmentOutcome.ALLOTTED);
        assertThat(Lottery.draw(List.of(), 10, 5L)).isEmpty();
    }
}
