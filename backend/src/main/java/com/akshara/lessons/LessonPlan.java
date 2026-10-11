package com.akshara.lessons;

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

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/**
 * A teacher's lesson plan for a section and subject in a week (its Monday), optionally on one day of it, usually for
 * a syllabus topic. See {@link PlanStatus} for how it moves from draft to taught.
 */
@Entity
@Table(schema = "lessons", name = "lesson_plan")
class LessonPlan extends AssignedIdEntity {

    /** What a teacher writes in a plan. */
    record Content(UUID topicId, String title, LocalDate weekStart, LocalDate planDate, int periods,
            String objectives, String teachingMethod, String resources, String resourceLinks, String homeworkIdea,
            String assessmentNote) {
    }

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID academicYearId;

    @Column(nullable = false)
    private UUID sectionId;

    @Column(nullable = false)
    private UUID subjectId;

    private UUID topicId;

    @Column(nullable = false, updatable = false)
    private UUID teacherId;

    @Column(nullable = false)
    private String teacherName;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private LocalDate weekStart;

    private LocalDate planDate;

    @Column(nullable = false)
    private int periods;

    private String objectives;

    private String teachingMethod;

    private String resources;

    private String resourceLinks;

    private String homeworkIdea;

    private String assessmentNote;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PlanStatus status;

    private Instant submittedAt;

    private Instant reviewedAt;

    private UUID reviewedById;

    private String reviewedByName;

    private String reviewComment;

    private LocalDate taughtOn;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected LessonPlan() {
    }

    LessonPlan(UUID academicYearId, UUID sectionId, UUID subjectId, UUID teacherId, String teacherName,
            Content content) {
        this.id = Ids.newId();
        this.academicYearId = academicYearId;
        this.teacherId = teacherId;
        this.teacherName = teacherName;
        this.status = PlanStatus.DRAFT;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
        update(sectionId, subjectId, content);
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void update(UUID sectionId, UUID subjectId, Content c) {
        this.sectionId = sectionId;
        this.subjectId = subjectId;
        this.topicId = c.topicId();
        this.title = c.title();
        this.weekStart = c.weekStart();
        this.planDate = c.planDate();
        this.periods = c.periods();
        this.objectives = c.objectives();
        this.teachingMethod = c.teachingMethod();
        this.resources = c.resources();
        this.resourceLinks = c.resourceLinks();
        this.homeworkIdea = c.homeworkIdea();
        this.assessmentNote = c.assessmentNote();
    }

    void submit(Instant at) {
        this.status = PlanStatus.SUBMITTED;
        this.submittedAt = at;
    }

    void approve(UUID byId, String byName, String comment, Instant at) {
        review(PlanStatus.APPROVED, byId, byName, comment, at);
    }

    void returnWith(UUID byId, String byName, String comment, Instant at) {
        review(PlanStatus.RETURNED, byId, byName, comment, at);
    }

    private void review(PlanStatus to, UUID byId, String byName, String comment, Instant at) {
        this.status = to;
        this.reviewedById = byId;
        this.reviewedByName = byName;
        this.reviewComment = comment;
        this.reviewedAt = at;
    }

    void taught(LocalDate on) {
        this.status = PlanStatus.TAUGHT;
        this.taughtOn = on;
    }

    void untaught() {
        this.status = PlanStatus.APPROVED;
        this.taughtOn = null;
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getAcademicYearId() {
        return academicYearId;
    }

    UUID getSectionId() {
        return sectionId;
    }

    UUID getSubjectId() {
        return subjectId;
    }

    UUID getTopicId() {
        return topicId;
    }

    UUID getTeacherId() {
        return teacherId;
    }

    String getTeacherName() {
        return teacherName;
    }

    String getTitle() {
        return title;
    }

    LocalDate getWeekStart() {
        return weekStart;
    }

    LocalDate getPlanDate() {
        return planDate;
    }

    int getPeriods() {
        return periods;
    }

    String getObjectives() {
        return objectives;
    }

    String getTeachingMethod() {
        return teachingMethod;
    }

    String getResources() {
        return resources;
    }

    String getResourceLinks() {
        return resourceLinks;
    }

    String getHomeworkIdea() {
        return homeworkIdea;
    }

    String getAssessmentNote() {
        return assessmentNote;
    }

    PlanStatus getStatus() {
        return status;
    }

    Instant getSubmittedAt() {
        return submittedAt;
    }

    Instant getReviewedAt() {
        return reviewedAt;
    }

    String getReviewedByName() {
        return reviewedByName;
    }

    String getReviewComment() {
        return reviewComment;
    }

    LocalDate getTaughtOn() {
        return taughtOn;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }

    long getVersion() {
        return version;
    }
}
