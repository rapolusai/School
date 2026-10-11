package com.akshara.chat;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.TenantId;

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/**
 * A PTM slot booked for one child, by a parent in the app or by the school for a family. The teacher marks whether
 * the family came and keeps a private note and a note for the parent.
 */
@Entity
@Table(schema = "chat", name = "ptm_booking")
class PtmBooking extends AssignedIdEntity {

    enum Status {
        BOOKED, CANCELLED
    }

    enum BookedBy {
        PARENT, SCHOOL
    }

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID eventId;

    @Column(nullable = false, updatable = false)
    private UUID slotId;

    @Column(nullable = false, updatable = false)
    private UUID teacherUserId;

    @Column(nullable = false, updatable = false)
    private UUID studentId;

    @Column(updatable = false)
    private UUID parentUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "booked_by_side", nullable = false, updatable = false)
    private BookedBy bookedBy;

    @Column(nullable = false)
    private String bookedByName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Enumerated(EnumType.STRING)
    private PtmAttendance attendance;

    private String privateNote;

    private String parentNote;

    private Instant markedAt;

    private String markedByName;

    private Instant cancelledAt;

    private String cancelledByName;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected PtmBooking() {
    }

    PtmBooking(PtmSlot slot, UUID studentId, UUID parentUserId, BookedBy bookedBy, String bookedByName, Instant at) {
        this.id = Ids.newId();
        this.eventId = slot.getEventId();
        this.slotId = slot.getId();
        this.teacherUserId = slot.getTeacherUserId();
        this.studentId = studentId;
        this.parentUserId = parentUserId;
        this.bookedBy = bookedBy;
        this.bookedByName = bookedByName;
        this.status = Status.BOOKED;
        this.createdAt = at;
        this.updatedAt = at;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void cancel(String byName, Instant at) {
        status = Status.CANCELLED;
        cancelledAt = at;
        cancelledByName = byName;
    }

    void mark(PtmAttendance attendance, String privateNote, String parentNote, String byName, Instant at) {
        this.attendance = attendance;
        this.privateNote = privateNote;
        this.parentNote = parentNote;
        this.markedAt = at;
        this.markedByName = byName;
    }

    /** Clears the notes about the child and the parent's name; the booking itself stays for the counts. */
    void erase() {
        privateNote = null;
        parentNote = null;
        if (bookedBy == BookedBy.PARENT) {
            bookedByName = ChatMessage.ERASED_PARENT;
        }
    }

    boolean isBooked() {
        return status == Status.BOOKED;
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getEventId() {
        return eventId;
    }

    UUID getSlotId() {
        return slotId;
    }

    UUID getTeacherUserId() {
        return teacherUserId;
    }

    UUID getStudentId() {
        return studentId;
    }

    UUID getParentUserId() {
        return parentUserId;
    }

    BookedBy getBookedBy() {
        return bookedBy;
    }

    String getBookedByName() {
        return bookedByName;
    }

    Status getStatus() {
        return status;
    }

    PtmAttendance getAttendance() {
        return attendance;
    }

    String getPrivateNote() {
        return privateNote;
    }

    String getParentNote() {
        return parentNote;
    }

    Instant getMarkedAt() {
        return markedAt;
    }

    String getMarkedByName() {
        return markedByName;
    }

    Instant getCancelledAt() {
        return cancelledAt;
    }

    String getCancelledByName() {
        return cancelledByName;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
