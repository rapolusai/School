package com.akshara.library;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.akshara.library.LibraryViews.FineView;
import com.akshara.library.LibraryViews.HoldView;
import com.akshara.library.LibraryViews.LoanView;
import com.akshara.library.LibraryViews.Member;

/**
 * Turns loans, fines and holds into their views, looking up titles, copies and members for a whole list at once.
 */
@Component
class LibraryMapper {

    private final TitleRepository titles;
    private final CopyRepository copies;
    private final ReservationRepository reservations;
    private final LibraryMembers members;
    private final LibraryCalendar calendar;
    private final LibrarySetupService setup;

    LibraryMapper(TitleRepository titles, CopyRepository copies, ReservationRepository reservations,
            LibraryMembers members, LibraryCalendar calendar, LibrarySetupService setup) {
        this.titles = titles;
        this.copies = copies;
        this.reservations = reservations;
        this.members = members;
        this.calendar = calendar;
        this.setup = setup;
    }

    LoanView loan(Loan loan, LocalDate today) {
        return loans(List.of(loan), today).getFirst();
    }

    /** Loans as views. Overdue ones carry the fine they would raise if they came back today. */
    List<LoanView> loans(List<Loan> loans, LocalDate today) {
        if (loans.isEmpty()) {
            return List.of();
        }
        Map<UUID, Title> titleById = titlesOf(loans.stream().map(Loan::getTitleId).toList());
        Map<UUID, Copy> copyById = copiesOf(loans.stream().map(Loan::getCopyId).toList());
        Map<MemberRef, Member> people = members.describe(distinct(loans.stream().map(Loan::member).toList()));
        Map<MemberType, BorrowingRule.Values> rules = new EnumMap<>(MemberType.class);
        for (MemberType type : MemberType.values()) {
            rules.put(type, setup.rule(type));
        }
        LocalDate earliestOverdue = loans.stream().filter(l -> l.isOpen() && l.getDueOn().isBefore(today))
                .map(Loan::getDueOn).min(LocalDate::compareTo).orElse(null);
        Predicate<LocalDate> closed = earliestOverdue == null ? d -> false
                : calendar.closedBetween(earliestOverdue, today);
        List<LoanView> views = new ArrayList<>();
        for (Loan l : loans) {
            Title t = titleById.get(l.getTitleId());
            Copy c = copyById.get(l.getCopyId());
            BorrowingRule.Values rule = rules.get(l.getMemberType());
            boolean overdue = l.isOpen() && l.getDueOn().isBefore(today);
            int daysOverdue = overdue ? (int) ChronoUnit.DAYS.between(l.getDueOn(), today) : 0;
            long fineSoFar = overdue ? LoanMath.fine(LoanMath.daysLate(l.getDueOn(), today, closed),
                    rule.finePerDayPaise(), rule.maxFinePaise()) : 0;
            int renewalsLeft = l.isOpen() ? Math.max(0, rule.maxRenewals() - l.getRenewals()) : 0;
            views.add(new LoanView(l.getId(), l.getCopyId(), c == null ? null : c.getAccessionNo(), l.getTitleId(),
                    t == null ? null : t.getTitle(), t == null ? null : t.getAuthors(), people.get(l.member()),
                    l.getIssuedOn(), l.getDueOn(), l.getRenewals(), renewalsLeft, l.getStatus(), l.getReturnedOn(),
                    overdue, daysOverdue, fineSoFar, l.getIssuedByName(), l.getReturnedByName()));
        }
        return views;
    }

    FineView fine(Fine fine) {
        return fines(List.of(fine)).getFirst();
    }

    List<FineView> fines(List<Fine> fines) {
        if (fines.isEmpty()) {
            return List.of();
        }
        Map<UUID, Title> titleById = titlesOf(fines.stream().map(Fine::getTitleId).toList());
        Map<UUID, Copy> copyById = copiesOf(fines.stream().map(Fine::getCopyId).toList());
        Map<MemberRef, Member> people = members.describe(distinct(fines.stream().map(Fine::member).toList()));
        return fines.stream().map(f -> {
            Title t = titleById.get(f.getTitleId());
            Copy c = copyById.get(f.getCopyId());
            return new FineView(f.getId(), f.getLoanId(), f.getTitleId(), t == null ? null : t.getTitle(),
                    c == null ? null : c.getAccessionNo(), people.get(f.member()), f.getKind(), f.getAmountPaise(),
                    f.getDaysLate(), f.getNote(), f.getStatus(), f.getReceiptNo(), f.getPaymentMode(),
                    f.getPaymentReference(), f.getPaidAt(), f.getCollectedByName(), f.getWaiveReason(),
                    f.getWaivedAt(), f.getWaivedByName(), f.getCreatedAt());
        }).toList();
    }

    HoldView hold(Reservation r) {
        return holds(List.of(r)).getFirst();
    }

    /** Holds as views; an active hold's position is its place in its title's queue (1 = next). */
    List<HoldView> holds(List<Reservation> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        Set<UUID> titleIds = list.stream().map(Reservation::getTitleId).collect(Collectors.toSet());
        Map<UUID, Title> titleById = titlesOf(titleIds);
        Map<UUID, Integer> positions = new HashMap<>();
        Map<UUID, Integer> counters = new HashMap<>();
        for (Reservation r : reservations.activeOfTitles(titleIds)) {
            positions.put(r.getId(), counters.merge(r.getTitleId(), 1, Integer::sum));
        }
        Map<UUID, Copy> copyById = copiesOf(list.stream().map(Reservation::getCopyId)
                .filter(java.util.Objects::nonNull).toList());
        Map<MemberRef, Member> people = members.describe(distinct(list.stream().map(Reservation::member).toList()));
        return list.stream().map(r -> {
            Title t = titleById.get(r.getTitleId());
            Copy c = r.getCopyId() == null ? null : copyById.get(r.getCopyId());
            return new HoldView(r.getId(), r.getTitleId(), t == null ? null : t.getTitle(),
                    t == null ? null : t.getAuthors(), people.get(r.member()), r.getStatus(),
                    r.isActive() ? positions.getOrDefault(r.getId(), 0) : 0,
                    c == null ? null : c.getAccessionNo(), r.getHoldUntil(), r.getReadyAt(), r.getCreatedAt(),
                    r.getCreatedByName());
        }).toList();
    }

    Map<UUID, Title> titlesOf(Collection<UUID> ids) {
        Set<UUID> wanted = new LinkedHashSet<>(ids);
        return wanted.isEmpty() ? Map.of() : titles.byIds(wanted).stream()
                .collect(Collectors.toMap(Title::getId, Function.identity()));
    }

    Map<UUID, Copy> copiesOf(Collection<UUID> ids) {
        Set<UUID> wanted = new LinkedHashSet<>(ids);
        return wanted.isEmpty() ? Map.of() : copies.byIds(wanted).stream()
                .collect(Collectors.toMap(Copy::getId, Function.identity()));
    }

    private static List<MemberRef> distinct(List<MemberRef> refs) {
        return new ArrayList<>(new LinkedHashSet<>(refs));
    }
}
