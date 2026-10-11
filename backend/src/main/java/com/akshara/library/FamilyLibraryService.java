package com.akshara.library;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.library.LibraryViews.FamilyFine;
import com.akshara.library.LibraryViews.FamilyHold;
import com.akshara.library.LibraryViews.FamilyLoan;
import com.akshara.library.LibraryViews.FamilyMember;
import com.akshara.library.LibraryViews.Member;
import com.akshara.library.LibraryViews.MyLibrary;
import com.akshara.shared.TenantContext;
import com.akshara.students.StudentService.ChildView;

/**
 * The library as a family sees it: for a parent, each child's books on loan with their due dates, holds and fines;
 * for a student, their own; for a member of staff, their own. Nobody else's records are reachable from here.
 */
@Service
@Transactional(readOnly = true)
public class FamilyLibraryService {

    private final LoanRepository loans;
    private final ReservationRepository reservations;
    private final FineRepository fines;
    private final LibraryMembers members;
    private final LibraryMapper mapper;

    FamilyLibraryService(LoanRepository loans, ReservationRepository reservations, FineRepository fines,
            LibraryMembers members, LibraryMapper mapper) {
        this.loans = loans;
        this.reservations = reservations;
        this.fines = fines;
        this.members = members;
        this.mapper = mapper;
    }

    public MyLibrary mine(UUID userId, LocalDate today) {
        TenantContext.require();
        Map<UUID, ChildView> children = new LinkedHashMap<>();
        members.childrenOf(userId).forEach(c -> children.putIfAbsent(c.id(), c));
        members.studentOf(userId).ifPresent(c -> children.putIfAbsent(c.id(), c));
        List<FamilyMember> result = new ArrayList<>();
        for (ChildView child : children.values()) {
            result.add(member(MemberType.STUDENT, child.id(), child.fullName(),
                    LibraryMembers.label(child.className(), child.sectionName()), loans.openOfStudent(child.id()),
                    reservations.activeOfStudent(child.id()), fines.ofStudent(child.id()), today));
        }
        Optional<Member> staff = children.isEmpty() ? members.staffOf(userId) : Optional.empty();
        staff.ifPresent(m -> result.add(member(MemberType.STAFF, m.id(), m.name(), m.detail(),
                loans.openOfStaff(m.id()), reservations.activeOfStaff(m.id()), fines.ofStaff(m.id()), today)));
        return new MyLibrary(result);
    }

    private FamilyMember member(MemberType type, UUID id, String name, String className, List<Loan> open,
            List<Reservation> holds, List<Fine> owed, LocalDate today) {
        List<FamilyLoan> loanRows = mapper.loans(open, today).stream()
                .map(l -> new FamilyLoan(l.title(), l.authors(), l.accessionNo(), l.issuedOn(), l.dueOn(),
                        l.overdue(), l.daysOverdue(), l.renewals(), l.fineSoFarPaise()))
                .toList();
        List<FamilyHold> holdRows = mapper.holds(holds).stream()
                .map(h -> new FamilyHold(h.title(), h.status(), h.position(), h.holdUntil()))
                .toList();
        List<FamilyFine> fineRows = mapper.fines(owed).stream()
                .map(f -> new FamilyFine(f.title(), f.kind(), f.amountPaise(), f.status(), f.createdAt(),
                        f.receiptNo()))
                .toList();
        long due = owed.stream().filter(Fine::isDue).mapToLong(Fine::getAmountPaise).sum();
        return new FamilyMember(type, id, name, className, loanRows, holdRows, fineRows, due);
    }
}
