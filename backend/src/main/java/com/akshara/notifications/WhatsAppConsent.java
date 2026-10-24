package com.akshara.notifications;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.shared.TenantContext;

/**
 * Which parents' phones may get WhatsApp. The privacy module tells it about every WhatsApp consent decision: when
 * consent for a child is withdrawn or declined, that child's guardians' phones are opted out; when it is given again,
 * the child's opt-outs go. {@link MessageDispatcher} never sends WhatsApp to an opted-out phone: it skips the message
 * and sends the same text by SMS instead.
 */
@Service
public class WhatsAppConsent {

    private final WhatsAppOptOutRepository optOuts;

    WhatsAppConsent(WhatsAppOptOutRepository optOuts) {
        this.optOuts = optOuts;
    }

    /** WhatsApp consent for the child is not given: none of these guardian phones gets WhatsApp from now on. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void optOut(UUID studentId, Collection<String> guardianPhones) {
        TenantContext.require();
        optOuts.deleteByStudent(studentId);
        Set<String> keys = new LinkedHashSet<>();
        for (String phone : guardianPhones) {
            String key = phoneKey(phone);
            if (!key.isEmpty()) {
                keys.add(key);
            }
        }
        Instant now = Instant.now();
        keys.forEach(key -> optOuts.save(new WhatsAppOptOut(studentId, key, now)));
        optOuts.flush();
    }

    /** WhatsApp consent for the child is given (again): the child no longer keeps any phone off WhatsApp. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void optIn(UUID studentId) {
        TenantContext.require();
        optOuts.deleteByStudent(studentId);
    }

    /** True when WhatsApp must not be used for this phone in the current school. */
    @Transactional(readOnly = true)
    public boolean optedOut(String phone) {
        String key = phoneKey(phone);
        return !key.isEmpty() && optOuts.existsByAddress(key);
    }

    /** A phone number as it is compared: digits only, the last ten for an Indian number with its country code. */
    static String phoneKey(String phone) {
        if (phone == null) {
            return "";
        }
        String digits = phone.replaceAll("[^0-9]", "");
        return digits.length() > 10 ? digits.substring(digits.length() - 10) : digits;
    }
}
