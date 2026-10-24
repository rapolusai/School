package com.akshara.notifications;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.shared.TenantContext;

/**
 * Clears what the message log keeps about a student whose data is erased (privacy module), inside the erasure's
 * transaction: the recipient's phone or email, their name, the text and its parameters of every message about the
 * student, and of every message sent to the contacts of the guardian records that were deleted with them. Channel,
 * template, status, attempts and dates stay, so the school's message counts still add up. A message still waiting is
 * skipped, and the student's WhatsApp opt-outs (which hold phone numbers) go too.
 */
@Service
public class MessageErasure {

    /** What an erased recipient reads in the log (shown masked, as "•••"). */
    static final String ERASED = "erased";

    /** The name an erased student is shown with in the log, as everywhere else. */
    static final String ERASED_LABEL = "Erased student";

    static final String REASON = "Student data erased";

    /** Matches nothing, so an empty list of contacts never makes an empty {@code in ()}. */
    private static final String NONE = "-";

    private final MessageRepository messages;
    private final WhatsAppOptOutRepository optOuts;

    MessageErasure(MessageRepository messages, WhatsAppOptOutRepository optOuts) {
        this.messages = messages;
        this.optOuts = optOuts;
    }

    /**
     * Clears the student's log entries and those sent to {@code deletedContacts} (phone numbers or emails of guardian
     * records deleted with the student; entries about another student are left alone). Returns how many entries were
     * cleared.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int eraseStudent(UUID studentId, Collection<String> deletedContacts) {
        TenantContext.require();
        List<String> phones = new ArrayList<>();
        List<String> emails = new ArrayList<>();
        for (String contact : deletedContacts) {
            if (contact == null || contact.isBlank()) {
                continue;
            }
            if (contact.contains("@")) {
                emails.add(contact.trim().toLowerCase(Locale.ROOT));
            } else {
                String digits = WhatsAppConsent.phoneKey(contact);
                if (!digits.isEmpty()) {
                    phones.add(digits);
                }
            }
        }
        int cleared = messages.eraseAboutStudent(studentId, ERASED_LABEL, REASON);
        if (!phones.isEmpty() || !emails.isEmpty()) {
            cleared += messages.eraseSentTo(studentId, phones.isEmpty() ? List.of(NONE) : phones,
                    emails.isEmpty() ? List.of(NONE) : emails, REASON);
        }
        optOuts.deleteByStudent(studentId);
        return cleared;
    }
}
