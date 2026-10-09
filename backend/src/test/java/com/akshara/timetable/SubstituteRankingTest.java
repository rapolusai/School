package com.akshara.timetable;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.akshara.timetable.SubstituteRanking.Candidate;

class SubstituteRankingTest {

    @Test
    void sameSubjectTeachersComeFirstThenTheLeastBusyThenByName() {
        Candidate busyMathsTeacher = new Candidate(UUID.randomUUID(), "Ravi Kumar", true, 6);
        Candidate freeMathsTeacher = new Candidate(UUID.randomUUID(), "Pooja Desai", true, 2);
        Candidate idleArtTeacher = new Candidate(UUID.randomUUID(), "Deepa Nair", false, 0);
        Candidate idleMusicTeacher = new Candidate(UUID.randomUUID(), "Anil Rao", false, 0);
        Candidate busyEnglishTeacher = new Candidate(UUID.randomUUID(), "Kavitha Menon", false, 5);

        List<Candidate> ranked = SubstituteRanking.rank(List.of(idleArtTeacher, busyEnglishTeacher,
                busyMathsTeacher, idleMusicTeacher, freeMathsTeacher));

        assertThat(ranked).containsExactly(freeMathsTeacher, busyMathsTeacher, idleMusicTeacher, idleArtTeacher,
                busyEnglishTeacher);
    }

    @Test
    void nobodyFreeMeansNoSuggestions() {
        assertThat(SubstituteRanking.rank(List.of())).isEmpty();
    }
}
