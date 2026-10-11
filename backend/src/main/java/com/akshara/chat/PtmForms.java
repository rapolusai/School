package com.akshara.chat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Request bodies of the parent-teacher meeting API (docs/api/phase-2-chat.md). The demo data uses the same records. */
public final class PtmForms {

    private PtmForms() {
    }

    public record BreakForm(@NotNull LocalTime from, @NotNull LocalTime to) {
    }

    /**
     * A meeting day for some sections. The class teacher of each section meets parents; with
     * {@code includeSubjectTeachers} the subject teachers of those sections too. Parents can book, cancel and change
     * until {@code cutoffHours} (default 12) before a slot.
     */
    public record EventForm(
            @NotBlank @Size(max = 150) String title,
            @NotNull LocalDate date,
            @NotNull LocalTime startsAt,
            @NotNull LocalTime endsAt,
            @NotNull @Min(5) @Max(60) Integer slotMinutes,
            @Size(max = PtmSlots.MAX_BREAKS) List<@NotNull @Valid BreakForm> breaks,
            @NotNull MeetingMode mode,
            @Size(max = 200) String location,
            @Size(max = 500) String videoLink,
            @NotEmpty @Size(max = 100) List<@NotNull UUID> sectionIds,
            Boolean includeSubjectTeachers,
            @Min(0) @Max(72) Integer cutoffHours,
            @Size(max = ChatRules.MAX_NOTE) String note) {
    }

    public record CancelEventForm(@NotBlank @Size(max = ChatRules.MAX_REASON) String reason) {
    }

    /** A teacher's hours at the meeting, inside the event's hours. Their slots are made again from them. */
    public record HoursForm(@NotNull LocalTime availableFrom, @NotNull LocalTime availableTo) {
    }

    public record BlockForm(@NotNull Boolean blocked) {
    }

    /** A slot for a child: by a parent for their own child, or by the school for a family. */
    public record BookForm(@NotNull UUID slotId, @NotNull UUID studentId) {
    }

    /** Another slot with the same teacher, in place of the booked one. */
    public record MoveForm(@NotNull UUID slotId) {
    }

    /**
     * After the meeting: whether the family came (on or after the meeting day; null leaves it unmarked), a note only
     * staff see and a note for the parent.
     */
    public record MarkForm(PtmAttendance attendance,
            @Size(max = ChatRules.MAX_NOTE) String privateNote,
            @Size(max = ChatRules.MAX_NOTE) String parentNote) {
    }

    public record PtmSettingsForm(@NotNull Boolean remindersEnabled) {
    }
}
