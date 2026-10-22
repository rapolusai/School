package com.akshara.certificates;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.academics.AcademicsDirectory;
import com.akshara.academics.AcademicsDirectory.YearInfo;
import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.certificates.CertificateForms.SettingsForm;
import com.akshara.certificates.CertificateForms.TemplateForm;
import com.akshara.certificates.CertificateViews.SchoolHeader;
import com.akshara.certificates.CertificateViews.SettingsView;
import com.akshara.certificates.CertificateViews.Signatory;
import com.akshara.certificates.CertificateViews.TemplateView;
import com.akshara.platform.SchoolProfile;
import com.akshara.platform.TenantDirectory;
import com.akshara.shared.Ids;
import com.akshara.shared.TenantContext;

/**
 * The module's settings (header, signatory, the dues warning, card validity), one template per certificate type
 * (the type's defaults until the school saves its own) and the serial counters per type and academic year, moved
 * under a row lock held until the transaction ends so two certificates never get the same number.
 */
@Service
@Transactional
public class CertificateSetupService {

    private final CertificateSettingsRepository settings;
    private final CertificateTemplateRepository templates;
    private final AcademicsDirectory academics;
    private final TenantDirectory tenants;
    private final AuditService audit;
    private final EntityManager entityManager;

    CertificateSetupService(CertificateSettingsRepository settings, CertificateTemplateRepository templates,
            AcademicsDirectory academics, TenantDirectory tenants, AuditService audit, EntityManager entityManager) {
        this.settings = settings;
        this.templates = templates;
        this.academics = academics;
        this.tenants = tenants;
        this.audit = audit;
        this.entityManager = entityManager;
    }

    // ------------------------------------------------------------------ settings

    @Transactional(readOnly = true)
    public SettingsView settings() {
        TenantContext.require();
        return settings.findFirstBy()
                .map(s -> new SettingsView(true, s.getHeaderName(), s.getHeaderAddress(), s.getAffiliationNo(),
                        s.getLogoText(), s.getSignatoryName(), s.getSignatoryDesignation(), s.isTcDuesCheck(),
                        s.getCardValidMonths(), s.getLostCardFeePaise()))
                .orElseGet(() -> {
                    SchoolProfile p = profile();
                    return new SettingsView(false, p == null ? null : p.name(), p == null ? null : p.address(),
                            null, null, null, "Principal", true, CertificateSettings.DEFAULT_CARD_MONTHS, 0);
                });
    }

    public SettingsView updateSettings(SettingsForm form, Actor actor) {
        CertificateSettings s = ensureSettings();
        s.update(blankToNull(form.headerName()), blankToNull(form.headerAddress()), blankToNull(form.affiliationNo()),
                blankToNull(form.logoText()), blankToNull(form.signatoryName()),
                blankToNull(form.signatoryDesignation()), form.tcDuesCheck(), form.cardValidMonths(),
                form.lostCardFeePaise());
        settings.saveAndFlush(s);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("signatoryName", s.getSignatoryName());
        details.put("tcDuesCheck", s.isTcDuesCheck());
        details.put("cardValidMonths", s.getCardValidMonths());
        details.put("lostCardFeePaise", s.getLostCardFeePaise());
        audit.record(actor, "certificate_settings.updated", "certificate_settings", s.getId(), details);
        return settings();
    }

    /** The header as printed: the saved one, with the school profile filling what is missing. */
    SchoolHeader header() {
        SettingsView s = settings();
        SchoolProfile p = profile();
        return new SchoolHeader(s.headerName() != null ? s.headerName() : p == null ? "" : p.name(),
                s.headerAddress() != null ? s.headerAddress() : p == null ? null : p.address(), s.affiliationNo(),
                s.logoText(), p == null ? null : p.city(), p == null ? null : p.phone());
    }

    Signatory signatory() {
        SettingsView s = settings();
        return new Signatory(s.signatoryName(), s.signatoryDesignation());
    }

    boolean tcDuesCheck() {
        return settings().tcDuesCheck();
    }

    int cardValidMonths() {
        return settings().cardValidMonths();
    }

    long lostCardFeePaise() {
        return settings().lostCardFeePaise();
    }

    private SchoolProfile profile() {
        return tenants.profile(TenantContext.require()).orElse(null);
    }

    /** The settings row of the current school, made with the defaults on first use. */
    private CertificateSettings ensureSettings() {
        UUID tenantId = TenantContext.require();
        entityManager.createNativeQuery("insert into certificates.settings (id, tenant_id, signatory_designation, "
                + "tc_dues_check, card_valid_months, lost_card_fee_paise, created_at, updated_at, version) "
                + "values (?1, ?2, 'Principal', true, ?3, 0, now(), now(), 0) on conflict (tenant_id) do nothing")
                .setParameter(1, Ids.newId())
                .setParameter(2, tenantId)
                .setParameter(3, CertificateSettings.DEFAULT_CARD_MONTHS)
                .executeUpdate();
        return settings.findFirstBy().orElseThrow();
    }

    // ------------------------------------------------------------------ templates

    @Transactional(readOnly = true)
    public List<TemplateView> templates() {
        TenantContext.require();
        Map<CertificateType, CertificateTemplate> saved = new LinkedHashMap<>();
        templates.findAllByOrderByType().forEach(t -> saved.put(t.getType(), t));
        String year = yearKey(Serials.today());
        return Arrays.stream(CertificateType.values()).map(t -> view(t, saved.get(t), year)).toList();
    }

    @Transactional(readOnly = true)
    public TemplateView template(CertificateType type) {
        TenantContext.require();
        return view(type, templates.findByType(type).orElse(null), yearKey(Serials.today()));
    }

    public TemplateView updateTemplate(CertificateType type, TemplateForm form, Actor actor) {
        TenantContext.require();
        CertificateTemplate t = templates.findByType(type).orElse(null);
        if (t == null) {
            t = new CertificateTemplate(type, form.title().trim(), form.body().strip(), form.serialPrefix());
        } else {
            t.update(form.title().trim(), form.body().strip(), form.serialPrefix());
        }
        templates.saveAndFlush(t);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("type", type.name());
        details.put("title", t.getTitle());
        details.put("serialPrefix", t.getSerialPrefix());
        audit.record(actor, "certificate_template.updated", "certificate_template", t.getId(), details);
        return template(type);
    }

    /** The wording in force for a type: the school's own, or the default. */
    Wording wording(CertificateType type) {
        return templates.findByType(type)
                .map(t -> new Wording(t.getTitle(), t.getBody(), t.getSerialPrefix()))
                .orElseGet(() -> new Wording(type.defaultTitle(), type.defaultBody(), type.defaultPrefix()));
    }

    record Wording(String title, String body, String serialPrefix) {
    }

    private TemplateView view(CertificateType type, CertificateTemplate saved, String year) {
        Wording w = saved == null ? wording(type) : new Wording(saved.getTitle(), saved.getBody(),
                saved.getSerialPrefix());
        return new TemplateView(type, w.title(), w.body(), w.serialPrefix(), type.isStaff(), type.placeholders(),
                saved != null, Serials.serialNo(w.serialPrefix(), year, lastSeq(type.name(), year) + 1));
    }

    // ------------------------------------------------------------------ counters

    /** The key of the current academic year ("2026-27"): the school's current year, else the year of the day. */
    String yearKey(LocalDate day) {
        return Serials.yearKey(academics.currentYear().map(YearInfo::name).orElse(null), day);
    }

    /** The next serial number of a type in the academic year of {@code day}. Must run inside the caller's transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public String nextSerial(CertificateType type, LocalDate day) {
        String year = yearKey(day);
        return Serials.serialNo(wording(type).serialPrefix(), year, next(type.name(), year));
    }

    /** The next ID card number ("ID/2026-27/0001"). Must run inside the caller's transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public String nextCardNo(LocalDate day) {
        String year = yearKey(day);
        return Serials.serialNo(Serials.ID_CARD_PREFIX, year, next(Serials.ID_CARD_COUNTER, year));
    }

    private int next(String counter, String year) {
        UUID tenantId = TenantContext.require();
        entityManager.createNativeQuery("insert into certificates.serial_counter (tenant_id, cert_type, "
                + "academic_year, last_seq, updated_at) values (?1, ?2, ?3, 0, now()) "
                + "on conflict (tenant_id, cert_type, academic_year) do nothing")
                .setParameter(1, tenantId).setParameter(2, counter).setParameter(3, year).executeUpdate();
        Number last = (Number) entityManager.createNativeQuery("select last_seq from certificates.serial_counter "
                + "where tenant_id = ?1 and cert_type = ?2 and academic_year = ?3 for update")
                .setParameter(1, tenantId).setParameter(2, counter).setParameter(3, year).getSingleResult();
        int next = last.intValue() + 1;
        entityManager.createNativeQuery("update certificates.serial_counter set last_seq = ?4, updated_at = now() "
                + "where tenant_id = ?1 and cert_type = ?2 and academic_year = ?3")
                .setParameter(1, tenantId).setParameter(2, counter).setParameter(3, year).setParameter(4, next)
                .executeUpdate();
        return next;
    }

    private int lastSeq(String counter, String year) {
        Optional<Object> last = entityManager.createNativeQuery("select last_seq from certificates.serial_counter "
                + "where tenant_id = ?1 and cert_type = ?2 and academic_year = ?3")
                .setParameter(1, TenantContext.require()).setParameter(2, counter).setParameter(3, year)
                .getResultStream().findFirst();
        return last.map(n -> ((Number) n).intValue()).orElse(0);
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
