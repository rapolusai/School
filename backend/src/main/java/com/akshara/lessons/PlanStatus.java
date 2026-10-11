package com.akshara.lessons;

/**
 * Where a lesson plan stands. A teacher edits DRAFT and RETURNED plans and submits them; an approver approves or
 * returns a SUBMITTED plan; an APPROVED plan is marked TAUGHT on the day it was taught.
 */
public enum PlanStatus {
    DRAFT,
    SUBMITTED,
    APPROVED,
    RETURNED,
    TAUGHT;

    boolean editable() {
        return this == DRAFT || this == RETURNED;
    }
}
