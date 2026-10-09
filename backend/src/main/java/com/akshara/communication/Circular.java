package com.akshara.communication;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
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
import com.akshara.communication.CommunicationTypes.Category;
import com.akshara.communication.CommunicationTypes.NoticeChannel;
import com.akshara.communication.CommunicationTypes.ReviewOutcome;
import com.akshara.communication.CommunicationTypes.Source;
import com.akshara.communication.CommunicationTypes.Status;
import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/**
 * A circular (announcement): a plain-text title and body for parents, students or staff, its audience and channels,
 * and its way from draft to sent. The people who wrote, reviewed, sent and withdrew it are kept by name as well, so it
 * still reads correctly after someone leaves.
 */
@Entity
@Table(schema = "communication", name = "circular")
class Circular extends AssignedIdEntity {

    /** What sending produced. */
    record Counts(int inApp, int staff, int parents, int students, int sms, int whatsapp, int email) {
    }

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Category category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Source source;

    private UUID calendarEntryId;

    @Column(nullable = false)
    private boolean wholeSchool;

    @Column(nullable = false)
    private String audienceLabel;

    @Column(name = "send_sms", nullable = false)
    private boolean sendSms;

    @Column(name = "send_whatsapp", nullable = false)
    private boolean sendWhatsapp;

    @Column(name = "send_email", nullable = false)
    private boolean sendEmail;

    private Instant scheduledAt;

    private UUID createdById;

    private String createdByName;

    private Instant submittedAt;

    private UUID reviewedById;

    private String reviewedByName;

    private Instant reviewedAt;

    @Enumerated(EnumType.STRING)
    private ReviewOutcome reviewOutcome;

    private String reviewNote;

    private Instant sentAt;

    private String sentByName;

    @Column(nullable = false)
    private int inAppCount;

    @Column(nullable = false)
    private int staffCount;

    @Column(nullable = false)
    private int parentCount;

    @Column(nullable = false)
    private int studentCount;

    @Column(nullable = false)
    private int smsCount;

    @Column(nullable = false)
    private int whatsappCount;

    @Column(nullable = false)
    private int emailCount;

    private Instant withdrawnAt;

    private UUID withdrawnById;

    private String withdrawnByName;

    private String withdrawReason;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Circular() {
    }

    Circular(Source source, UUID calendarEntryId, Actor author, Instant at) {
        this.id = Ids.newId();
        this.source = source;
        this.calendarEntryId = calendarEntryId;
        this.status = Status.DRAFT;
        this.createdById = author.id();
        this.createdByName = author.name();
        this.createdAt = at;
        this.updatedAt = at;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void edit(String title, String body, Category category, boolean wholeSchool, String audienceLabel,
            Set<NoticeChannel> channels, Instant scheduledAt) {
        this.title = title;
        this.body = body;
        this.category = category;
        this.wholeSchool = wholeSchool;
        this.audienceLabel = audienceLabel;
        this.sendSms = channels.contains(NoticeChannel.SMS);
        this.sendWhatsapp = channels.contains(NoticeChannel.WHATSAPP);
        this.sendEmail = channels.contains(NoticeChannel.EMAIL);
        this.scheduledAt = scheduledAt;
    }

    void submitForApproval(Instant at) {
        this.status = Status.PENDING_APPROVAL;
        this.submittedAt = at;
    }

    void review(ReviewOutcome outcome, Actor reviewer, String note, Instant at) {
        this.reviewOutcome = outcome;
        this.reviewedById = reviewer.id();
        this.reviewedByName = reviewer.name();
        this.reviewNote = note;
        this.reviewedAt = at;
        if (outcome == ReviewOutcome.REJECTED) {
            this.status = Status.DRAFT;
        }
    }

    void schedule(Instant at) {
        if (submittedAt == null) {
            submittedAt = at;
        }
        this.status = Status.SCHEDULED;
    }

    /** Back to a draft: a pending or scheduled circular the sender wants to change or stop. */
    void backToDraft() {
        this.status = Status.DRAFT;
    }

    void sent(Instant at, String byName, Counts counts) {
        if (submittedAt == null) {
            submittedAt = at;
        }
        this.status = Status.SENT;
        this.sentAt = at;
        this.sentByName = byName;
        this.inAppCount = counts.inApp();
        this.staffCount = counts.staff();
        this.parentCount = counts.parents();
        this.studentCount = counts.students();
        this.smsCount = counts.sms();
        this.whatsappCount = counts.whatsapp();
        this.emailCount = counts.email();
    }

    void withdraw(Actor by, String reason, Instant at) {
        this.status = Status.WITHDRAWN;
        this.withdrawnAt = at;
        this.withdrawnById = by.id();
        this.withdrawnByName = by.name();
        this.withdrawReason = reason;
    }

    Set<NoticeChannel> channels() {
        Set<NoticeChannel> channels = EnumSet.noneOf(NoticeChannel.class);
        if (sendSms) {
            channels.add(NoticeChannel.SMS);
        }
        if (sendWhatsapp) {
            channels.add(NoticeChannel.WHATSAPP);
        }
        if (sendEmail) {
            channels.add(NoticeChannel.EMAIL);
        }
        return channels;
    }

    Counts counts() {
        return new Counts(inAppCount, staffCount, parentCount, studentCount, smsCount, whatsappCount, emailCount);
    }

    @Override
    public UUID getId() {
        return id;
    }

    String getTitle() {
        return title;
    }

    String getBody() {
        return body;
    }

    Category getCategory() {
        return category;
    }

    Status getStatus() {
        return status;
    }

    Source getSource() {
        return source;
    }

    UUID getCalendarEntryId() {
        return calendarEntryId;
    }

    boolean isWholeSchool() {
        return wholeSchool;
    }

    String getAudienceLabel() {
        return audienceLabel;
    }

    Instant getScheduledAt() {
        return scheduledAt;
    }

    UUID getCreatedById() {
        return createdById;
    }

    String getCreatedByName() {
        return createdByName;
    }

    Instant getSubmittedAt() {
        return submittedAt;
    }

    String getReviewedByName() {
        return reviewedByName;
    }

    Instant getReviewedAt() {
        return reviewedAt;
    }

    ReviewOutcome getReviewOutcome() {
        return reviewOutcome;
    }

    String getReviewNote() {
        return reviewNote;
    }

    Instant getSentAt() {
        return sentAt;
    }

    String getSentByName() {
        return sentByName;
    }

    Instant getWithdrawnAt() {
        return withdrawnAt;
    }

    String getWithdrawnByName() {
        return withdrawnByName;
    }

    String getWithdrawReason() {
        return withdrawReason;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }
}
