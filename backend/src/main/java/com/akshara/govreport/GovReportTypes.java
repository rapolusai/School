package com.akshara.govreport;

/** Small value types of the government reporting API. */
public final class GovReportTypes {

    private GovReportTypes() {
    }

    /** The code lists kept as data rows (docs/api/phase-3-govreport.md, "Code lists"). */
    public enum CodeListType {
        SOCIAL_CATEGORY, RELIGION, MOTHER_TONGUE, CWSN_TYPE, SCHOOL_CATEGORY, MANAGEMENT, RTE_INCOME_LIMIT, SCHEME,
        RTE_DOCUMENT
    }

    /** Who last changed a student's government fields. */
    public enum UpdatedVia {
        OFFICE, FAMILY, IMPORT
    }

    public enum IntakeStatus {
        OPEN, LOTTERY_DONE, CLOSED
    }

    public enum Eligibility {
        PENDING, ELIGIBLE, INELIGIBLE
    }

    public enum AllotmentOutcome {
        ALLOTTED, WAITLISTED
    }

    public enum SchemeStatus {
        APPLIED, APPROVED, RECEIVED, REJECTED
    }

    /** The UDISE+ and OASIS exports, each audited when downloaded. */
    public enum ExportKind {
        UDISE_CSV, UDISE_XLSX, OASIS_XLSX, CBSE_REGISTRATION_XLSX, SCHEME_CSV
    }
}
