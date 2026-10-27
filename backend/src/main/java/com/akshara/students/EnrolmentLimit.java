package com.akshara.students;

/**
 * How many students a school may have on roll, asked before students are added (admitted one at a time, by the
 * admissions office, or imported). The billing module implements it from the school's plan; the students module never
 * depends on billing. Called inside the caller's transaction, so a school's additions are counted one at a time.
 */
public interface EnrolmentLimit {

    /**
     * Throws a {@code 409} problem when adding {@code adding} students would take the current school past its plan's
     * student limit.
     */
    void checkRoomFor(int adding);
}
