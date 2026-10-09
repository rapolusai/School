package com.akshara.staff;

import java.time.Instant;
import java.time.LocalDate;
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

import com.akshara.audit.AuditService.Actor;
import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/**
 * One staff member's school day: the status, check-in and check-out times, where it came from and, for a day of
 * approved leave, the leave request. Who first marked it and who last changed it are kept by name as well.
 */
@Entity
@Table(schema = "staff", name = "attendance")
class StaffAttendance extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false, updatable = false)
    private LocalDate attendanceDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StaffAttendanceStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AttendanceSource source;

    private Instant checkInAt;

    private Instant checkOutAt;

    private String checkInNote;

    private String checkOutNote;

    private UUID leaveRequestId;

    @Column(updatable = false)
    private UUID markedById;

    @Column(updatable = false)
    private String markedByName;

    private UUID updatedById;

    private String updatedByName;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected StaffAttendance() {
    }

    StaffAttendance(UUID userId, LocalDate date, StaffAttendanceStatus status, AttendanceSource source, Actor by,
            Instant at) {
        this.id = Ids.newId();
        this.userId = userId;
        this.attendanceDate = date;
        this.status = status;
        this.source = source;
        this.markedById = by.id();
        this.markedByName = by.name();
        this.createdAt = at;
        this.updatedAt = at;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    /** A new day of approved leave, linked to its request. */
    StaffAttendance linkedTo(UUID requestId) {
        this.leaveRequestId = requestId;
        return this;
    }

    /** The times of a newly marked day. */
    StaffAttendance withTimes(Instant in, Instant out) {
        this.checkInAt = in;
        this.checkOutAt = out;
        return this;
    }

    void checkIn(Instant at, String note) {
        this.checkInAt = at;
        this.checkInNote = note;
        if (status == StaffAttendanceStatus.ABSENT) {
            status = StaffAttendanceStatus.PRESENT;
            source = AttendanceSource.SELF;
        }
    }

    void checkOut(Instant at, String note) {
        this.checkOutAt = at;
        this.checkOutNote = note;
    }

    /** Sets the status and times from the daily sheet. Returns true when anything changed. */
    boolean correct(StaffAttendanceStatus newStatus, Instant in, Instant out, Actor by) {
        boolean changed = newStatus != status || !same(in, checkInAt) || !same(out, checkOutAt);
        if (!changed) {
            return false;
        }
        status = newStatus;
        source = AttendanceSource.ADMIN;
        checkInAt = in;
        checkOutAt = out;
        updatedById = by.id();
        updatedByName = by.name();
        return true;
    }

    /** Puts the day on approved leave (HALF_DAY for a half-day leave), keeping any check-in times. */
    void onLeave(UUID requestId, StaffAttendanceStatus leaveStatus, Actor by) {
        this.leaveRequestId = requestId;
        this.status = leaveStatus;
        this.source = AttendanceSource.LEAVE;
        this.updatedById = by.id();
        this.updatedByName = by.name();
    }

    /** The leave was cancelled: a day the person checked in on becomes present again. */
    void leaveCancelled(Actor by) {
        this.leaveRequestId = null;
        this.status = StaffAttendanceStatus.PRESENT;
        this.source = checkInAt != null ? AttendanceSource.SELF : AttendanceSource.ADMIN;
        this.updatedById = by.id();
        this.updatedByName = by.name();
    }

    private static boolean same(Instant a, Instant b) {
        return a == null ? b == null : a.equals(b);
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getUserId() {
        return userId;
    }

    LocalDate getAttendanceDate() {
        return attendanceDate;
    }

    StaffAttendanceStatus getStatus() {
        return status;
    }

    AttendanceSource getSource() {
        return source;
    }

    Instant getCheckInAt() {
        return checkInAt;
    }

    Instant getCheckOutAt() {
        return checkOutAt;
    }

    String getCheckInNote() {
        return checkInNote;
    }

    String getCheckOutNote() {
        return checkOutNote;
    }

    UUID getLeaveRequestId() {
        return leaveRequestId;
    }

    String getMarkedByName() {
        return markedByName;
    }

    String getUpdatedByName() {
        return updatedByName;
    }

    /** Null until someone other than the first marker changes the day. */
    Instant getEditedAt() {
        return updatedById == null && updatedByName == null ? null : updatedAt;
    }
}
