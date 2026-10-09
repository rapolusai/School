package com.akshara.admissions;

/** Small value types of the admissions API. */
public final class AdmissionTypes {

    private AdmissionTypes() {
    }

    /** An entrance test or an interview. */
    public enum AssessmentKind {
        TEST, INTERVIEW
    }

    public enum AssessmentMode {
        IN_PERSON, ONLINE
    }

    public enum SlotStatus {
        SCHEDULED, DONE, CANCELLED
    }

    /** The application fee was paid, or the school decided not to charge it. */
    public enum FeeStatus {
        PAID, WAIVED
    }

    /** How a fee was paid at the school office. There are no online payments in this release. */
    public enum PaymentMethod {
        CASH, UPI, CARD, BANK_TRANSFER
    }

    /** What happened to an application, as shown on its timeline. */
    public enum TimelineKind {
        CREATED, STAGE_CHANGED, NOTE, UPDATED, FEE_RECORDED, OFFER_UPDATED, SLOT_SCHEDULED, SLOT_RESCHEDULED,
        SLOT_OUTCOME, SLOT_CANCELLED
    }

    /** The year a family applies for on the public form: this academic year or the next one. */
    public enum YearChoice {
        CURRENT, NEXT
    }
}
