package com.akshara.lessons;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.TenantId;

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/** A bank question in an online test, in order, with the marks it carries in this test. */
@Entity
@Table(schema = "lessons", name = "test_question")
class TestQuestion extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID testId;

    @Column(nullable = false, updatable = false)
    private UUID questionId;

    @Column(nullable = false)
    private int position;

    @Column(nullable = false)
    private BigDecimal marks;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected TestQuestion() {
    }

    TestQuestion(UUID testId, UUID questionId, int position, BigDecimal marks) {
        this.id = Ids.newId();
        this.testId = testId;
        this.questionId = questionId;
        this.createdAt = Instant.now();
        update(position, marks);
    }

    void update(int position, BigDecimal marks) {
        this.position = position;
        this.marks = marks;
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getTestId() {
        return testId;
    }

    UUID getQuestionId() {
        return questionId;
    }

    int getPosition() {
        return position;
    }

    BigDecimal getMarks() {
        return marks;
    }
}
