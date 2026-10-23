package com.akshara.assist;

/** The features a school can switch on, each counted in the usage log. */
public enum AssistFeature {
    /** Report-card remark drafts. */
    REMARK,
    /** The early warning watch list (rule-based; no provider call, listed for completeness of the log). */
    EARLY_WARNING,
    /** Draft question papers. */
    PAPER,
    /** The "improve this circular" writing helper. */
    WRITING
}
