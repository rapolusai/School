package com.akshara.chat;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Request bodies of the messages API (docs/api/phase-2-chat.md). The demo data uses the same records. */
public final class ChatForms {

    private ChatForms() {
    }

    /** A member of staff starts a conversation with a parent about a student. */
    public record StartWithParent(@NotNull UUID studentId, @NotNull UUID parentUserId) {
    }

    /** A parent starts a conversation with a teacher of their child. */
    public record StartWithTeacher(@NotNull UUID studentId, @NotNull UUID teacherUserId) {
    }

    public record ReportForm(@NotBlank @Size(max = ChatRules.MAX_REASON) String reason) {
    }

    public record CloseForm(@NotBlank @Size(max = ChatRules.MAX_REASON) String reason) {
    }

    public record ReviewForm(@Size(max = ChatRules.MAX_REASON) String note) {
    }

    /** "Away until": the day the teacher is back, and a note parents see. A null {@code returnsOn} clears it. */
    public record AwayForm(LocalDate returnsOn, @Size(max = 300) String note) {
    }

    public record SettingsForm(
            @NotEmpty @Size(max = 7) List<DayOfWeek> availableDays,
            @NotNull LocalTime availableFrom,
            @NotNull LocalTime availableTo,
            @NotNull Boolean notifyNewMessages) {
    }
}
