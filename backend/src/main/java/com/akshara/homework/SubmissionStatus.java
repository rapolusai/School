package com.akshara.homework;

/** Where a student's submission stands. */
public enum SubmissionStatus {
    /** Sent and waiting for the teacher. */
    SUBMITTED,
    /** Checked by the teacher; it can no longer be changed. */
    REVIEWED,
    /** The teacher asked for it to be done again; the student can submit again. */
    NEEDS_REDO
}
