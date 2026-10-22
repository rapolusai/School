package com.akshara.certificates;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Published when a transfer certificate is signed. The student record was already marked as left through the
 * students module; other modules (fees, library, transport) may close what they hold for the student.
 */
public record TransferCertificateIssued(UUID tenantId, UUID certificateId, UUID studentId, String serialNo,
        LocalDate issuedOn, LocalDate leftOn) {
}
