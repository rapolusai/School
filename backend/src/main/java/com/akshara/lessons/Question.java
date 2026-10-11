package com.akshara.lessons;

import java.math.BigDecimal;
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
 * A question in the school's bank for a class and subject. {@code answerText} is TRUE or FALSE for a true/false
 * question, the accepted answers (one per line) for fill in the blank, and the model answer for a short answer; the
 * options of a multiple choice question are {@link QuestionOption}s.
 */
@Entity
@Table(schema = "lessons", name = "question")
class Question extends AssignedIdEntity {

    /** What a teacher writes for a question (options apart). */
    record Content(UUID classId, UUID subjectId, UUID topicId, QuestionType type, Difficulty difficulty,
            BloomLevel bloomLevel, String text, String answerText, String explanation, BigDecimal marks,
            boolean shared) {
    }

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private UUID classId;

    @Column(nullable = false)
    private UUID subjectId;

    private UUID topicId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private QuestionType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Difficulty difficulty;

    @Enumerated(EnumType.STRING)
    private BloomLevel bloomLevel;

    @Column(nullable = false)
    private String text;

    private String answerText;

    private String explanation;

    @Column(nullable = false)
    private BigDecimal marks;

    @Column(nullable = false)
    private boolean shared;

    private UUID createdById;

    @Column(nullable = false)
    private String createdByName;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Question() {
    }

    Question(UUID createdById, String createdByName, Content content) {
        this.id = Ids.newId();
        this.createdById = createdById;
        this.createdByName = createdByName;
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
        this.topicId = c.topicId();
        this.type = c.type();
        this.difficulty = c.difficulty();
        this.bloomLevel = c.bloomLevel();
        this.text = c.text();
        this.answerText = c.answerText();
        this.explanation = c.explanation();
        this.marks = c.marks();
        this.shared = c.shared();
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getClassId() {
        return classId;
    }

    UUID getSubjectId() {
        return subjectId;
    }

    UUID getTopicId() {
        return topicId;
    }

    QuestionType getType() {
        return type;
    }

    Difficulty getDifficulty() {
        return difficulty;
    }

    BloomLevel getBloomLevel() {
        return bloomLevel;
    }

    String getText() {
        return text;
    }

    String getAnswerText() {
        return answerText;
    }

    String getExplanation() {
        return explanation;
    }

    BigDecimal getMarks() {
        return marks;
    }

    boolean isShared() {
        return shared;
    }

    UUID getCreatedById() {
        return createdById;
    }

    String getCreatedByName() {
        return createdByName;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }
}
