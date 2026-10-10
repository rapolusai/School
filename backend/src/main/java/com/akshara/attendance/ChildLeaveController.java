package com.akshara.attendance;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.attendance.ChildLeaveService.Application;
import com.akshara.attendance.ChildLeaveService.ChildLeaveView;
import com.akshara.attendance.ChildLeaveService.FamilyLeave;
import com.akshara.attendance.ChildLeaveService.Inbox;
import com.akshara.audit.AuditService.Actor;
import com.akshara.shared.CurrentUser;

/**
 * A child's leave (absence notes). Parents (child.view) apply for and cancel their own child's leave; a student sees
 * their own requests; class teachers (attendance.mark, their own sections) and attendance.manage (every section) see
 * the requests and approve or reject them.
 */
@RestController
public class ChildLeaveController {

    private final ChildLeaveService leave;
    private final AttendanceAccess access;

    ChildLeaveController(ChildLeaveService leave, AttendanceAccess access) {
        this.leave = leave;
        this.access = access;
    }

    public record ApplyRequest(@NotNull LocalDate fromDate, @NotNull LocalDate toDate, Boolean halfDay,
            @NotBlank @Size(max = ChildLeaveService.REASON_MAX) String reason) {
    }

    public record DecisionRequest(@Size(max = ChildLeaveService.COMMENT_MAX) String comment) {
    }

    // ------------------------------------------------------------------ parents and students

    @GetMapping("/api/me/children/{studentId}/leave-requests")
    @PreAuthorize("hasAuthority('child.view')")
    public FamilyLeave child(@PathVariable UUID studentId) {
        return leave.forChild(CurrentUser.requireId(), studentId, AttendanceService.today());
    }

    @PostMapping("/api/me/children/{studentId}/leave-requests")
    @PreAuthorize("hasAuthority('child.view')")
    @ResponseStatus(HttpStatus.CREATED)
    public ChildLeaveView apply(@PathVariable UUID studentId, @Valid @RequestBody ApplyRequest request) {
        return leave.apply(CurrentUser.requireId(), studentId, new Application(request.fromDate(), request.toDate(),
                Boolean.TRUE.equals(request.halfDay()), request.reason()), actor(), AttendanceService.today(),
                Instant.now());
    }

    @PostMapping("/api/me/children/{studentId}/leave-requests/{id}/cancel")
    @PreAuthorize("hasAuthority('child.view')")
    public ChildLeaveView cancel(@PathVariable UUID studentId, @PathVariable UUID id) {
        return leave.cancel(CurrentUser.requireId(), studentId, id, actor(), AttendanceService.today(), Instant.now());
    }

    /** A student's own leave requests, read only (their parents apply). */
    @GetMapping("/api/me/leave-requests")
    @PreAuthorize("hasAuthority('dashboard.view')")
    public FamilyLeave mine() {
        return leave.forStudent(CurrentUser.requireId(), AttendanceService.today());
    }

    // ------------------------------------------------------------------ class teachers and admins

    @GetMapping("/api/attendance/leave-requests")
    @PreAuthorize(AttendanceController.READ)
    public Inbox inbox() {
        return leave.inbox(access.current(), AttendanceService.today(), Instant.now());
    }

    @PostMapping("/api/attendance/leave-requests/{id}/approve")
    @PreAuthorize(AttendanceController.MARK)
    public ChildLeaveView approve(@PathVariable UUID id,
            @Valid @RequestBody(required = false) DecisionRequest request) {
        return leave.approve(access.current(), id, request == null ? null : request.comment(), actor(),
                AttendanceService.today(), Instant.now());
    }

    @PostMapping("/api/attendance/leave-requests/{id}/reject")
    @PreAuthorize(AttendanceController.MARK)
    public ChildLeaveView reject(@PathVariable UUID id, @Valid @RequestBody DecisionRequest request) {
        return leave.reject(access.current(), id, request.comment(), actor(), AttendanceService.today(),
                Instant.now());
    }

    private static Actor actor() {
        return new Actor(CurrentUser.requireId(), CurrentUser.name().orElse(null));
    }
}
