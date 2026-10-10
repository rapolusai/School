package com.akshara.privacy;

/**
 * Why the school processes a child's personal data. ESSENTIAL covers running the school (admission, classes,
 * attendance, fees, messages about the child) and is required to use the parent app; the others are optional and can
 * be withdrawn at any time.
 */
public enum PrivacyPurpose {
    /** School records: required to enrol the child and to use the parent app. */
    ESSENTIAL,
    /** Photos of the child in school publicity (website, prospectus, social media). */
    PHOTOS,
    /** School updates on WhatsApp (circulars, events). */
    WHATSAPP;

    public boolean optional() {
        return this != ESSENTIAL;
    }
}
