package com.akshara.staff;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.akshara.academics.AcademicsDirectory;
import com.akshara.academics.AcademicsDirectory.YearInfo;

/**
 * Works out leave balances: for each academic year, opening + accrued − taken, where
 * <ul>
 * <li>opening is the previous year's closing balance carried forward, never above the type's cap (0 in the school's
 * first year),</li>
 * <li>accrued is the type's yearly quota (0 for years that ended before the person joined),</li>
 * <li>taken is the days of approved requests of that year; pending requests are reported separately,</li>
 * </ul>
 * unless the school set the opening balance and allowance for that person, type and year by hand. Loss of pay has no
 * balance.
 */
@Component
class LeaveBalances {

    /** A person's balance of one type in one year, and whether it was set by hand. */
    record Entry(LeaveType type, LeaveMath.Balance balance, boolean setByHand) {
    }

    private final LeaveRequestRepository requests;
    private final LeaveBalanceRepository stored;
    private final AcademicsDirectory academics;

    LeaveBalances(LeaveRequestRepository requests, LeaveBalanceRepository stored, AcademicsDirectory academics) {
        this.requests = requests;
        this.stored = stored;
        this.academics = academics;
    }

    /** Balances of the given types for one person in {@code year}, in the order of {@code types}. */
    Map<UUID, Entry> of(UUID userId, LocalDate joinedOn, YearInfo year, List<LeaveType> types) {
        List<YearInfo> years = academics.years().stream()
                .filter(y -> !y.startsOn().isAfter(year.startsOn()))
                .sorted(Comparator.comparing(YearInfo::startsOn))
                .toList();
        List<LeaveRequest> all = requests.findByUser(userId);
        Map<String, LeaveBalance> byHand = new HashMap<>();
        stored.findByUserId(userId).forEach(b -> byHand.put(key(b.getLeaveTypeId(), b.getAcademicYearId()), b));

        Map<UUID, Entry> result = new LinkedHashMap<>();
        for (LeaveType type : types) {
            BigDecimal closing = null;
            Entry entry = null;
            for (YearInfo y : years) {
                BigDecimal taken = days(all, type.getId(), y.id(), LeaveStatus.APPROVED);
                BigDecimal pending = days(all, type.getId(), y.id(), LeaveStatus.PENDING);
                LeaveBalance hand = byHand.get(key(type.getId(), y.id()));
                BigDecimal opening;
                BigDecimal accrued;
                if (hand != null) {
                    opening = hand.getOpening();
                    accrued = hand.getAccrued();
                } else {
                    opening = closing == null ? LeaveMath.ZERO : LeaveMath.carryForward(closing,
                            type.getCarryForwardCap());
                    accrued = joinedOn != null && joinedOn.isAfter(y.endsOn()) ? LeaveMath.ZERO
                            : type.getYearlyQuota();
                }
                LeaveMath.Balance balance = LeaveMath.balance(opening, accrued, taken, pending, type.isLossOfPay());
                closing = balance.closing();
                entry = new Entry(type, balance, hand != null);
            }
            if (entry != null) {
                result.put(type.getId(), entry);
            }
        }
        return result;
    }

    Entry of(UUID userId, LocalDate joinedOn, YearInfo year, LeaveType type) {
        return of(userId, joinedOn, year, List.of(type)).get(type.getId());
    }

    private static BigDecimal days(List<LeaveRequest> all, UUID typeId, UUID yearId, LeaveStatus status) {
        return LeaveMath.sum(all.stream()
                .filter(r -> r.getStatus() == status && r.getLeaveTypeId().equals(typeId)
                        && r.getAcademicYearId().equals(yearId))
                .map(LeaveRequest::getDays)
                .toList());
    }

    private static String key(UUID typeId, UUID yearId) {
        return typeId + "|" + yearId;
    }
}
