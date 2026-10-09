package com.akshara.fees;

/** How a school charges for paying an instalment late. */
public enum LateFeeMode {
    /** No late fee. */
    NONE,
    /** A flat amount per overdue instalment once the grace days have passed. */
    FLAT,
    /** An amount for each day after the grace days, up to a cap per instalment. */
    PER_DAY
}
