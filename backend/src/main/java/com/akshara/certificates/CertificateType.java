package com.akshara.certificates;

import java.util.List;

/**
 * The kinds of certificate the school issues, each with a default title, serial prefix and wording. Student
 * certificates use the student placeholders; the two staff certificates use the staff ones.
 */
public enum CertificateType {

    TRANSFER("TC", "Transfer Certificate", false, """
            This is to certify that {student}, {relation} {father}, admission number {admissionNo}, \
            was a bona fide student of this school from {admissionDate} to {dateOfLeaving}. \
            Date of birth as per the admission register: {dob}. Class studied at the time of leaving: {class}. \
            {promotionStatus} School dues cleared: {duesCleared}. Working days in the current session: \
            {workingDays}; days present: {daysPresent}. Reason for leaving: {reason}. \
            General conduct: {conduct}."""),
    BONAFIDE("BC", "Bonafide Certificate", false, """
            This is to certify that {student}, {relation} {father}, is a bona fide student of this school, \
            studying in {class} during the academic year {academicYear}. Admission number: {admissionNo}. \
            Date of birth as per our records: {dob}. This certificate is issued on request for {purpose}."""),
    CHARACTER("CC", "Character Certificate", false, """
            This is to certify that {student}, {relation} {father}, admission number {admissionNo}, \
            studied in this school from {admissionDate} to {to} in {class}. During this period, \
            {student} bore a {conduct} character and was found to be regular and sincere. \
            We wish {student} success in the future."""),
    FEE("FC", "Fee Certificate", false, """
            This is to certify that {student}, {relation} {father}, studying in {class} \
            (admission number {admissionNo}), has paid school fees of {feesPaid} to this school \
            for the period {from} to {to} ({receipts} receipts). This certificate is issued on request for \
            {purpose}."""),
    STUDY("SC", "Study Certificate", false, """
            This is to certify that {student}, {relation} {father}, date of birth {dob}, \
            studied in this school from {admissionDate} to {to} and is studying in {class} \
            during the academic year {academicYear}. Admission number: {admissionNo}. \
            This certificate is issued on request for {purpose}."""),
    MIGRATION("MC", "Migration / No Objection Certificate", false, """
            This is to certify that {student}, {relation} {father}, admission number {admissionNo}, \
            was a student of this school and studied in {class}. The school has no objection to \
            {student} seeking admission to any other institution. Reason: {reason}. \
            All records held by the school are in order."""),
    CUSTOM("CERT", "Certificate", false, """
            This is to certify that {student}, {relation} {father}, admission number {admissionNo}, \
            is a student of this school in {class}. {purpose}"""),
    EXPERIENCE("EXP", "Experience Certificate", true, """
            This is to certify that {staff} (employee code {employeeCode}) worked in this school as \
            {designation} in the {department} department from {dateOfJoining} to {dateOfLeaving}. \
            During this period {staff} was found to be sincere, hard-working and of {conduct} conduct. \
            We wish {staff} success in the future."""),
    SALARY("SAL", "Salary Certificate", true, """
            This is to certify that {staff} (employee code {employeeCode}) is employed in this school as \
            {designation} since {dateOfJoining}. The gross monthly salary for {salaryMonth} is {grossSalary} \
            and the net salary paid is {netSalary}. This certificate is issued on request for {purpose}.""");

    /** Placeholders of the student certificates, in the order the editor shows them. */
    public static final List<String> STUDENT_PLACEHOLDERS = List.of("student", "firstName", "admissionNo", "class",
            "section", "dob", "gender", "relation", "father", "mother", "guardian", "admissionDate", "academicYear",
            "address", "previousSchool", "purpose", "from", "to", "conduct", "reason", "dateOfLeaving",
            "promotionStatus", "duesCleared", "workingDays", "daysPresent", "feesPaid", "receipts");

    /** Placeholders of the staff certificates. */
    public static final List<String> STAFF_PLACEHOLDERS = List.of("staff", "firstName", "employeeCode", "designation",
            "department", "dateOfJoining", "dateOfLeaving", "purpose", "conduct", "reason", "salaryMonth",
            "grossSalary", "netSalary");

    /** Placeholders every certificate has, filled when it is signed. */
    public static final List<String> COMMON_PLACEHOLDERS = List.of("school", "date", "serialNo");

    private final String defaultPrefix;
    private final String defaultTitle;
    private final boolean staff;
    private final String defaultBody;

    CertificateType(String defaultPrefix, String defaultTitle, boolean staff, String defaultBody) {
        this.defaultPrefix = defaultPrefix;
        this.defaultTitle = defaultTitle;
        this.staff = staff;
        this.defaultBody = defaultBody;
    }

    public String defaultPrefix() {
        return defaultPrefix;
    }

    public String defaultTitle() {
        return defaultTitle;
    }

    /** A certificate for a member of staff (experience, salary) rather than a student. */
    public boolean isStaff() {
        return staff;
    }

    public String defaultBody() {
        return defaultBody;
    }

    /** The placeholders this type's wording may use. */
    public List<String> placeholders() {
        return staff ? STAFF_PLACEHOLDERS : STUDENT_PLACEHOLDERS;
    }

    /** The two certificates a parent may ask for from the family app. */
    public boolean familyRequestable() {
        return this == BONAFIDE || this == FEE;
    }
}
