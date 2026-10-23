package com.akshara.preferences;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.audit.AuditService;
import com.akshara.identity.UserAccount;
import com.akshara.identity.UserRepository;
import com.akshara.notifications.Channel;
import com.akshara.notifications.MessageTemplates;
import com.akshara.notifications.RecipientLanguages;
import com.akshara.shared.ApiException;
import com.akshara.shared.CurrentUser;
import com.akshara.shared.TenantContext;
import com.akshara.students.StudentRoster;

/**
 * Each person's chosen language. Anyone signed in to a school reads and changes only their own; a change is audited.
 * Also tells the notifications module which language a message address's owner chose.
 */
@Service
@Transactional
public class PreferencesService implements RecipientLanguages {

    /** What {@code GET /api/me/preferences} returns. {@code language} is null until the person chooses one. */
    public record PreferencesView(String language) {
    }

    private final UserLanguageRepository languages;
    private final UserRepository users;
    private final StudentRoster roster;
    private final AuditService audit;

    PreferencesService(UserLanguageRepository languages, UserRepository users, StudentRoster roster,
            AuditService audit) {
        this.languages = languages;
        this.users = users;
        this.roster = roster;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PreferencesView mine() {
        TenantContext.require();
        return new PreferencesView(languages.findByUserAccountId(CurrentUser.requireId())
                .map(UserLanguage::getLanguage).orElse(null));
    }

    public PreferencesView setMine(String language) {
        TenantContext.require();
        if (language == null || !MessageTemplates.LANGUAGES.contains(language)) {
            throw ApiException.badRequest("Choose English, Hindi or Telugu.", "language");
        }
        UUID userId = CurrentUser.requireId();
        users.findById(userId).filter(UserAccount::isActive)
                .orElseThrow(() -> ApiException.unauthorized("Sign in again."));
        UserLanguage row = languages.findByUserAccountId(userId).orElse(null);
        String before = row == null ? null : row.getLanguage();
        if (language.equals(before)) {
            return new PreferencesView(language);
        }
        if (row == null) {
            row = languages.saveAndFlush(new UserLanguage(userId, language));
        } else {
            row.change(language);
            languages.saveAndFlush(row);
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("from", before);
        details.put("to", language);
        audit.record("user_language.updated", "user_language", row.getId(), details);
        return new PreferencesView(language);
    }

    /** The language chosen by the parent whose mobile number this is, or by the person signing in with this email. */
    @Override
    public Optional<String> preferredLanguage(Channel channel, String address) {
        if (address == null || address.isBlank() || TenantContext.current().isEmpty()) {
            return Optional.empty();
        }
        Set<UUID> owners = switch (channel) {
            case SMS, WHATSAPP -> roster.guardianAccountsByPhone(address);
            case EMAIL -> users.findByEmailWithRoles(address.trim()).map(UserAccount::getId).map(Set::of)
                    .orElse(Set.of());
        };
        if (owners.isEmpty()) {
            return Optional.empty();
        }
        return languages.findByUserAccountIdIn(owners).stream()
                .map(UserLanguage::getLanguage)
                .filter(MessageTemplates.LANGUAGES::contains)
                .findFirst();
    }
}
