package com.akshara.hostel;

import java.time.LocalDate;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.akshara.audit.AuditService.Actor;
import com.akshara.hostel.HostelForms.VacateForm;
import com.akshara.shared.CurrentUser;
import com.akshara.students.StudentLeft;
import com.akshara.students.StudentStatus;

/**
 * When a student leaves the school, their bed is vacated from the leaving day (or the day they moved in, if later),
 * which also stops the hostel fee. Runs inside the leaving's transaction, so a transfer certificate is never signed
 * while the bed stays taken.
 */
@Component
class HostelOnStudentLeft {

    private final AllotmentRepository allotments;
    private final AllotmentService service;

    HostelOnStudentLeft(AllotmentRepository allotments, AllotmentService service) {
        this.allotments = allotments;
        this.service = service;
    }

    @EventListener
    void on(StudentLeft event) {
        allotments.activeOfStudent(event.studentId()).ifPresent(a -> {
            LocalDate toDate = event.leftOn().isBefore(a.getFromDate()) ? a.getFromDate() : event.leftOn();
            Actor by = new Actor(CurrentUser.id().orElse(null), CurrentUser.name().orElse(null));
            service.vacate(a.getId(), new VacateForm(reason(event.status()), toDate), by);
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
