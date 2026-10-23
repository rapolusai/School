package com.akshara.assist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.akshara.assist.AssistProvider.AssistRequest;
import com.akshara.assist.AssistProvider.AssistResponse;
import com.akshara.assist.AssistProvider.Templates;
import com.akshara.exams.ExamsViews.CardAttendance;
import com.akshara.exams.ExamsViews.CardSubject;
import com.akshara.exams.ExamsViews.ReportCard;
import com.akshara.exams.OverallResult;

/** The simulated provider: deterministic, bilingual, labelled simulated, and never fed forbidden fields. */
class SimulatedAssistProviderTest {

    private final SimulatedAssistProvider provider = new SimulatedAssistProvider();

    private static ReportCard card(BigDecimal percent, Double attendance) {
        UUID maths = UUID.randomUUID();
        UUID english = UUID.randomUUID();
        return new ReportCard(UUID.randomUUID(), "Asha Rao", "A-1", 1, "Class 5 A", List.of(
                new CardSubject(maths, "Mathematics", List.of(), BigDecimal.valueOf(92), "A1", BigDecimal.TEN, true),
                new CardSubject(english, "English", List.of(), BigDecimal.valueOf(68), "B2", BigDecimal.valueOf(7),
                        true)),
                percent, "A2", BigDecimal.valueOf(9), OverallResult.PASS, 2,
                attendance == null ? null : new CardAttendance(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 9, 30),
                        80, 76, 4, 0, 0, 0, attendance), null, null, null);
    }

    @Test
    void draftsTheSameRemarkForTheSameRequestAndLabelsItSimulated() {
        AssistRequest request = RemarkService.overallRequest(card(BigDecimal.valueOf(80), 95.0), "Asha",
                "Class 5 A", RemarkTone.ENCOURAGING, "en");
        AssistResponse first = provider.generate(request);
        AssistResponse again = provider.generate(request);
        assertThat(first).isEqualTo(again);
        assertThat(first.simulated()).isTrue();
        assertThat(first.provider()).isEqualTo("simulated");
        assertThat(first.tokenEstimate()).isPositive();
        assertThat(first.text()).isEqualTo("Asha has done very well in Class 5 A this term, scoring 80% overall "
                + "with a A2 grade. Mathematics is a clear strength, and a little more practice in English will "
                + "help. Regular attendance (95%) has supported this progress.");
        // Two to three sentences.
        assertThat(first.text().split("\\. ").length).isBetween(2, 3);
    }

    @Test
    void switchesToneAndLanguage() {
        ReportCard card = card(BigDecimal.valueOf(45.5), 70.0);
        String formal = provider.generate(RemarkService.overallRequest(card, "Asha", "Class 5 A", RemarkTone.FORMAL,
                "en")).text();
        assertThat(formal).startsWith("Asha secured 45.5% overall (A2) in Class 5 A this term.")
                .contains("Performance in Mathematics was the strongest; English needs attention.")
                .contains("Better attendance (currently 70%) will make a real difference.");
        String hindi = provider.generate(RemarkService.overallRequest(card, "आशा", "कक्षा 5 A",
                RemarkTone.ENCOURAGING, "hi")).text();
        assertThat(hindi).contains("आशा").contains("45.5%").doesNotContain("Asha").endsWith("।");
        // Marks missing: still two sentences, no percentage.
        String none = provider.generate(RemarkService.overallRequest(card(null, null), "Asha", "Class 5 A",
                RemarkTone.ENCOURAGING, "en")).text();
        assertThat(none).startsWith("Asha has taken part regularly").doesNotContain("%");
    }

    @Test
    void subjectRemarksPaperInstructionsAndWritingHelp() {
        ReportCard card = card(BigDecimal.valueOf(80), 95.0);
        UUID english = card.subjects().get(1).subjectId();
        String subject = provider.generate(RemarkService.subjectRequest(card, "Asha", english, "English",
                RemarkTone.FORMAL, "en")).text();
        assertThat(subject).isEqualTo("In English, Asha scored 68% (B2). Regular practice will bring further "
                + "improvement.");
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("title", "Unit test");
        fields.put("duration", "60");
        fields.put("totalMarks", "25");
        String paper = provider.generate(new AssistRequest(AssistFeature.PAPER, "en", Templates.PAPER_INSTRUCTIONS,
                fields, List.of("Section A|10|1", "Section B|5|3"))).text();
        assertThat(paper).contains("Time allowed: 60 minutes. Maximum marks: 25.")
                .contains("Section A: 10 questions of 1 mark each.")
                .contains("Section B: 5 questions of 3 marks each.");
        String improved = provider.generate(new AssistRequest(AssistFeature.WRITING, "en", Templates.WRITING_IMPROVE,
                Map.of("text", "  dear parents,\n\nthe school   will remain closed on monday  \n\nplease note"),
                null)).text();
        assertThat(improved).isEqualTo("Dear parents,\n\nThe school will remain closed on monday.\n\nPlease note."
                + "\n\nWarm regards,\nThe School Office");
    }

    @Test
    void requestsRefuseForbiddenFieldsAndValues() {
        assertThatThrownBy(() -> new AssistRequest(AssistFeature.REMARK, "en", Templates.REMARK_OVERALL,
                Map.of("first", "Asha", "guardianPhone", "9876500011"), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("guardianPhone");
        assertThatThrownBy(() -> new AssistRequest(AssistFeature.REMARK, "en", Templates.REMARK_OVERALL,
                Map.of("address", "12 MG Road"), null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AssistRequest(AssistFeature.REMARK, "en", Templates.REMARK_OVERALL,
                Map.of("healthNote", "asthma"), null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AssistRequest(AssistFeature.WRITING, "en", Templates.WRITING_IMPROVE,
                Map.of("text", "Call 9876543210"), null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("phone");
        assertThatThrownBy(() -> new AssistRequest(AssistFeature.WRITING, "en", Templates.WRITING_IMPROVE,
                Map.of("text", "Aadhaar 1234 5678 9012"), null)).isInstanceOf(IllegalArgumentException.class);
        // The remark request is built from the minimum fields only.
        AssistRequest request = RemarkService.overallRequest(card(BigDecimal.valueOf(80), 95.0), "Asha",
                "Class 5 A", RemarkTone.ENCOURAGING, "en");
        assertThat(request.fields().keySet()).containsExactly("first", "classLabel", "tone", "percent", "grade",
                "attendance", "best", "weak");
        assertThat(request.fields().values()).noneMatch(v -> v.contains("Rao") || v.contains("A-1"));
        assertThat(request.lines()).containsExactly("Mathematics: 92% (A1)", "English: 68% (B2)");
        assertThat(UsageService.hash(request)).hasSize(64).isEqualTo(UsageService.hash(request));
    }
}
