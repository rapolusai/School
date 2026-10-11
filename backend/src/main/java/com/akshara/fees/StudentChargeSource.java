package com.akshara.fees;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Lets another module add per-student charges (for example the school bus) to a student's dues, so they are owed,
 * collected, receipted and paid online exactly like the rest of the fees.
 *
 * <p>Whenever the fees module works out what a student should owe from their class's published structure (on publish,
 * when dues are created lazily, after a concession changes, or when {@link FeeDuesService#refreshStudentCharges} is
 * called), it also asks every bean implementing this interface for the student's charges on that structure's
 * instalments. Each charge goes to the first active fee head of its kind (by display order) and is added to that
 * head's amount in the instalment; a charge for a kind the school has no active head for is ignored. Concessions on
 * that head apply to it as usual. Charges that disappear later are removed while unpaid and kept once something is
 * paid, the same as any other due.
 *
 * <p>Implementations run inside the fees transaction with the school selected and must not change data.
 */
public interface StudentChargeSource {

    /** One instalment of the structure the student's dues come from. */
    record ChargeInstalment(UUID id, int seq, String label, LocalDate dueDate) {
    }

    /** An amount the student owes in one instalment, under a head of the given kind. */
    record StudentCharge(UUID instalmentId, FeeHeadKind kind, long amountPaise) {
    }

    /**
     * The student's charges on the given instalments of an academic year (instalments in order). Return an empty list
     * when there is nothing to charge.
     */
    List<StudentCharge> chargesFor(UUID studentId, UUID academicYearId, List<ChargeInstalment> instalments);
}
