package com.akshara.privacy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.data.domain.Limit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.platform.TenantDirectory;
import com.akshara.privacy.PrivacyForms.NoticeForm;
import com.akshara.privacy.PrivacyForms.OfficerForm;
import com.akshara.privacy.PrivacyViews.Notice;
import com.akshara.privacy.PrivacyViews.NoticeAdmin;
import com.akshara.privacy.PrivacyViews.NoticeVersion;
import com.akshara.privacy.PrivacyViews.Officer;
import com.akshara.privacy.PrivacyViews.PublicNotice;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;

/**
 * The school's privacy notice and grievance officer. Each publication is a new, numbered version in English and
 * Hindi; older versions stay readable. Parents are asked to accept again whenever a new version is published.
 */
@Service
@Transactional
public class PrivacyNoticeService {

    /** Versions listed on the notice page; a school publishing more than this is very unusual. */
    static final int MAX_VERSIONS = 200;

    private final PrivacyNoticeRepository notices;
    private final GrievanceOfficerRepository officers;
    private final TenantDirectory tenants;
    private final AuditService audit;

    PrivacyNoticeService(PrivacyNoticeRepository notices, GrievanceOfficerRepository officers,
            TenantDirectory tenants, AuditService audit) {
        this.notices = notices;
        this.officers = officers;
        this.tenants = tenants;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ staff

    /** The notice page: officer, current version, the text to start the next version from, and the history. */
    @Transactional(readOnly = true)
    public NoticeAdmin admin() {
        TenantContext.require();
        Optional<GrievanceOfficer> officer = officers.findCurrent();
        Optional<PrivacyNotice> current = notices.current();
        String school = schoolName();
        return new NoticeAdmin(officer.map(PrivacyNoticeService::officerView).orElse(null),
                current.map(n -> view(n, true, officer)).orElse(null),
                current.map(PrivacyNotice::getBodyEn).orElseGet(() -> NoticeTemplate.english(school)),
                current.map(PrivacyNotice::getBodyHi).orElseGet(() -> NoticeTemplate.hindi(school)),
                current.isEmpty(), versions());
    }

    /** One version, current or older; 404 when the school has no such version. */
    @Transactional(readOnly = true)
    public Notice version(int number) {
        TenantContext.require();
        PrivacyNotice notice = notices.findByNumber(number).orElseThrow(() -> ApiException.notFound("Notice version"));
        boolean current = notices.latestNumber() == number;
        return view(notice, current, officers.findCurrent());
    }

    /** Publishes a new version. The grievance officer must be set first, so parents always have someone to ask. */
    public Notice publish(NoticeForm form, Actor actor) {
        TenantContext.require();
        GrievanceOfficer officer = officers.findCurrent().orElseThrow(() -> ApiException.badRequest(
                "Add the grievance officer before publishing the notice.", "grievanceOfficer"));
        int number = notices.latestNumber() + 1;
        String summary = blankToNull(form.changeSummary());
        if (number > 1 && summary == null) {
            throw ApiException.badRequest("Tell parents what changed in this version.", "changeSummary");
        }
        PrivacyNotice notice = notices.saveAndFlush(new PrivacyNotice(number, form.bodyEn().strip(),
                form.bodyHi().strip(), summary, officer, actor.id(), actor.name()));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("version", number);
        details.put("changeSummary", summary);
        audit.record(actor, "privacy_notice.published", "privacy_notice", notice.getId(), details);
        return view(notice, true, Optional.of(officer));
    }

    public Officer updateOfficer(OfficerForm form, Actor actor) {
        TenantContext.require();
        String name = form.name().trim();
        String email = form.email().trim();
        String phone = form.phone().trim();
        GrievanceOfficer officer = officers.findCurrent().map(existing -> {
            existing.update(name, email, phone);
            return existing;
        }).orElseGet(() -> officers.save(new GrievanceOfficer(name, email, phone)));
        officers.flush();
        audit.record(actor, "grievance_officer.updated", "grievance_officer", officer.getId(), Map.of("name", name));
        return officerView(officer);
    }

    // ------------------------------------------------------------------ for the other privacy services

    Optional<PrivacyNotice> current() {
        return notices.current();
    }

    /** The current version as parents read it, or empty before the first publication. */
    Optional<Notice> currentView() {
        return notices.current().map(n -> view(n, true, officers.findCurrent()));
    }

    PrivacyNotice requireCurrent() {
        return notices.current().orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "No privacy notice",
                "The school has not published its privacy notice yet."));
    }

    // ------------------------------------------------------------------ public page

    /**
     * The notice of an existing school, current or an older version, for anyone. Runs inside the school's context
     * (the caller selects it); 404 when the school has not published one.
     */
    @Transactional(readOnly = true)
    public PublicNotice publicNotice(String schoolName, String schoolCode, Integer number) {
        TenantContext.require();
        PrivacyNotice current = notices.current().orElseThrow(() -> ApiException.notFound("Privacy notice"));
        PrivacyNotice shown = number == null ? current
                : notices.findByNumber(number).orElseThrow(() -> ApiException.notFound("Privacy notice"));
        Notice view = view(shown, shown.getNumber() == current.getNumber(), officers.findCurrent());
        return new PublicNotice(schoolName, schoolCode, shown.getNumber(), current.getNumber(),
                shown.getPublishedAt(), shown.getChangeSummary(), shown.getBodyEn(), shown.getBodyHi(),
                view.grievanceOfficer(), versions().stream()
                        // Staff names stay inside the school.
                        .map(v -> new NoticeVersion(v.version(), v.publishedAt(), null, v.changeSummary()))
                        .toList());
    }

    // ------------------------------------------------------------------ helpers

    private List<NoticeVersion> versions() {
        return notices.newestFirst(Limit.of(MAX_VERSIONS)).stream()
                .map(n -> new NoticeVersion(n.getNumber(), n.getPublishedAt(), n.getPublishedByName(),
                        n.getChangeSummary()))
                .toList();
    }

    private String schoolName() {
        return tenants.profile(TenantContext.require()).map(p -> p.name()).orElse("The school");
    }

    /** The current version names today's officer; an older one the officer named when it was published. */
    static Notice view(PrivacyNotice n, boolean current, Optional<GrievanceOfficer> officer) {
        Officer shown = current && officer.isPresent() ? officerView(officer.get())
                : new Officer(n.getGrievanceName(), n.getGrievanceEmail(), n.getGrievancePhone(), null);
        return new Notice(n.getNumber(), current, n.getBodyEn(), n.getBodyHi(), n.getChangeSummary(),
                n.getPublishedAt(), n.getPublishedByName(), shown);
    }

    static Officer officerView(GrievanceOfficer o) {
        return new Officer(o.getName(), o.getEmail(), o.getPhone(), o.getUpdatedAt());
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
