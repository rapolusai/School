package com.akshara.staff;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Request bodies of the staff, leave and staff attendance APIs (docs/api/phase-1-staff.md). */
public final class StaffForms {

    public static final String EMPLOYEE_CODE_PATTERN = "^[A-Za-z0-9][A-Za-z0-9/._-]*$";
    public static final String EMPLOYEE_CODE_MESSAGE =
            "Use letters, digits and / . _ -, starting with a letter or digit.";
    public static final String TIME_PATTERN = "^([01][0-9]|2[0-3]):[0-5][0-9]$";

    private StaffForms() {
    }

    /** The staff profile fields. Mobile numbers are Indian mobiles; the emergency contact is both or neither. */
    public record ProfileFields(
            @NotBlank @Size(max = 30) @Pattern(regexp = EMPLOYEE_CODE_PATTERN, message = EMPLOYEE_CODE_MESSAGE)
            String employeeCode,
            @NotBlank @Size(max = 100) String designation,
            UUID departmentId,
            @NotNull EmploymentType employmentType,
            @NotNull LocalDate dateOfJoining,
            @NotBlank @Size(max = 20) String mobile,
            @Size(max = 500) String qualifications,
            @Size(max = 200) String emergencyContactName,
            @Size(max = 20) String emergencyContactMobile) {
    }

    /** "Add staff": the sign-in (name, email, temporary password, staff roles) and the profile, saved together. */
    public record CreateStaff(
            @NotBlank @Size(max = 200) String name,
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 10, max = 200, message = "Use at least 10 characters.") String password,
            @NotEmpty(message = "Pick at least one role.") @Size(max = 10) List<@NotBlank String> roles,
            @NotBlank @Size(max = 30) @Pattern(regexp = EMPLOYEE_CODE_PATTERN, message = EMPLOYEE_CODE_MESSAGE)
            String employeeCode,
            @NotBlank @Size(max = 100) String designation,
            UUID departmentId,
            @NotNull EmploymentType employmentType,
            @NotNull LocalDate dateOfJoining,
            @NotBlank @Size(max = 20) String mobile,
            @Size(max = 500) String qualifications,
            @Size(max = 200) String emergencyContactName,
            @Size(max = 20) String emergencyContactMobile) {

        public ProfileFields profile() {
            return new ProfileFields(employeeCode, designation, departmentId, employmentType, dateOfJoining, mobile,
                    qualifications, emergencyContactName, emergencyContactMobile);
        }
    }

    /** Recording that someone left: their sign-in is disabled; nothing is deleted. */
    public record Leaving(@NotNull LocalDate leftOn, @NotBlank @Size(max = 500) String reason) {
    }

    public record DepartmentFields(@NotBlank @Size(max = 100) String name, UUID headUserId) {
    }

    /** Days are whole or half days. Loss of pay ignores the quota and carry-forward cap. */
    public record LeaveTypeFields(
            @NotBlank @Size(max = 60) String name,
            @NotBlank @Size(max = 10) @Pattern(regexp = "^[A-Za-z0-9]+$", message = "Use letters and digits only.")
            String code,
            BigDecimal yearlyQuota,
            BigDecimal carryForwardCap,
            boolean halfDayAllowed,
            boolean lossOfPay,
            Boolean active) {
    }

    public record LeaveApplication(
            @NotNull UUID leaveTypeId,
            @NotNull LocalDate fromDate,
            @NotNull LocalDate toDate,
            boolean halfDay,
            @NotBlank @Size(max = 500) String reason) {
    }

    /** The dialog's live preview: the same checks as applying, without the reason. */
    public record LeavePreview(@NotNull UUID leaveTypeId, @NotNull LocalDate fromDate, @NotNull LocalDate toDate,
            boolean halfDay) {
    }

    /** Approve, reject (comment required) or cancel. */
    public record Decision(@Size(max = 500) String comment) {
    }

    /** A balance set by hand for one person, leave type and academic year (the current year when left out). */
    public record BalanceFields(@NotNull UUID userId, @NotNull UUID leaveTypeId, UUID academicYearId,
            @NotNull BigDecimal opening, @NotNull BigDecimal accrued) {
    }

    public record CheckNote(@Size(max = 200) String note) {
    }

    /** One line of the daily sheet. Times are "HH:mm" in India time and optional. */
    public record DayEntry(
            @NotNull UUID userId,
            @NotNull StaffAttendanceStatus status,
            @Pattern(regexp = TIME_PATTERN, message = "Enter a time like 08:30.") String checkIn,
            @Pattern(regexp = TIME_PATTERN, message = "Enter a time like 16:00.") String checkOut) {
    }

    public record SaveDay(@NotNull @Size(max = 500) List<@NotNull @Valid DayEntry> entries) {
    }
}
