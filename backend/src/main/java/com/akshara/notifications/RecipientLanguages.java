package com.akshara.notifications;

import java.util.Optional;

/**
 * The language a recipient chose for themselves, if any. {@link NotificationQueue} asks this for every recipient and
 * uses the answer instead of the school's default message language. Another module provides it (the preferences
 * module, which knows each person's language); without one, every message goes out in the school's language.
 */
@FunctionalInterface
public interface RecipientLanguages {

    /** The language code (one of {@link MessageTemplates#LANGUAGES}) chosen by whoever owns this address. */
    Optional<String> preferredLanguage(Channel channel, String address);
}
