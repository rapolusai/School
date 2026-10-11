package com.akshara.library;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.library.LibraryForms.IssueForm;
import com.akshara.library.LibraryForms.LostForm;
import com.akshara.library.LibraryForms.ReturnForm;
import com.akshara.library.LibraryViews.FineView;
import com.akshara.library.LibraryViews.HoldView;
import com.akshara.library.LibraryViews.LoanPage;
import com.akshara.library.LibraryViews.LoanView;
import com.akshara.library.LibraryViews.Member;
import com.akshara.library.LibraryViews.MemberAccount;
import com.akshara.library.LibraryViews.ReturnResult;
import com.akshara.library.LibraryViews.RuleView;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;

/**
 * The circulation desk: lending a copy to a member by its accession number, taking it back (working out the overdue
 * fine and keeping it for the next hold), renewing, and copies reported lost or returned damaged with a charge.
 * Due dates and late days skip Sundays and the school's whole-school holidays.
 */
@Service
@Transactional
public class CirculationService {

    public static final int MAX_PAGE_SIZE = 100;
    /** How far back a loan may be recorded (a book lent while the system was unavailable). */
    public static final int MAX_BACKDATE_DAYS = 60;
    private static final int HISTORY = 20;

    private final LoanRepository loans;
    private final CopyRepository copies;
    private final TitleRepository titles;
    private final FineRepository fines;
    private final ReservationRepository reservations;
    private final LibrarySetupService setup;
    private final LibraryCalendar calendar;
    private final LibraryMembers members;
    private final LibraryMapper mapper;
    private final HoldService holds;
    private final AuditService audit;
    private final EntityManager entityManager;

    CirculationService(LoanRepository loans, CopyRepository copies, TitleRepository titles, FineRepository fines,
            ReservationRepository reservations, LibrarySetupService setup, LibraryCalendar calendar,
            LibraryMembers members, LibraryMapper mapper, HoldService holds, AuditService audit,
            EntityManager entityManager) {
        this.loans = loans;
        this.copies = copies;
        this.titles = titles;
        this.fines = fines;
        this.reservations = reservations;
        this.setup = setup;
        this.calendar = calendar;
        this.members = members;
        this.mapper = mapper;
        this.holds = holds;
        this.audit = audit;
        this.entityManager = entityManager;
    }

    // ------------------------------------------------------------------ lending

    /**
     * Lends the copy to the member, due after the member type's loan days (the next open day if that is a Sunday or a
     * holiday). Refused when the copy is out, kept for someone else's hold, lost, damaged or withdrawn, when the member
     * already has as many books as the rule allows or a copy of the same title, or when they have left the school.
     */
    public LoanView issue(IssueForm form, Actor actor, LocalDate today) {
        TenantContext.require();
        LocalDate issuedOn = form.issuedOn() == null ? today : form.issuedOn();
        if (issuedOn.isAfter(today)) {
            throw ApiException.badRequest("The issue date cannot be in the future.", "issuedOn");
        }
        if (issuedOn.isBefore(today.minusDays(MAX_BACKDATE_DAYS))) {
            throw ApiException.badRequest("A loan can be recorded at most " + MAX_BACKDATE_DAYS + " days back.",
                    "issuedOn");
        }
        MemberRef ref = new MemberRef(form.memberType(), form.memberId());
        Member member = members.requireActive(ref, "memberId");
        holds.expireDue(today);
        Copy copy = lockCopy(form.accessionNo());
        Title title = titles.findById(copy.getTitleId()).orElseThrow();
        Reservation hold = null;
        switch (copy.getStatus()) {
            case AVAILABLE -> {
                // Lendable.
            }
            case RESERVED -> {
                hold = reservations.readyWithCopy(copy.getId()).orElse(null);
                if (hold != null && !hold.member().equals(ref)) {
                    Member keptFor = members.describe(List.of(hold.member())).get(hold.member());
                    throw ApiException.conflict("Copy " + copy.getAccessionNo() + " is kept for "
                            + (keptFor == null || keptFor.name() == null ? "another member" : keptFor.name())
                            + " until " + hold.getHoldUntil() + ".", "accessionNo");
                }
            }
            case ISSUED -> {
                Loan open = loans.openOfCopy(copy.getId()).orElse(null);
                throw ApiException.conflict("Copy " + copy.getAccessionNo() + " is already lent"
                        + (open == null ? "" : ", due back on " + open.getDueOn()) + ". Take it back first.",
                        "accessionNo");
            }
            default -> throw ApiException.conflict("Copy " + copy.getAccessionNo() + " is marked "
                    + copy.getStatus().name().toLowerCase(Locale.ROOT) + " and cannot be lent.", "accessionNo");
        }
        BorrowingRule.Values rule = setup.rule(ref.type());
        List<Loan> open = openOf(ref);
        if (open.size() >= rule.maxBooks()) {
            throw ApiException.conflict(member.name() + " already has " + open.size()
                    + (open.size() == 1 ? " book" : " books") + ", the most allowed. Take one back first.",
                    "memberId");
        }
        if (open.stream().anyMatch(l -> l.getTitleId().equals(title.getId()))) {
            throw ApiException.conflict(member.name() + " already has a copy of " + title.getTitle() + ".",
                    "accessionNo");
        }
        LocalDate dueOn = LoanMath.dueDate(issuedOn, rule.loanDays(), calendar.closedFrom(issuedOn));
        Loan loan = loans.saveAndFlush(new Loan(copy, ref, issuedOn, dueOn, actor));
        copy.setStatus(CopyStatus.ISSUED, null);
        copies.saveAndFlush(copy);
        if (hold == null) {
            // A member who was waiting for this title and gets a copy from the shelf no longer waits.
            hold = reservations.queueOf(title.getId()).stream()
                    .filter(r -> r.member().equals(ref) && r.getStatus() == ReservationStatus.WAITING)
                    .findFirst().orElse(null);
        }
        if (hold != null) {
            holds.fulfil(hold);
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("accessionNo", copy.getAccessionNo());
        details.put("title", title.getTitle());
        details.put("memberType", ref.type().name());
        details.put("memberId", ref.id());
        details.put("issuedOn", issuedOn.toString());
        details.put("dueOn", dueOn.toString());
        audit.record(actor, "library_loan.issued", "library_loan", loan.getId(), details);
        return mapper.loan(loan, today);
    }

    // ------------------------------------------------------------------ taking back

    /**
     * Takes a copy back today. A late copy raises the overdue fine (open days late times the rule's daily rate, up to
     * its cap); a damaged one is set aside with an optional charge. Otherwise the copy goes to the first member
     * waiting for its title, or back on the shelf.
     */
    public ReturnResult giveBack(ReturnForm form, Actor actor, LocalDate today) {
        TenantContext.require();
        holds.expireDue(today);
        Copy copy = lockCopy(form.accessionNo());
        Loan loan = loans.openOfCopy(copy.getId()).orElseThrow(() -> ApiException.conflict(
                "Copy " + copy.getAccessionNo() + " is not on loan.", "accessionNo"));
        boolean damaged = Boolean.TRUE.equals(form.damaged());
        List<Fine> raised = new ArrayList<>();
        overdueFine(loan, today).ifPresent(raised::add);
        loan.returned(today, actor);
        loans.saveAndFlush(loan);
        String note = CatalogueService.blankToNull(form.note());
        if (damaged) {
            copy.setStatus(CopyStatus.DAMAGED, note);
            long charge = form.chargePaise() == null ? 0 : form.chargePaise();
            if (charge > 0) {
                raised.add(new Fine(loan, FineKind.DAMAGED, charge, null, note));
            }
        } else {
            copy.setStatus(CopyStatus.AVAILABLE, null);
        }
        copies.saveAndFlush(copy);
        fines.saveAllAndFlush(raised);
        Optional<Reservation> hold = damaged ? Optional.empty() : holds.offer(copy, today);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("accessionNo", copy.getAccessionNo());
        details.put("memberType", loan.getMemberType().name());
        details.put("memberId", loan.member().id());
        details.put("dueOn", loan.getDueOn().toString());
        details.put("finePaise", raised.stream().mapToLong(Fine::getAmountPaise).sum());
        details.put("damaged", damaged);
        audit.record(actor, "library_loan.returned", "library_loan", loan.getId(), details);
        HoldView heldFor = hold.map(mapper::hold).orElse(null);
        return new ReturnResult(mapper.loan(loan, today), mapper.fines(raised), heldFor);
    }

    /** Lends the copy again from today. Not for an overdue copy, without renewals left, or while others wait. */
    public LoanView renew(UUID loanId, Actor actor, LocalDate today) {
        TenantContext.require();
        Loan loan = loans.findById(loanId).orElseThrow(() -> ApiException.notFound("Loan"));
        if (!loan.isOpen()) {
            throw ApiException.conflict("This book has already been returned.", "loan");
        }
        BorrowingRule.Values rule = setup.rule(loan.getMemberType());
        if (loan.getRenewals() >= rule.maxRenewals()) {
            throw ApiException.conflict(rule.maxRenewals() == 0 ? "Loans of this kind cannot be renewed."
                    : "This loan has been renewed " + loan.getRenewals() + " times, the most allowed.", "loan");
        }
        if (LoanMath.daysLate(loan.getDueOn(), today, calendar.closedBetween(loan.getDueOn(), today)) > 0) {
            throw ApiException.conflict("This book is overdue. Take it back (the fine is worked out then) and lend "
                    + "it again.", "loan");
        }
        if (!reservations.waitingFor(loan.getTitleId()).isEmpty()) {
            throw ApiException.conflict("Another member is waiting for this title, so it cannot be renewed.", "loan");
        }
        LocalDate newDue = LoanMath.dueDate(today, rule.loanDays(), calendar.closedFrom(today));
        if (!newDue.isAfter(loan.getDueOn())) {
            throw ApiException.conflict("Renewing today would not move the due date (" + loan.getDueOn() + ").",
                    "loan");
        }
        LocalDate before = loan.getDueOn();
        loan.renew(newDue);
        loans.saveAndFlush(loan);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("from", before.toString());
        details.put("to", newDue.toString());
        details.put("renewals", loan.getRenewals());
        audit.record(actor, "library_loan.renewed", "library_loan", loan.getId(), details);
        return mapper.loan(loan, today);
    }

    /**
     * The member reports the copy lost: the loan closes, the copy is marked lost, and the member is charged (the
     * copy's price unless another amount is given) plus any overdue fine up to today.
     */
    public ReturnResult lost(UUID loanId, LostForm form, Actor actor, LocalDate today) {
        TenantContext.require();
        Loan loan = loans.findById(loanId).orElseThrow(() -> ApiException.notFound("Loan"));
        if (!loan.isOpen()) {
            throw ApiException.conflict("This book has already been returned.", "loan");
        }
        Copy copy = copies.lockById(loan.getCopyId()).orElseThrow();
        List<Fine> raised = new ArrayList<>();
        overdueFine(loan, today).ifPresent(raised::add);
        long charge = form.chargePaise() != null ? form.chargePaise()
                : copy.getPricePaise() == null ? 0 : copy.getPricePaise();
        String note = CatalogueService.blankToNull(form.note());
        if (charge > 0) {
            raised.add(new Fine(loan, FineKind.LOST, charge, null, note));
        }
        loan.lost(actor);
        loans.saveAndFlush(loan);
        copy.setStatus(CopyStatus.LOST, note);
        copies.saveAndFlush(copy);
        fines.saveAllAndFlush(raised);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("accessionNo", copy.getAccessionNo());
        details.put("memberType", loan.getMemberType().name());
        details.put("memberId", loan.member().id());
        details.put("chargePaise", charge);
        audit.record(actor, "library_loan.lost", "library_loan", loan.getId(), details);
        return new ReturnResult(mapper.loan(loan, today), mapper.fines(raised), null);
    }

    /** The overdue fine for a loan closed today, if it is late and the rule charges for it. */
    private Optional<Fine> overdueFine(Loan loan, LocalDate today) {
        if (!today.isAfter(loan.getDueOn())) {
            return Optional.empty();
        }
        BorrowingRule.Values rule = setup.rule(loan.getMemberType());
        Predicate<LocalDate> closed = calendar.closedBetween(loan.getDueOn(), today);
        int daysLate = LoanMath.daysLate(loan.getDueOn(), today, closed);
        long amount = LoanMath.fine(daysLate, rule.finePerDayPaise(), rule.maxFinePaise());
        return amount > 0 ? Optional.of(new Fine(loan, FineKind.OVERDUE, amount, daysLate, null)) : Optional.empty();
    }

    // ------------------------------------------------------------------ lists and members

    /** Loans, newest first; {@code overdue} keeps open loans past their due date (oldest due first). */
    @Transactional(readOnly = true)
    public LoanPage list(LoanStatus status, boolean overdue, MemberType memberType, UUID memberId, int page, int size,
            LocalDate today) {
        TenantContext.require();
        int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int pageNo = Math.max(page, 0);
        StringBuilder where = new StringBuilder(" from Loan l where 1 = 1");
        Map<String, Object> params = new HashMap<>();
        if (overdue) {
            where.append(" and l.status = com.akshara.library.LoanStatus.ISSUED and l.dueOn < :today");
            params.put("today", today);
        } else if (status != null) {
            where.append(" and l.status = :status");
            params.put("status", status);
        }
        if (memberType != null) {
            where.append(" and l.memberType = :memberType");
            params.put("memberType", memberType);
            if (memberId != null) {
                where.append(memberType == MemberType.STUDENT ? " and l.studentId = :member"
                        : " and l.staffUserId = :member");
                params.put("member", memberId);
            }
        }
        String order = overdue ? " order by l.dueOn, l.id" : " order by l.issuedOn desc, l.createdAt desc";
        TypedQuery<Long> count = entityManager.createQuery("select count(l)" + where, Long.class);
        TypedQuery<Loan> rows = entityManager.createQuery("select l" + where + order, Loan.class);
        params.forEach((k, v) -> {
            count.setParameter(k, v);
            rows.setParameter(k, v);
        });
        long total = count.getSingleResult();
        List<Loan> found = rows.setFirstResult(pageNo * pageSize).setMaxResults(pageSize).getResultList();
        return new LoanPage(mapper.loans(found, today), pageNo, pageSize, total);
    }

    /** Everything the desk needs about a member. 404 when they are not a student or staff member of this school. */
    @Transactional(readOnly = true)
    public MemberAccount account(MemberRef ref, LocalDate today) {
        TenantContext.require();
        Member member = members.require(ref);
        RuleView rule = setup.ruleView(ref.type());
        List<Loan> open = openOf(ref);
        List<Loan> history = (ref.type() == MemberType.STUDENT ? loans.historyOfStudent(ref.id())
                : loans.historyOfStaff(ref.id())).stream().filter(l -> !l.isOpen()).limit(HISTORY).toList();
        List<Fine> owed = (ref.type() == MemberType.STUDENT ? fines.ofStudent(ref.id()) : fines.ofStaff(ref.id()))
                .stream().filter(Fine::isDue).toList();
        List<Reservation> waiting = ref.type() == MemberType.STUDENT ? reservations.activeOfStudent(ref.id())
                : reservations.activeOfStaff(ref.id());
        String blocked = null;
        if (!member.active()) {
            blocked = member.name() + " has left the school and cannot borrow.";
        } else if (open.size() >= rule.maxBooks()) {
            blocked = member.name() + " has " + open.size() + (open.size() == 1 ? " book" : " books")
                    + ", the most allowed.";
        }
        List<FineView> due = mapper.fines(owed);
        return new MemberAccount(member, rule, mapper.loans(open, today), due,
                owed.stream().mapToLong(Fine::getAmountPaise).sum(), mapper.holds(waiting),
                mapper.loans(history, today), blocked == null, blocked);
    }

    private List<Loan> openOf(MemberRef ref) {
        return ref.type() == MemberType.STUDENT ? loans.openOfStudent(ref.id()) : loans.openOfStaff(ref.id());
    }

    private Copy lockCopy(String accessionNo) {
        String acc = accessionNo.trim().toUpperCase(Locale.ROOT);
        return copies.lockByAccessionNo(acc).orElseThrow(() -> ApiException.badRequest(
                "No copy has the accession number " + acc + ".", "accessionNo"));
    }
}
