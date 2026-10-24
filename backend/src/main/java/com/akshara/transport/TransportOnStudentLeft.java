package com.akshara.transport;

import java.time.LocalDate;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.akshara.audit.AuditService.Actor;
import com.akshara.shared.CurrentUser;
import com.akshara.students.StudentLeft;
import com.akshara.students.StudentStatus;
import com.akshara.transport.TransportForms.EndAllocationForm;

/**
 * When a student leaves the school, their bus seat ends on the leaving day (or their first day on the bus, if later),
 * which frees the seat and stops the transport fee. Runs inside the leaving's transaction.
 */
@Component
class TransportOnStudentLeft {

    private final AllocationRepository allocations;
    private final AllocationService service;

    TransportOnStudentLeft(AllocationRepository allocations, AllocationService service) {
        this.allocations = allocations;
        this.service = service;
    }

    @EventListener
    void on(StudentLeft event) {
        allocations.activeOf(event.studentId()).ifPresent(a -> {
            LocalDate endDate = event.leftOn().isBefore(a.getStartDate()) ? a.getStartDate() : event.leftOn();
            Actor by = new Actor(CurrentUser.id().orElse(null), CurrentUser.name().orElse(null));
            service.end(a.getId(), new EndAllocationForm(endDate, reason(event.status())), by);
        });
    }

    private static String reason(StudentStatus status) {
        return switch (status) {
            case TRANSFERRED -> "Left the school (transferred)";
            case WITHDRAWN -> "Left the school (withdrawn)";
            case ALUMNI -> "Left the school (graduated)";
            case ACTIVE -> "Left the school";
        };
    }
}
