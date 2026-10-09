package com.akshara.staff;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.staff.LeaveService.StaffLeave;
import com.akshara.staff.StaffAttendanceService.PersonMonth;
import com.akshara.staff.StaffForms.CreateStaff;
import com.akshara.staff.StaffForms.Leaving;
import com.akshara.staff.StaffForms.ProfileFields;
import com.akshara.staff.StaffService.RoleRef;
import com.akshara.staff.StaffService.StaffDetail;
import com.akshara.staff.StaffService.StaffPage;
import com.akshara.staff.StaffService.StaffQuery;

/** The staff directory and profiles. staff.read to look, staff.manage to add staff, edit profiles and record leaving. */
@RestController
@RequestMapping("/api/staff")
public class StaffController {

    static final String READ = "hasAuthority('staff.read')";
    static final String MANAGE = "hasAuthority('staff.manage')";

    private final StaffService staff;
    private final LeaveService leave;
    private final StaffAttendanceService attendance;
    private final PasswordEncoder passwordEncoder;

    StaffController(StaffService staff, LeaveService leave, StaffAttendanceService attendance,
            PasswordEncoder passwordEncoder) {
        this.staff = staff;
        this.leave = leave;
        this.attendance = attendance;
        this.passwordEncoder = passwordEncoder;
    }

    /** The directory, by name. {@code status} is ACTIVE or LEFT; {@code incomplete=true} lists missing profiles. */
    @GetMapping
    @PreAuthorize(READ)
    public StaffPage list(
            @RequestParam(required = false) UUID departmentId,
            @RequestParam(required = false) @Size(max = 100) String designation,
            @RequestParam(required = false) @Size(max = 50) String role,
            @RequestParam(required = false) StaffStatus status,
            @RequestParam(required = false) Boolean incomplete,
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100_000) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(StaffService.MAX_PAGE_SIZE) int size) {
        return staff.list(new StaffQuery(departmentId, designation, role, status, incomplete, q, page, size));
    }

    @GetMapping("/designations")
    @PreAuthorize(READ)
    public List<String> designations() {
        return staff.designations();
    }

    /** The roles a staff member can be given (all but Parent and Student). */
    @GetMapping("/roles")
    @PreAuthorize("hasAnyAuthority('staff.read', 'staff.manage')")
    public List<RoleRef> roles() {
        return staff.staffRoles();
    }

    @GetMapping("/{userId}")
    @PreAuthorize(READ)
    public StaffDetail get(@PathVariable UUID userId) {
        return staff.detail(userId);
    }

    /** Adds a staff member: the sign-in and the profile in one transaction. */
    @PostMapping
    @PreAuthorize(MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public StaffDetail create(@Valid @RequestBody CreateStaff request) {
        String hash = passwordEncoder.encode(request.password());
        return staff.create(request, hash, StaffAuth.actor());
    }

    /** Creates or updates the profile of someone who already has a staff sign-in. */
    @PutMapping("/{userId}/profile")
    @PreAuthorize(MANAGE)
    public StaffDetail saveProfile(@PathVariable UUID userId, @Valid @RequestBody ProfileFields request) {
        return staff.saveProfile(userId, request, StaffAuth.actor());
    }

    /** Records that the person left: their sign-in is disabled and open leave is cancelled. */
    @PostMapping("/{userId}/leaving")
    @PreAuthorize(MANAGE)
    public StaffDetail leaving(@PathVariable UUID userId, @Valid @RequestBody Leaving request) {
        return staff.recordLeaving(userId, request, StaffAuth.actor());
    }

    /** Their leave balances and requests for an academic year (the current one by default). */
    @GetMapping("/{userId}/leave")
    @PreAuthorize("hasAnyAuthority('staff.read', 'leave.approve')")
    public StaffLeave leave(@PathVariable UUID userId, @RequestParam(required = false) UUID yearId) {
        return leave.ofStaff(userId, yearId, StaffAuth.caller());
    }

    /** Their attendance for a month (YYYY-MM, this month by default). */
    @GetMapping("/{userId}/attendance")
    @PreAuthorize("hasAnyAuthority('staff.read', 'staff_attendance.manage')")
    public PersonMonth attendance(@PathVariable UUID userId, @RequestParam(required = false) String month) {
        return attendance.personMonth(userId, StaffAuth.month(month));
    }
}
