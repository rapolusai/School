package com.akshara.health;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.shared.TenantContext;

/**
 * Who is in the infirmary now and who was sent home today, for the attendance day view and the dashboards of other
 * modules: a student's id, name and section and the visit's state, nothing medical. The attendance module is not
 * edited by this slice; it may read this and the {@link StudentSentHome} event (docs/api/phase-3-health.md).
 */
@Service
@Transactional(readOnly = true)
public class InfirmaryToday {

    /** A student's visit today: open (in the infirmary now) or closed with its outcome. */
    public record StudentVisit(UUID visitId, UUID studentId, String studentName, UUID sectionId, Instant timeIn,
            Instant timeOut, VisitOutcome outcome) {
    }

    private final InfirmaryVisitRepository visits;

    InfirmaryToday(InfirmaryVisitRepository visits) {
        this.visits = visits;
    }

    /** Students' visits on a day (today when null), latest first. */
    public List<StudentVisit> studentVisits(LocalDate day) {
        TenantContext.require();
        LocalDate d = day == null ? HealthText.today() : day;
        return visits.between(HealthText.startOfDay(d), HealthText.startOfDay(d.plusDays(1))).stream()
                .filter(v -> v.getPersonKind() == PersonKind.STUDENT)
                .map(v -> new StudentVisit(v.getId(), v.getStudentId(), v.getPersonName(), v.getSectionId(),
                        v.getTimeIn(), v.getTimeOut(), v.getOutcome()))
                .toList();
    }

    /** Students in the infirmary right now. */
    public List<StudentVisit> inInfirmaryNow() {
        TenantContext.require();
        return visits.open().stream().filter(v -> v.getPersonKind() == PersonKind.STUDENT)
                .map(v -> new StudentVisit(v.getId(), v.getStudentId(), v.getPersonName(), v.getSectionId(),
                        v.getTimeIn(), null, null))
                .toList();
    }
}
