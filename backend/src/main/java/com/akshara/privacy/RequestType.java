package com.akshara.privacy;

/** What a parent asks the school for under the Digital Personal Data Protection Act, 2023 (sections 11 to 13). */
public enum RequestType {
    /** A copy of the data the school holds (section 11): answered with a downloadable export. */
    ACCESS,
    /** Correct or complete inaccurate data (section 12). */
    CORRECTION,
    /** Erase data that is no longer needed (section 12): possible once the child has left the school. */
    ERASURE,
    /** A complaint about how the school handles personal data (section 13). */
    GRIEVANCE
}
