package com.akshara.chat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.chat.PtmForms.BlockForm;
import com.akshara.chat.PtmForms.BookForm;
import com.akshara.chat.PtmForms.CancelEventForm;
import com.akshara.chat.PtmForms.EventForm;
import com.akshara.chat.PtmForms.HoursForm;
import com.akshara.chat.PtmForms.MarkForm;
import com.akshara.chat.PtmForms.PtmSettingsForm;
import com.akshara.chat.PtmViews.AttendanceSummary;
import com.akshara.chat.PtmViews.BookingView;
import com.akshara.chat.PtmViews.EventDetail;
import com.akshara.chat.PtmViews.EventSummary;
import com.akshara.chat.PtmViews.PtmSettings;
import com.akshara.chat.PtmViews.SlotView;
import com.akshara.chat.PtmViews.TeacherSchedule;

/**
 * Parent-teacher meetings for leadership (ptm.manage): meetings, teachers' hours and slots, bookings for families,
 * attendance, reminders and the attendance summary. docs/api/phase-2-chat.md.
 */
@RestController
@RequestMapping("/api/ptm")
public class PtmController {

    static final String MANAGE = "hasAuthority('ptm.manage')";

    private final PtmService ptm;
    private final PtmBookingService bookings;
    private final PtmReports reports;

    PtmController(PtmService ptm, PtmBookingService bookings, PtmReports reports) {
        this.ptm = ptm;
        this.bookings = bookings;
        this.reports = reports;
    }

    @GetMapping("/events")
    @PreAuthorize(MANAGE)
    public List<EventSummary> list() {
        return ptm.list(ChatRules.today());
    }

    @PostMapping("/events")
    @PreAuthorize(MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public EventDetail create(@Valid @RequestBody EventForm form) {
        return ptm.create(form, ChatAccess.actor(), Instant.now(), ChatRules.today());
    }

    @GetMapping("/events/{id}")
    @PreAuthorize(MANAGE)
    public EventDetail detail(@PathVariable UUID id) {
        return ptm.detail(id, ChatRules.today());
    }

    @PutMapping("/events/{id}")
    @PreAuthorize(MANAGE)
    public EventDetail update(@PathVariable UUID id, @Valid @RequestBody EventForm form) {
        return ptm.update(id, form, ChatAccess.actor(), Instant.now(), ChatRules.today());
    }

    @PostMapping("/events/{id}/cancel")
    @PreAuthorize(MANAGE)
    public EventDetail cancel(@PathVariable UUID id, @Valid @RequestBody CancelEventForm form) {
        return ptm.cancel(id, form.reason(), ChatAccess.actor(), Instant.now(), ChatRules.today());
    }

    @PutMapping("/events/{id}/teachers/{teacherId}/hours")
    @PreAuthorize(MANAGE)
    public TeacherSchedule hours(@PathVariable UUID id, @PathVariable UUID teacherId,
            @Valid @RequestBody HoursForm form) {
        return ptm.setHours(id, teacherId, form, ChatAccess.actor(), Instant.now(), ChatRules.today());
    }

    @PutMapping("/slots/{id}/block")
    @PreAuthorize(MANAGE)
    public SlotView block(@PathVariable UUID id, @Valid @RequestBody BlockForm form) {
        return ptm.block(id, form.blocked(), null, ChatAccess.actor(), ChatRules.today());
    }

    /** Books a slot for a family (for parents without the app, or after the parents' cut-off). */
    @PostMapping("/events/{id}/bookings")
    @PreAuthorize(MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public BookingView book(@PathVariable UUID id, @Valid @RequestBody BookForm form) {
        return bookings.bookForFamily(id, form.slotId(), form.studentId(), ChatAccess.actor(), Instant.now(),
                ChatRules.today());
    }

    @DeleteMapping("/bookings/{id}")
    @PreAuthorize(MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelBooking(@PathVariable UUID id) {
        bookings.cancelBySchool(id, ChatAccess.actor(), Instant.now());
    }

    @PutMapping("/bookings/{id}")
    @PreAuthorize(MANAGE)
    public BookingView mark(@PathVariable UUID id, @Valid @RequestBody MarkForm form) {
        return bookings.mark(id, form, null, ChatAccess.actor(), Instant.now(), ChatRules.today());
    }

    @GetMapping("/settings")
    @PreAuthorize(MANAGE)
    public PtmSettings settings() {
        return ptm.ptmSettings();
    }

    @PutMapping("/settings")
    @PreAuthorize(MANAGE)
    public PtmSettings updateSettings(@Valid @RequestBody PtmSettingsForm form) {
        return ptm.updatePtmSettings(form.remindersEnabled(), ChatAccess.actor(), Instant.now(), ChatRules.today());
    }

    @GetMapping("/reports/attendance")
    @PreAuthorize(MANAGE)
    public AttendanceSummary attendance(@RequestParam UUID eventId) {
        return reports.attendance(eventId, ChatRules.today());
    }

    @GetMapping("/reports/attendance.csv")
    @PreAuthorize(MANAGE)
    public ResponseEntity<byte[]> attendanceCsv(@RequestParam UUID eventId) {
        return PtmReports.download(reports.attendanceCsv(eventId, ChatRules.today()), "ptm-attendance.csv");
    }
}
