package com.akshara.students;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Request bodies of the students API. The same records are used by the demo data seeder. */
public final class StudentForms {

    static final String ADMISSION_NO_PATTERN = "^[A-Za-z0-9][A-Za-z0-9/._-]*$";
    static final String ADMISSION_NO_MESSAGE = "Use letters, digits and / . _ - only.";
    static final String BLOOD_GROUP_PATTERN = "^$|^(A|B|AB|O)[+-]$";
    static final String APAAR_PATTERN = "^$|^[0-9]{12}$";

    private StudentForms() {
    }

    public record GuardianFields(
            @NotBlank @Size(max = 200) String name,
            @NotNull GuardianRelation relation,
            @NotBlank @Pattern(regexp = Phones.INPUT_PATTERN, message = Phones.MESSAGE) String phone,
            @Email @Size(max = 254) String email,
            @Size(max = 100) String occupation,
            Boolean primary) {

        /** Leaving out "primary" means false. */
        public GuardianFields {
            primary = Boolean.TRUE.equals(primary);
        }
    }

    public record CreateStudent(
            @NotBlank @Size(max = 30) @Pattern(regexp = ADMISSION_NO_PATTERN, message = ADMISSION_NO_MESSAGE)
            String admissionNo,
            @NotBlank @Size(max = 100) String firstName,
            @Size(max = 100) String lastName,
            @NotNull @Past LocalDate dateOfBirth,
            @NotNull Gender gender,
            @NotNull LocalDate admissionDate,
            @Pattern(regexp = BLOOD_GROUP_PATTERN, message = "Pick a blood group from the list.") String bloodGroup,
            @Size(max = 500) String address,
            @Size(max = 200) String previousSchool,
            @Pattern(regexp = APAAR_PATTERN, message = "An APAAR ID has 12 digits.") String apaarId,
            @NotNull(message = "Pick a section.") UUID sectionId,
            @Min(1) @Max(999) Integer rollNo,
            @NotEmpty(message = "Add at least one parent or guardian.") @Size(max = 4,
                    message = "Add at most 4 parents or guardians.") List<@NotNull @Valid GuardianFields> guardians) {
    }

    /** Profile changes. When sectionId is set the student's current-year class and roll number change too. */
    public record UpdateStudent(
            @NotBlank @Size(max = 30) @Pattern(regexp = ADMISSION_NO_PATTERN, message = ADMISSION_NO_MESSAGE)
            String admissionNo,
            @NotBlank @Size(max = 100) String firstName,
            @Size(max = 100) String lastName,
            @NotNull @Past LocalDate dateOfBirth,
            @NotNull Gender gender,
            @NotNull LocalDate admissionDate,
            @Pattern(regexp = BLOOD_GROUP_PATTERN, message = "Pick a blood group from the list.") String bloodGroup,
            @Size(max = 500) String address,
            @Size(max = 200) String previousSchool,
            @Pattern(regexp = APAAR_PATTERN, message = "An APAAR ID has 12 digits.") String apaarId,
            UUID sectionId,
            @Min(1) @Max(999) Integer rollNo) {
    }

    /** Transfer (the student moves to another school) or withdrawal. */
    public record Leave(
            @NotNull StudentStatus status,
            @NotNull LocalDate leftOn,
            @NotBlank @Size(max = 500) String reason) {
    }

    /**
     * Moves every active student of a section to a section of another year, or marks them alumni when the class
     * graduates. The source year defaults to the current year.
     */
    public record Promote(
            @NotNull(message = "Pick the section to promote.") UUID fromSectionId,
            UUID fromYearId,
            UUID toSectionId,
            UUID toYearId,
            Boolean graduate) {

        /** Leaving out "graduate" means false. */
        public Promote {
            graduate = Boolean.TRUE.equals(graduate);
        }
    }

    public enum SignInMode {
        /** Create a new sign-in with the given email and password. */
        CREATE,
        /** Link a sign-in that already exists in this school. */
        LINK
    }

    public record SignIn(
            @NotNull SignInMode mode,
            @NotBlank @Email @Size(max = 254) String email,
            @Size(min = 10, max = 200, message = "Use at least 10 characters.") String password) {

        @Override
        public String toString() {
            // Never let the password reach a log line.
            return "SignIn[mode=" + mode + ", email=" + email + "]";
        }
    }
}
