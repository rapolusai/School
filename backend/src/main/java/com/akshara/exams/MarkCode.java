package com.akshara.exams;

/**
 * Instead of marks: AB absent (counts as zero and fails the subject), ML medical and EX exempt (both left out of the
 * totals).
 */
public enum MarkCode {
    AB,
    ML,
    EX;

    /** Left out of totals and percentages altogether. */
    boolean excluded() {
        return this != AB;
    }
}
