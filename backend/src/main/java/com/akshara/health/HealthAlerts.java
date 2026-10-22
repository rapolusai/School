package com.akshara.health;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.shared.TenantContext;

/**
 * Allergy badges for other modules (the mess, transport, attendance, the front office): the severity and a short
 * label only, never the rest of the record. A badge is not a diagnosis and needs no health permission to show next
 * to a student the caller may already see. Call {@link #forStudents(Collection)} with the ids on the screen.
 */
@Service
@Transactional(readOnly = true)
public class HealthAlerts {

    /** One allergy as a badge: "Peanuts (SEVERE)". */
    public record AllergyBadge(UUID studentId, AllergySeverity severity, String label) {
    }

    private static final Comparator<AllergyBadge> MOST_SEVERE_FIRST = Comparator
            .comparing((AllergyBadge b) -> b.severity().ordinal()).reversed()
            .thenComparing(AllergyBadge::label, String.CASE_INSENSITIVE_ORDER);

    private final RecordItemRepository items;

    HealthAlerts(RecordItemRepository items) {
        this.items = items;
    }

    /** The allergy badges of each student that has any, most severe first. Unknown ids are simply missing. */
    public Map<UUID, List<AllergyBadge>> forStudents(Collection<UUID> studentIds) {
        TenantContext.require();
        if (studentIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<AllergyBadge>> result = new LinkedHashMap<>();
        items.findByStudentIdInAndKind(Set.copyOf(studentIds), ItemKind.ALLERGY).stream()
                .map(i -> new AllergyBadge(i.getStudentId(), i.getSeverity(), i.getName()))
                .sorted(MOST_SEVERE_FIRST)
                .forEach(b -> result.computeIfAbsent(b.studentId(), k -> new java.util.ArrayList<>()).add(b));
        result.replaceAll((k, v) -> List.copyOf(v));
        return result;
    }

    /** The badges of one student, most severe first; empty when none. */
    public List<AllergyBadge> forStudent(UUID studentId) {
        return forStudents(List.of(studentId)).getOrDefault(studentId, List.of());
    }
}
