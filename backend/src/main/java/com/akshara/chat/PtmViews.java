package com.akshara.chat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * Responses of the parent-teacher meeting API (docs/api/phase-2-chat.md). Parents see teachers' names and what they
 * teach their child, and only whether a slot is free, never who booked the others. Staff see parents' names, never a
 * phone number or an email address.
 */
public final class PtmViews {

    private PtmViews() {
    }

    public record SectionRef(UUID id, String label) {
    }

    public record BreakView(LocalTime from, LocalTime to) {
    }

    public record StudentRef(UUID id, String name, String classLabel, UUID sectionId) {
    }

    /** A booking as staff see it. {@code parentName} is empty for a booking the school made for a family. */
    public record BookingView(UUID id, UUID slotId, LocalTime startsAt, LocalTime endsAt, UUID teacherId,
            String teacherName, StudentRef student, String parentName, String bookedBy, String bookedByName,
            PtmAttendance attendance, String privateNote, String parentNote, Instant markedAt, String markedByName,
            Instant createdAt) {
    }

    public record SlotView(UUID id, LocalTime startsAt, LocalTime endsAt, boolean blocked, BookingView booking) {
    }

    /** A teacher's slots at a meeting with what is booked in each. */
    public record TeacherSchedule(UUID ptmTeacherId, UUID teacherId, String name, String kind, String subjects,
            LocalTime availableFrom, LocalTime availableTo, int booked, int free, List<SlotView> slots) {
    }

    public record EventSection(UUID id, String label, UUID classTeacherId, String classTeacherName, int students,
            int studentsBooked) {
    }

    public record EventSummary(UUID id, String title, LocalDate date, LocalTime startsAt, LocalTime endsAt,
            int slotMinutes, MeetingMode mode, String location, String status, List<SectionRef> sections,
            int teachers, int slots, int booked, int attended, int noShow, boolean past) {
    }

    public record EventDetail(UUID id, String title, LocalDate date, LocalTime startsAt, LocalTime endsAt,
            int slotMinutes, List<BreakView> breaks, MeetingMode mode, String location, String videoLink,
            boolean includeSubjectTeachers, int cutoffHours, String note, String status, String cancelledByName,
            String cancelReason, Instant cancelledAt, String createdByName, Instant createdAt, boolean past,
            List<EventSection> sections, List<TeacherSchedule> teachers, boolean remindersEnabled) {
    }

    /** A teacher's own meeting: the event and their schedule at it. */
    public record MyEvent(EventSummary event, String videoLink, String note, String cancelReason, int cutoffHours,
            TeacherSchedule schedule) {
    }

    // ------------------------------------------------------------------ parents

    /** A slot as a parent sees it: free or not, and until when it can be booked. */
    public record FamilySlot(UUID id, LocalTime startsAt, LocalTime endsAt, boolean available, Instant bookableUntil) {
    }

    /** The child's booking with a teacher; attendance and the teacher's note show once they are marked. */
    public record FamilyBooking(UUID id, UUID slotId, LocalTime startsAt, LocalTime endsAt, String bookedBy,
            boolean canChange, Instant changeUntil, PtmAttendance attendance, String parentNote) {
    }

    public record FamilyTeacher(UUID teacherId, String name, String detail, List<FamilySlot> slots,
            FamilyBooking booking) {
    }

    public record FamilyEvent(UUID id, String title, LocalDate date, LocalTime startsAt, LocalTime endsAt,
            int slotMinutes, MeetingMode mode, String location, String videoLink, String note, String status,
            String cancelReason, int cutoffHours, boolean past, List<FamilyTeacher> teachers) {
    }

    public record ChildPtm(UUID studentId, String name, String classLabel, List<FamilyEvent> events) {
    }

    public record FamilyPtm(List<ChildPtm> children, boolean remindersEnabled, Instant serverTime) {
    }

    public record PtmSettings(boolean remindersEnabled) {
    }

    // ------------------------------------------------------------------ attendance summary (reports hub)

    public record SectionAttendance(UUID sectionId, String label, int students, int booked, int attended, int noShow,
            int notMarked) {
    }

    public record TeacherAttendance(UUID teacherId, String name, int slots, int booked, int attended, int noShow,
            int notMarked) {
    }

    public record AttendanceSummary(UUID eventId, String title, LocalDate date, String status,
            List<SectionAttendance> sections, List<TeacherAttendance> teachers, SectionAttendance total) {
    }
}
