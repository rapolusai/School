package com.akshara.assist;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.akshara.assist.AssistViews.Blueprint;
import com.akshara.assist.AssistViews.BlueprintSection;
import com.akshara.assist.AssistViews.PaperQuestion;
import com.akshara.assist.AssistViews.PaperSection;
import com.akshara.lessons.Difficulty;
import com.akshara.lessons.LessonsViews.OptionView;
import com.akshara.lessons.LessonsViews.QuestionView;
import com.akshara.shared.ApiException;

/**
 * Picks and orders questions from the bank for a blueprint: per section, questions worth the section's marks
 * each, in the difficulty mix asked for, easiest first. Deterministic: the same bank and blueprint give the same
 * paper. Where the bank falls short the section is shorter and a warning says so.
 */
final class PaperBuilder {

    static final int MAX_SECTIONS = 10;
    static final int MAX_QUESTIONS = 50;

    record Built(List<PaperSection> sections, BigDecimal totalMarks, List<String> warnings) {
    }

    private static final Comparator<QuestionView> STABLE = Comparator
            .comparing((QuestionView q) -> q.difficulty().ordinal())
            .thenComparing(q -> q.text().toLowerCase(java.util.Locale.ROOT))
            .thenComparing(QuestionView::id);

    private PaperBuilder() {
    }

    /** 400 with a field error when the blueprint cannot be built from. */
    static Blueprint check(Blueprint b) {
        if (b == null || b.sections() == null || b.sections().isEmpty()) {
            throw ApiException.badRequest("Add at least one section.", "blueprint.sections");
        }
        if (b.sections().size() > MAX_SECTIONS) {
            throw ApiException.badRequest("Use at most " + MAX_SECTIONS + " sections.", "blueprint.sections");
        }
        List<BlueprintSection> sections = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (int i = 0; i < b.sections().size(); i++) {
            BlueprintSection s = b.sections().get(i);
            String field = "blueprint.sections[" + i + "]";
            String name = s.name() == null ? "" : s.name().strip();
            if (name.isEmpty() || name.length() > 40) {
                throw ApiException.badRequest("Name each section (up to 40 characters).", field + ".name");
            }
            if (!names.add(name.toLowerCase(java.util.Locale.ROOT))) {
                throw ApiException.badRequest("Each section needs a different name.", field + ".name");
            }
            if (s.questions() < 1 || s.questions() > MAX_QUESTIONS) {
                throw ApiException.badRequest("Ask for 1 to " + MAX_QUESTIONS + " questions.", field + ".questions");
            }
            if (s.marksEach() == null || s.marksEach().signum() <= 0
                    || s.marksEach().compareTo(BigDecimal.valueOf(100)) > 0) {
                throw ApiException.badRequest("Give the marks per question (above 0, up to 100).",
                        field + ".marksEach");
            }
            sections.add(new BlueprintSection(name, s.questions(), s.marksEach().stripTrailingZeros()));
        }
        int easy = b.easyPercent();
        int medium = b.mediumPercent();
        int hard = b.hardPercent();
        if (easy == 0 && medium == 0 && hard == 0) {
            easy = 40;
            medium = 40;
            hard = 20;
        }
        if (easy < 0 || medium < 0 || hard < 0 || easy + medium + hard != 100) {
            throw ApiException.badRequest("The difficulty mix must add up to 100%.", "blueprint.easyPercent");
        }
        return new Blueprint(sections, easy, medium, hard,
                b.bloomLevels() == null ? List.of() : List.copyOf(b.bloomLevels()),
                b.topicIds() == null ? List.of() : List.copyOf(b.topicIds()));
    }

    static Built build(List<QuestionView> bank, Blueprint blueprint) {
        List<QuestionView> pool = bank.stream()
                .filter(q -> blueprint.topicIds().isEmpty() || (q.topicId() != null
                        && blueprint.topicIds().contains(q.topicId())))
                .filter(q -> blueprint.bloomLevels().isEmpty() || (q.bloomLevel() != null
                        && blueprint.bloomLevels().contains(q.bloomLevel())))
                .sorted(STABLE)
                .toList();
        Set<UUID> used = new HashSet<>();
        List<PaperSection> sections = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (BlueprintSection s : blueprint.sections()) {
            List<QuestionView> exact = pool.stream()
                    .filter(q -> !used.contains(q.id()) && q.marks().compareTo(s.marksEach()) == 0).toList();
            List<QuestionView> chosen = new ArrayList<>();
            int n = s.questions();
            int easy = (int) Math.round(n * blueprint.easyPercent() / 100.0);
            int medium = (int) Math.round(n * blueprint.mediumPercent() / 100.0);
            int hard = Math.max(0, n - easy - medium);
            take(exact, chosen, used, Difficulty.EASY, easy);
            take(exact, chosen, used, Difficulty.MEDIUM, medium);
            take(exact, chosen, used, Difficulty.HARD, hard);
            take(exact, chosen, used, null, n - chosen.size());
            if (chosen.size() < n) {
                List<QuestionView> others = pool.stream().filter(q -> !used.contains(q.id())).toList();
                int before = chosen.size();
                take(others, chosen, used, null, n - chosen.size());
                if (chosen.size() > before) {
                    warnings.add(s.name() + ": " + (chosen.size() - before)
                            + (chosen.size() - before == 1 ? " question carries" : " questions carry")
                            + " different marks from "
                            + s.marksEach().toPlainString() + " (the bank had no more worth that).");
                }
            }
            if (chosen.size() < n) {
                warnings.add(s.name() + ": only " + chosen.size() + " of " + n + " questions found in the bank.");
            }
            chosen.sort(STABLE);
            List<PaperQuestion> questions = chosen.stream().map(PaperBuilder::question).toList();
            for (PaperQuestion q : questions) {
                total = total.add(q.marks());
            }
            sections.add(new PaperSection(s.name(), null, s.marksEach(), n, questions));
        }
        return new Built(sections, total.stripTrailingZeros(), warnings);
    }

    private static void take(List<QuestionView> from, List<QuestionView> into, Set<UUID> used,
            Difficulty difficulty, int count) {
        for (QuestionView q : from) {
            if (count <= 0) {
                return;
            }
            if (used.contains(q.id()) || (difficulty != null && q.difficulty() != difficulty)) {
                continue;
            }
            used.add(q.id());
            into.add(q);
            count--;
        }
    }

    static PaperQuestion question(QuestionView q) {
        return new PaperQuestion(q.id(), q.text(), q.type(), q.difficulty(), q.bloomLevel(),
                q.marks().stripTrailingZeros(), q.options().stream().map(OptionView::text).toList());
    }
}
