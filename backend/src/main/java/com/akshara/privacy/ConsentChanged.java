package com.akshara.privacy;

import java.time.Instant;
import java.util.UUID;

/**
 * Published for every consent decision (given, declined or withdrawn), online or on paper, inside the transaction that
 * records it. Nothing listens yet: absence alerts and fee messages are sent exactly as before. A later change can make
 * the notifications module skip WhatsApp for a child whose WHATSAPP consent was withdrawn (see
 * docs/api/phase-1-privacy.md).
 */
public record ConsentChanged(UUID tenantId, UUID studentId, PrivacyPurpose purpose, ConsentAction action,
        ConsentMethod method, int noticeVersion, Instant at) {
}
