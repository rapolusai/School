package com.akshara.students;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;

/**
 * Erasure of a student's personal data for a data protection request (Digital Personal Data Protection Act, 2023).
 * Deliberately narrow: it only anonymises a student who has already left the school, and keeps the student row with
 * its admission number, so the fee receipts and other records the school must keep by law still point at a student.
 * The privacy module calls it after its own checks (an open erasure request and a second confirmation).
 */
@Service
@Transactional
public class StudentErasure {

    /** The name an erased student is shown with everywhere, including old class lists and reports. */
    public static final String ERASED_NAME = "Erased student";

    /**
     * What was erased. {@code studentUserId} is the student's own sign-in that was unlinked (if any);
     * {@code parentUserIds} are parents' sign-ins whose guardian record was deleted because it had no other child.
     */
    public record Result(UUID studentId, String admissionNo, UUID studentUserId, List<UUID> parentUserIds,
            int guardiansDeleted, int guardiansKept) {
    }

    private final StudentRepository students;
    private final GuardianRepository guardians;
    private final StudentGuardianRepository links;
    private final AuditService audit;

    StudentErasure(StudentRepository students, GuardianRepository guardians, StudentGuardianRepository links,
            AuditService audit) {
        this.students = students;
        this.guardians = guardians;
        this.links = links;
        this.audit = audit;
    }

    /**
     * Anonymises a student who has transferred, withdrawn or graduated: placeholder name, year of birth only, no
     * address, blood group, previous school, APAAR ID, leaving reason or sign-in. The student's links to parents are
     * removed; a parent record with no other child in the school is deleted, one shared with a sibling stays. 404 for
     * a student of another school, 409 for a student who is still active.
     */
    public Result anonymiseLeftStudent(UUID studentId, Actor actor) {
        TenantContext.require();
        Student student = students.findById(studentId).orElseThrow(() -> ApiException.notFound("Student"));
        if (student.isActive()) {
            throw new ApiException(HttpStatus.CONFLICT, "Still enrolled",
                    "Only a student who has left the school (transferred, withdrawn or alumni) can be erased.");
        }
        UUID studentUserId = student.getUserAccountId();
        student.anonymise(ERASED_NAME);
        students.flush();

        List<StudentGuardian> studentLinks = links.findByStudentId(studentId);
        links.deleteAll(studentLinks);
        links.flush();
        List<UUID> parentUserIds = new ArrayList<>();
        int deleted = 0;
        int kept = 0;
        for (StudentGuardian link : studentLinks) {
            if (links.countByGuardianId(link.getGuardianId()) > 0) {
                kept++;
                continue;
            }
            Guardian guardian = guardians.findById(link.getGuardianId()).orElse(null);
            if (guardian == null) {
                continue;
            }
            if (guardian.getUserAccountId() != null) {
                parentUserIds.add(guardian.getUserAccountId());
            }
            guardians.delete(guardian);
            deleted++;
        }
        guardians.flush();

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("admissionNo", student.getAdmissionNo());
        details.put("guardiansDeleted", deleted);
        details.put("guardiansKept", kept);
        details.put("signInUnlinked", studentUserId != null);
        audit.record(actor, "student.anonymised", "student", studentId, details);
        return new Result(studentId, student.getAdmissionNo(), studentUserId, List.copyOf(parentUserIds), deleted,
                kept);
    }
}
