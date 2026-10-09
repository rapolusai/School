package com.akshara.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

import org.junit.jupiter.api.Test;

class QuietHoursTest {

    static final LocalDate DAY = LocalDate.of(2026, 10, 9);
    static final LocalTime NINE_PM = LocalTime.of(21, 0);
    static final LocalTime SEVEN_AM = LocalTime.of(7, 0);

    @Test
    void anOvernightWindowHoldsUntilTheMorning() {
        assertThat(QuietHours.holdUntil(at(DAY, 22, 30), NINE_PM, SEVEN_AM)).contains(at(DAY.plusDays(1), 7, 0));
        assertThat(QuietHours.holdUntil(at(DAY, 21, 0), NINE_PM, SEVEN_AM)).contains(at(DAY.plusDays(1), 7, 0));
        assertThat(QuietHours.holdUntil(at(DAY, 2, 15), NINE_PM, SEVEN_AM)).contains(at(DAY, 7, 0));
        assertThat(QuietHours.holdUntil(at(DAY, 7, 0), NINE_PM, SEVEN_AM)).isEmpty();
        assertThat(QuietHours.holdUntil(at(DAY, 12, 0), NINE_PM, SEVEN_AM)).isEmpty();
        assertThat(QuietHours.holdUntil(at(DAY, 20, 59), NINE_PM, SEVEN_AM)).isEmpty();
    }

    @Test
    void aDaytimeWindowWorksToo() {
        LocalTime one = LocalTime.of(13, 0);
        LocalTime two = LocalTime.of(14, 0);
        assertThat(QuietHours.holdUntil(at(DAY, 13, 30), one, two)).contains(at(DAY, 14, 0));
        assertThat(QuietHours.holdUntil(at(DAY, 14, 0), one, two)).isEmpty();
        assertThat(QuietHours.holdUntil(at(DAY, 12, 59), one, two)).isEmpty();
    }

    @Test
    void timesAreIndianWhateverTheServerZone() {
        // 16:00 UTC is 21:30 in India.
        assertThat(QuietHours.holdUntil(Instant.parse("2026-10-09T16:00:00Z"), NINE_PM, SEVEN_AM))
                .contains(Instant.parse("2026-10-10T01:30:00Z"));
    }

    @Test
    void switchedOffOrEmptyWindowsNeverHold() {
        MessageSettings off = new MessageSettings(true, AlertChannel.SMS, "en", false, NINE_PM, SEVEN_AM);
        assertThat(QuietHours.holdUntil(at(DAY, 23, 0), off)).isEmpty();
        assertThat(QuietHours.holdUntil(at(DAY, 23, 0), MessageSettings.DEFAULTS)).isPresent();
        assertThat(QuietHours.holdUntil(at(DAY, 23, 0), SEVEN_AM, SEVEN_AM)).isEmpty();
    }

    static Instant at(LocalDate day, int hour, int minute) {
        return day.atTime(hour, minute).atZone(QuietHours.INDIA).toInstant();
    }
}
