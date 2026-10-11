package com.akshara.library;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.library.LibraryForms.RuleForm;
import com.akshara.library.LibraryForms.SettingsForm;
import com.akshara.library.LibraryViews.RuleView;
import com.akshara.library.LibraryViews.SettingsView;
import com.akshara.shared.Ids;
import com.akshara.shared.TenantContext;

/**
 * The library's settings (accession prefix, how long a hold is kept, reminders to parents), its borrowing rules per
 * member type, and its two counters: accession numbers for new copies and receipt numbers for fines. Both counters
 * are moved under a row lock held until the transaction ends, so concurrent requests never get the same number and a
 * request that rolls back gives its numbers back.
 */
@Service
@Transactional
public class LibrarySetupService {

    private final LibrarySettingsRepository settings;
    private final BorrowingRuleRepository rules;
    private final CopyRepository copies;
    private final AuditService audit;
    private final EntityManager entityManager;

    LibrarySetupService(LibrarySettingsRepository settings, BorrowingRuleRepository rules, CopyRepository copies,
            AuditService audit, EntityManager entityManager) {
        this.settings = settings;
        this.rules = rules;
        this.copies = copies;
        this.audit = audit;
        this.entityManager = entityManager;
    }

    // ------------------------------------------------------------------ settings

    @Transactional(readOnly = true)
    public SettingsView settings() {
        TenantContext.require();
        return settings.findFirstBy()
                .map(s -> new SettingsView(s.getAccessionPrefix(),
                        nextFree(s.getAccessionPrefix(), s.getLastAccessionSeq()), s.getHoldDays(),
                        s.isRemindersEnabled()))
                .orElseGet(() -> new SettingsView(LibrarySettings.DEFAULT_PREFIX,
                        nextFree(LibrarySettings.DEFAULT_PREFIX, 0), LibrarySettings.DEFAULT_HOLD_DAYS, false));
    }

    public SettingsView updateSettings(SettingsForm form, Actor actor) {
        LibrarySettings s = ensureSettings();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("accessionPrefix", form.accessionPrefix());
        details.put("holdDays", form.holdDays());
        details.put("remindersEnabled", form.remindersEnabled());
        s.update(form.accessionPrefix(), form.holdDays(), form.remindersEnabled());
        settings.saveAndFlush(s);
        audit.record(actor, "library_settings.updated", "library_settings", s.getId(), details);
        return settings();
    }

    int holdDays() {
        return settings.findFirstBy().map(LibrarySettings::getHoldDays).orElse(LibrarySettings.DEFAULT_HOLD_DAYS);
    }

    boolean remindersEnabled() {
        return settings.findFirstBy().map(LibrarySettings::isRemindersEnabled).orElse(false);
    }

    /** The settings row of the current school, made with the defaults on first use. */
    private LibrarySettings ensureSettings() {
        UUID tenantId = TenantContext.require();
        entityManager.createNativeQuery("insert into library.settings (id, tenant_id, accession_prefix, "
                + "last_accession_seq, hold_days, reminders_enabled, created_at, updated_at, version) "
                + "values (?1, ?2, ?3, 0, ?4, false, now(), now(), 0) on conflict (tenant_id) do nothing")
                .setParameter(1, Ids.newId())
                .setParameter(2, tenantId)
                .setParameter(3, LibrarySettings.DEFAULT_PREFIX)
                .setParameter(4, LibrarySettings.DEFAULT_HOLD_DAYS)
                .executeUpdate();
        return settings.findFirstBy().orElseThrow();
    }

    // ------------------------------------------------------------------ borrowing rules

    @Transactional(readOnly = true)
    public List<RuleView> rules() {
        TenantContext.require();
        return Arrays.stream(MemberType.values()).map(this::ruleView).toList();
    }

    public RuleView updateRule(MemberType type, RuleForm form, Actor actor) {
        TenantContext.require();
        BorrowingRule.Values values = new BorrowingRule.Values(form.maxBooks(), form.loanDays(), form.maxRenewals(),
                form.finePerDayPaise(), form.maxFinePaise());
        BorrowingRule rule = rules.findByMemberType(type).orElse(null);
        if (rule == null) {
            rule = new BorrowingRule(type, values);
        } else {
            rule.update(values);
        }
        rules.saveAndFlush(rule);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("memberType", type.name());
        details.put("maxBooks", values.maxBooks());
        details.put("loanDays", values.loanDays());
        details.put("maxRenewals", values.maxRenewals());
        details.put("finePerDayPaise", values.finePerDayPaise());
        details.put("maxFinePaise", values.maxFinePaise());
        audit.record(actor, "library_rule.updated", "library_rule", rule.getId(), details);
        return ruleView(type);
    }

    /** The rule in force for a kind of member: the school's own, or the defaults. */
    BorrowingRule.Values rule(MemberType type) {
        return rules.findByMemberType(type).map(BorrowingRule::values).orElse(BorrowingRule.defaults(type));
    }

    RuleView ruleView(MemberType type) {
        BorrowingRule saved = rules.findByMemberType(type).orElse(null);
        BorrowingRule.Values v = saved == null ? BorrowingRule.defaults(type) : saved.values();
        return new RuleView(type, v.maxBooks(), v.loanDays(), v.maxRenewals(), v.finePerDayPaise(), v.maxFinePaise(),
                saved != null);
    }

    // ------------------------------------------------------------------ counters

    /**
     * The next {@code count} accession numbers of the school: its prefix and running number, skipping any number a
     * copy already has (one typed in by hand or imported). Must run inside the caller's transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<String> allocateAccessionNumbers(int count) {
        if (count < 1) {
            return List.of();
        }
        ensureSettings();
        UUID tenantId = TenantContext.require();
        Object[] row = (Object[]) entityManager.createNativeQuery("select accession_prefix, last_accession_seq "
                + "from library.settings where tenant_id = ?1 for update")
                .setParameter(1, tenantId).getSingleResult();
        String prefix = (String) row[0];
        long seq = ((Number) row[1]).longValue();
        List<String> numbers = new ArrayList<>();
        while (numbers.size() < count) {
            List<String> candidates = new ArrayList<>();
            for (int i = numbers.size(); i < count; i++) {
                seq++;
                candidates.add(LoanMath.accessionNo(prefix, seq));
            }
            Set<String> taken = new HashSet<>(copies.existingAccessionNos(candidates));
            candidates.stream().filter(c -> !taken.contains(c)).forEach(numbers::add);
        }
        entityManager.createNativeQuery("update library.settings set last_accession_seq = ?2, updated_at = now() "
                + "where tenant_id = ?1")
                .setParameter(1, tenantId).setParameter(2, seq).executeUpdate();
        return numbers;
    }

    /** The next fine receipt number of the financial year of {@code day}. Must run inside the caller's transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public String nextReceiptNo(LocalDate day) {
        UUID tenantId = TenantContext.require();
        String financialYear = LoanMath.financialYear(day);
        entityManager.createNativeQuery("insert into library.receipt_counter (tenant_id, financial_year, last_seq, "
                + "updated_at) values (?1, ?2, 0, now()) on conflict (tenant_id, financial_year) do nothing")
                .setParameter(1, tenantId).setParameter(2, financialYear).executeUpdate();
        Number last = (Number) entityManager.createNativeQuery("select last_seq from library.receipt_counter "
                + "where tenant_id = ?1 and financial_year = ?2 for update")
                .setParameter(1, tenantId).setParameter(2, financialYear).getSingleResult();
        int next = last.intValue() + 1;
        entityManager.createNativeQuery("update library.receipt_counter set last_seq = ?3, updated_at = now() "
                + "where tenant_id = ?1 and financial_year = ?2")
                .setParameter(1, tenantId).setParameter(2, financialYear).setParameter(3, next).executeUpdate();
        return LoanMath.receiptNo(financialYear, next);
    }

    /** The number the next copy would get (skipping numbers already used), without taking it. */
    private String nextFree(String prefix, long lastSeq) {
        long seq = lastSeq;
        for (int i = 0; i < 1000; i++) {
            String candidate = LoanMath.accessionNo(prefix, ++seq);
            if (copies.findByAccessionNo(candidate).isEmpty()) {
                return candidate;
            }
        }
        return LoanMath.accessionNo(prefix, seq);
    }
}
