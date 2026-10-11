package com.akshara.lessons;

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
 * An online test of a class and subject for one or more of its sections (see {@link TestSection}), built from bank
 * questions ({@link TestQuestion}). Students take it between {@code opensAt} and {@code closesAt}; each attempt lasts
 * {@code durationMinutes}. Scores reach students and parents only once the results are released.
 */
@Entity
@Table(schema = "lessons", name = "online_test")
class OnlineTest extends AssignedIdEntity {

    /** What a teacher sets for a test. */
    record Content(UUID classId, UUID subjectId, String title, String instructions, Instant opensAt,
            Instant closesAt, int durationMinutes, int attemptsAllowed, boolean shuffleQuestions,
            boolean shuffleOptions, ShowAnswers showAnswers) {
    }

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID academicYearId;

    @Column(nullable = false)
    private UUID classId;

    @Column(nullable = false)
    private UUID subjectId;

    @Column(nullable = false)
    private String title;

    private String instructions;

    @Column(nullable = false)
    private Instant opensAt;

    @Column(nullable = false)
    private Instant closesAt;

    @Column(nullable = false)
    private int durationMinutes;

    @Column(nullable = false)
    private int attemptsAllowed;

    @Column(nullable = false)
    private boolean shuffleQuestions;

    @Column(nullable = false)
    private boolean shuffleOptions;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ShowAnswers showAnswers;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TestStatus status;

    private Instant publishedAt;

    private Instant resultsReleasedAt;

    private String releasedByName;

    private UUID createdById;

    @Column(nullable = false)
    private String createdByName;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected OnlineTest() {
    }

    OnlineTest(UUID academicYearId, UUID createdById, String createdByName, Content content) {
        this.id = Ids.newId();
        this.academicYearId = academicYearId;
        this.createdById = createdById;
        this.createdByName = createdByName;
        this.status = TestStatus.DRAFT;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
        update(content);
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void update(Content c) {
        this.classId = c.classId();
        this.subjectId = c.subjectId();
        this.title = c.title();
        this.instructions = c.instructions();
        this.opensAt = c.opensAt();
        this.closesAt = c.closesAt();
        this.durationMinutes = c.durationMinutes();
        this.attemptsAllowed = c.attemptsAllowed();
        this.shuffleQuestions = c.shuffleQuestions();
        this.shuffleOptions = c.shuffleOptions();
        this.showAnswers = c.showAnswers();
    }

    void publish(Instant at) {
        this.status = TestStatus.PUBLISHED;
        this.publishedAt = at;
    }

    void unpublish() {
        this.status = TestStatus.DRAFT;
        this.publishedAt = null;
    }

    /** Closes the test now: no new attempts, and attempts in progress end now. */
    void closeAt(Instant at) {
        this.closesAt = at;
        if (!opensAt.isBefore(at)) {
            this.opensAt = at.minusSeconds(1);
        }
    }

    void release(String byName, Instant at) {
        this.resultsReleasedAt = at;
        this.releasedByName = byName;
    }

    void withdrawResults() {
        this.resultsReleasedAt = null;
        this.releasedByName = null;
    }

    boolean isPublished() {
        return status == TestStatus.PUBLISHED;
    }

    boolean isReleased() {
        return resultsReleasedAt != null;
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getAcademicYearId() {
        return academicYearId;
    }

    UUID getClassId() {
        return classId;
    }

    UUID getSubjectId() {
        return subjectId;
    }

    String getTitle() {
        return title;
    }

    String getInstructions() {
        return instructions;
    }

    Instant getOpensAt() {
        return opensAt;
    }

    Instant getClosesAt() {
        return closesAt;
    }

    int getDurationMinutes() {
        return durationMinutes;
    }

    int getAttemptsAllowed() {
        return attemptsAllowed;
    }

    boolean isShuffleQuestions() {
        return shuffleQuestions;
    }

    boolean isShuffleOptions() {
        return shuffleOptions;
    }

    ShowAnswers getShowAnswers() {
        return showAnswers;
    }

    TestStatus getStatus() {
        return status;
    }

    Instant getPublishedAt() {
        return publishedAt;
    }

    Instant getResultsReleasedAt() {
        return resultsReleasedAt;
    }

    String getReleasedByName() {
        return releasedByName;
    }

    UUID getCreatedById() {
        return createdById;
    }

    String getCreatedByName() {
        return createdByName;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    long getVersion() {
        return version;
    }
}
