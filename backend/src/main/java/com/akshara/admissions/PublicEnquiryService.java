package com.akshara.admissions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.akshara.admissions.AdmissionForms.PublicEnquiry;
import com.akshara.admissions.AdmissionsService.PublicSchoolInfo;
import com.akshara.platform.TenantDirectory;
import com.akshara.platform.TenantStatus;
import com.akshara.platform.TenantView;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;

/**
 * The public side of admissions, used without signing in. The school is looked up from the code in the address and
 * selected for the work; an unknown or suspended school is simply "not found". Only the school's name, board, city,
 * classes and admission years are ever shown.
 */
@Service
class PublicEnquiryService {

    private static final Logger log = LoggerFactory.getLogger(PublicEnquiryService.class);
    private static final int MAX_CODE_LENGTH = 40;

    private final TenantDirectory tenants;
    private final AdmissionsService admissions;
    private final EnquiryRateLimiter limiter;

    PublicEnquiryService(TenantDirectory tenants, AdmissionsService admissions, EnquiryRateLimiter limiter) {
        this.tenants = tenants;
        this.admissions = admissions;
        this.limiter = limiter;
    }

    PublicSchoolInfo info(String schoolCode, String clientIp) {
        limiter.takeInfo(clientIp);
        TenantView school = school(schoolCode);
        // The admissions service opens its transaction inside runAs, so the connection is stamped with this school.
        return TenantContext.runAs(school.id(), () -> admissions.publicInfo(school));
    }

    /**
     * Stores an enquiry, or quietly drops it when the hidden honeypot field was filled in (a bot). Either way the
     * caller sees the same answer, so a bot learns nothing.
     */
    void submit(String schoolCode, PublicEnquiry form, String clientIp) {
        limiter.takeEnquiryFromIp(clientIp);
        TenantView school = school(schoolCode);
        limiter.takeEnquiryForSchool(school.id());
        if (form.website() != null && !form.website().isBlank()) {
            log.info("Dropped a public enquiry for school {} that filled in the hidden field", school.id());
            return;
        }
        TenantContext.runAs(school.id(), () -> admissions.receiveEnquiry(form));
    }

    private TenantView school(String code) {
        if (code == null || code.isBlank() || code.length() > MAX_CODE_LENGTH) {
            throw ApiException.notFound("School");
        }
        return tenants.findByCode(code)
                .filter(t -> t.status() != TenantStatus.SUSPENDED)
                .orElseThrow(() -> ApiException.notFound("School"));
    }
}
