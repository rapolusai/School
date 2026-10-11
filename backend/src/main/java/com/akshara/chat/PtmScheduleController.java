package com.akshara.chat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.chat.PtmForms.BlockForm;
import com.akshara.chat.PtmForms.HoursForm;
import com.akshara.chat.PtmForms.MarkForm;
import com.akshara.chat.PtmViews.BookingView;
import com.akshara.chat.PtmViews.MyEvent;
import com.akshara.chat.PtmViews.SlotView;
import com.akshara.chat.PtmViews.TeacherSchedule;
import com.akshara.shared.CurrentUser;

/**
 * A teacher's own PTM schedule (chat.use or ptm.manage): their meetings and slots, their hours, blocking their own
 * slots, and marking their bookings. Other teachers' slots and bookings answer 404. docs/api/phase-2-chat.md.
 */
@RestController
@RequestMapping("/api/ptm/my")
public class PtmScheduleController {

    static final String TEACHER = "hasAnyAuthority('chat.use', 'ptm.manage')";

    private final PtmService ptm;
    private final PtmBookingService bookings;

    PtmScheduleController(PtmService ptm, PtmBookingService bookings) {
        this.ptm = ptm;
        this.bookings = bookings;
    }

    @GetMapping
    @PreAuthorize(TEACHER)
    public List<MyEvent> mine() {
        return ptm.mine(CurrentUser.requireId(), ChatRules.today());
    }

    @GetMapping("/events/{id}")
    @PreAuthorize(TEACHER)
    public MyEvent event(@PathVariable UUID id) {
        return ptm.myEvent(id, CurrentUser.requireId(), ChatRules.today());
    }

    @PutMapping("/events/{id}/hours")
    @PreAuthorize(TEACHER)
    public TeacherSchedule hours(@PathVariable UUID id, @Valid @RequestBody HoursForm form) {
        return ptm.setHours(id, CurrentUser.requireId(), form, ChatAccess.actor(), Instant.now(), ChatRules.today());
    }

    @PutMapping("/slots/{id}/block")
    @PreAuthorize(TEACHER)
    public SlotView block(@PathVariable UUID id, @Valid @RequestBody BlockForm form) {
        return ptm.block(id, form.blocked(), CurrentUser.requireId(), ChatAccess.actor(), ChatRules.today());
    }

    @PutMapping("/bookings/{id}")
    @PreAuthorize(TEACHER)
    public BookingView mark(@PathVariable UUID id, @Valid @RequestBody MarkForm form) {
        return bookings.mark(id, form, CurrentUser.requireId(), ChatAccess.actor(), Instant.now(), ChatRules.today());
    }
}
