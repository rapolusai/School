package com.akshara.assist;

/** Where the follow-up of a watch list entry stands. */
public enum WatchStatus {
    /** Listed, nobody assigned yet. */
    OPEN,
    /** A teacher or counsellor is following it up. */
    FOLLOWING,
    /** Followed up and closed; the student stays listed while the score says so. */
    RESOLVED
}
