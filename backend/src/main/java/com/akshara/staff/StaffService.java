package com.akshara.staff;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.identity.RoleCatalog;
import com.akshara.identity.RoleRepository;
import com.akshara.identity.UserAccount;
import com.akshara.identity.UserService;
import com.akshara.notifications.RecipientMask;
import com.akshara.shared.ApiException;
import com.akshara.shared.CurrentUser;
import com.akshara.shared.TenantContext;
import com.akshara.staff.StaffForms.CreateStaff;
import com.akshara.staff.StaffForms.Leaving;
import com.akshara.staff.StaffForms.ProfileFields;
import com.akshara.staff.StaffRoster.Member;
import com.akshara.students.Phones;

/**
 * Staff records: the directory, profiles, adding a staff member (sign-in and profile in one transaction) and recording
 * that someone left (their sign-in is disabled, nothing is deleted). Every change is audited.
 */
@Service
@Transactional
public class StaffService {

    public static final int MAX_PAGE_SIZE = 100;

    public record PersonRef(UUID id, String name) {
    }

    public record DepartmentRef(UUID id, String name) {
    }

    public record RoleRef(String code, String name) {
    }

    /** A line of the directory. {@code mobile} is masked; {@code today} is today's attendance, if any. */
    public record StaffRow(UUID userId, String name, String email, List<String> roles, String employeeCode,
            String designation, DepartmentRef department, EmploymentType employmentType, LocalDate dateOfJoining,
            LocalDate dateOfLeaving, String mobile, StaffStatus status, boolean profileComplete,
            StaffAttendanceStatus today) {
    }

    public record StaffPage(List<StaffRow> items, int page, int size, long total, long incompleteProfiles) {
    }

    /** Directory filters; null means any. */
    public record StaffQuery(UUID departmentId, String designation, String role, StaffStatus status, Boolean incomplete,
            String search, int page, int size) {
    }

    public record ProfileView(String employeeCode, String designation, DepartmentRef department,
            EmploymentType employmentType, LocalDate dateOfJoining, LocalDate dateOfLeaving, String leavingReason,
            String mobile, String qualifications, String emergencyContactName, String emergencyContactMobile) {
    }

    /** Who decides this person's leave: their department head, or the School Admin and Principal (SCHOOL). */
    public record LeaveApprover(String routing, PersonRef departmentHead) {

        static final String DEPARTMENT_HEAD = "DEPARTMENT_HEAD";
        static final String SCHOOL = "SCHOOL";
    }

    public record StaffDetail(UUID userId, String name, String email, List<String> roles, StaffStatus status,
            boolean accountActive, Instant lastLoginAt, boolean profileComplete, ProfileView profile,
            LeaveApprover leaveApprover) {
    }

    private final StaffRoster roster;
    private final StaffProfileRepository profiles;
    private final DepartmentRepository departments;
    private final StaffAttendanceRepository attendance;
    private final RoleRepository roles;
    private final UserService userService;
    private final LeaveService leave;
    private final AuditService audit;

    public StaffService(StaffRoster roster, StaffProfileRepository profiles, DepartmentRepository departments,
            StaffAttendanceRepository attendance, RoleRepository roles, UserService userService, LeaveService leave,
            AuditService audit) {
        this.roster = roster;
        this.profiles = profiles;
        this.departments = departments;
        this.attendance = attendance;
        this.roles = roles;
        this.userService = userService;
        this.leave = leave;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ directory

    @Transactional(readOnly = true)
    public StaffPage list(StaffQuery query) {
        TenantContext.require();
        int size = Math.min(Math.max(query.size(), 1), MAX_PAGE_SIZE);
        int page = Math.max(query.page(), 0);
        List<Member> all = roster.all();
        Map<UUID, Department> departmentById = departmentsById();
        Map<UUID, StaffAttendanceStatus> today = attendance.findByAttendanceDate(StaffAttendanceService.today())
                .stream().collect(Collectors.toMap(StaffAttendance::getUserId, StaffAttendance::getStatus));
        String search = query.search() == null ? "" : query.search().trim().toLowerCase(Locale.ROOT);
        String designation = query.designation() == null ? "" : query.designation().trim();

        List<Member> matching = all.stream()
                .filter(m -> query.status() == null || m.status() == query.status())
                .filter(m -> query.departmentId() == null || query.departmentId().equals(m.departmentId()))
                .filter(m -> designation.isEmpty()
                        || (m.profile() != null && m.profile().getDesignation().equalsIgnoreCase(designation)))
                .filter(m -> query.role() == null || query.role().isBlank() || m.roles().contains(query.role().trim()))
                .filter(m -> query.incomplete() == null || query.incomplete() == (m.profile() == null))
                .filter(m -> search.isEmpty() || matches(m, search))
                .toList();
        List<StaffRow> items = matching.stream()
                .skip((long) page * size)
                .limit(size)
                .map(m -> row(m, departmentById, today.get(m.userId())))
                .toList();
        long incomplete = all.stream().filter(m -> m.active() && m.profile() == null).count();
        return new StaffPage(items, page, size, matching.size(), incomplete);
    }

    @Transactional(readOnly = true)
    public StaffDetail detail(UUID userId) {
        TenantContext.require();
        Member member = roster.find(userId).orElseThrow(() -> ApiException.notFound("Staff member"));
        return detail(member);
    }

    /** The designations in use, for the directory filter. */
    @Transactional(readOnly = true)
    public List<String> designations() {
        TenantContext.require();
        return profiles.designations();
    }

    /** Roles a staff member can be given: everything except Parent and Student. */
    @Transactional(readOnly = true)
    public List<RoleRef> staffRoles() {
        TenantContext.require();
        return roles.findAllByOrderByNameAsc().stream()
                .filter(r -> RoleCatalog.isStaffRole(r.getCode()))
                .map(r -> new RoleRef(r.getCode(), r.getName()))
                .toList();
    }

    // ------------------------------------------------------------------ adding and editing

    /**
     * Creates the sign-in (with a temporary password, already hashed by the caller) and the staff profile in one
     * transaction: if either is refused, neither is saved.
     */
    public StaffDetail create(CreateStaff form, String passwordHash, Actor actor) {
        TenantContext.require();
        List<String> roleCodes = form.roles().stream().map(String::trim).distinct().toList();
        if (roleCodes.stream().anyMatch(code -> !RoleCatalog.isStaffRole(code))) {
            throw ApiException.badRequest("Parent and Student are not staff roles. Pick a staff role.", "roles");
        }
        StaffProfile.Fields fields = clean(form.profile());
        UserAccount user = userService.createUser(form.name(), form.email(), passwordHash, roleCodes, actor);
        checkEmployeeCode(fields.employeeCode(), user.getId());
        StaffProfile profile = profiles.saveAndFlush(new StaffProfile(user.getId(), fields));
        record(actor, "staff.created", user.getId(), profileDetails(profile));
        return detail(user.getId());
    }

    /** Creates the profile of a staff member who has none yet ("profile incomplete"), or changes it. */
    public StaffDetail saveProfile(UUID userId, ProfileFields form, Actor actor) {
        TenantContext.require();
        Member member = roster.find(userId).orElseThrow(() -> ApiException.notFound("Staff member"));
        StaffProfile.Fields fields = clean(form);
        checkEmployeeCode(fields.employeeCode(), userId);
        StaffProfile profile = member.profile();
        if (profile != null && profile.getDateOfLeaving() != null
                && fields.dateOfJoining().isAfter(profile.getDateOfLeaving())) {
            throw ApiException.badRequest("The joining date must be before the leaving date.", "dateOfJoining");
        }
        if (profile == null) {
            profile = profiles.saveAndFlush(new StaffProfile(userId, fields));
            record(actor, "staff.profile_created", userId, profileDetails(profile));
        } else {
            List<String> changed = changedFields(profile.fields(), fields);
            profile.apply(fields);
            profiles.flush();
            if (!changed.isEmpty()) {
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("employeeCode", fields.employeeCode());
                details.put("changed", changed);
                record(actor, "staff.profile_updated", userId, details);
            }
        }
        return detail(userId);
    }

    /**
     * Records that a staff member left. Their sign-in is disabled (they can no longer sign in), they stop heading any
     * department, and their pending leave and approved leave after the leaving date are cancelled. Nothing is deleted.
     */
    public StaffDetail recordLeaving(UUID userId, Leaving form, Actor actor) {
        TenantContext.require();
        Member member = roster.find(userId).orElseThrow(() -> ApiException.notFound("Staff member"));
        UUID me = actor != null ? actor.id() : CurrentUser.id().orElse(null);
        if (userId.equals(me)) {
            throw new ApiException(HttpStatus.CONFLICT, "Not allowed",
                    "You cannot record your own leaving. Ask another School Admin.");
        }
        StaffProfile profile = member.profile();
        if (profile == null) {
            throw new ApiException(HttpStatus.CONFLICT, "Profile incomplete",
                    "Complete the staff profile before recording that they left.");
        }
        if (member.status() == StaffStatus.LEFT) {
            throw new ApiException(HttpStatus.CONFLICT, "Already left", member.name() + " has already left.");
        }
        if (form.leftOn().isAfter(StaffAttendanceService.today())) {
            throw ApiException.badRequest("The leaving date cannot be in the future.", "leftOn");
        }
        if (form.leftOn().isBefore(profile.getDateOfJoining())) {
            throw ApiException.badRequest("The leaving date cannot be before the joining date.", "leftOn");
        }
        profile.leave(form.leftOn(), form.reason().trim());
        profiles.flush();
        for (Department d : departments.findByHeadUserId(userId)) {
            d.update(d.getName(), null);
        }
        departments.flush();
        Actor by = actor != null ? actor : current();
        int cancelled = leave.cancelForLeaver(userId, form.leftOn(), by);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("employeeCode", profile.getEmployeeCode());
        details.put("leftOn", form.leftOn().toString());
        details.put("leaveCancelled", cancelled);
        record(actor, "staff.left", userId, details);
        userService.disableUser(userId, Map.of("reason", "left the school"), actor);
        return detail(userId);
    }

    // ------------------------------------------------------------------ helpers

    StaffDetail detail(Member member) {
        StaffProfile p = member.profile();
        ProfileView profile = null;
        if (p != null) {
            Department d = p.getDepartmentId() == null ? null : departments.findById(p.getDepartmentId()).orElse(null);
            profile = new ProfileView(p.getEmployeeCode(), p.getDesignation(),
                    d == null ? null : new DepartmentRef(d.getId(), d.getName()), p.getEmploymentType(),
                    p.getDateOfJoining(), p.getDateOfLeaving(), p.getLeavingReason(), p.getMobile(),
                    p.getQualifications(), p.getEmergencyContactName(), p.getEmergencyContactMobile());
        }
        Optional<Member> head = leave.departmentHeadOf(member);
        LeaveApprover approver = head
                .map(h -> new LeaveApprover(LeaveApprover.DEPARTMENT_HEAD, new PersonRef(h.userId(), h.name())))
                .orElse(new LeaveApprover(LeaveApprover.SCHOOL, null));
        return new StaffDetail(member.userId(), member.name(), member.email(), member.roles(), member.status(),
                member.accountActive(), member.lastLoginAt(), p != null, profile, approver);
    }

    private StaffProfile.Fields clean(ProfileFields form) {
        String mobile = Phones.normalize(form.mobile());
        if (mobile == null) {
            throw ApiException.badRequest(Phones.MESSAGE, "mobile");
        }
        String emergencyName = blankToNull(form.emergencyContactName());
        String emergencyMobile = null;
        if (form.emergencyContactMobile() != null && !form.emergencyContactMobile().isBlank()) {
            emergencyMobile = Phones.normalize(form.emergencyContactMobile());
            if (emergencyMobile == null) {
                throw ApiException.badRequest(Phones.MESSAGE, "emergencyContactMobile");
            }
        }
        if (emergencyName != null && emergencyMobile == null) {
            throw ApiException.badRequest("Add the emergency contact's mobile number too.", "emergencyContactMobile");
        }
        if (emergencyName == null && emergencyMobile != null) {
            throw ApiException.badRequest("Add the emergency contact's name too.", "emergencyContactName");
        }
        if (form.departmentId() != null && departments.findById(form.departmentId()).isEmpty()) {
            throw ApiException.badRequest("Pick a department of this school.", "departmentId");
        }
        if (form.dateOfJoining().isAfter(StaffAttendanceService.today().plusYears(1))) {
            throw ApiException.badRequest("The joining date is too far in the future.", "dateOfJoining");
        }
        return new StaffProfile.Fields(form.employeeCode().trim(), form.designation().trim(), form.departmentId(),
                form.employmentType(), form.dateOfJoining(), mobile, blankToNull(form.qualifications()), emergencyName,
                emergencyMobile);
    }

    private void checkEmployeeCode(String code, UUID userId) {
        if (profiles.employeeCodeTaken(code, userId)) {
            throw ApiException.conflict("Employee code " + code + " is already used.", "employeeCode");
        }
    }

    private StaffRow row(Member m, Map<UUID, Department> departmentById, StaffAttendanceStatus today) {
        StaffProfile p = m.profile();
        Department d = p == null || p.getDepartmentId() == null ? null : departmentById.get(p.getDepartmentId());
        return new StaffRow(m.userId(), m.name(), m.email(), m.roles(), p == null ? null : p.getEmployeeCode(),
                p == null ? null : p.getDesignation(), d == null ? null : new DepartmentRef(d.getId(), d.getName()),
                p == null ? null : p.getEmploymentType(), p == null ? null : p.getDateOfJoining(),
                p == null ? null : p.getDateOfLeaving(), p == null ? null : RecipientMask.mask(p.getMobile()),
                m.status(), p != null, m.active() ? today : null);
    }

    private static boolean matches(Member m, String search) {
        return m.name().toLowerCase(Locale.ROOT).contains(search)
                || m.email().toLowerCase(Locale.ROOT).contains(search)
                || (m.employeeCode() != null && m.employeeCode().toLowerCase(Locale.ROOT).contains(search));
    }

    Map<UUID, Department> departmentsById() {
        return departments.findAll().stream().collect(Collectors.toMap(Department::getId, Function.identity()));
    }

    private Map<String, Object> profileDetails(StaffProfile p) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("employeeCode", p.getEmployeeCode());
        details.put("designation", p.getDesignation());
        if (p.getDepartmentId() != null) {
            departments.findById(p.getDepartmentId()).ifPresent(d -> details.put("department", d.getName()));
        }
        details.put("employmentType", p.getEmploymentType().name());
        return details;
    }

    /** The names of the fields that differ. Values are not listed: they can be personal (mobile numbers). */
    private static List<String> changedFields(StaffProfile.Fields before, StaffProfile.Fields after) {
        Map<String, Object[]> pairs = new LinkedHashMap<>();
        pairs.put("employeeCode", new Object[] {before.employeeCode(), after.employeeCode()});
        pairs.put("designation", new Object[] {before.designation(), after.designation()});
        pairs.put("departmentId", new Object[] {before.departmentId(), after.departmentId()});
        pairs.put("employmentType", new Object[] {before.employmentType(), after.employmentType()});
        pairs.put("dateOfJoining", new Object[] {before.dateOfJoining(), after.dateOfJoining()});
        pairs.put("mobile", new Object[] {before.mobile(), after.mobile()});
        pairs.put("qualifications", new Object[] {before.qualifications(), after.qualifications()});
        pairs.put("emergencyContactName", new Object[] {before.emergencyContactName(), after.emergencyContactName()});
        pairs.put("emergencyContactMobile",
                new Object[] {before.emergencyContactMobile(), after.emergencyContactMobile()});
        List<String> changed = new ArrayList<>();
        pairs.forEach((field, values) -> {
            if (!Objects.equals(values[0], values[1])) {
                changed.add(field);
            }
        });
        return changed;
    }

    private void record(Actor actor, String action, UUID userId, Map<String, ?> details) {
        if (actor == null) {
            audit.record(action, "staff", userId, details);
        } else {
            audit.record(actor, action, "staff", userId, details);
        }
    }

    static Actor current() {
        return new Actor(CurrentUser.id().orElse(null), CurrentUser.name().orElse(null));
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
