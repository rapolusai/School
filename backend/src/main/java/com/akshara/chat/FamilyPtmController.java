package com.akshara.chat;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.chat.PtmForms.BookForm;
import com.akshara.chat.PtmForms.MoveForm;
import com.akshara.chat.PtmViews.FamilyBooking;
import com.akshara.chat.PtmViews.FamilyPtm;

/**
 * Parent-teacher meetings for parents (child.view): each child's meetings, booking one slot per child per teacher, and
 * changing or cancelling it until the cut-off. docs/api/phase-2-chat.md.
 */
@RestController
@RequestMapping("/api/me/ptm")
public class FamilyPtmController {

    private final PtmBookingService bookings;

    FamilyPtmController(PtmBookingService bookings) {
        this.bookings = bookings;
    }

    @GetMapping
    @PreAuthorize(MyChatController.PARENT)
    public FamilyPtm mine() {
        return bookings.family(ChatAccess.parent(), Instant.now(), ChatRules.today());
    }

    @PostMapping("/bookings")
    @PreAuthorize(MyChatController.PARENT)
    @ResponseStatus(HttpStatus.CREATED)
    public FamilyBooking book(@Valid @RequestBody BookForm form) {
        return bookings.bookForChild(ChatAccess.parent(), form.slotId(), form.studentId(), Instant.now(),
                ChatRules.today());
    }

    @PutMapping("/bookings/{id}")
    @PreAuthorize(MyChatController.PARENT)
    public FamilyBooking move(@PathVariable UUID id, @Valid @RequestBody MoveForm form) {
        return bookings.moveForChild(ChatAccess.parent(), id, form.slotId(), Instant.now(), ChatRules.today());
    }

    @DeleteMapping("/bookings/{id}")
    @PreAuthorize(MyChatController.PARENT)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@PathVariable UUID id) {
        bookings.cancelForChild(ChatAccess.parent(), id, Instant.now());
    }
}
