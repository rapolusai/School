package com.akshara.staff;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.persistence.EntityManager;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.academics.AcademicsDirectory;
import com.akshara.academics.AcademicsDirectory.YearInfo;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;
import com.akshara.staff.StaffRoster.Member;

/**
 * Read-only leave figures for dashboards and reports: how many requests wait for the signed-in person, and the leave
 * each member of staff has taken in an academic year by leave type.
 */
@Service
@Transactional(readOnly = true)
public class LeaveInsights {

    public record LeaveTypeRef(UUID id, String name, String code, boolean lossOfPay, boolean active) {
    }

    /**
     * One person's approved leave in the year: {@code days} by type, in the order of the report's types;
     * {@code lossOfPay} the part of the total that is loss of pay; {@code pending} days still waiting for a decision.
     */
    public record StaffLeaveLine(UUID userId, String name, String employeeCode, UUID departmentId,
            String departmentName, boolean active, List<BigDecimal> days, BigDecimal total, BigDecimal lossOfPay,
            BigDecimal pending) {
    }

    public record LeaveTaken(UUID academicYearId, String academicYearName, UUID departmentId, UUID leaveTypeId,
            List<LeaveTypeRef> types, List<StaffLeaveLine> staff, List<BigDecimal> typeTotals, BigDecimal total,
            BigDecimal lossOfPay, BigDecimal pending) {
    }

    private final LeaveService leave;
    private final StaffRoster roster;
    private final LeaveTypeRepository types;
    private final DepartmentRepository departments;
    private final AcademicsDirectory academics;
    private final EntityManager entityManager;

    LeaveInsights(LeaveService leave, StaffRoster roster, LeaveTypeRepository types, DepartmentRepository departments,
            AcademicsDirectory academics, EntityManager entityManager) {
        this.leave = leave;
        this.roster = roster;
        this.types = types;
        this.departments = departments;
        this.academics = academics;
        this.entityManager = entityManager;
    }

    /** Pending requests the signed-in person can decide: exactly what their leave inbox lists. */
    public int waitingForMe() {
        return leave.inbox(StaffAuth.caller(), false).size();
    }

    /**
     * Approved leave in the academic year (the current one by default) for every member of staff, by leave type. Staff
     * who have left are listed only when they took leave that year. 404 for another school's year, department or
     * leave type.
     */
    public LeaveTaken taken(UUID academicYearId, UUID departmentId, UUID leaveTypeId) {
        TenantContext.require();
        Optional<YearInfo> year = academicYearId == null ? academics.currentYear()
                : Optional.of(academics.year(academicYearId).orElseThrow(() -> ApiException.notFound("Academic year")));
        Map<UUID, String> departmentNames = departments.findAllOrdered().stream()
                .collect(Collectors.toMap(Department::getId, Department::getName));
        if (departmentId != null && !departmentNames.containsKey(departmentId)) {
            throw ApiException.notFound("Department");
        }
        List<LeaveType> allTypes = types.findAllOrdered();
        if (leaveTypeId != null && allTypes.stream().noneMatch(t -> t.getId().equals(leaveTypeId))) {
            throw ApiException.notFound("Leave type");
        }
        List<LeaveRequest> requests = year.isEmpty() ? List.of()
                : entityManager.createQuery("select r from LeaveRequest r where r.academicYearId = :year "
                        + "and r.status in :statuses", LeaveRequest.class)
                        .setParameter("year", year.get().id())
                        .setParameter("statuses", List.of(LeaveStatus.APPROVED, LeaveStatus.PENDING))
                        .getResultList();
        Set<UUID> used = requests.stream().filter(r -> r.getStatus() == LeaveStatus.APPROVED)
                .map(LeaveRequest::getLeaveTypeId).collect(Collectors.toSet());
        List<LeaveType> columns = allTypes.stream()
                .filter(t -> leaveTypeId == null ? t.isActive() || used.contains(t.getId()) : t.getId().equals(leaveTypeId))
                .toList();
        Map<UUID, Integer> column = new HashMap<>();
        for (int i = 0; i < columns.size(); i++) {
            column.put(columns.get(i).getId(), i);
        }
        Set<UUID> lossOfPayTypes = allTypes.stream().filter(LeaveType::isLossOfPay).map(LeaveType::getId)
                .collect(Collectors.toSet());

        Map<UUID, List<LeaveRequest>> byUser = requests.stream()
                .filter(r -> leaveTypeId == null || r.getLeaveTypeId().equals(leaveTypeId))
                .collect(Collectors.groupingBy(LeaveRequest::getUserId));
        List<StaffLeaveLine> lines = new ArrayList<>();
        roster.all().stream()
                .filter(m -> m.active() || byUser.containsKey(m.userId()))
                .filter(m -> departmentId == null || departmentId.equals(m.departmentId()))
                .sorted(Comparator.comparing((Member m) -> m.name().toLowerCase(Locale.ROOT)))
                .forEach(m -> {
                    List<BigDecimal> days = new ArrayList<>(columns.stream().map(t -> BigDecimal.ZERO).toList());
                    BigDecimal total = BigDecimal.ZERO;
                    BigDecimal lop = BigDecimal.ZERO;
                    BigDecimal pending = BigDecimal.ZERO;
                    for (LeaveRequest r : byUser.getOrDefault(m.userId(), List.of())) {
                        if (r.getStatus() == LeaveStatus.PENDING) {
                            pending = pending.add(r.getDays());
                            continue;
                        }
                        Integer i = column.get(r.getLeaveTypeId());
                        if (i != null) {
                            days.set(i, days.get(i).add(r.getDays()));
                        }
                        total = total.add(r.getDays());
                        if (lossOfPayTypes.contains(r.getLeaveTypeId())) {
                            lop = lop.add(r.getDays());
                        }
                    }
                    lines.add(new StaffLeaveLine(m.userId(), m.name(), m.employeeCode(), m.departmentId(),
                            m.departmentId() == null ? null : departmentNames.get(m.departmentId()), m.active(),
                            days, total, lop, pending));
                });

        List<BigDecimal> typeTotals = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            int index = i;
            typeTotals.add(lines.stream().map(l -> l.days().get(index)).reduce(BigDecimal.ZERO, BigDecimal::add));
        }
        return new LeaveTaken(year.map(YearInfo::id).orElse(null), year.map(YearInfo::name).orElse(null),
                departmentId, leaveTypeId,
                columns.stream().map(t -> new LeaveTypeRef(t.getId(), t.getName(), t.getCode(), t.isLossOfPay(),
                        t.isActive())).toList(),
                lines, typeTotals,
                lines.stream().map(StaffLeaveLine::total).reduce(BigDecimal.ZERO, BigDecimal::add),
                lines.stream().map(StaffLeaveLine::lossOfPay).reduce(BigDecimal.ZERO, BigDecimal::add),
                lines.stream().map(StaffLeaveLine::pending).reduce(BigDecimal.ZERO, BigDecimal::add));
    }
}
