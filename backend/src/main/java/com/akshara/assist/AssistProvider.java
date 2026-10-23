package com.akshara.assist;

import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Generates text from a structured request in the school's language. The only implementation in this phase is the
 * deterministic {@link SimulatedAssistProvider}; a real provider (an HTTP client to a hosted model, with its key in
 * configuration) can be added later behind {@code akshara.assist.provider} without changing callers. Every request
 * is built from the minimum fields: {@link AssistRequest} refuses any field that looks like Aadhaar, a phone number,
 * an address or health data, whichever provider is in use.
 */
public interface AssistProvider {

    /** The provider's name, written on every draft and in the usage log. */
    String name();

    AssistResponse generate(AssistRequest request);

    /**
     * What a draft is made from. {@code template} names the kind of text wanted ({@link Templates}); {@code fields}
     * are the named values the text is built from and {@code lines} any list of lines (one per subject, say). The
     * request only ever carries first names, class labels, marks, grades and attendance percentages.
     */
    record AssistRequest(AssistFeature feature, String language, String template, Map<String, String> fields,
            List<String> lines) {

        /** Field names that must never reach a provider, matched anywhere in the key (ignoring case). */
        static final Set<String> FORBIDDEN_KEYS = Set.of("aadhaar", "aadhar", "phone", "mobile", "whatsapp",
                "address", "email", "health", "medical", "blood", "allergy", "diagnosis", "dob", "dateofbirth",
                "guardian", "parent", "lastname", "surname", "fullname", "admissionno", "pan", "bank");
        /** An Aadhaar number (12 digits, with or without spaces) or an Indian mobile number anywhere in a value. */
        static final Pattern FORBIDDEN_VALUES =
                Pattern.compile("(?<!\\d)(\\d{4}\\s?\\d{4}\\s?\\d{4}|[6-9]\\d{9})(?!\\d)");

        public AssistRequest {
            if (feature == null || template == null || template.isBlank()) {
                throw new IllegalArgumentException("A request needs a feature and a template");
            }
            language = "hi".equals(language) ? "hi" : "en";
            Map<String, String> copy = new LinkedHashMap<>();
            if (fields != null) {
                fields.forEach((k, v) -> {
                    check(k, v);
                    copy.put(k, v == null ? "" : v);
                });
            }
            fields = Collections.unmodifiableMap(copy);
            List<String> safeLines = lines == null ? List.of() : List.copyOf(lines);
            safeLines.forEach(line -> check("line", line));
            lines = safeLines;
        }

        private static void check(String key, String value) {
            String flat = key.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
            for (String forbidden : FORBIDDEN_KEYS) {
                if (flat.contains(forbidden)) {
                    throw new IllegalArgumentException("The field '" + key + "' must never be sent to a provider");
                }
            }
            if (value != null && FORBIDDEN_VALUES.matcher(value).find()) {
                throw new IllegalArgumentException("The field '" + key + "' holds a number that looks like an Aadhaar "
                        + "or phone number and must never be sent to a provider");
            }
        }

        /** Roughly how many tokens the request is, for the usage log: about one per four characters. */
        public int tokenEstimate() {
            int chars = template.length();
            for (Map.Entry<String, String> e : fields.entrySet()) {
                chars += e.getKey().length() + e.getValue().length() + 2;
            }
            for (String line : lines) {
                chars += line.length() + 1;
            }
            return Math.max(1, chars / 4);
        }
    }

    /**
     * The generated text, the provider that made it, a token estimate for the usage log (request and answer) and
     * whether the text is a simulated draft (always true in this phase, shown on every screen).
     */
    record AssistResponse(String text, String provider, int tokenEstimate, boolean simulated) {
    }

    /** The kinds of text the module asks for. */
    final class Templates {

        public static final String REMARK_OVERALL = "remark.overall";
        public static final String REMARK_SUBJECT = "remark.subject";
        public static final String PAPER_INSTRUCTIONS = "paper.instructions";
        public static final String WRITING_IMPROVE = "writing.improve";

        private Templates() {
        }
    }
}
