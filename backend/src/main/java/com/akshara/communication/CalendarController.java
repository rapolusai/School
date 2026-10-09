package com.akshara.communication;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.communication.CalendarService.EntryList;
import com.akshara.communication.CalendarService.EntryView;
import com.akshara.communication.CalendarService.Suggestions;
import com.akshara.communication.NoticeForms.BulkEntriesRequest;
import com.akshara.communication.NoticeForms.EntryRequest;

/**
 * The school calendar. Everyone with notices.read sees the entries meant for them (staff see all); calendar.manage
 * adds, changes and removes entries and reviews the holiday starter list.
 */
@RestController
@RequestMapping("/api")
public class CalendarController {

    static final String READ = "hasAuthority('notices.read')";
    static final String MANAGE = "hasAuthority('calendar.manage')";

    private final NoticeAccess access;
    private final CalendarService calendar;

    CalendarController(NoticeAccess access, CalendarService calendar) {
        this.access = access;
        this.calendar = calendar;
    }

    /** Entries between two dates (both included); the current academic year (or the given year) by default. */
    @GetMapping("/calendar/entries")
    @PreAuthorize(READ)
    public EntryList entries(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID yearId) {
        return calendar.entries(access.current(), from, to, yearId);
    }

    /** The next entries that have not ended, for the dashboard. */
    @GetMapping("/calendar/upcoming")
    @PreAuthorize(READ)
    public List<EntryView> upcoming(@RequestParam(defaultValue = "5") int limit) {
        return calendar.upcoming(access.current(), CalendarService.today(), limit);
    }

    @GetMapping("/calendar/entries/{id}")
    @PreAuthorize(READ)
    public EntryView entry(@PathVariable UUID id) {
        return calendar.entry(access.current(), id);
    }

    @PostMapping("/calendar/entries")
    @PreAuthorize(MANAGE)
    public ResponseEntity<EntryView> create(@Valid @RequestBody EntryRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(calendar.create(access.current(), request));
    }

    /** Adds several entries at once (all or none), for example holidays picked from the starter list. */
    @PostMapping("/calendar/entries/bulk")
    @PreAuthorize(MANAGE)
    public ResponseEntity<List<EntryView>> createAll(@Valid @RequestBody BulkEntriesRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(calendar.createAll(access.current(),
                request.entries()));
    }

    @PutMapping("/calendar/entries/{id}")
    @PreAuthorize(MANAGE)
    public EntryView update(@PathVariable UUID id, @Valid @RequestBody EntryRequest request) {
        return calendar.update(access.current(), id, request);
    }

    @DeleteMapping("/calendar/entries/{id}")
    @PreAuthorize(MANAGE)
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        calendar.delete(access.current(), id);
        return ResponseEntity.noContent().build();
    }

    /** Common Indian holidays for the academic year, for the admin to review and add. Never added on their own. */
    @GetMapping("/calendar/holiday-suggestions")
    @PreAuthorize(MANAGE)
    public Suggestions holidaySuggestions(@RequestParam(required = false) UUID yearId) {
        return calendar.suggestions(yearId);
    }

    /** The caller's calendar as an iCalendar file, to import into a phone or computer calendar. */
    @GetMapping(value = "/calendar.ics", produces = "text/calendar")
    @PreAuthorize(READ)
    public ResponseEntity<String> ics() {
        String body = calendar.ics(access.current(), CalendarService.today(), Instant.now());
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "calendar", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("school-calendar.ics")
                        .build().toString())
                .body(body);
    }
}
