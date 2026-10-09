package com.akshara.staff;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;
import com.akshara.staff.StaffForms.DepartmentFields;
import com.akshara.staff.StaffRoster.Member;
import com.akshara.staff.StaffService.PersonRef;

/** Departments of the school (Primary, Science, Administration…) and their heads, who approve their staff's leave. */
@Service
@Transactional
public class DepartmentService {

    /** {@code staffCount} counts active staff whose profile names the department. */
    public record DepartmentView(UUID id, String name, PersonRef head, long staffCount) {
    }

    private final DepartmentRepository departments;
    private final StaffProfileRepository profiles;
    private final StaffRoster roster;
    private final AuditService audit;

    public DepartmentService(DepartmentRepository departments, StaffProfileRepository profiles, StaffRoster roster,
            AuditService audit) {
        this.departments = departments;
        this.profiles = profiles;
        this.roster = roster;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<DepartmentView> list() {
        TenantContext.require();
        Map<UUID, Long> counts = new HashMap<>();
        for (Object[] row : profiles.countPerDepartment()) {
            counts.put((UUID) row[0], ((Number) row[1]).longValue());
        }
        Map<UUID, String> names = new HashMap<>();
        roster.all().forEach(m -> names.put(m.userId(), m.name()));
        return departments.findAllOrdered().stream()
                .map(d -> view(d, names, counts.getOrDefault(d.getId(), 0L)))
                .toList();
    }

    public DepartmentView create(DepartmentFields form, Actor actor) {
        TenantContext.require();
        String name = form.name().trim();
        if (departments.nameTaken(name, new UUID(0, 0))) {
            throw ApiException.conflict("A department called " + name + " already exists.", "name");
        }
        UUID head = checkHead(form.headUserId());
        Department department = departments.saveAndFlush(new Department(name, head));
        record(actor, "department.created", department.getId(), details(department));
        return view(department);
    }

    public DepartmentView update(UUID id, DepartmentFields form, Actor actor) {
        TenantContext.require();
        Department department = departments.findById(id).orElseThrow(() -> ApiException.notFound("Department"));
        String name = form.name().trim();
        if (departments.nameTaken(name, id)) {
            throw ApiException.conflict("A department called " + name + " already exists.", "name");
        }
        UUID head = checkHead(form.headUserId());
        boolean changed = !department.getName().equals(name) || !Objects.equals(department.getHeadUserId(), head);
        department.update(name, head);
        departments.flush();
        if (changed) {
            record(actor, "department.updated", id, details(department));
        }
        return view(department);
    }

    public void delete(UUID id, Actor actor) {
        TenantContext.require();
        Department department = departments.findById(id).orElseThrow(() -> ApiException.notFound("Department"));
        long staff = profiles.countByDepartmentId(id);
        if (staff > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "In use", department.getName() + " still has " + staff
                    + (staff == 1 ? " staff member" : " staff members")
                    + ". Move them to another department first.");
        }
        departments.delete(department);
        departments.flush();
        record(actor, "department.deleted", id, Map.of("name", department.getName()));
    }

    /** The head must be an active staff member of this school. */
    private UUID checkHead(UUID headUserId) {
        if (headUserId == null) {
            return null;
        }
        Member head = roster.find(headUserId).filter(Member::active)
                .orElseThrow(() -> ApiException.badRequest("Pick an active staff member of this school.",
                        "headUserId"));
        return head.userId();
    }

    private DepartmentView view(Department d) {
        Map<UUID, String> names = new HashMap<>();
        if (d.getHeadUserId() != null) {
            roster.find(d.getHeadUserId()).ifPresent(m -> names.put(m.userId(), m.name()));
        }
        long count = profiles.countPerDepartment().stream().filter(r -> d.getId().equals(r[0]))
                .mapToLong(r -> ((Number) r[1]).longValue()).sum();
        return view(d, names, count);
    }

    private static DepartmentView view(Department d, Map<UUID, String> names, long count) {
        PersonRef head = d.getHeadUserId() == null ? null
                : new PersonRef(d.getHeadUserId(), names.getOrDefault(d.getHeadUserId(), null));
        return new DepartmentView(d.getId(), d.getName(), head, count);
    }

    private Map<String, Object> details(Department d) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("name", d.getName());
        details.put("headUserId", d.getHeadUserId() == null ? null : d.getHeadUserId().toString());
        return details;
    }

    private void record(Actor actor, String action, UUID id, Map<String, ?> details) {
        if (actor == null) {
            audit.record(action, "department", id, details);
        } else {
            audit.record(actor, action, "department", id, details);
        }
    }
}
