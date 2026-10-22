package com.akshara.certificates;

import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.akshara.certificates.CertificateViews.Verification;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;

/**
 * The public verification page behind a certificate's QR code. No one is signed in, so the school is found from the
 * token through a security-definer function; the rest runs as that school, and shows only the school name, the
 * type, the serial number, the student's first name, the issue date and whether the certificate stands.
 */
@Service
public class PublicVerifyService {

    private final CertificateService certificates;
    private final VerifyRateLimiter limiter;
    private final EntityManager entityManager;
    private final TransactionTemplate tx;

    PublicVerifyService(CertificateService certificates, VerifyRateLimiter limiter, EntityManager entityManager,
            TransactionTemplate tx) {
        this.certificates = certificates;
        this.limiter = limiter;
        this.entityManager = entityManager;
        this.tx = tx;
    }

    /**
     * Two transactions on purpose: the school is found without one (the function runs as the owner), then the
     * certificate is read in a transaction that starts as that school, so row-level security sees it.
     */
    public Verification verify(String token, String ip) {
        limiter.take(ip == null ? "unknown" : ip);
        if (!Serials.looksLikeToken(token)) {
            throw ApiException.notFound("certificate");
        }
        UUID tenantId = tx.execute(status -> (UUID) entityManager.createNativeQuery(
                "select certificates.tenant_of_verify_token(?1)").setParameter(1, token).getSingleResult());
        if (tenantId == null) {
            throw ApiException.notFound("certificate");
        }
        return TenantContext.runAs(tenantId, () -> tx.execute(status -> certificates.verification(token)
                .orElseThrow(() -> ApiException.notFound("certificate"))));
    }
}
