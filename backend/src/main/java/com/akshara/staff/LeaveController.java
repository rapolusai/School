package com.akshara.staff;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.staff.LeaveService.BalanceView;
import com.akshara.staff.LeaveService.LeaveRequestView;
import com.akshara.staff.LeaveService.LeaveTypeView;
import com.akshara.staff.LeaveService.MyLeave;
import com.akshara.staff.LeaveService.Preview;
import com.akshara.staff.StaffForms.BalanceFields;
import com.akshara.staff.StaffForms.Decision;
import com.akshara.staff.StaffForms.LeaveApplication;
import com.akshara.staff.StaffForms.LeavePreview;
import com.akshara.staff.StaffForms.LeaveTypeFields;

/**
 * Leave. leave.request to apply, see one's own balances and cancel one's own requests; department heads decide what
 * is routed to them; leave.approve decides any request but one's own; staff.manage sets up leave types and balances.
 */
@RestController
@RequestMapping("/api/leave")
public class LeaveController {

    static final String REQUEST = "hasAuthority('leave.request')";
    static final String REQUEST_OR_APPROVE = "hasAnyAuthority('leave.request', 'leave.approve')";
    static final String MANAGE = "hasAuthority('staff.manage')";

    private final LeaveService leave;

    LeaveController(LeaveService leave) {
        this.leave = leave;
    }

    @GetMapping("/types")
    @PreAuthorize("hasAnyAuthority('leave.request', 'leave.approve', 'staff.read', 'staff.manage')")
    public List<LeaveTypeView> types(@RequestParam(defaultValue = "false") boolean all) {
        return leave.types(all);
    }

    @PostMapping("/types")
    @PreAuthorize(MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public LeaveTypeView createType(@Valid @RequestBody LeaveTypeFields request) {
        return leave.createType(request, StaffAuth.actor());
    }

    /** Adds the usual types (casual, sick, earned, maternity, paternity, loss of pay) the school does not have yet. */
    @PostMapping("/types/standard")
    @PreAuthorize(MANAGE)
    public List<LeaveTypeView> addStandardTypes() {
        return leave.addStandardTypes(StaffAuth.actor());
    }

    @PutMapping("/types/{id}")
    @PreAuthorize(MANAGE)
    public LeaveTypeView updateType(@PathVariable UUID id, @Valid @RequestBody LeaveTypeFields request) {
        return leave.updateType(id, request, StaffAuth.actor());
    }

    @DeleteMapping("/types/{id}")
    @PreAuthorize(MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteType(@PathVariable UUID id) {
        leave.deleteType(id, StaffAuth.actor());
    }

    /** The caller's balances and requests for an academic year (the current one by default). */
    @GetMapping("/me")
    @PreAuthorize(REQUEST)
    public MyLeave mine(@RequestParam(required = false) UUID yearId) {
        return leave.mine(StaffAuth.caller(), yearId);
    }

    /** Working days and the balance after the request, for the apply dialog. Nothing is saved. */
    @PostMapping("/preview")
    @PreAuthorize(REQUEST)
    public Preview preview(@Valid @RequestBody LeavePreview request) {
        return leave.preview(StaffAuth.caller(), request.leaveTypeId(), request.fromDate(), request.toDate(),
                request.halfDay());
    }

    @PostMapping("/requests")
    @PreAuthorize(REQUEST)
    @ResponseStatus(HttpStatus.CREATED)
    public LeaveRequestView apply(@Valid @RequestBody LeaveApplication request) {
        return leave.apply(StaffAuth.caller(), request, Instant.now());
    }

    @PostMapping("/requests/{id}/cancel")
    @PreAuthorize(REQUEST_OR_APPROVE)
    public LeaveRequestView cancel(@PathVariable UUID id, @Valid @RequestBody(required = false) Decision request) {
        return leave.cancel(id, request == null ? null : request.comment(), StaffAuth.caller(), Instant.now());
    }

    @PostMapping("/requests/{id}/approve")
    @PreAuthorize(REQUEST_OR_APPROVE)
    public LeaveRequestView approve(@PathVariable UUID id, @Valid @RequestBody(required = false) Decision request) {
        return leave.approve(id, request == null ? null : request.comment(), StaffAuth.caller(), Instant.now());
    }

    /** Rejecting needs a comment saying why. */
    @PostMapping("/requests/{id}/reject")
    @PreAuthorize(REQUEST_OR_APPROVE)
    public LeaveRequestView reject(@PathVariable UUID id, @Valid @RequestBody(required = false) Decision request) {
        return leave.reject(id, request == null ? null : request.comment(), StaffAuth.caller(), Instant.now());
    }

    /**
     * Pending requests the caller can decide: those routed to them as department head and, for leave.approve, those
     * routed to the school ({@code all=true}: every pending request but their own).
     */
    @GetMapping("/inbox")
    @PreAuthorize(REQUEST_OR_APPROVE)
    public List<LeaveRequestView> inbox(@RequestParam(defaultValue = "false") boolean all) {
        return leave.inbox(StaffAuth.caller(), all);
    }

    /** Sets a person's opening balance and allowance of a type for a year. */
    @PutMapping("/balances")
    @PreAuthorize(MANAGE)
    public BalanceView setBalance(@Valid @RequestBody BalanceFields request) {
        return leave.setBalance(request, StaffAuth.actor());
    }
}
