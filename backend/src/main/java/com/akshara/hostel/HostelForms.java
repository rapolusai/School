package com.akshara.hostel;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Request bodies of the hostel API (docs/api/phase-3-hostel.md). The demo data uses the same records. */
public final class HostelForms {

    private HostelForms() {
    }

    public record HostelForm(
            @NotBlank(message = "Name the hostel.") @Size(max = 120) String name,
            @NotNull(message = "Boys, girls or staff?") HostelType type,
            UUID wardenUserId,
            @Size(max = 300) String address,
            Boolean active) {
    }

    public record BlockForm(
            @NotBlank(message = "Name the block.") @Size(max = 80) String name,
            @Min(1) @Max(30) Integer floors) {
    }

    public record RoomForm(
            @Min(0) @Max(30) Integer floor,
            @NotBlank(message = "Give the room number.") @Size(max = 20) String number,
            @NotNull(message = "Give the capacity.") @Min(1) @Max(40) Integer capacity,
            @NotNull(message = "Choose the room type.") RoomType type,
            @Min(0) Long monthlyFeePaise,
            Boolean active) {
    }

    /** Beds to add to a room, by label; labels already in the room are refused. */
    public record BedsForm(@NotEmpty(message = "Give at least one bed label.") @Size(max = 40)
            List<@NotBlank @Size(max = 20) String> labels) {
    }

    public record AllotForm(
            @NotNull(message = "Pick the student.") UUID studentId,
            @NotNull(message = "Pick the bed.") UUID bedId,
            @NotNull(message = "Give the start date.") LocalDate fromDate,
            Boolean guardianConsent) {
    }

    public record VacateForm(
            @NotBlank(message = "Say why the bed is vacated.") @Size(max = 300) String reason,
            LocalDate toDate) {
    }

    public record MoveForm(
            @NotNull(message = "Pick the new bed.") UUID bedId,
            LocalDate fromDate) {
    }

    public record MenuEntry(
            @NotNull @Min(1) @Max(7) Integer dayOfWeek,
            @NotNull Meal meal,
            @Size(max = 200) String dish,
            Boolean veg) {
    }

    /** The whole week's menu; a slot with a blank dish is cleared. */
    public record MenuForm(@NotNull @Size(max = 28) List<@Valid MenuEntry> items) {
    }

    public record MealCount(@NotNull Meal meal, @NotNull @Min(0) @Max(5000) Integer headCount) {
    }

    public record MessAttendanceForm(@NotEmpty @Size(max = 4) List<@Valid MealCount> counts) {
    }

    /** A parent's request for the child in the path. */
    public record PassRequest(
            @NotNull(message = "Outing or home leave?") PassKind kind,
            @NotNull(message = "Give the date.") LocalDate fromDate,
            LocalDate toDate,
            @NotBlank(message = "Give the reason.") @Size(max = 500) String reason) {
    }

    /** The warden raises a pass for a boarder. */
    public record WardenPassForm(
            @NotNull(message = "Pick the boarder.") UUID studentId,
            @NotNull(message = "Outing or home leave?") PassKind kind,
            @NotNull(message = "Give the date.") LocalDate fromDate,
            LocalDate toDate,
            @NotBlank(message = "Give the reason.") @Size(max = 500) String reason) {
    }

    public record DecisionForm(@Size(max = 500) String note) {
    }

    public record RollCallMark(
            @NotNull UUID studentId,
            @NotNull RollCallStatus status,
            @Size(max = 300) String note) {
    }

    public record RollCallForm(@NotNull @Size(max = 2000) List<@Valid RollCallMark> entries) {
    }

    public record ResolveForm(@Size(max = 300) String note) {
    }

    public record ComplaintForm(
            @NotNull(message = "Pick the room.") UUID roomId,
            @NotNull(message = "Choose the category.") ComplaintCategory category,
            @NotBlank(message = "Describe the problem.") @Size(max = 1000) String description) {
    }

    public record ComplaintStatusForm(
            @NotNull(message = "Choose the status.") ComplaintStatus status,
            @Size(max = 500) String note) {
    }
}
