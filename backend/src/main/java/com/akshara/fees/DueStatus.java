package com.akshara.fees;

/** Where a due (or a whole instalment) stands on a given day. */
public enum DueStatus {
    /** Nothing paid yet and the due date is more than {@link FeeMath#DUE_SOON_DAYS} days away. */
    UPCOMING,
    /** Nothing paid yet and the due date is today or within {@link FeeMath#DUE_SOON_DAYS} days. */
    DUE,
    /** Part paid and the due date has not passed. */
    PARTIAL,
    /** Something is still owed after the due date. */
    OVERDUE,
    /** Nothing is owed. */
    PAID
}
