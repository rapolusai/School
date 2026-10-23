package com.akshara.assist;

import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
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
        /** Digits in groups joined by one space or dash ("98765 43210", "2345-6789-0123", "+91 98765-43210"). */
        private static final Pattern DIGIT_GROUPS = Pattern.compile("\\d+(?:[ \\u00A0-]\\d+)*");

        /**
         * True when the value holds something that looks like an Aadhaar number (12 digits, together or as 4-4-4) or
         * an Indian mobile number (10 digits from 6 to 9, in at most three groups, optionally after +91, 91 or 0),
         * written with or without single spaces or dashes. Runs of short numbers (marks, dates, amounts) are not
         * matched: a phone number is at most three groups and an Aadhaar number split up is exactly three of four.
         */
        static boolean holdsIdentifier(String value) {
            if (FORBIDDEN_VALUES.matcher(value).find()) {
                return true;
            }
            Matcher m = DIGIT_GROUPS.matcher(value);
            while (m.find()) {
                String[] groups = m.group().split("[ \\u00A0-]");
                for (int from = 0; from < groups.length; from++) {
                    StringBuilder digits = new StringBuilder();
                    for (int to = from; to < groups.length && to < from + 4; to++) {
                        digits.append(groups[to]);
                        int count = to - from + 1;
                        if (identifier(digits.toString(), count, groups, from)) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        private static boolean identifier(String digits, int groupCount, String[] groups, int from) {
            if (digits.length() == 12 && (groupCount == 1 || (groupCount == 3 && groups[from].length() == 4
                    && groups[from + 1].length() == 4))) {
                return true;
            }
            if (digits.length() == 10 && groupCount <= 3) {
                return digits.charAt(0) >= '6';
            }
            // A country or trunk prefix as its own group: "+91 98765 43210", "0 98765-43210".
            String prefix = groups[from];
            if (groupCount >= 2 && (prefix.equals("91") || prefix.equals("0") || prefix.equals("091"))) {
                String rest = digits.substring(prefix.length());
                return rest.length() == 10 && rest.charAt(0) >= '6' && groupCount - 1 <= 3;
            }
            return false;
        }

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
            if (value != null && holdsIdentifier(value)) {
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
