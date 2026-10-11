package com.akshara.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.akshara.chat.PtmSlots.Range;

class PtmSlotsTest {

    private static LocalTime t(String hhmm) {
        return LocalTime.parse(hhmm);
    }

    private static Range r(String from, String to) {
        return new Range(t(from), t(to));
    }

    @Test
    void makesBackToBackSlotsUpToTheEnd() {
        assertThat(PtmSlots.generate(t("09:00"), t("10:00"), 15, List.of()))
                .containsExactly(r("09:00", "09:15"), r("09:15", "09:30"), r("09:30", "09:45"), r("09:45", "10:00"));
    }

    @Test
    void aSlotNeverRunsPastTheEnd() {
        assertThat(PtmSlots.generate(t("09:00"), t("09:50"), 20, List.of()))
                .containsExactly(r("09:00", "09:20"), r("09:20", "09:40"));
    }

    @Test
    void skipsBreaksAndStartsAgainWhenTheyEnd() {
        assertThat(PtmSlots.generate(t("09:00"), t("11:00"), 20, List.of(r("09:50", "10:10"))))
                .containsExactly(r("09:00", "09:20"), r("09:20", "09:40"), r("10:10", "10:30"), r("10:30", "10:50"));
    }

    @Test
    void aSlotThatWouldRunIntoABreakStartsAfterIt() {
        // 09:30-09:45 would overlap the 09:40 break, so the next slot starts at 10:00.
        assertThat(PtmSlots.generate(t("09:00"), t("10:30"), 15, List.of(r("09:40", "10:00"))))
                .containsExactly(r("09:00", "09:15"), r("09:15", "09:30"), r("10:00", "10:15"), r("10:15", "10:30"));
    }

    @Test
    void nothingWhenNoSlotFitsOrTheHoursAreWrong() {
        assertThat(PtmSlots.generate(t("09:00"), t("09:10"), 15, List.of())).isEmpty();
        assertThat(PtmSlots.generate(t("10:00"), t("09:00"), 15, List.of())).isEmpty();
        assertThat(PtmSlots.generate(t("09:00"), t("10:00"), 15, List.of(r("09:00", "10:00")))).isEmpty();
    }

    @Test
    void neverWrapsPastMidnight() {
        assertThat(PtmSlots.generate(t("23:00"), t("23:59"), 30, List.of()))
                .containsExactly(r("23:00", "23:30"));
    }

    @Test
    void readsAndWritesBreaks() {
        List<Range> breaks = PtmSlots.parseBreaks("12:00-12:30, 10:30-10:45");
        assertThat(breaks).containsExactly(r("12:00", "12:30"), r("10:30", "10:45"));
        assertThat(PtmSlots.formatBreaks(breaks)).isEqualTo("10:30-10:45,12:00-12:30");
        assertThat(PtmSlots.parseBreaks("")).isEmpty();
        assertThat(PtmSlots.parseBreaks(null)).isEmpty();
        assertThatThrownBy(() -> PtmSlots.parseBreaks("10:30")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PtmSlots.parseBreaks("10:30-ten")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PtmSlots.parseBreaks("10:45-10:30")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void findsProblemsWithBreaks() {
        assertThat(PtmSlots.problem(t("09:00"), t("12:00"), List.of(r("10:00", "10:15")))).isNull();
        assertThat(PtmSlots.problem(t("09:00"), t("12:00"), List.of(r("08:30", "09:15"))))
                .contains("inside the meeting hours");
        assertThat(PtmSlots.problem(t("09:00"), t("12:00"), List.of(r("10:00", "10:30"), r("10:15", "10:45"))))
                .contains("overlap");
        List<Range> six = List.of(r("09:00", "09:05"), r("09:10", "09:15"), r("09:20", "09:25"), r("09:30", "09:35"),
                r("09:40", "09:45"), r("09:50", "09:55"));
        assertThat(PtmSlots.problem(t("09:00"), t("12:00"), six)).contains("at most 5");
    }

    @Test
    void stopsAtTheMostSlotsATeacherCanHave() {
        assertThat(PtmSlots.generate(t("00:00"), t("23:59"), 5, List.of())).hasSize(PtmSlots.MAX_SLOTS);
    }
}
