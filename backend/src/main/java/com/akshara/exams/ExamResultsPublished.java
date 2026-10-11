package com.akshara.exams;

import java.time.Instant;
import java.util.UUID;

/**
 * Published inside the transaction that publishes an exam's results for a class. The exams module itself tells the
 * students' parents through the notifications outbox; other modules (analytics, a future parent push service) can
 * listen too. A listener that must not act on a rolled-back publication should use {@code @TransactionalEventListener}.
 */
public record ExamResultsPublished(UUID tenantId, UUID examId, UUID examClassId, UUID classId, UUID academicYearId,
        String examName, String className, Instant publishedAt) {
}
