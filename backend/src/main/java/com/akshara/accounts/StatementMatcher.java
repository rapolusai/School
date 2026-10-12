package com.akshara.accounts;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Pairs the lines of a bank statement with the school's own unreconciled bank payments and receipts. A line is MATCHED
 * when exactly one candidate has the same amount on the same side and the line mentions its reference (or cheque
 * number); PROBABLE when the amount alone finds exactly one candidate; otherwise NONE, and the accountant picks by
 * hand or leaves it. Each candidate is used at most once.
 */
public final class StatementMatcher {

    public enum Quality {
        MATCHED, PROBABLE, NONE
    }

    /** A paid expense (debit) or an income receipt (credit) not yet cleared. */
    public record Candidate(UUID id, boolean debit, long amountPaise, String reference, String label) {
    }

    public record Line(int index, long debitPaise, long creditPaise, String description, String reference) {
    }

    public record Match(int lineIndex, Candidate candidate, Quality quality) {
    }

    private StatementMatcher() {
    }

    public static List<Match> match(List<Line> lines, List<Candidate> candidates) {
        List<Match> matches = new ArrayList<>();
        Set<UUID> used = new HashSet<>();
        for (Line line : lines) {
            boolean debit = line.debitPaise() > 0;
            long amount = debit ? line.debitPaise() : line.creditPaise();
            String text = ((line.description() == null ? "" : line.description()) + " "
                    + (line.reference() == null ? "" : line.reference())).toLowerCase(Locale.ROOT);
            List<Candidate> sameAmount = candidates.stream()
                    .filter(c -> !used.contains(c.id()) && c.debit() == debit && c.amountPaise() == amount).toList();
            List<Candidate> withReference = sameAmount.stream().filter(c -> c.reference() != null
                    && !c.reference().isBlank() && text.contains(c.reference().toLowerCase(Locale.ROOT))).toList();
            Candidate chosen = null;
            Quality quality = Quality.NONE;
            if (withReference.size() == 1) {
                chosen = withReference.getFirst();
                quality = Quality.MATCHED;
            } else if (sameAmount.size() == 1) {
                chosen = sameAmount.getFirst();
                quality = Quality.PROBABLE;
            }
            if (chosen != null) {
                used.add(chosen.id());
            }
            matches.add(new Match(line.index(), chosen, quality));
        }
        return matches;
    }
}
