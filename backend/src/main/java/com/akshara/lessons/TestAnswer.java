package com.akshara.lessons;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import org.hibernate.annotations.TenantId;

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/**
 * A student's answer to one question of an attempt, in the order they saw the questions. {@code optionOrder} is the
 * order the options were shown in and {@code selectedOptions} the chosen ones; {@code response} is a typed answer
 * (or TRUE/FALSE). {@code marks} is null until the answer is marked.
 */
@Entity
@Table(schema = "lessons", name = "test_answer")
class TestAnswer extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID attemptId;

    @Column(nullable = false, updatable = false)
    private UUID testQuestionId;

    @Column(nullable = false, updatable = false)
    private int position;

    @Column(updatable = false)
    private String optionOrder;

    private String selectedOptions;

    private String response;

    private Instant savedAt;

    private BigDecimal marks;

    private String markedByName;

    private Instant markedAt;

    private String feedback;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected TestAnswer() {
    }

    TestAnswer(UUID attemptId, UUID testQuestionId, int position, List<UUID> optionOrder) {
        this.id = Ids.newId();
        this.attemptId = attemptId;
        this.testQuestionId = testQuestionId;
        this.position = position;
        this.optionOrder = optionOrder.isEmpty() ? null : join(optionOrder);
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void save(List<UUID> selected, String response, Instant at) {
        this.selectedOptions = selected.isEmpty() ? null : join(selected);
        this.response = response;
        this.savedAt = at;
    }

    void mark(BigDecimal marks, String byName, String feedback, Instant at) {
        this.marks = marks;
        this.markedByName = byName;
        this.feedback = feedback;
        this.markedAt = at;
    }

    /** Clears what the student wrote (data erasure); marks stay. */
    void eraseText() {
        this.response = response == null ? null : "";
        this.feedback = null;
    }

    static String join(List<UUID> ids) {
        return ids.stream().map(UUID::toString).collect(Collectors.joining(","));
    }

    static List<UUID> split(String ids) {
        return ids == null || ids.isBlank() ? List.of()
                : Arrays.stream(ids.split(",")).map(String::trim).map(UUID::fromString).toList();
    }

    boolean isAnswered() {
        return selectedOptions != null || (response != null && !response.isBlank());
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getAttemptId() {
        return attemptId;
    }

    UUID getTestQuestionId() {
        return testQuestionId;
    }

    int getPosition() {
        return position;
    }

    List<UUID> optionOrder() {
        return split(optionOrder);
    }

    List<UUID> selected() {
        return split(selectedOptions);
    }

    String getResponse() {
        return response;
    }

    Instant getSavedAt() {
        return savedAt;
    }

    BigDecimal getMarks() {
        return marks;
    }

    String getMarkedByName() {
        return markedByName;
    }

    Instant getMarkedAt() {
        return markedAt;
    }

    String getFeedback() {
        return feedback;
    }
}
