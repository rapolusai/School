package com.akshara.accounts;

/**
 * The approval chain, as pure rules. An approver has a limit (or none). They approve an expense whose total is within
 * their limit; a bigger one they can only pass on (escalate) to someone with a larger limit, and once passed on, only
 * approvers whose limit is above the passing approver's can act. Anyone in the chain can reject, with a reason.
 */
public final class Approvals {

    public enum Outcome {
        /** Within the limit: the expense is approved. */
        APPROVE,
        /** Above the limit: passed on to the next approver. */
        ESCALATE,
        /** Someone with a larger limit already passed it on; this approver cannot act. */
        NOT_YOURS,
        /** The limit is too small and nobody has a larger one: nothing can be done until the limits change. */
        NOBODY_ABOVE
    }

    private Approvals() {
    }

    /** True when the approver may act on an expense in this state (approve, pass on or reject). */
    public static boolean mayAct(Long limitPaise, Long escalatedAbovePaise) {
        return escalatedAbovePaise == null || limitPaise == null || limitPaise > escalatedAbovePaise;
    }

    /**
     * @param limitPaise the approver's limit, null for none
     * @param escalatedAbovePaise the limit of the approver who last passed it on, null when nobody did
     * @param totalPaise the expense's total including GST
     * @param largestOtherLimitPaise the largest limit among the other approvers, null when one of them has no limit,
     *     and 0 when there is nobody else
     */
    public static Outcome decide(Long limitPaise, Long escalatedAbovePaise, long totalPaise,
            Long largestOtherLimitPaise) {
        if (!mayAct(limitPaise, escalatedAbovePaise)) {
            return Outcome.NOT_YOURS;
        }
        if (limitPaise == null || totalPaise <= limitPaise) {
            return Outcome.APPROVE;
        }
        if (largestOtherLimitPaise == null || largestOtherLimitPaise > limitPaise) {
            return Outcome.ESCALATE;
        }
        return Outcome.NOBODY_ABOVE;
    }
}
