package com.akshara.admissions;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Where an application is in the admissions pipeline. It moves forward from an enquiry to an admitted student, or ends
 * as rejected or withdrawn. Admitted, rejected and withdrawn are final.
 */
public enum ApplicationStage {
    ENQUIRY, APPLICATION, ASSESSMENT, OFFERED, ADMITTED, REJECTED, WITHDRAWN;

    /** The pipeline columns, in order. Rejected and withdrawn applications are closed and shown separately. */
    public static final List<ApplicationStage> PIPELINE = List.of(ENQUIRY, APPLICATION, ASSESSMENT, OFFERED, ADMITTED);

    /**
     * The stages an application may move to from this one. A test or interview can be skipped (an application may be
     * offered a place directly), but an enquiry must become an application first. ADMITTED is only reached by
     * admitting the child as a student.
     */
    public Set<ApplicationStage> next() {
        return switch (this) {
            case ENQUIRY -> EnumSet.of(APPLICATION, REJECTED, WITHDRAWN);
            case APPLICATION -> EnumSet.of(ASSESSMENT, OFFERED, REJECTED, WITHDRAWN);
            case ASSESSMENT -> EnumSet.of(OFFERED, REJECTED, WITHDRAWN);
            case OFFERED -> EnumSet.of(ADMITTED, REJECTED, WITHDRAWN);
            case ADMITTED, REJECTED, WITHDRAWN -> EnumSet.noneOf(ApplicationStage.class);
        };
    }

    public boolean canMoveTo(ApplicationStage target) {
        return next().contains(target);
    }

    /** Admitted, rejected and withdrawn applications cannot change any more (notes can still be added). */
    public boolean isFinal() {
        return next().isEmpty();
    }

    /** Still being worked on: an enquiry, an application, an assessment or an offer. */
    public boolean isOpen() {
        return !isFinal();
    }
}
