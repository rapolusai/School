package com.akshara.notifications;

/**
 * Hides most of a phone number or email address for lists and logs: "9876543201" becomes "98765•••01" and
 * "anitha@family.test" becomes "an•••@family.test". Enough to recognise a contact, not enough to use it.
 */
public final class RecipientMask {

    static final String DOTS = "•••";

    private RecipientMask() {
    }

    public static String mask(String recipient) {
        if (recipient == null || recipient.isBlank()) {
            return "";
        }
        String value = recipient.trim();
        int at = value.indexOf('@');
        if (at >= 0) {
            String local = value.substring(0, at);
            String keep = local.length() > 2 ? local.substring(0, 2) : local.isEmpty() ? "" : local.substring(0, 1);
            return keep + DOTS + value.substring(at);
        }
        String digits = value.replaceAll("[^0-9]", "");
        if (digits.length() < 8) {
            return DOTS;
        }
        return digits.substring(0, 5) + DOTS + digits.substring(digits.length() - 2);
    }
}
