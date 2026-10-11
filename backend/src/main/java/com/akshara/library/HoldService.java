package com.akshara.library;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.library.LibraryForms.HoldForm;
import com.akshara.library.LibraryViews.HoldView;
import com.akshara.library.LibraryViews.Member;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;

/**
 * Holds (reservations): a queue per title for members waiting for a copy. When a copy comes back, or a copy is added
 * or put back on the shelf, it is kept for the first member waiting (the copy is RESERVED, the hold READY) until the
 * school's hold days have passed; then it goes to the next member, or back on the shelf. The member is told: the
 * {@link LibraryHoldReady} event, the hold on their family page, and a message to a student's parent when the school
 * has library messages on.
 */
@Service
@Transactional
public class HoldService {

    private static final Actor SYSTEM = new Actor(null, "Library");

    private final ReservationRepository reservations;
    private final TitleRepository titles;
    private final CopyRepository copies;
    private final LoanRepository loans;
    private final LibrarySetupService setup;
    private final LibraryCalendar calendar;
    private final LibraryMembers members;
    private final LibraryMapper mapper;
    private final LibraryReminders reminders;
    private final AuditService audit;
    private final ApplicationEventPublisher events;

    HoldService(ReservationRepository reservations, TitleRepository titles, CopyRepository copies,
            LoanRepository loans, LibrarySetupService setup, LibraryCalendar calendar, LibraryMembers members,
            LibraryMapper mapper, LibraryReminders reminders, AuditService audit, ApplicationEventPublisher events) {
        this.reservations = reservations;
        this.titles = titles;
        this.copies = copies;
        this.loans = loans;
        this.setup = setup;
        this.calendar = calendar;
        this.members = members;
        this.mapper = mapper;
        this.reminders = reminders;
        this.audit = audit;
        this.events = events;
    }

    /** Every hold still waiting or being kept, by title and then queue order. */
    public List<HoldView> active(LocalDate today) {
        TenantContext.require();
        expireDue(today);
        return mapper.holds(reservations.allActive());
    }

    /** Puts a member in a title's queue. Only when no copy is on the shelf: otherwise lend that copy. */
    public HoldView place(HoldForm form, Actor actor, LocalDate today) {
        TenantContext.require();
        Title title = titles.findById(form.titleId())
                .orElseThrow(() -> ApiException.badRequest("Pick a title from the catalogue.", "titleId"));
        MemberRef ref = new MemberRef(form.memberType(), form.memberId());
        Member member = members.requireActive(ref, "memberId");
        expireDue(today);
        if (!copies.ofTitleWithStatus(title.getId(), CopyStatus.AVAILABLE).isEmpty()) {
            throw ApiException.conflict("A copy of " + title.getTitle() + " is on the shelf now: lend it instead.",
                    "titleId");
        }
        List<Loan> open = ref.type() == MemberType.STUDENT ? loans.openOfStudent(ref.id())
                : loans.openOfStaff(ref.id());
        if (open.stream().anyMatch(l -> l.getTitleId().equals(title.getId()))) {
            throw ApiException.conflict(member.name() + " already has a copy of " + title.getTitle() + ".",
                    "titleId");
        }
        if (reservations.queueOf(title.getId()).stream().anyMatch(r -> r.member().equals(ref))) {
            throw ApiException.conflict(member.name() + " is already waiting for " + title.getTitle() + ".",
                    "titleId");
        }
        Reservation hold = reservations.saveAndFlush(new Reservation(title.getId(), ref, actor));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("title", title.getTitle());
        details.put("memberType", ref.type().name());
        details.put("memberId", ref.id());
        audit.record(actor, "library_hold.placed", "library_hold", hold.getId(), details);
        return mapper.hold(hold);
    }

    public HoldView cancel(UUID id, Actor actor, LocalDate today) {
        TenantContext.require();
        Reservation hold = reservations.findById(id).orElseThrow(() -> ApiException.notFound("Hold"));
        if (!hold.isActive()) {
            throw ApiException.conflict("This hold is already closed.", "status");
        }
        boolean wasReady = hold.getStatus() == ReservationStatus.READY;
        hold.close(ReservationStatus.CANCELLED, Instant.now());
        reservations.saveAndFlush(hold);
        audit.record(actor, "library_hold.cancelled", "library_hold", hold.getId(),
                Map.of("title", titleName(hold.getTitleId())));
        if (wasReady) {
            release(hold.getCopyId(), today);
        }
        return mapper.hold(hold);
    }

    /**
     * Keeps a copy that is now on the shelf for the first member waiting for its title, if anyone is. Returns that
     * hold. The copy must be locked or new in the caller's transaction.
     */
    Optional<Reservation> offer(Copy copy, LocalDate today) {
        if (copy.getStatus() != CopyStatus.AVAILABLE) {
            return Optional.empty();
        }
        List<Reservation> waiting = reservations.waitingFor(copy.getTitleId());
        if (waiting.isEmpty()) {
            return Optional.empty();
        }
        Reservation next = waiting.getFirst();
        LocalDate until = LoanMath.holdUntil(today, setup.holdDays(), calendar.closedFrom(today));
        next.ready(copy.getId(), until, Instant.now());
        copy.setStatus(CopyStatus.RESERVED, null);
        copies.saveAndFlush(copy);
        reservations.saveAndFlush(next);
        Title title = titles.findById(copy.getTitleId()).orElseThrow();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("title", title.getTitle());
        details.put("accessionNo", copy.getAccessionNo());
        details.put("holdUntil", until.toString());
        audit.record(SYSTEM, "library_hold.ready", "library_hold", next.getId(), details);
        MemberRef member = next.member();
        events.publishEvent(new LibraryHoldReady(TenantContext.require(), next.getId(), title.getId(),
                title.getTitle(), member.type(), member.id(), until));
        reminders.holdReady(next, title, until);
        return Optional.of(next);
    }

    /**
     * Closes holds whose copy was not collected in time: the copy goes to the next member waiting, or back on the
     * shelf. Runs before the desk lends or holds are listed, so nothing waits for a scheduled job.
     */
    void expireDue(LocalDate today) {
        for (Reservation hold : reservations.readyExpiredBefore(today)) {
            hold.close(ReservationStatus.EXPIRED, Instant.now());
            reservations.saveAndFlush(hold);
            audit.record(SYSTEM, "library_hold.expired", "library_hold", hold.getId(),
                    Map.of("title", titleName(hold.getTitleId())));
            release(hold.getCopyId(), today);
        }
    }

    /** Marks a hold collected when its member borrows the title. */
    void fulfil(Reservation hold) {
        hold.close(ReservationStatus.FULFILLED, Instant.now());
        reservations.saveAndFlush(hold);
    }

    /** A copy no longer kept for a hold: offered to the next member waiting, otherwise back on the shelf. */
    private void release(UUID copyId, LocalDate today) {
        if (copyId == null) {
            return;
        }
        copies.lockById(copyId).ifPresent(copy -> {
            if (copy.getStatus() == CopyStatus.RESERVED) {
                copy.setStatus(CopyStatus.AVAILABLE, null);
                copies.saveAndFlush(copy);
                offer(copy, today);
            }
        });
    }

    private String titleName(UUID titleId) {
        return titles.findById(titleId).map(Title::getTitle).orElse("");
    }
}
