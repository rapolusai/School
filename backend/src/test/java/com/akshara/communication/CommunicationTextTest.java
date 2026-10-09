package com.akshara.communication;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.akshara.communication.HolidayStarterList.Group;
import com.akshara.communication.HolidayStarterList.Holiday;

/** SMS parts, circular text clean-up, the iCalendar writer and the holiday starter list. */
class CommunicationTextTest {

    @Test
    void smsPartsFollowTheGsmAndUnicodeLimits() {
        assertThat(SmsParts.count("")).isZero();
        assertThat(SmsParts.count("a".repeat(160))).isEqualTo(1);
        assertThat(SmsParts.count("a".repeat(161))).isEqualTo(2);
        assertThat(SmsParts.count("a".repeat(306))).isEqualTo(2);
        assertThat(SmsParts.count("a".repeat(307))).isEqualTo(3);
        // Extension characters take two places.
        assertThat(SmsParts.count("{".repeat(80))).isEqualTo(1);
        assertThat(SmsParts.count("{".repeat(81))).isEqualTo(2);
        // Hindi is sent as UCS-2: 70 characters, then 67 per part.
        assertThat(SmsParts.isGsm("विद्यालय")).isFalse();
        assertThat(SmsParts.count("क".repeat(70))).isEqualTo(1);
        assertThat(SmsParts.count("क".repeat(71))).isEqualTo(2);
        assertThat(SmsParts.count("क".repeat(135))).isEqualTo(3);
        // One rupee sign turns the whole message into UCS-2.
        assertThat(SmsParts.count("Fees of ₹500 are due." + "a".repeat(60))).isEqualTo(2);
    }

    @Test
    void circularTextStaysPlain() {
        assertThat(CircularService.cleanTitle("  Sports\n day\t ")).isEqualTo("Sports day");
        assertThat(CircularService.cleanBody("Dear parents,\r\n\r\n\r\n\r\n\r\nSee you.\u0007 ")).isEqualTo(
                "Dear parents,\n\n\nSee you.");
        assertThat(CircularService.cleanBody("<b>Bold</b>")).isEqualTo("<b>Bold</b>");
        assertThat(CircularSender.summary("Line one.\nLine two.", 300)).isEqualTo("Line one. Line two.");
        String cut = CircularSender.summary("word ".repeat(100), 50);
        assertThat(cut).hasSizeLessThanOrEqualTo(50).endsWith("...").doesNotContain("\n");
    }

    @Test
    void iCalendarTextIsEscapedFoldedAndEndsInCrLf() {
        ZoneId india = ZoneId.of("Asia/Kolkata");
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
        String ics = IcsWriter.write("Demo, School", india, List.of(
                new IcsWriter.Event(id, "Exams; week, 1", "Line one\nLine two\\", "EXAM", LocalDate.of(2026, 12, 7),
                        LocalDate.of(2026, 12, 12), null, null, Instant.parse("2026-10-01T00:00:00Z")),
                new IcsWriter.Event(id, "PTM", "परीक्षा ".repeat(20), "PTM", LocalDate.of(2026, 10, 24),
                        LocalDate.of(2026, 10, 24), LocalTime.of(9, 0), null, null)),
                Instant.parse("2026-10-09T00:00:00Z"));
        assertThat(ics).contains("X-WR-CALNAME:Demo\\, School\r\n")
                .contains("SUMMARY:Exams\\; week\\, 1\r\n")
                .contains("DESCRIPTION:Line one\\nLine two\\\\\r\n")
                .contains("DTSTART;VALUE=DATE:20261207\r\nDTEND;VALUE=DATE:20261213\r\n")
                .contains("DTSTART:20261024T033000Z\r\nDTEND:20261024T043000Z\r\n")
                .contains("DTSTAMP:20261009T000000Z");
        for (String line : ics.split("\r\n")) {
            assertThat(line.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(75);
        }
        // Unfolding gives back the description, with no broken characters.
        String unfolded = ics.replace("\r\n ", "");
        assertThat(unfolded).contains("DESCRIPTION:" + "परीक्षा ".repeat(20).stripTrailing());
    }

    @Test
    void theStarterListOffersNationalHolidaysEveryYearAndFestivalsToConfirm() {
        List<Holiday> year = HolidayStarterList.between(LocalDate.of(2026, 4, 1), LocalDate.of(2027, 3, 31));
        assertThat(year).filteredOn(h -> h.group() == Group.NATIONAL).extracting(Holiday::date)
                .containsExactly(LocalDate.of(2026, 8, 15), LocalDate.of(2026, 10, 2), LocalDate.of(2027, 1, 26));
        assertThat(year).filteredOn(h -> h.group() == Group.FESTIVAL).allMatch(Holiday::needsConfirmation)
                .extracting(Holiday::title).contains("Diwali", "Holi", "Christmas", "Id-ul-Fitr");
        assertThat(year).noneMatch(h -> h.group() == Group.NATIONAL && h.needsConfirmation());
        assertThat(year).isSortedAccordingTo((a, b) -> a.date().compareTo(b.date()));
        // Other years get the national holidays only.
        assertThat(HolidayStarterList.between(LocalDate.of(2027, 4, 1), LocalDate.of(2028, 3, 31)))
                .extracting(Holiday::group).containsOnly(Group.NATIONAL).hasSize(3);
    }

    @Test
    void calendarKindsMapToCircularCategories() {
        assertThat(CalendarReminders.category(CommunicationTypes.EntryKind.HOLIDAY))
                .isEqualTo(CommunicationTypes.Category.HOLIDAY);
        assertThat(CalendarReminders.category(CommunicationTypes.EntryKind.EXAM))
                .isEqualTo(CommunicationTypes.Category.ACADEMIC);
        assertThat(CalendarReminders.category(CommunicationTypes.EntryKind.PTM))
                .isEqualTo(CommunicationTypes.Category.EVENT);
    }
}
