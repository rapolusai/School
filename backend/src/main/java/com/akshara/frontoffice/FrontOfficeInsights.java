package com.akshara.frontoffice;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.frontoffice.FrontOfficePeople.Pupil;
import com.akshara.frontoffice.FrontOfficeViews.FollowUpQueue;
import com.akshara.shared.Permissions;
import com.akshara.shared.TenantContext;

/**
 * Figures for the staff dashboard's front office card: what the office has to do (passes to approve, follow-ups due),
 * what the gate has waiting (passes approved, visitors inside), and, for a class teacher, the early pickups approved
 * today for their sections (the in-app notice). Each figure is filled only for people with the permission behind it.
 */
@Service
@Transactional(readOnly = true)
public class FrontOfficeInsights {

    /** One early pickup a class teacher is told about. */
    public record ClassPickup(UUID passId, String studentName, String className, Instant pickupAt, String collector,
            PassStatus status) {
    }

    /** Null figures are not for this person. Null card when nothing on it is. */
    public record Card(Integer passesToApprove, Integer followUpsDue, Integer followUpsOverdue, Integer passesAtGate,
            Integer visitorsInside, Integer visitorsToday, List<ClassPickup> classPickups) {
    }

    private final GatePassRepository passes;
    private final VisitorRepository visitors;
    private final EnquiryService enquiries;
    private final FrontOfficePeople people;

    FrontOfficeInsights(GatePassRepository passes, VisitorRepository visitors, EnquiryService enquiries,
            FrontOfficePeople people) {
        this.passes = passes;
        this.visitors = visitors;
        this.enquiries = enquiries;
        this.people = people;
    }

    /** The card for the signed-in person with these permissions, or null when none of it is for them. */
    public Card card(UUID userId, Set<String> permissions, LocalDate today) {
        TenantContext.require();
        boolean manage = permissions.contains(Permissions.FRONTOFFICE_MANAGE);
        boolean read = manage || permissions.contains(Permissions.FRONTOFFICE_READ);
        boolean gate = manage || permissions.contains(Permissions.GATE_PASS);
        List<GatePass> open = (read || gate) ? passes.open() : List.of();
        Instant now = Instant.now();
        Integer toApprove = read ? (int) open.stream().filter(p -> p.getStatus() == PassStatus.REQUESTED).count()
                : null;
        Integer atGate = (read || gate) ? (int) open.stream()
                .filter(p -> p.getStatus() == PassStatus.APPROVED && !p.otpExpired(now)).count() : null;
        Integer inside = (read || gate) ? (int) visitors.countInside() : null;
        Integer todayCount = read ? visitors.registerBetween(FrontOfficeText.startOfDay(today),
                FrontOfficeText.startOfDay(today.plusDays(1))).size() : null;
        Integer due = null;
        Integer overdue = null;
        if (read) {
            FollowUpQueue queue = enquiries.followUps();
            due = queue.dueToday();
            overdue = queue.overdue();
        }
        List<ClassPickup> classPickups = classPickups(userId, today);
        if (!read && !gate && classPickups.isEmpty()) {
            return null;
        }
        return new Card(toApprove, due, overdue, atGate, inside, todayCount, classPickups);
    }

    /** Today's approved or released early pickups of the sections the user is class teacher of. */
    private List<ClassPickup> classPickups(UUID userId, LocalDate today) {
        Set<UUID> sections = people.sectionsLedBy(userId);
        if (sections.isEmpty()) {
            return List.of();
        }
        List<GatePass> rows = passes.between(FrontOfficeText.startOfDay(today),
                FrontOfficeText.startOfDay(today.plusDays(1))).stream()
                .filter(p -> p.getStatus() == PassStatus.APPROVED || p.getStatus() == PassStatus.RELEASED).toList();
        if (rows.isEmpty()) {
            return List.of();
        }
        var pupils = people.pupils(rows.stream().map(GatePass::getStudentId).toList());
        return rows.stream()
                .filter(p -> {
                    Pupil pupil = pupils.get(p.getStudentId());
                    return pupil != null && pupil.sectionId() != null && sections.contains(pupil.sectionId());
                })
                .map(p -> {
                    Pupil pupil = pupils.get(p.getStudentId());
                    return new ClassPickup(p.getId(), pupil.fullName(), pupil.className(), p.getPickupAt(),
                            p.getCollectorKind() == CollectorKind.SELF ? "Parent" : p.getCollectorName(),
                            p.getStatus());
                })
                .sorted((a, b) -> a.pickupAt().compareTo(b.pickupAt()))
                .toList();
    }
}
