package com.akshara.assist;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.akshara.assist.AssistProvider.Templates;

/**
 * The only provider in this phase: deterministic text from templates and the request's own fields, in English or
 * Hindi, labelled "Simulated draft" everywhere. The same request always gives the same text. Selected by
 * {@code akshara.assist.provider=simulated} (the default); any other value leaves no provider and the API refuses to
 * start, so a real provider cannot be switched on by accident.
 */
@Component
@ConditionalOnProperty(name = "akshara.assist.provider", havingValue = "simulated", matchIfMissing = true)
class SimulatedAssistProvider implements AssistProvider {

    static final String NAME = "simulated";
    static final String LABEL_EN = "Simulated draft";
    static final String LABEL_HI = "अनुकरणीय प्रारूप";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public AssistResponse generate(AssistRequest request) {
        boolean hindi = "hi".equals(request.language());
        String text = switch (request.template()) {
            case Templates.REMARK_OVERALL -> remarkOverall(request.fields(), hindi);
            case Templates.REMARK_SUBJECT -> remarkSubject(request.fields(), hindi);
            case Templates.PAPER_INSTRUCTIONS -> paperInstructions(request.fields(), request.lines(), hindi);
            case Templates.WRITING_IMPROVE -> improve(request.fields().getOrDefault("text", ""), hindi);
            default -> throw new IllegalArgumentException("Unknown template " + request.template());
        };
        return new AssistResponse(text, NAME, request.tokenEstimate() + Math.max(1, text.length() / 4), true);
    }

    // ------------------------------------------------------------------ remarks

    private static String remarkOverall(Map<String, String> f, boolean hindi) {
        String first = f.getOrDefault("first", "");
        String cls = f.getOrDefault("classLabel", "");
        boolean formal = "FORMAL".equals(f.get("tone"));
        Double percent = number(f.get("percent"));
        String grade = f.getOrDefault("grade", "");
        Double attendance = number(f.get("attendance"));
        String best = f.getOrDefault("best", "");
        String weak = f.getOrDefault("weak", "");
        List<String> s = new ArrayList<>();
        if (hindi) {
            if (percent == null) {
                s.add(first + " ने " + cls + " में इस सत्र में नियमित भागीदारी की है।");
            } else if (formal) {
                s.add(first + " ने " + cls + " में इस सत्र में कुल " + pct(percent) + "% अंक"
                        + (grade.isEmpty() ? "" : " (" + grade + " ग्रेड)") + " प्राप्त किए।");
            } else if (percent >= 75) {
                s.add(first + " ने " + cls + " में इस सत्र में " + pct(percent) + "% के साथ बहुत अच्छा प्रदर्शन किया है।");
            } else if (percent >= 50) {
                s.add(first + " ने " + cls + " में इस सत्र में " + pct(percent) + "% के साथ स्थिर प्रगति की है।");
            } else {
                s.add(first + " को " + cls + " में इस सत्र में " + pct(percent) + "% मिले हैं और नियमित सहयोग से सुधार होगा।");
            }
            if (!best.isEmpty() && !weak.isEmpty() && !best.equals(weak)) {
                s.add(formal ? best + " में प्रदर्शन सबसे अच्छा रहा; " + weak + " पर ध्यान देना आवश्यक है।"
                        : best + " एक स्पष्ट मजबूती है, और " + weak + " में थोड़ा और अभ्यास मदद करेगा।");
            } else if (!best.isEmpty()) {
                s.add(best + " में प्रदर्शन उल्लेखनीय रहा।");
            }
            if (attendance != null) {
                if (attendance >= 90) {
                    s.add("नियमित उपस्थिति (" + pct(attendance) + "%) से सीखने में मदद मिली है।");
                } else if (attendance < 75) {
                    s.add("बेहतर उपस्थिति (अभी " + pct(attendance) + "%) से बड़ा अंतर आएगा।");
                } else if (s.size() < 2) {
                    s.add("उपस्थिति " + pct(attendance) + "% रही।");
                }
            }
            if (s.size() < 2) {
                s.add(formal ? "आगामी सत्र में निरंतर प्रयास अपेक्षित है।" : "अगले सत्र के लिए शुभकामनाएँ!");
            }
        } else {
            if (percent == null) {
                s.add(first + " has taken part regularly in " + cls + " this term.");
            } else if (formal) {
                s.add(first + " secured " + pct(percent) + "% overall" + (grade.isEmpty() ? "" : " (" + grade + ")")
                        + " in " + cls + " this term.");
            } else if (percent >= 75) {
                s.add(first + " has done very well in " + cls + " this term, scoring " + pct(percent) + "% overall"
                        + (grade.isEmpty() ? "" : " with a " + grade + " grade") + ".");
            } else if (percent >= 50) {
                s.add(first + " has made steady progress in " + cls + " this term with " + pct(percent) + "% overall"
                        + (grade.isEmpty() ? "" : " (" + grade + ")") + ".");
            } else {
                s.add(first + " scored " + pct(percent) + "% overall in " + cls
                        + " this term and will improve with regular support.");
            }
            if (!best.isEmpty() && !weak.isEmpty() && !best.equals(weak)) {
                s.add(formal ? "Performance in " + best + " was the strongest; " + weak + " needs attention."
                        : best + " is a clear strength, and a little more practice in " + weak + " will help.");
            } else if (!best.isEmpty()) {
                s.add("Work in " + best + " stood out.");
            }
            if (attendance != null) {
                if (attendance >= 90) {
                    s.add("Regular attendance (" + pct(attendance) + "%) has supported this progress.");
                } else if (attendance < 75) {
                    s.add("Better attendance (currently " + pct(attendance) + "%) will make a real difference.");
                } else if (s.size() < 2) {
                    s.add("Attendance stood at " + pct(attendance) + "%.");
                }
            }
            if (s.size() < 2) {
                s.add(formal ? "Sustained effort is expected in the coming term." : "Keep it up next term!");
            }
        }
        return String.join(" ", s.subList(0, Math.min(3, s.size())));
    }

    private static String remarkSubject(Map<String, String> f, boolean hindi) {
        String first = f.getOrDefault("first", "");
        String subject = f.getOrDefault("subject", "");
        boolean formal = "FORMAL".equals(f.get("tone"));
        Double percent = number(f.get("percent"));
        String grade = f.getOrDefault("grade", "");
        List<String> s = new ArrayList<>();
        if (hindi) {
            if (percent == null) {
                s.add(subject + " में " + first + " के अंक अभी दर्ज नहीं हैं।");
            } else {
                s.add(subject + " में " + first + " ने " + pct(percent) + "%" + (grade.isEmpty() ? "" : " (" + grade + ")")
                        + " प्राप्त किए।");
                if (percent >= 75) {
                    s.add(formal ? "विषय की समझ सुदृढ़ है।" : "विषय पर अच्छी पकड़ है, इसे बनाए रखें!");
                } else if (percent >= 50) {
                    s.add(formal ? "नियमित अभ्यास से और सुधार संभव है।" : "थोड़े और अभ्यास से और बेहतर होगा।");
                } else {
                    s.add(formal ? "मूल अवधारणाओं पर अतिरिक्त ध्यान आवश्यक है।" : "मूल बातों पर मिलकर काम करने से मदद मिलेगी।");
                }
            }
        } else {
            if (percent == null) {
                s.add(first + "'s marks in " + subject + " are not recorded yet.");
            } else {
                s.add("In " + subject + ", " + first + " scored " + pct(percent) + "%"
                        + (grade.isEmpty() ? "" : " (" + grade + ")") + ".");
                if (percent >= 75) {
                    s.add(formal ? "The grasp of the subject is sound." : "A strong grasp of the subject; keep it up!");
                } else if (percent >= 50) {
                    s.add(formal ? "Regular practice will bring further improvement."
                            : "A little more practice will take this further.");
                } else {
                    s.add(formal ? "Additional attention to the basics is required."
                            : "Working together on the basics will help a lot.");
                }
            }
        }
        return String.join(" ", s);
    }

    // ------------------------------------------------------------------ papers

    /** Lines are "name|questions|marksEach"; the result is the paper's general instructions. */
    private static String paperInstructions(Map<String, String> f, List<String> lines, boolean hindi) {
        String duration = f.getOrDefault("duration", "");
        String total = f.getOrDefault("totalMarks", "");
        List<String> s = new ArrayList<>();
        if (hindi) {
            s.add("समय: " + duration + " मिनट। अधिकतम अंक: " + total + "।");
            s.add("सभी प्रश्न अनिवार्य हैं। प्रश्नों के अंक उनके सामने लिखे हैं।");
            for (String line : lines) {
                String[] p = line.split("\\|", -1);
                if (p.length >= 3) {
                    s.add(p[0] + ": " + p[1] + " प्रश्न, प्रत्येक " + p[2] + " अंक।");
                }
            }
            s.add("उत्तर स्पष्ट और सुपाठ्य लिखें।");
        } else {
            s.add("Time allowed: " + duration + " minutes. Maximum marks: " + total + ".");
            s.add("All questions are compulsory. Marks for each question are shown against it.");
            for (String line : lines) {
                String[] p = line.split("\\|", -1);
                if (p.length >= 3) {
                    s.add(p[0] + ": " + p[1] + " questions of " + p[2] + " mark" + ("1".equals(p[2]) ? "" : "s")
                            + " each.");
                }
            }
            s.add("Write neatly and show your working where needed.");
        }
        return String.join("\n", s);
    }

    // ------------------------------------------------------------------ writing

    /** Tidies a circular: trims spaces, capitalises sentences, ends them with a stop and adds a formal sign-off. */
    static String improve(String text, boolean hindi) {
        List<String> out = new ArrayList<>();
        for (String raw : text.replace("\r\n", "\n").split("\n")) {
            String line = raw.strip().replaceAll("\\s+", " ");
            if (line.isEmpty()) {
                if (!out.isEmpty() && !out.getLast().isEmpty()) {
                    out.add("");
                }
                continue;
            }
            out.add(hindi ? sentencesHindi(line) : sentences(line));
        }
        while (!out.isEmpty() && out.getLast().isEmpty()) {
            out.removeLast();
        }
        if (out.isEmpty()) {
            return "";
        }
        String signOff = hindi ? "सादर,\nविद्यालय कार्यालय" : "Warm regards,\nThe School Office";
        String body = String.join("\n", out);
        if (body.toLowerCase(Locale.ROOT).contains("regards") || body.contains("सादर")) {
            return body;
        }
        return body + "\n\n" + signOff;
    }

    private static String sentences(String line) {
        StringBuilder b = new StringBuilder();
        boolean start = true;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (start && Character.isLetter(c)) {
                b.append(Character.toUpperCase(c));
                start = false;
            } else {
                b.append(c);
                if (c == '.' || c == '!' || c == '?') {
                    start = true;
                } else if (!Character.isWhitespace(c)) {
                    start = false;
                }
            }
        }
        char last = b.charAt(b.length() - 1);
        if (Character.isLetterOrDigit(last) || last == ')') {
            b.append('.');
        }
        return b.toString();
    }

    private static String sentencesHindi(String line) {
        char last = line.charAt(line.length() - 1);
        int type = Character.getType(last);
        boolean wordEnd = Character.isLetterOrDigit(last) || type == Character.NON_SPACING_MARK
                || type == Character.COMBINING_SPACING_MARK || last == ')';
        return wordEnd ? line + "।" : line;
    }

    // ------------------------------------------------------------------ helpers

    private static Double number(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Double.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** "85" or "85.5": whole numbers without a decimal point. */
    static String pct(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value)
                : String.format(Locale.ROOT, "%.1f", value);
    }
}
