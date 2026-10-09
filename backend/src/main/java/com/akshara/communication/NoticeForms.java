package com.akshara.communication;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.akshara.communication.CommunicationTypes.AckChannel;
import com.akshara.communication.CommunicationTypes.CalendarAudience;
import com.akshara.communication.CommunicationTypes.Category;
import com.akshara.communication.CommunicationTypes.EntryKind;
import com.akshara.communication.CommunicationTypes.NoticeChannel;
import com.akshara.communication.CommunicationTypes.ReminderChannel;

/** Request bodies of the circulars and calendar API. The same records are used by the demo data seeder. */
public final class NoticeForms {

    public static final int MAX_TITLE = 200;
    public static final int MAX_BODY = 5000;
    public static final int MAX_BULK = 60;

    private NoticeForms() {
    }

    /**
     * Who a circular is for: the whole school, or any mix of classes, sections and roles. Role codes are staff roles
     * (for example TEACHER) or PARENT and STUDENT, which mean the parents or students of the chosen classes and
     * sections (of every class when none is chosen).
     */
    public record AudienceRequest(
            Boolean wholeSchool,
            @Size(max = 100) List<@NotNull UUID> classIds,
            @Size(max = 300) List<@NotNull UUID> sectionIds,
            @Size(max = 20) List<@NotBlank @Size(max = 40) String> roles) {

        public AudienceRequest {
            wholeSchool = Boolean.TRUE.equals(wholeSchool);
            classIds = classIds == null ? List.of() : List.copyOf(classIds);
            sectionIds = sectionIds == null ? List.of() : List.copyOf(sectionIds);
            roles = roles == null ? List.of() : List.copyOf(roles);
        }

        public static AudienceRequest wholeSchoolAudience() {
            return new AudienceRequest(true, List.of(), List.of(), List.of());
        }
    }

    /**
     * A new circular or the changed draft. The board (in the app) is always used; {@code channels} adds SMS, WhatsApp
     * or email. {@code scheduledAt} sends it later instead of on approval or submission.
     */
    public record CircularRequest(
            @NotBlank @Size(max = MAX_TITLE) String title,
            @NotBlank @Size(max = MAX_BODY) String body,
            @NotNull Category category,
            @NotNull @Valid AudienceRequest audience,
            @Size(max = 3) Set<@NotNull NoticeChannel> channels,
            Instant scheduledAt) {

        public CircularRequest {
            channels = channels == null ? Set.of() : Set.copyOf(channels);
        }
    }

    /** What sending would reach and cost, before the circular is saved. Title and body may still be empty. */
    public record EstimateRequest(
            @Size(max = MAX_TITLE) String title,
            @Size(max = MAX_BODY) String body,
            @NotNull @Valid AudienceRequest audience,
            @Size(max = 3) Set<@NotNull NoticeChannel> channels) {

        public EstimateRequest {
            channels = channels == null ? Set.of() : Set.copyOf(channels);
        }
    }

    public record ApproveRequest(@Size(max = 1000) String note) {
    }

    public record RejectRequest(@NotBlank @Size(max = 1000) String note) {
    }

    public record WithdrawRequest(@NotBlank @Size(max = 500) String reason) {
    }

    public record SettingsRequest(
            @NotNull Boolean teacherCircularsNeedApproval,
            @NotNull Boolean enquiryAckEnabled,
            @NotNull AckChannel enquiryAckChannel) {
    }

    /**
     * A calendar entry. {@code endsOn} defaults to {@code startsOn}. {@code classIds} are read only for the audience
     * CLASSES. {@code reminderDays} reminds the audience that many days before; {@code reminderChannels} adds SMS or
     * WhatsApp to the in-app reminder.
     */
    public record EntryRequest(
            @NotNull EntryKind kind,
            @NotBlank @Size(max = MAX_TITLE) String title,
            @Size(max = 2000) String description,
            @NotNull LocalDate startsOn,
            LocalDate endsOn,
            LocalTime startTime,
            LocalTime endTime,
            @NotNull CalendarAudience audience,
            @Size(max = 100) List<@NotNull UUID> classIds,
            @Min(1) @Max(30) Integer reminderDays,
            @Size(max = 2) Set<@NotNull ReminderChannel> reminderChannels) {

        public EntryRequest {
            classIds = classIds == null ? List.of() : List.copyOf(classIds);
            reminderChannels = reminderChannels == null ? Set.of() : Set.copyOf(reminderChannels);
        }
    }

    /** Several entries at once, for example holidays picked from the starter list. All are added or none. */
    public record BulkEntriesRequest(@NotEmpty @Size(max = MAX_BULK) List<@NotNull @Valid EntryRequest> entries) {
    }
}
