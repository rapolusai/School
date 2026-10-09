package com.akshara.communication;

/**
 * How many SMS parts a text takes. Texts in the GSM 7-bit alphabet fit 160 characters in one SMS and 153 per part
 * when split; anything else (Hindi, for example) is sent as UCS-2 with 70 characters, or 67 per part. Characters of
 * the GSM extension table ({@code ^ { } \ [ ] ~ | €}) count twice.
 */
final class SmsParts {

    private static final String GSM_BASIC = "@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?"
            + "¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà";
    private static final String GSM_EXTENDED = "^{}\\[~]|€\f";

    private SmsParts() {
    }

    /** True when the text can be sent in the GSM 7-bit alphabet. */
    static boolean isGsm(String text) {
        return text.codePoints().allMatch(cp -> GSM_BASIC.indexOf(cp) >= 0 || GSM_EXTENDED.indexOf(cp) >= 0);
    }

    /** The parts the text takes; 0 for an empty text. */
    static int count(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        if (isGsm(text)) {
            int septets = text.codePoints().map(cp -> GSM_EXTENDED.indexOf(cp) >= 0 ? 2 : 1).sum();
            return septets <= 160 ? 1 : (septets + 152) / 153;
        }
        // UCS-2 counts UTF-16 code units: a character outside the basic plane takes two.
        int units = text.length();
        return units <= 70 ? 1 : (units + 66) / 67;
    }
}
