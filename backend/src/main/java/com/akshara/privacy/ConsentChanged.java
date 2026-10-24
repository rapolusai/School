package com.akshara.privacy;

import java.time.Instant;
import java.util.UUID;

/**
 * Published for every consent decision (given, declined or withdrawn), online or on paper, inside the transaction that
 * records it. The WhatsApp follow-up does not wait for this event: {@link ConsentService} tells the notifications
 * module directly (notifications cannot depend on privacy without a module cycle), so a guardian whose child's
 * WHATSAPP consent is not given gets SMS instead of WhatsApp (see docs/api/phase-1-privacy.md).
 */
public record ConsentChanged(UUID tenantId, UUID studentId, PrivacyPurpose purpose, ConsentAction action,
        ConsentMethod method, int noticeVersion, Instant at) {
}
