package com.akshara.lessons;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.TenantId;

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/** An option of a multiple choice question, in order. */
@Entity
@Table(schema = "lessons", name = "question_option")
class QuestionOption extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID questionId;

    @Column(nullable = false)
    private int position;

    @Column(nullable = false)
    private String text;

    @Column(nullable = false)
    private boolean correct;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected QuestionOption() {
    }

    QuestionOption(UUID questionId, int position, String text, boolean correct) {
        this.id = Ids.newId();
        this.questionId = questionId;
        this.position = position;
        this.text = text;
        this.correct = correct;
        this.createdAt = Instant.now();
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getQuestionId() {
        return questionId;
    }

    int getPosition() {
        return position;
    }

    String getText() {
        return text;
    }

    boolean isCorrect() {
        return correct;
    }
}
