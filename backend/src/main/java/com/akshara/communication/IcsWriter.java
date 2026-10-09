package com.akshara.communication;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * Writes an iCalendar (RFC 5545) file: all-day entries as dates (the end date is the day after, as the standard
 * says), timed entries in UTC, text escaped, lines folded at 75 octets and ended with CRLF.
 */
final class IcsWriter {

    record Event(UUID id, String title, String description, String category, LocalDate startsOn, LocalDate endsOn,
            LocalTime startTime, LocalTime endTime, Instant updatedAt) {
    }

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter UTC = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC);

    private IcsWriter() {
    }

    static String write(String calendarName, ZoneId zone, List<Event> events, Instant now) {
        StringBuilder out = new StringBuilder();
        line(out, "BEGIN:VCALENDAR");
        line(out, "VERSION:2.0");
        line(out, "PRODID:-//Akshara//School calendar//EN");
        line(out, "CALSCALE:GREGORIAN");
        line(out, "METHOD:PUBLISH");
        line(out, "X-WR-CALNAME:" + text(calendarName));
        line(out, "X-WR-TIMEZONE:" + zone.getId());
        for (Event e : events) {
            line(out, "BEGIN:VEVENT");
            line(out, "UID:" + e.id() + "@akshara");
            line(out, "DTSTAMP:" + UTC.format(e.updatedAt() != null ? e.updatedAt() : now));
            if (e.startTime() == null) {
                line(out, "DTSTART;VALUE=DATE:" + DATE.format(e.startsOn()));
                line(out, "DTEND;VALUE=DATE:" + DATE.format(e.endsOn().plusDays(1)));
                line(out, "TRANSP:TRANSPARENT");
            } else {
                Instant start = e.startsOn().atTime(e.startTime()).atZone(zone).toInstant();
                Instant end = e.endTime() != null ? e.endsOn().atTime(e.endTime()).atZone(zone).toInstant()
                        : e.endsOn().atTime(e.startTime()).atZone(zone).toInstant().plusSeconds(3600);
                line(out, "DTSTART:" + UTC.format(start));
                line(out, "DTEND:" + UTC.format(end));
            }
            line(out, "SUMMARY:" + text(e.title()));
            if (e.description() != null && !e.description().isBlank()) {
                line(out, "DESCRIPTION:" + text(e.description()));
            }
            if (e.category() != null) {
                line(out, "CATEGORIES:" + text(e.category()));
            }
            line(out, "END:VEVENT");
        }
        line(out, "END:VCALENDAR");
        return out.toString();
    }

    /** Escapes a TEXT value: backslash, semicolon, comma and line breaks. */
    static String text(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,")
                .replace("\r\n", "\\n").replace("\n", "\\n").replace("\r", "\\n")
                .replaceAll("\\p{Cntrl}", "");
    }

    /** Appends a content line folded at 75 octets (never inside a UTF-8 character), ending in CRLF. */
    static void line(StringBuilder out, String content) {
        int octets = 0;
        int limit = 75;
        for (int i = 0; i < content.length(); ) {
            int cp = content.codePointAt(i);
            int size = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8).length;
            if (octets + size > limit) {
                out.append("\r\n ");
                octets = 1;
                limit = 75;
            }
            out.appendCodePoint(cp);
            octets += size;
            i += Character.charCount(cp);
        }
        out.append("\r\n");
    }
}
