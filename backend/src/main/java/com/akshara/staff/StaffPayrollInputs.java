package com.akshara.staff;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.shared.TenantContext;
import com.akshara.staff.StaffRoster.Member;
import com.akshara.staff.StaffService.DepartmentRef;

/**
 * Read-only facts the payroll module needs from the staff records, and nothing more: who is on the staff with their
 * employee code, designation, department and joining and leaving dates, and the approved loss-of-pay leave in a date
 * range. Changes nothing in this module.
 */
@Service
@Transactional(readOnly = true)
public class StaffPayrollInputs {

    /**
     * One member of staff. {@code profileComplete} is false while the staff profile is missing (then the code,
     * designation, department and dates are null). {@code active} is false once they have left or their sign-in is
     * disabled.
     */
    public record PayrollPerson(UUID userId, String name, String email, String employeeCode, String designation,
            UUID departmentId, String departmentName, LocalDate dateOfJoining, LocalDate dateOfLeaving,
            boolean active, boolean profileComplete) {
    }

    private final StaffRoster roster;
    private final DepartmentRepository departments;
    private final LeaveRequestRepository requests;
    private final LeaveTypeRepository types;
    private final WorkingDayCalendar calendar;

    StaffPayrollInputs(StaffRoster roster, DepartmentRepository departments, LeaveRequestRepository requests,
            LeaveTypeRepository types, WorkingDayCalendar calendar) {
        this.roster = roster;
        this.departments = departments;
        this.requests = requests;
        this.types = types;
        this.calendar = calendar;
    }

    /** Everyone on the staff, active or not, in the roster's order (by name). */
    public List<PayrollPerson> people() {
        TenantContext.require();
        Map<UUID, String> names = departmentNames();
        return roster.all().stream().map(m -> person(m, names)).toList();
    }

    /** A staff member of this school; empty for unknown ids, other schools' people, parents and students. */
    public Optional<PayrollPerson> person(UUID userId) {
        TenantContext.require();
        return roster.find(userId).map(m -> person(m, departmentNames()));
    }

    /** The school's departments by name. */
    public List<DepartmentRef> departments() {
        TenantContext.require();
        return departments.findAllOrdered().stream().map(d -> new DepartmentRef(d.getId(), d.getName())).toList();
    }

    /**
     * Approved loss-of-pay leave from {@code from} to {@code to} (both included), in working days per person, as the
     * leave module counts them (half days as 0.5). A request that runs past the range counts only its working days
     * inside it. People without such leave are not in the map.
     */
    public Map<UUID, BigDecimal> lossOfPayDays(LocalDate from, LocalDate to) {
        TenantContext.require();
        Set<UUID> lossOfPay = types.findAll().stream().filter(LeaveType::isLossOfPay).map(LeaveType::getId)
                .collect(Collectors.toSet());
        Map<UUID, BigDecimal> days = new HashMap<>();
        if (lossOfPay.isEmpty()) {
            return days;
        }
        for (LeaveRequest r : requests.findApprovedBetween(from, to)) {
            if (!lossOfPay.contains(r.getLeaveTypeId())) {
                continue;
            }
            BigDecimal inRange;
            if (!r.getFromDate().isBefore(from) && !r.getToDate().isAfter(to)) {
                inRange = r.getDays();
            } else {
                LocalDate start = r.getFromDate().isBefore(from) ? from : r.getFromDate();
                LocalDate end = r.getToDate().isAfter(to) ? to : r.getToDate();
                inRange = BigDecimal.valueOf(calendar.workingDays(start, end).size());
            }
            days.merge(r.getUserId(), inRange, BigDecimal::add);
        }
        return days;
    }

    private Map<UUID, String> departmentNames() {
        return departments.findAll().stream().collect(Collectors.toMap(Department::getId, Department::getName));
    }

    private static PayrollPerson person(Member m, Map<UUID, String> departmentNames) {
        StaffProfile p = m.profile();
        UUID departmentId = p == null ? null : p.getDepartmentId();
        return new PayrollPerson(m.userId(), m.name(), m.email(), p == null ? null : p.getEmployeeCode(),
                p == null ? null : p.getDesignation(), departmentId,
                departmentId == null ? null : departmentNames.get(departmentId),
                p == null ? null : p.getDateOfJoining(), p == null ? null : p.getDateOfLeaving(), m.active(),
                p != null);
    }
}
