package com.akshara.students;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Published inside the transaction when a student leaves the school: transferred (a signed transfer certificate or
 * the student form), withdrawn, or graduated as alumni. Other modules end what they still hold for the student, such
 * as a hostel bed or a bus seat, from {@code leftOn}.
 */
public record StudentLeft(UUID tenantId, UUID studentId, StudentStatus status, LocalDate leftOn, String reason) {
}
